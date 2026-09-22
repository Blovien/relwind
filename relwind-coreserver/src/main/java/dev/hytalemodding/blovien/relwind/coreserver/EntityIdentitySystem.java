/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.StoreSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.compat.AllEntitiesQuerySystem;

import javax.annotation.Nonnull;

import java.util.Objects;
import java.util.Set;

/// Registers each entity's UUID on load and prepares its removal while the Ref is still valid.
final class EntityIdentitySystem extends RefSystem<EntityStore> implements AllEntitiesQuerySystem<EntityStore> {
    private final CoreServerTracker tracker;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
        new SystemDependency<>(Order.AFTER, EntityStore.UUIDSystem.class)
    );
    private final PlayerLifecycle players;
    private final ComponentType<EntityStore, UUIDComponent> identities;
    private final ComponentType<EntityStore, PlayerRef> playerRefs;
    private volatile boolean active = true;

    EntityIdentitySystem(CoreServerTracker tracker, PlayerLifecycle players) {
        this.players = Objects.requireNonNull(players, "players");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.identities = Objects.requireNonNull(UUIDComponent.getComponentType(), "identities");
        this.playerRefs = Objects.requireNonNull(PlayerRef.getComponentType(), "playerRefs");
    }

    @Nonnull
    CoreServerTracker getTracker() {
        return tracker;
    }

    @Nonnull
    PlayerLifecycle getPlayerLifecycle() {
        return players;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return identities;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void onEntityAdded(
        Ref<EntityStore> ref,
        AddReason reason,
        Store<EntityStore> store,
        CommandBuffer<EntityStore> commandBuffer
    ) {
        registerEntity(commandBuffer, store, ref);
    }

    private void registerEntity(CommandBuffer<EntityStore> commandBuffer, Store<EntityStore> store, Ref<EntityStore> ref) {
        var player = store.getComponent(ref, playerRefs);
        if (player != null) {
            players.onPlayerLoaded(commandBuffer, player, ref);
            return;
        }
        var id = Objects.requireNonNull(store.getComponent(ref, identities)).getUuid();
        tracker.onEntityLoaded(commandBuffer, id, ref);
    }

    @Override
    public void onEntityRemove(
        Ref<EntityStore> ref,
        RemoveReason reason,
        Store<EntityStore> store,
        CommandBuffer<EntityStore> commandBuffer
    ) {
        var id = Objects.requireNonNull(store.getComponent(ref, identities)).getUuid();
        var player = store.getComponent(ref, playerRefs);
        if (player != null) players.onPlayerUnloading(player, ref);
        tracker.onEntityUnloading(commandBuffer, id, ref);
    }

    @Override
    public void onSystemUnregistered() {
        active = false;
    }

    /// Registers the entities a Store already holds when this system is added to a running world.
    final class ReconciliationSystem extends StoreSystem<EntityStore> {
        @Override
        public void onSystemAddedToStore(Store<EntityStore> store) {
            if (store.getEntityCount() == 0) return;
            store.getExternalData().getWorld().execute(() -> {
                if (!active || store.isShutdown()) return;
                store.forEachChunk(getQuery(), (chunk, commands) -> {
                    for (int index = 0; index < chunk.size(); index++) {
                        var ref = chunk.getReferenceTo(index);
                        var id = Objects.requireNonNull(store.getComponent(ref, identities)).getUuid();
                        var linkedEntity = tracker.getRef(id);
                        if (linkedEntity != ref) registerEntity(commands, store, ref);
                    }
                });
            });
        }

        @Override
        public void onSystemRemovedFromStore(Store<EntityStore> store) {
        }
    }
}
