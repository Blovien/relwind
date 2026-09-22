/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.examples.circuit;

/// The immutable link data of one wire: the strength that reached the block at its far end on the last tick.
public record Signal(int strength) {
}
