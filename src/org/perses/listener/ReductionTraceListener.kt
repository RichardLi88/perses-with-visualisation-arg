/*
 * Copyright (C) 2018-2025 University of Waterloo.
 *
 * This file is part of Perses.
 *
 * Perses is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3, or (at your option) any later version.
 */
package org.perses.listener

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import difflib.DiffUtils
import org.perses.program.TokenizedProgram
import org.perses.reduction.AbstractReductionListener
import org.perses.reduction.event.AbstractTestScriptExecutionEvent
import org.perses.reduction.event.BestProgramUpdateEvent
import org.perses.reduction.event.FixpointIterationStartEvent
import org.perses.reduction.event.LazyProgramOutputer
import org.perses.reduction.event.ProgramStateTransitionEvent
import org.perses.reduction.event.ReductionEndEvent
import org.perses.reduction.event.ReductionStartEvent
import org.perses.spartree.AbstractSparTreeEdit
import org.perses.spartree.NodeDeletionAction
import org.perses.spartree.NodeReplacementAction
import org.perses.spartree.ParserRuleSparTreeNode
import org.perses.util.FileStreamPool
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** Builds one schema-2 reduction document directly from Perses listener events. */
class ReductionTraceListener(
  private val stream: FileStreamPool.ManagedPrintStream,
  private val sourceFile: String? = null,
  private val testScript: String? = null,
) : AbstractReductionListener() {
  private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
  private val candidates = linkedMapOf<String, CandidateRecord>()
  private val states = linkedMapOf<String, StateRecord>()
  private val programs = linkedMapOf<String, Map<String, Any>>()
  private val snapshotsByState = linkedMapOf<String, Snapshot>()
  private val errors = mutableListOf<Map<String, Any?>>()
  private val steps = mutableListOf<StepRecord>()
  private val reducerPlan = mutableListOf<String>()
  private var sequence = 0L
  private var reducerPass = 0
  private var reducer: String? = null
  private var startedAtMillis: Long? = null
  private var finishedAtMillis: Long? = null
  private var commandLine: String? = null
  private var language: String? = null
  private var endEvent: ReductionEndEvent? = null
  private var finalStateId: String? = null
  private var written = false

  override fun onReductionStart(event: ReductionStartEvent) {
    startedAtMillis = event.currentTimeMillis
    commandLine = event.commandLineOptions
    language =
      event.program
        ?.factory
        ?.languageKind
        ?.toString()
    val snapshot = snapshot(event.program, event.textualProgram)
    val programRef = intern(snapshot)
    val initialState =
      StateRecord(
        stateId = INITIAL_STATE_ID,
        parentStateId = null,
        createdByCandidateId = null,
        programRef = programRef,
        tokens = snapshot.tokenCount,
        kind = "INITIAL",
        acceptedAtSeq = null,
        reason = null,
      )
    states[initialState.stateId] = initialState
    snapshotsByState[initialState.stateId] = snapshot
    finalStateId = initialState.stateId
  }

  override fun onFixpointIterationStart(event: FixpointIterationStartEvent) {
    ++reducerPass
    reducer = event.reducerClass.shortName
    if (reducerPlan.lastOrNull() != reducer) {
      reducerPlan.add(checkNotNull(reducer))
    }
  }

  override fun onTestScriptExecution(
    event: AbstractTestScriptExecutionEvent.TestScriptExecutionEvent,
  ) {
    val status =
      when {
        event.result.isInteresting -> "INTERESTING"
        event.result.exitCode.intValue == INVALID_SYNTAX_EXIT_CODE -> "INVALID"
        else -> "REJECTED"
      }
    observeCandidate(
      event = event,
      status = status,
      exitCode = event.result.exitCode.intValue,
      elapsedMillis = event.result.elapsedMillis.toLong(),
      cancelDurationMillis = null,
    )
  }

  override fun onTestResultCacheHit(
    event: AbstractTestScriptExecutionEvent.TestResultCacheHitEvent,
  ) {
    observeCandidate(event, "CACHE_HIT", null, null, null)
  }

  override fun onTestScriptExecutionCancelled(
    event: AbstractTestScriptExecutionEvent.TestScriptExecutionCanceledEvent,
  ) {
    observeCandidate(event, "CANCELLED", null, null, event.millisToCancelTheTask)
  }

  override fun onBestProgramUpdated(event: BestProgramUpdateEvent) {
    if (states.containsKey(event.resultStateId)) {
      return
    }
    val acceptedAtSeq = sequence++
    val record =
      candidates.getOrPut(event.candidateId) {
        CandidateRecord.fromEdit(
          event.edit,
          reducer,
          reducerPass,
          status = "NOT_TESTED",
          observedAtSeq = null,
          observedAtMillis = null,
          snapshot = snapshot(event.program, event.textualProgram),
        )
      }
    val acceptedSnapshot = snapshot(event.program, event.textualProgram)
    val programRef = intern(acceptedSnapshot)
    record.resultStateId = event.resultStateId
    record.becameBest = true
    record.acceptedAtSeq = acceptedAtSeq
    record.acceptedAtMillis = relativeMillis(event.currentTimeMillis)
    record.programRef = programRef
    record.snapshot = acceptedSnapshot

    val state =
      StateRecord(
        stateId = event.resultStateId,
        parentStateId = event.parentStateId,
        createdByCandidateId = event.candidateId,
        programRef = programRef,
        tokens = event.program.tokenCount,
        kind = "CANDIDATE",
        acceptedAtSeq = acceptedAtSeq,
        reason = null,
      )
    states[state.stateId] = state
    snapshotsByState[state.stateId] = acceptedSnapshot
    steps.add(
      StepRecord(
        candidateId = event.candidateId,
        fromStateId = event.parentStateId,
        toStateId = event.resultStateId,
        acceptedAtSeq = acceptedAtSeq,
        systemReason = null,
      ),
    )
    finalStateId = state.stateId
  }

  override fun onCriticalException(exception: Exception) {
    errors.add(
      linkedMapOf(
        "exceptionClass" to exception.javaClass.name,
        "message" to exception.message,
        "stackTrace" to exception.stackTraceToString(),
      ),
    )
  }

  override fun onProgramStateTransition(event: ProgramStateTransitionEvent) {
    val acceptedAtSeq = sequence++
    val transitionSnapshot = snapshot(event.program, event.textualProgram)
    val programRef = intern(transitionSnapshot)
    val state =
      StateRecord(
        stateId = event.resultStateId,
        parentStateId = event.parentStateId,
        createdByCandidateId = null,
        programRef = programRef,
        tokens = event.program.tokenCount,
        kind = "SYSTEM",
        acceptedAtSeq = acceptedAtSeq,
        reason = event.reason,
      )
    states[state.stateId] = state
    snapshotsByState[state.stateId] = transitionSnapshot
    steps.add(
      StepRecord(
        candidateId = null,
        fromStateId = event.parentStateId,
        toStateId = event.resultStateId,
        acceptedAtSeq = acceptedAtSeq,
        systemReason = event.reason,
      ),
    )
    finalStateId = state.stateId
  }

  override fun onReductionEnd(event: ReductionEndEvent) {
    endEvent = event
    finishedAtMillis = event.currentTimeMillis
  }

  override fun close() {
    if (!written) {
      written = true
      val document = buildDocument()
      stream.print(objectMapper.writeValueAsString(document))
      stream.println()
      stream.flush()
    }
    stream.close()
  }

  private fun observeCandidate(
    event: AbstractTestScriptExecutionEvent,
    status: String,
    exitCode: Int?,
    elapsedMillis: Long?,
    cancelDurationMillis: Int?,
  ) {
    val observedAtSeq = sequence++
    val record =
      candidates.getOrPut(event.edit.candidateId) {
        CandidateRecord.fromEdit(
          event.edit,
          reducer,
          reducerPass,
          status,
          observedAtSeq,
          relativeMillis(event.currentTimeMillis),
          snapshot(event.program, event.textualProgram),
        )
      }
    record.status = status
    if (record.observedAtSeq == null) {
      record.observedAtSeq = observedAtSeq
      record.observedAtMillis = relativeMillis(event.currentTimeMillis)
    }
    record.exitCode = exitCode
    record.elapsedMillis = elapsedMillis
    record.cancelDurationMillis = cancelDurationMillis
  }

  private fun buildDocument(): Map<String, Any?> {
    validateGraph()
    val status =
      when {
        errors.isNotEmpty() -> "FAILED"
        endEvent != null -> "COMPLETED"
        else -> "INCOMPLETE"
      }
    val candidateDocuments = candidates.values.map(::candidateDocument)
    val end = endEvent
    val started = startedAtMillis
    val finished = finishedAtMillis
    val originalTokens = states[INITIAL_STATE_ID]?.tokens
    val finalTokens = end?.programSize ?: finalStateId?.let(states::get)?.tokens
    val reducerStatistics =
      candidates.values
        .groupBy { it.transformation["reducer"] as String? }
        .filterKeys { it != null }
        .mapKeys { checkNotNull(it.key) }
        .mapValues { (_, records) ->
          linkedMapOf(
            "candidates" to records.size,
            "accepted" to records.count(CandidateRecord::becameBest),
            "testTimeMs" to records.sumOf { maxOf(it.elapsedMillis ?: 0L, 0L) },
          )
        }
    return linkedMapOf(
      "schemaVersion" to SCHEMA_VERSION,
      "domain" to "program-reduction",
      "meta" to
        linkedMapOf(
          "tool" to "perses",
          "status" to status,
          "sourceFile" to sourceFile,
          "testScript" to testScript,
          "language" to language,
          "commandLine" to commandLine,
          "startedAtMillis" to started,
          "finishedAtMillis" to finished,
          "reducerPlan" to reducerPlan,
        ),
      "summary" to
        linkedMapOf(
          "originalTokens" to originalTokens,
          "finalTokens" to finalTokens,
          "reductionRatio" to
            if (originalTokens != null && originalTokens > 0 && finalTokens != null) {
              (originalTokens - finalTokens).toDouble() / originalTokens
            } else {
              null
            },
          "totalCandidates" to candidates.size,
          "acceptedSteps" to steps.size,
          "rejectedCandidates" to candidates.values.count { it.status in REJECTED_STATUSES },
          "queryCacheHits" to candidates.values.count { it.status == "CACHE_HIT" },
          "cancelledCandidates" to candidates.values.count { it.status == "CANCELLED" },
          "wallTimeMs" to
            if (started != null && finished != null) finished - started else null,
          "testTimeMs" to candidates.values.sumOf { maxOf(it.elapsedMillis ?: 0L, 0L) },
          "scriptExecutions" to end?.testScriptExecutorServiceStatistics?.scriptExecutionNumber,
          "externalCacheHits" to
            end?.testScriptExecutorServiceStatistics?.externalCacheHitNumber,
          "reducerPasses" to reducerPass,
          "reducers" to reducerStatistics,
        ),
      "originalStateId" to states[INITIAL_STATE_ID]?.stateId,
      "finalStateId" to finalStateId,
      "programs" to programs,
      "states" to states.values.map(StateRecord::toDocument),
      "steps" to
        steps.sortedBy { it.acceptedAtSeq }.mapIndexed { index, step ->
          step.toDocument(
            index = index,
            states = states,
            candidates = candidates,
            patches =
              createPatches(
                snapshotsByState.getValue(step.fromStateId),
                snapshotsByState.getValue(step.toStateId),
              ),
          )
        },
      "candidates" to candidateDocuments,
      "errors" to errors,
    )
  }

  private fun candidateDocument(record: CandidateRecord): Map<String, Any?> {
    val baseSnapshot = snapshotsByState[record.baseStateId]
    val patches =
      if (record.programRef == null && baseSnapshot != null) {
        createPatches(baseSnapshot, record.snapshot)
      } else {
        null
      }
    return linkedMapOf(
      "candidateId" to record.candidateId,
      "editId" to record.editId,
      "baseStateId" to record.baseStateId,
      "resultStateId" to record.resultStateId,
      "status" to record.status,
      "becameBest" to record.becameBest,
      "observedAtSeq" to record.observedAtSeq,
      "acceptedAtSeq" to record.acceptedAtSeq,
      "observedAtMs" to record.observedAtMillis,
      "acceptedAtMs" to record.acceptedAtMillis,
      "tokensAfter" to record.snapshot.tokenCount,
      "programRef" to record.programRef,
      "patches" to patches,
      "exitCode" to record.exitCode,
      "elapsedMillis" to record.elapsedMillis,
      "cancelDurationMillis" to record.cancelDurationMillis,
      "transformation" to record.transformation,
    )
  }

  private fun validateGraph() {
    if (states.isEmpty()) {
      check(candidates.isEmpty()) { "Trace candidates exist without an initial state" }
      return
    }
    check(states.containsKey(INITIAL_STATE_ID)) { "Missing initial state" }
    states.values.forEach { state ->
      check(programs.containsKey(state.programRef)) { "Unknown programRef ${state.programRef}" }
      if (state.stateId == INITIAL_STATE_ID) {
        check(state.parentStateId == null && state.createdByCandidateId == null)
      } else {
        check(states.containsKey(state.parentStateId)) { "Unknown parent ${state.parentStateId}" }
        if (state.kind == "CANDIDATE") {
          check(candidates.containsKey(state.createdByCandidateId)) {
            "Unknown creator ${state.createdByCandidateId}"
          }
        } else {
          check(state.kind == "SYSTEM" && state.createdByCandidateId == null)
        }
      }
    }
    candidates.values.forEach { candidate ->
      check(states.containsKey(candidate.baseStateId)) {
        "Unknown baseStateId ${candidate.baseStateId}"
      }
      candidate.resultStateId?.let { resultStateId ->
        val state = checkNotNull(states[resultStateId]) { "Unknown resultStateId $resultStateId" }
        check(state.createdByCandidateId == candidate.candidateId)
        check(candidate.becameBest)
      }
      check(candidate.transformation["kind"] != null) { "Missing transformation kind" }
    }
    states.values.forEach { state ->
      val visited = mutableSetOf<String>()
      var cursor: StateRecord? = state
      while (cursor != null) {
        check(visited.add(cursor.stateId)) { "Cycle in state ancestry at ${cursor.stateId}" }
        cursor = cursor.parentStateId?.let(states::get)
      }
      check(
        visited.contains(INITIAL_STATE_ID),
      ) { "State ${state.stateId} is detached from initial" }
    }
    finalStateId?.let { check(states.containsKey(it)) { "Unknown finalStateId $it" } }
  }

  private fun snapshot(
    program: TokenizedProgram?,
    output: LazyProgramOutputer?,
  ): Snapshot {
    val contentList = output?.fileContentList.orEmpty()
    val formattedContent =
      contentList
        .singleOrNull { it.fileName == LazyProgramOutputer.FORMATTED_PROGRAM_FILE_NAME }
        ?.content
        ?.asTextFileContent
        ?.text
    val outputFiles =
      contentList
        .asSequence()
        .filter { it.fileName != LazyProgramOutputer.FORMATTED_PROGRAM_FILE_NAME }
        .map { SourceFile(it.fileName, it.content.asTextFileContent.text) }
        .sortedBy { it.path }
        .toList()
    val files =
      if (formattedContent == null) {
        outputFiles
      } else {
        check(outputFiles.size == 1) {
          "A formatted tokenized program requires exactly one source file, " +
            "but found ${outputFiles.size}"
        }
        listOf(outputFiles.single().copy(content = formattedContent))
      }
    return Snapshot(
      tokenCount = program?.tokenCount ?: 0,
      tokens = program?.tokens?.map { it.lexemeText }.orEmpty(),
      files = files,
    )
  }

  private fun intern(snapshot: Snapshot): String {
    val digest = MessageDigest.getInstance("SHA-256")
    snapshot.files.forEach { file ->
      updateDigest(digest, file.path)
      updateDigest(digest, file.content)
    }
    val programRef = "p_" + digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    programs.putIfAbsent(
      programRef,
      linkedMapOf(
        "files" to snapshot.files.map(SourceFile::toDocument),
        "tokenCount" to snapshot.tokenCount,
        "tokens" to
          snapshot.tokens.mapIndexed { index, text ->
            linkedMapOf("index" to index, "text" to text)
          },
      ),
    )
    return programRef
  }

  private fun createPatches(
    base: Snapshot,
    candidate: Snapshot,
  ): List<Map<String, Any>> {
    val baseFiles = base.files.associateBy(SourceFile::path)
    val candidateFiles = candidate.files.associateBy(SourceFile::path)
    return (baseFiles.keys + candidateFiles.keys).sorted().mapNotNull { path ->
      val before = baseFiles[path]?.content
      val after = candidateFiles[path]?.content
      if (before == after) {
        return@mapNotNull null
      }
      val kind =
        when {
          before == null -> "ADD"
          after == null -> "DELETE"
          else -> "MODIFY"
        }
      val beforeLines = before?.lines().orEmpty()
      val afterLines = after?.lines().orEmpty()
      val patch = DiffUtils.diff(beforeLines, afterLines)
      val diff =
        DiffUtils
          .generateUnifiedDiff(
            if (before == null) "/dev/null" else "a/$path",
            if (after == null) "/dev/null" else "b/$path",
            beforeLines,
            patch,
            3,
          ).joinToString("\n")
      linkedMapOf("path" to path, "kind" to kind, "diff" to diff)
    }
  }

  private fun relativeMillis(timestampMillis: Long): Long? =
    startedAtMillis?.let { timestampMillis - it }

  private data class SourceFile(
    val path: String,
    val content: String,
  ) {
    fun toDocument(): Map<String, Any> =
      linkedMapOf(
        "path" to path,
        "content" to content,
        "lines" to content.count { it == '\n' },
        "chars" to content.length,
      )
  }

  private data class Snapshot(
    val tokenCount: Int,
    val tokens: List<String>,
    val files: List<SourceFile>,
  )

  private data class StateRecord(
    val stateId: String,
    val parentStateId: String?,
    val createdByCandidateId: String?,
    val programRef: String,
    val tokens: Int,
    val kind: String,
    val acceptedAtSeq: Long?,
    val reason: String?,
  ) {
    fun toDocument(): Map<String, Any?> =
      linkedMapOf(
        "stateId" to stateId,
        "parentStateId" to parentStateId,
        "createdByCandidateId" to createdByCandidateId,
        "programRef" to programRef,
        "tokens" to tokens,
        "kind" to kind,
        "acceptedAtSeq" to acceptedAtSeq,
        "reason" to reason,
      )
  }

  private data class StepRecord(
    val candidateId: String?,
    val fromStateId: String,
    val toStateId: String,
    val acceptedAtSeq: Long,
    val systemReason: String?,
  ) {
    fun toDocument(
      index: Int,
      states: Map<String, StateRecord>,
      candidates: Map<String, CandidateRecord>,
      patches: List<Map<String, Any>>,
    ): Map<String, Any?> {
      val before = states.getValue(fromStateId)
      val after = states.getValue(toStateId)
      return linkedMapOf(
        "index" to index,
        "candidateId" to candidateId,
        "fromStateId" to fromStateId,
        "toStateId" to toStateId,
        "acceptedAtSeq" to acceptedAtSeq,
        "tokensBefore" to before.tokens,
        "tokensAfter" to after.tokens,
        "programRef" to after.programRef,
        "baseProgramRef" to before.programRef,
        "patches" to patches,
        "transformation" to
          if (candidateId == null) {
            linkedMapOf("kind" to "SYSTEM", "reason" to systemReason)
          } else {
            candidates.getValue(candidateId).transformation
          },
      )
    }
  }

  private data class CandidateRecord(
    val candidateId: String,
    val editId: Int,
    val baseStateId: String,
    var resultStateId: String?,
    var status: String,
    var becameBest: Boolean,
    var observedAtSeq: Long?,
    var acceptedAtSeq: Long?,
    var observedAtMillis: Long?,
    var acceptedAtMillis: Long?,
    var snapshot: Snapshot,
    var programRef: String?,
    var exitCode: Int?,
    var elapsedMillis: Long?,
    var cancelDurationMillis: Int?,
    val transformation: Map<String, Any?>,
  ) {
    companion object {
      fun fromEdit(
        edit: AbstractSparTreeEdit<*>,
        reducer: String?,
        reducerPass: Int,
        status: String,
        observedAtSeq: Long?,
        observedAtMillis: Long?,
        snapshot: Snapshot,
      ): CandidateRecord =
        CandidateRecord(
          candidateId = edit.candidateId,
          editId = edit.id,
          baseStateId = edit.baseStateId,
          resultStateId = null,
          status = status,
          becameBest = false,
          observedAtSeq = observedAtSeq,
          acceptedAtSeq = null,
          observedAtMillis = observedAtMillis,
          acceptedAtMillis = null,
          snapshot = snapshot,
          programRef = null,
          exitCode = null,
          elapsedMillis = null,
          cancelDurationMillis = null,
          transformation = transformation(edit, reducer, reducerPass),
        )

      private fun transformation(
        edit: AbstractSparTreeEdit<*>,
        reducer: String?,
        reducerPass: Int,
      ): Map<String, Any?> =
        linkedMapOf(
          "kind" to edit.transformationKind.name,
          "editClass" to edit.javaClass.simpleName,
          "description" to edit.actionSet.actionsDescription,
          "reducer" to reducer,
          "reducerPass" to reducerPass,
          "actions" to
            edit.actionSet.actions.map { action ->
              linkedMapOf<String, Any?>(
                "kind" to
                  when (action) {
                    is NodeDeletionAction -> "DELETE"
                    is NodeReplacementAction -> "REPLACE"
                  },
                "targetNodeId" to action.targetNode.nodeId,
                "replacementNodeId" to
                  (action as? NodeReplacementAction)?.replacingNode?.nodeId,
              )
            },
          "targets" to
            edit.actionSet.actions.map { action ->
              val target = action.targetNode
              linkedMapOf(
                "nodeId" to target.nodeId,
                "ruleName" to target.ruleName,
                "ruleType" to (target as? ParserRuleSparTreeNode)?.ruleType?.name,
                "replacementNodeId" to
                  (action as? NodeReplacementAction)?.replacingNode?.nodeId,
              )
            },
        )
    }
  }

  companion object {
    const val SCHEMA_VERSION = "2.0.0"
    private const val INITIAL_STATE_ID = "initial"
    private const val INVALID_SYNTAX_EXIT_CODE = 99
    private val REJECTED_STATUSES = setOf("REJECTED", "INVALID")

    private fun updateDigest(
      digest: MessageDigest,
      value: String,
    ) {
      val bytes = value.toByteArray(StandardCharsets.UTF_8)
      digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
      digest.update(0)
      digest.update(bytes)
      digest.update(0)
    }
  }
}
