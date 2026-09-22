/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.IComponentRegistry;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.math.vector.Vector3iUtil;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.RelationshipMetadata;
import dev.hytalemodding.blovien.relwind.StoreInstallation;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Objects;
import java.util.UUID;

/// The composition root that installs Relwind on one Hytale server for one plugin tracker.
public final class CoreServerIntegration {
    @Nullable
    private static CoreServerIntegration instance;

    private final StoreInstallation<EntityStore, UUID> entities;
    private final StoreInstallation<ChunkStore, Vector3i> chunks;
    private final CoreServerTracker tracker;
    private final PlayerLifecycle players;
    private final ComponentType<EntityStore, RelationshipPlayerSavingSystem.Pending> playerPendingType;

    // cached metadata type for player saving system
    private final ComponentType<EntityStore, RelationshipMetadata<EntityStore>> entityMetadataType;
    private boolean closed;

    /// Call after Hytale has set up EntityModule, LegacyModule and the Universe. Pass the two
    /// registry proxies Hytale gave the plugin. Hytale releases what they registered on disable.
    /// @throws IllegalStateException if an installation is already registered
    @Nonnull
    public static CoreServerIntegration register(
        EventRegistry events,
        IComponentRegistry<EntityStore> entityRegistrar,
        IComponentRegistry<ChunkStore> chunkRegistrar
    ) {
        // a second installation would register the same components again
        if (instance != null) throw new IllegalStateException("Relwind is already installed");
        var registered = new CoreServerIntegration(events, entityRegistrar, chunkRegistrar);
        instance = registered;
        return registered;
    }

    /// The installation the running plugin registered. A system reads the component types from it.
    /// @throws IllegalStateException if no installation is registered
    @Nonnull
    public static CoreServerIntegration get() {
        var registered = instance;
        if (registered == null) throw new IllegalStateException("Relwind is not installed");
        return registered;
    }

    private CoreServerIntegration(
        EventRegistry events,
        IComponentRegistry<EntityStore> entityRegistrar,
        IComponentRegistry<ChunkStore> chunkRegistrar
    ) {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(entityRegistrar, "entityRegistrar");
        Objects.requireNonNull(chunkRegistrar, "chunkRegistrar");

        var entityDeletions = new Tombstones.Installation<>(EntityStore.REGISTRY, entityRegistrar, Tombstones.ENTITY_RESOURCE_ID, CoreServerTracker.IDENTITY_CODEC);
        playerPendingType = entityRegistrar.registerComponent(RelationshipPlayerSavingSystem.Pending.class, () -> RelationshipPlayerSavingSystem.Pending.INSTANCE);
        var entityRuntime = new EntityStoreRuntime(entityDeletions);
        entities = new StoreInstallation<>(EntityStore.REGISTRY, entityRegistrar, new EntityPersistenceIdentity(entityDeletions), entityRuntime);
        var identities = (EntityIdentitySystem) entities.getTransitionSystem();
        tracker = identities.getTracker();
        players = identities.getPlayerLifecycle();
        players.registerEvents(events);
        entityRegistrar.registerSystem(identities.new ReconciliationSystem());
        entityRegistrar.registerSystem(new EntityRemovalSystem(tracker, players));
        entityRegistrar.registerSystem(new NonPlayerTeleportCompletionSystem(tracker));

        var chunkDeletions = new Tombstones.Installation<>(ChunkStore.REGISTRY, chunkRegistrar, Tombstones.CHUNK_RESOURCE_ID, Vector3iUtil.CODEC);
        var chunkPositions = ChunkPositions.installed();
        var chunkRuntime = new ChunkStoreRuntime(chunkPositions, chunkDeletions);
        chunks = new StoreInstallation<>(ChunkStore.REGISTRY, chunkRegistrar, new ChunkPersistenceIdentity(chunkPositions, chunkDeletions), chunkRuntime);
        chunkRegistrar.registerSystem(new SectionDeactivationSystem(tracker));

        var installed = entities.installPersistence();
        entityMetadataType = installed.getComponentType();
        var sources = Query.and(UUIDComponent.getComponentType(), entityMetadataType);

        entityRegistrar.registerSystem(new RelationshipPersistenceSystem<>(installed, sources, EntityIdentitySystem.class));
        entityRegistrar.registerSystem(new RelationshipPlayerSavingSystem(playerPendingType, entityMetadataType));
        entityRegistrar.registerSystem(new RelationshipPersistenceSystem.RestoreSystem<>(installed, sources, entityRuntime));

        chunkRegistrar.registerSystem(new RelationshipPersistenceSystem.ParkedHolderSystem(entityRuntime.getParkedSections(), entityMetadataType));
        var chunkPersistence = chunks.installPersistence();
        var chunkSources = Query.and(chunkPositions.blockState(), chunkPersistence.getComponentType());
        chunkRegistrar.registerSystem(new RelationshipPersistenceSystem<>(chunkPersistence, chunkSources, ChunkRefSystem.class));
        chunkRegistrar.registerSystem(new RelationshipPersistenceSystem.RestoreSystem<>(chunkPersistence, chunkSources, chunkRuntime));
    }

    @Nonnull
    public StoreInstallation<EntityStore, UUID> getEntityInstallation() {
        return entities;
    }

    @Nonnull
    public StoreInstallation<ChunkStore, Vector3i> getChunkInstallation() {
        return chunks;
    }

    @Nonnull
    public ComponentType<EntityStore, RelationshipPlayerSavingSystem.Pending> getPlayerPendingComponentType() {
        return playerPendingType;
    }

    /// The metadata component type of the entity installation, for a system that does not hold
    /// the installation itself.
    @Nonnull
    ComponentType<EntityStore, RelationshipMetadata<EntityStore>> getEntityMetadataComponentType() {
        return entityMetadataType;
    }

    @Nonnull
    CoreServerTracker getTracker() {
        return tracker;
    }

    /// Stops serving this installation and does nothing when repeated. Hytale releases the
    /// registrations through the plugin's registry proxies.
    public void close() {
        if (closed) return;
        closed = true;
        instance = null;
        players.close();
        tracker.close();
        chunks.getTracker().close();
    }
}
