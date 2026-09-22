/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import dev.hytalemodding.blovien.relwind.RelationshipTracker;
import dev.hytalemodding.blovien.relwind.UnloadReason;
import dev.hytalemodding.blovien.relwind.compat.AllEntitiesQuerySystem;
import org.joml.Vector3i;

import javax.annotation.Nullable;

import java.util.Objects;

/// Turns block entity loads and removals into loads, unloads and deletions of linked entities. A section is
/// dense storage, never a linked entity.
final class ChunkRefSystem extends RefSystem<ChunkStore> implements AllEntitiesQuerySystem<ChunkStore> {
    private final ChunkPositions positions;
    private final RelationshipTracker<ChunkStore, Vector3i> relationships;
    private final Tombstones.Installation<ChunkStore, Vector3i> deletions;
    private final Query<ChunkStore> query;
    ChunkRefSystem(
        ChunkPositions positions,
        RelationshipTracker<ChunkStore, Vector3i> relationships,
        Tombstones.Installation<ChunkStore, Vector3i> deletions
    ) {
        this.positions = Objects.requireNonNull(positions, "positions");
        this.relationships = Objects.requireNonNull(relationships, "relationships");
        this.deletions = Objects.requireNonNull(deletions, "deletions");
        query = positions.blockState();
    }

    @Nullable
    @Override
    public Query<ChunkStore> getQuery() {
        return query;
    }

    @Override
    public void onEntityAdded(
        Ref<ChunkStore> ref,
        AddReason reason,
        Store<ChunkStore> store,
        CommandBuffer<ChunkStore> commandBuffer
    ) {
        var position = positions.getPositionOf(store, ref);
        if (position == null) return;
        relationships.onEntityLoaded(commandBuffer, position, ref);
    }

    @Override
    public void onEntityRemove(
        Ref<ChunkStore> ref,
        RemoveReason reason,
        Store<ChunkStore> store,
        CommandBuffer<ChunkStore> commandBuffer
    ) {
        var position = positions.getPositionOf(store, ref);
        if (position == null) return;
        // Hytale invalidates the Ref after this callback
        relationships.onEntityUnloading(commandBuffer, position, ref);
        // UNLOAD parks the block entity. REMOVE and BUILDER_TOOLS_UNDO delete it.
        if (reason != RemoveReason.UNLOAD) {
            deletions.getTombstonesIn(store).record(position);
            commandBuffer.run(ignored -> relationships.onEntityDeleted(position, ref));
            return;
        }
        commandBuffer.run(ignored -> relationships.onEntityUnloaded(position, ref,
            UnloadReason.DEACTIVATION));
    }
}
