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
import org.perses.program.EnumFormatControl
import org.perses.reduction.ReducerFunctionalTestUtility
import org.perses.reduction.reducer.PersesNodePrioritizedDfsReducer
import org.perses.util.FileStreamPool
import java.nio.file.Files
import kotlin.io.path.deleteIfExists

@RunWith(JUnit4::class)
class ReductionTraceListenerTest {
  @Test
  fun testCriticalExceptionProducesFailedVersionTwoDocument() {
    val traceFile = Files.createTempFile("perses-visualization-error", ".json")
    try {
      FileStreamPool().use { streamPool ->
        ReductionTraceListener(
          streamPool.rentStream(traceFile, ReductionTraceListener::class.toString()),
        ).use { listener ->
          listener.onCriticalException(IllegalStateException("reducer failed"))
        }
      }

      val document = ObjectMapper().readTree(traceFile.toFile())
      assertThat(document["schemaVersion"].textValue()).isEqualTo("2.0.0")
      assertThat(document["meta"]["status"].textValue()).isEqualTo("FAILED")
      assertThat(document["errors"].single()["exceptionClass"].textValue())
        .isEqualTo(IllegalStateException::class.java.name)
      assertThat(document["errors"].single()["message"].textValue()).isEqualTo("reducer failed")
    } finally {
      traceFile.deleteIfExists()
    }
  }

  @Test
  fun testTraceHasAuthoritativeStatesAndTransformationKinds() {
    val traceFile = Files.createTempFile("perses-visualization", ".json")
    try {
      ReducerFunctionalTestUtility(
        reductionFolder = "test_data/delta_1",
        testScript = "r.sh",
        sourceFile = "t.c",
        reducerAnnotation = PersesNodePrioritizedDfsReducer.META,
        cmdCustomizer = {
          it.profilingFlags.visualizationDumpFile = traceFile
          it.reductionControlFlags.codeFormat = EnumFormatControl.SINGLE_TOKEN_PER_LINE
          it.verbosityFlags.fullyDeterministicMode = true
          it.latraFlags.enableLatra = false
        },
      ).use { utility ->
        utility.reductionDriver.reduce()
      }

      val document = ObjectMapper().readTree(traceFile.toFile())
      assertThat(document["schemaVersion"].textValue()).isEqualTo("2.0.0")
      assertThat(document["meta"]["status"].textValue()).isEqualTo("COMPLETED")
      assertThat(document["originalStateId"].textValue()).isEqualTo("initial")

      val states = document["states"].associateBy { it["stateId"].textValue() }
      val candidates = document["candidates"].associateBy { it["candidateId"].textValue() }
      val steps = document["steps"]
      assertThat(states.keys).contains("initial")
      assertThat(candidates).isNotEmpty()
      assertThat(steps.isEmpty).isFalse()
      assertThat(states.keys).contains(document["finalStateId"].textValue())

      val initialState = states.getValue("initial")
      val initialProgram = document["programs"][initialState["programRef"].textValue()]
      val initialFile = initialProgram["files"].single()
      assertThat(initialFile["path"].textValue()).isEqualTo("t.c")
      assertThat(initialFile["content"].textValue())
        .contains("int main (int argc, char *argv[]) {")

      candidates.values.forEach { candidate ->
        assertThat(states).containsKey(candidate["baseStateId"].textValue())
        assertThat(candidate["transformation"]["kind"].textValue()).isNotEmpty()
        assertThat(candidate["transformation"]["editClass"].textValue()).isNotEmpty()
        assertThat(candidate["transformation"]["actions"].isArray).isTrue()
        if (candidate["becameBest"].booleanValue()) {
          val resultState = states.getValue(candidate["resultStateId"].textValue())
          assertThat(resultState["parentStateId"].textValue())
            .isEqualTo(candidate["baseStateId"].textValue())
          assertThat(resultState["createdByCandidateId"].textValue())
            .isEqualTo(candidate["candidateId"].textValue())
          assertThat(candidate["programRef"].isTextual).isTrue()
          assertThat(candidate["patches"].isNull).isTrue()
        } else {
          assertThat(candidate["resultStateId"].isNull).isTrue()
          assertThat(candidate["programRef"].isNull).isTrue()
          assertThat(candidate["patches"].isArray).isTrue()
        }
      }

      states.values.forEach { state ->
        val program = document["programs"][state["programRef"].textValue()]
        assertThat(program).isNotNull()
        assertThat(program["files"].isEmpty).isFalse()
        assertThat(program["tokens"].size()).isEqualTo(program["tokenCount"].intValue())
        program["files"].forEach { file ->
          assertThat(file["path"].isTextual).isTrue()
          assertThat(file["content"].isTextual).isTrue()
        }
      }

      steps.forEachIndexed { index, step ->
        assertThat(step["index"].intValue()).isEqualTo(index)
        assertThat(states).containsKey(step["fromStateId"].textValue())
        assertThat(states).containsKey(step["toStateId"].textValue())
        assertThat(step["patches"].isArray).isTrue()
        assertThat(step["transformation"]["kind"].textValue()).isNotEmpty()
      }
      val diffs = steps.flatMap { step -> step["patches"].map { it["diff"].textValue() } }
      assertThat(diffs).isNotEmpty()
      assertThat(diffs.any { it.contains("int main (int argc, char *argv[]) {") }).isTrue()
    } finally {
      traceFile.deleteIfExists()
    }
  }

  private fun JsonNode.single(): JsonNode {
    assertThat(size()).isEqualTo(1)
    return first()
  }
}
