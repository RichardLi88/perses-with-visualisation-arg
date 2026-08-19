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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.perses.reduction.ReducerFunctionalTestUtility
import org.perses.reduction.reducer.PersesNodePrioritizedDfsReducer
import org.perses.util.FileStreamPool
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readLines

@RunWith(JUnit4::class)
class JsonlVisualizationListenerTest {
  @Test
  fun testCriticalExceptionUsesVersionOneErrorContract() {
    val traceFile = Files.createTempFile("perses-visualization-error", ".jsonl")
    try {
      FileStreamPool().use { streamPool ->
        JsonlVisualizationListener(
          streamPool.rentStream(traceFile, JsonlVisualizationListener::class.toString()),
        ).use { listener ->
          listener.onCriticalException(IllegalStateException("reducer failed"))
        }
      }

      val record = ObjectMapper().readTree(traceFile.toFile())
      assertThat(record["schemaVersion"].intValue()).isEqualTo(1)
      assertThat(record["sequence"].longValue()).isEqualTo(0L)
      assertThat(record["type"].textValue()).isEqualTo("error")
      assertThat(record["exceptionClass"].textValue())
        .isEqualTo(IllegalStateException::class.java.name)
      assertThat(record["message"].textValue()).isEqualTo("reducer failed")
      assertThat(record["stackTrace"].textValue()).contains("IllegalStateException")
    } finally {
      traceFile.deleteIfExists()
    }
  }

  @Test
  fun testVisualizationTraceLinksTestedCandidatesToCommits() {
    val traceFile = Files.createTempFile("perses-visualization", ".jsonl")
    try {
      ReducerFunctionalTestUtility(
        reductionFolder = "test_data/delta_1",
        testScript = "r.sh",
        sourceFile = "t.c",
        reducerAnnotation = PersesNodePrioritizedDfsReducer.META,
        cmdCustomizer = {
          it.profilingFlags.visualizationDumpFile = traceFile
          it.verbosityFlags.fullyDeterministicMode = true
          it.latraFlags.enableLatra = false
        },
      ).use { utility ->
        utility.reductionDriver.reduce()
      }

      val records = traceFile.readLines().map(ObjectMapper()::readTree)
      assertThat(records).isNotEmpty()
      assertThat(records.map { it["sequence"].longValue() })
        .containsExactlyElementsIn(records.indices.map(Int::toLong))
        .inOrder()
      assertThat(records.map { it["schemaVersion"].intValue() }.distinct())
        .containsExactly(JsonlVisualizationListener.SCHEMA_VERSION)
      assertThat(records.first()["type"].textValue()).isEqualTo("run_started")
      assertThat(records.last()["type"].textValue()).isEqualTo("run_finished")
      assertThat(records.first()["initialRevision"].longValue()).isEqualTo(0L)
      assertThat(records.first().has("revision")).isFalse()

      val testedCandidates =
        records
          .filter { it.hasType("candidate_tested") }
          .associateBy { it["candidateId"].textValue() }
      val commits = records.filter { it.hasType("candidate_committed") }
      assertThat(testedCandidates).isNotEmpty()
      assertThat(commits).isNotEmpty()
      assertThat(
        commits.any { commit ->
          testedCandidates[commit["candidateId"].textValue()]?.get("result")?.textValue() == "pass"
        },
      ).isTrue()

      records.filter { it.hasType("candidate_tested") }.forEach { candidate ->
        assertThat(candidate["candidateId"].isTextual).isTrue()
        assertThat(candidate["result"].textValue()).isAnyOf("pass", "fail")
        assertThat(candidate["edit"]["kind"].textValue()).isNotEmpty()
        candidate["edit"]["actions"].forEach { action ->
          assertThat(action["kind"].textValue()).isAnyOf("DELETE", "REPLACE")
        }
      }

      commits.forEachIndexed { index, commit ->
        assertThat(commit["baseRevision"].longValue()).isEqualTo(index.toLong())
        assertThat(commit["newRevision"].longValue()).isEqualTo(index + 1L)
        assertSnapshot(commit["snapshot"])
      }
      assertSnapshot(records.first()["snapshot"])
      assertThat(records.last()["finalRevision"].longValue()).isEqualTo(commits.size.toLong())
      assertThat(records.last().has("testExecutionCount")).isTrue()
      assertThat(records.last().has("externalCacheHitCount")).isTrue()
      assertThat(records.last().has("scriptExecutions")).isFalse()
      assertThat(records.last().has("externalCacheHits")).isFalse()
    } finally {
      traceFile.deleteIfExists()
    }
  }

  private fun assertSnapshot(snapshot: JsonNode) {
    assertThat(snapshot["tokens"].size()).isEqualTo(snapshot["tokenCount"].intValue())
    snapshot["tokens"].forEachIndexed { index, token ->
      assertThat(token["index"].intValue()).isEqualTo(index)
      assertThat(token["text"].isTextual).isTrue()
    }
    assertThat(snapshot["files"].isEmpty).isFalse()
    assertThat(snapshot.has("formattedSource")).isFalse()
    val mainFile = snapshot["files"].first { it["path"].textValue() == "t.c" }
    assertThat(mainFile["content"].textValue()).contains("\n")
  }

  private fun JsonNode.hasType(type: String): Boolean = get("type").textValue() == type
}
