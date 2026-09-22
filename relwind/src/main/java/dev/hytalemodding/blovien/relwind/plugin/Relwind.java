/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.plugin;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.RelationshipTypeRegistry;
import dev.hytalemodding.blovien.relwind.Relationships;
import dev.hytalemodding.blovien.relwind.coreserver.CoreServerIntegration;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class Relwind extends JavaPlugin {
    @Nullable
    private static volatile Relwind instance;

    @Nullable
    private CoreServerIntegration relationshipsIntegration;

    @Nullable
    private Relationships relationships;

    public Relwind(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        relationshipsIntegration = CoreServerIntegration.register(getEventRegistry(), getEntityStoreRegistry(), getChunkStoreRegistry());
        relationships = new Relationships();
        instance = this;
    }

    @Override
    protected void shutdown() {
        var lock = AssetRegistry.ASSET_LOCK.writeLock();
        lock.lock();
        try {
            instance = null;
            if (relationships != null) {
                relationships.close();
                relationships = null;
            }
            if (relationshipsIntegration == null) return;
            try {
                relationshipsIntegration.close();
            } finally {
                relationshipsIntegration = null;
            }
        } finally {
            lock.unlock();
        }
    }

    /// Relwind singleton and API access
    /// @throws IllegalStateException before successful setup or after shutdown
    @Nonnull
    public static Relwind get() {
        var current = instance;
        if (current == null) {
            throw new IllegalStateException(
                "Relwind is not initialized or has stopped. Declare Blovien:Relwind as a plugin dependency");
        }
        return current;
    }

    @Nonnull
    @SuppressWarnings("DataFlowIssue")
    public Relationships getRelationships() {
        requireInitialized();
        return relationships;
    }

    @Nonnull
    public RelationshipTypeRegistry<EntityStore> getEntityRelationshipTypeRegistry() {
        return getIntegration().getEntityInstallation().getRelationshipTypeRegistry();
    }

    @Nonnull
    public RelationshipTypeRegistry<ChunkStore> getChunkRelationshipTypeRegistry() {
        return getIntegration().getChunkInstallation().getRelationshipTypeRegistry();
    }

    private void requireInitialized() {
        if (instance != this) {
            throw new IllegalStateException("Relwind is not initialized or has stopped");
        }
    }

    @Nonnull
    @SuppressWarnings("DataFlowIssue")
    private CoreServerIntegration getIntegration() {
        requireInitialized();
        return relationshipsIntegration;
    }
}
