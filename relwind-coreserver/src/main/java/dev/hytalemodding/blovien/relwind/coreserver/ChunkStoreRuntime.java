/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import dev.hytalemodding.blovien.relwind.RelationshipTracker;
import dev.hytalemodding.blovien.relwind.StoreRuntime;
import org.joml.Vector3i;

import javax.annotation.Nonnull;

import java.util.Objects;

/// The Store runtime of the chunk Store. A deferred write runs on the block entity's world, and
/// a save mark goes to the section that holds the block.
final class ChunkStoreRuntime implements StoreRuntime<ChunkStore> {
    private final ChunkPositions positions;
    private final Tombstones.Installation<ChunkStore, Vector3i> deletions;

    ChunkStoreRuntime(ChunkPositions positions, Tombstones.Installation<ChunkStore, Vector3i> deletions) {
        this.positions = Objects.requireNonNull(positions, "positions");
        this.deletions = Objects.requireNonNull(deletions, "deletions");
    }

    @Override
    public void execute(Store<ChunkStore> store, Runnable action) {
        store.getExternalData().getWorld().execute(action);
    }

    @Override
    public void markNeedsSaving(ComponentAccessor<ChunkStore> accessor, Ref<ChunkStore> ref) {
        positions.markNeedsSaving(accessor, ref);
    }

    @Override
    public void markNeedsSaving(Holder<ChunkStore> parked) {
        positions.markNeedsSaving(parked);
    }

    /// Relwind never removes a block entity.
    @Override
    public boolean isDeletionSupported() {
        return false;
    }

    @Nonnull
    @Override
    public RefSystem<ChunkStore> getTransitionSystem(RelationshipTracker<ChunkStore, ?> installed) {
        @SuppressWarnings("unchecked")
        var relationships = (RelationshipTracker<ChunkStore, Vector3i>) installed;
        return new ChunkRefSystem(positions, relationships, deletions);
    }
}
