/*
 * Copyright (C) 2018-2025 University of Waterloo.
 *
 * This file is part of Perses.
 *
 * Perses is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3, or (at your option) any later version.
 */
package org.perses.reduction.event

import com.google.common.collect.ImmutableList
import org.perses.program.TokenizedProgram
import org.perses.util.FileNameContentPair

/** A source-changing transition which was not produced by a candidate tree edit. */
class ProgramStateTransitionEvent(
  currentTimeMillis: Long,
  val parentStateId: String,
  val resultStateId: String,
  val reason: String,
  val program: TokenizedProgram,
  outputCreator: (TokenizedProgram) -> ImmutableList<FileNameContentPair<String>>,
) : AbstractReductionEvent(currentTimeMillis) {
  val textualProgram = LazyProgramOutputer(program, outputCreator)
}
