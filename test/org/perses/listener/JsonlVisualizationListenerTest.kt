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
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readLines

@RunWith(JUnit4::class)
class JsonlVisualizationListenerTest {
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

      val testedCandidates =
        records
          .filter { it.hasType("candidate_tested") }
          .associateBy { it["candidateId"].longValue() }
      val commits = records.filter { it.hasType("candidate_committed") }
      assertThat(testedCandidates).isNotEmpty()
      assertThat(commits).isNotEmpty()
      assertThat(
        commits.any { commit ->
          testedCandidates[commit["candidateId"].longValue()]?.get("result")?.textValue() == "PASS"
        },
      ).isTrue()

      commits.forEachIndexed { index, commit ->
        assertThat(commit["baseRevision"].longValue()).isEqualTo(index.toLong())
        assertThat(commit["newRevision"].longValue()).isEqualTo(index + 1L)
        assertSnapshot(commit["snapshot"])
      }
      assertSnapshot(records.first()["snapshot"])
      assertThat(records.last()["finalRevision"].longValue()).isEqualTo(commits.size.toLong())
    } finally {
      traceFile.deleteIfExists()
    }
  }

  private fun assertSnapshot(snapshot: JsonNode) {
    assertThat(snapshot["tokens"].size()).isEqualTo(snapshot["tokenCount"].intValue())
    assertThat(snapshot["files"].isEmpty).isFalse()
    assertThat(snapshot["formattedSource"].textValue()).contains("int printf")
    val mainFile = snapshot["files"].first { it["name"].textValue() == "t.c" }
    assertThat(mainFile["content"].textValue()).contains("\n")
  }

  private fun JsonNode.hasType(type: String): Boolean = get("type").textValue() == type
}
