/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

/// Why a linked entity left its Store without being deleted.
public enum UnloadReason {
    /// the reason is not known yet, and `RelationshipTracker.onUnloadResolved` supplies it later
    PENDING,
    /// the entity moved to another Store
    TRANSFER,
    /// the entity is expected back in the same Store, for example after a disconnect or a parked section
    DEACTIVATION
}
