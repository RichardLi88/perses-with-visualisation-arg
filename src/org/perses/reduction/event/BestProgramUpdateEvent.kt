/*
 * Copyright (C) 2018-2025 University of Waterloo.
 *
 * This file is part of Perses.
 *
 * Perses is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3, or (at your option) any later version.
 *
 * Perses is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Perses; see the file LICENSE.  If not see <http://www.gnu.org/licenses/>.
 */
package org.perses.reduction.event

import com.google.common.collect.ImmutableList
import org.perses.program.TokenizedProgram
import org.perses.spartree.AbstractSparTreeEdit
import org.perses.util.FileNameContentPair

class BestProgramUpdateEvent(
  val currentFixpointIteration: FixpointIterationStartEvent,
  currentTimeMillis: Long,
  val programSizeBefore: Int,
  val edit: AbstractSparTreeEdit<*>,
  val program: TokenizedProgram,
  outputCreator: (TokenizedProgram) -> ImmutableList<FileNameContentPair<String>>,
) : AbstractReductionEventWithProgramSize(currentTimeMillis, program.tokenCount) {
  val textualProgram = LazyProgramOutputer(program, outputCreator)

  init {
    // FIXME(cnsun): this also needs to check the num of chars of tokens in the case of ==.
    //   FIXME(cnsun): fix this assertion
    //   check(programSizeBefore >= programSizeAfter)
  }

  override fun initialProgramSize() = currentFixpointIteration.initialProgramSize()

  override val prefixLabelFromRootToHere: String
    get() = currentFixpointIteration.prefixLabelFromRootToHere
}
