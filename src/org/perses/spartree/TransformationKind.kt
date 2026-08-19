/*
 * Copyright (C) 2018-2025 University of Waterloo.
 *
 * This file is part of Perses.
 *
 * Perses is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3, or (at your option) any later version.
 */
package org.perses.spartree

/** Stable, machine-readable categories for reduction transformations. */
enum class TransformationKind {
  DELETE,
  DELTA_DEBUG,
  LIST_MINIMIZE,
  TOKEN_SLICE,
  LINE_SLICE,
  HOIST,
  REPLACE,
  LATRA,
  LLM,
  OTHER,
}
