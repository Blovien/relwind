/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3iUtil;
import com.hypixel.hytale.server.core.universe.world.WorldProvider;
import dev.hytalemodding.blovien.relwind.PersistenceIdentity;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;

/// World block position identities and deletion evidence for the chunk Store of each world.
public final class ChunkPersistenceIdentity implements PersistenceIdentity<ChunkStore, Vector3i> {
    private final ChunkPositions positions;
    private final Tombstones.Installation<ChunkStore, Vector3i> deletions;

    ChunkPersistenceIdentity(ChunkPositions positions, Tombstones.Installation<ChunkStore, Vector3i> deletions) {
        this.positions = positions;
        this.deletions = deletions;
    }

    @Override
    public Vector3i getIdentity(Store<ChunkStore> store, Ref<ChunkStore> ref) {
        return positions.getPositionOf(store, ref);
    }

    @Nonnull
    @Override
    public Codec<Vector3i> getIdentityCodec() {
        return Vector3iUtil.CODEC;
    }

    @Nonnull
    @Override
    public String getInstallationName() {
        return "CHUNK_POSITIONS";
    }

    /// A world block position repeats in every world.
    @Override
    public boolean isScopedToStore() {
        return true;
    }

    @Override
    public boolean isDeleted(Store<ChunkStore> store, Vector3i id) {
        return deletions.getTombstonesIn(store).contains(id);
    }

    @Override
    public Store<ChunkStore> storeBeside(Store<?> peer) {
        if (!(peer.getExternalData() instanceof WorldProvider provider)) return null;
        var store = provider.getWorld().getChunkStore().getStore();
        return store.isShutdown() ? null : store;
    }
}
