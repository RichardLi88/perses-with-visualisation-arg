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
import org.perses.program.TokenizedProgram
import org.perses.reduction.AbstractReductionListener
import org.perses.reduction.event.AbstractTestScriptExecutionEvent
import org.perses.reduction.event.BestProgramUpdateEvent
import org.perses.reduction.event.LazyProgramOutputer
import org.perses.reduction.event.ReductionEndEvent
import org.perses.reduction.event.ReductionStartEvent
import org.perses.spartree.AbstractSparTreeEdit
import org.perses.spartree.AnyNodeReplacementTreeEdit
import org.perses.spartree.DescendantHoistingTreeEdit
import org.perses.spartree.LatraGeneralTreeEdit
import org.perses.spartree.NodeDeletionAction
import org.perses.spartree.NodeDeletionTreeEdit
import org.perses.spartree.NodeReplacementAction
import org.perses.util.FileStreamPool
import java.util.Collections
import java.util.IdentityHashMap

/** Writes a versioned, line-delimited JSON trace for reduction visualizers. */
class JsonlVisualizationListener(
  private val stream: FileStreamPool.ManagedPrintStream,
) : AbstractReductionListener() {
  private val objectMapper = ObjectMapper()
  private val candidateIds = IdentityHashMap<AbstractSparTreeEdit<*>, Long>()
  private val committedEdits =
    Collections.newSetFromMap(
      IdentityHashMap<AbstractSparTreeEdit<*>, Boolean>(),
    )
  private var nextCandidateId = 1L
  private var sequence = 0L
  private var revision = 0L

  override fun onReductionStart(event: ReductionStartEvent) {
    writeEvent(
      type = "run_started",
      timestampMillis = event.currentTimeMillis,
      fields =
        linkedMapOf(
          "initialRevision" to revision,
          "snapshot" to snapshot(event.program, event.textualProgram),
        ),
    )
  }

  override fun onTestScriptExecution(
    event: AbstractTestScriptExecutionEvent.TestScriptExecutionEvent,
  ) {
    writeCandidate(
      type = "candidate_tested",
      timestampMillis = event.currentTimeMillis,
      event = event,
      extraFields =
        linkedMapOf(
          "result" to if (event.result.isInteresting) "pass" else "fail",
          "exitCode" to event.result.exitCode.intValue,
          "elapsedMillis" to event.result.elapsedMillis,
        ),
    )
  }

  override fun onTestResultCacheHit(
    event: AbstractTestScriptExecutionEvent.TestResultCacheHitEvent,
  ) {
    writeCandidate(
      type = "candidate_cache_hit",
      timestampMillis = event.currentTimeMillis,
      event = event,
      extraFields = linkedMapOf("result" to "fail"),
    )
  }

  override fun onTestScriptExecutionCancelled(
    event: AbstractTestScriptExecutionEvent.TestScriptExecutionCanceledEvent,
  ) {
    writeCandidate(
      type = "candidate_cancelled",
      timestampMillis = event.currentTimeMillis,
      event = event,
      extraFields = linkedMapOf("cancelDurationMillis" to event.millisToCancelTheTask),
    )
  }

  override fun onBestProgramUpdated(event: BestProgramUpdateEvent) {
    if (!committedEdits.add(event.edit)) {
      return
    }
    val baseRevision = revision
    ++revision
    writeEvent(
      type = "candidate_committed",
      timestampMillis = event.currentTimeMillis,
      fields =
        linkedMapOf(
          "candidateId" to candidateId(event.edit),
          "baseRevision" to baseRevision,
          "newRevision" to revision,
          "beforeTokenCount" to event.programSizeBefore,
          "afterTokenCount" to event.program.tokenCount,
          "edit" to editMetadata(event.edit),
          "snapshot" to snapshot(event.program, event.textualProgram),
        ),
    )
  }

  override fun onCriticalException(exception: Exception) {
    writeEvent(
      type = "error",
      timestampMillis = System.currentTimeMillis(),
      fields =
        linkedMapOf(
          "exceptionClass" to exception.javaClass.name,
          "message" to exception.message,
          "stackTrace" to exception.stackTraceToString(),
        ),
    )
  }

  override fun onReductionEnd(event: ReductionEndEvent) {
    writeEvent(
      type = "run_finished",
      timestampMillis = event.currentTimeMillis,
      fields =
        linkedMapOf(
          "finalRevision" to revision,
          "finalTokenCount" to event.programSize,
          "testExecutionCount" to
            event.testScriptExecutorServiceStatistics.scriptExecutionNumber,
          "externalCacheHitCount" to
            event.testScriptExecutorServiceStatistics.externalCacheHitNumber,
        ),
    )
  }

  override fun close() {
    stream.close()
  }

  private fun writeCandidate(
    type: String,
    timestampMillis: Long,
    event: AbstractTestScriptExecutionEvent,
    extraFields: LinkedHashMap<String, Any>,
  ) {
    val fields =
      linkedMapOf<String, Any>(
        "candidateId" to candidateId(event.edit),
        "baseRevision" to revision,
        "edit" to editMetadata(event.edit),
        "snapshot" to snapshot(event.program, event.textualProgram),
      )
    fields.putAll(extraFields)
    writeEvent(type, timestampMillis, fields)
  }

  private fun candidateId(edit: AbstractSparTreeEdit<*>): String =
    candidateIds.computeIfAbsent(edit) { nextCandidateId++ }.toString()

  private fun snapshot(
    program: TokenizedProgram?,
    output: LazyProgramOutputer?,
  ): Map<String, Any?> {
    val outputFiles = output?.fileContentList.orEmpty()
    return linkedMapOf(
      "tokenCount" to (program?.tokenCount ?: 0),
      "tokens" to
        (
          program?.tokens?.mapIndexed { index, token ->
            linkedMapOf(
              "index" to index,
              "text" to token.lexemeText,
            )
          } ?: emptyList<Map<String, Any>>()
        ),
      "files" to
        outputFiles
          .asSequence()
          .filter { it.fileName != LazyProgramOutputer.FORMATTED_PROGRAM_FILE_NAME }
          .map {
            linkedMapOf(
              "path" to it.fileName,
              "content" to it.content.asTextFileContent.text,
            )
          }.toList(),
    )
  }

  private fun editMetadata(edit: AbstractSparTreeEdit<*>): Map<String, Any> =
    linkedMapOf(
      "kind" to
        when (edit) {
          is NodeDeletionTreeEdit -> "NODE_DELETION"
          is DescendantHoistingTreeEdit -> "DESCENDANT_HOISTING"
          is AnyNodeReplacementTreeEdit -> "ANY_NODE_REPLACEMENT"
          is LatraGeneralTreeEdit -> "LATRA_GENERAL"
          else -> error("Unsupported edit type: ${edit::class.qualifiedName}")
        },
      "description" to edit.actionSet.actionsDescription,
      "actions" to
        edit.actionSet.actions.map { action ->
          val fields =
            linkedMapOf<String, Any>(
              "kind" to
                when (action) {
                  is NodeDeletionAction -> "DELETE"
                  is NodeReplacementAction -> "REPLACE"
                },
              "targetNodeId" to action.targetNode.nodeId,
              "description" to action.description,
            )
          if (action is NodeReplacementAction) {
            fields["replacementNodeId"] = action.replacingNode.nodeId
          }
          fields
        },
    )

  private fun writeEvent(
    type: String,
    timestampMillis: Long,
    fields: Map<String, Any?>,
  ) {
    val record =
      linkedMapOf<String, Any?>(
        "schemaVersion" to SCHEMA_VERSION,
        "sequence" to sequence++,
        "timestampMillis" to timestampMillis,
        "type" to type,
      )
    record.putAll(fields)
    stream.println(objectMapper.writeValueAsString(record))
    stream.flush()
  }

  companion object {
    const val SCHEMA_VERSION = 1
  }
}
