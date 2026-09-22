/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

import java.util.Objects;

/// Confirms an entity's removal from its holder, once Hytale has invalidated the Ref.
final class EntityRemovalSystem extends HolderSystem<EntityStore> {
    private final CoreServerTracker tracker;
    private final PlayerLifecycle players;

    EntityRemovalSystem(CoreServerTracker tracker, PlayerLifecycle players) {
        this.players = Objects.requireNonNull(players, "players");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return UUIDComponent.getComponentType();
    }

    @Override
    public void onEntityAdd(Holder<EntityStore> holder, AddReason reason, Store<EntityStore> store) {
    }

    @Override
    public void onEntityRemoved(Holder<EntityStore> holder, RemoveReason reason, Store<EntityStore> store) {
        var identity = Objects.requireNonNull(holder.getComponent(UUIDComponent.getComponentType()));
        tracker.onEntityRemoved(identity.getUuid(), holder, reason);
        players.onConfirmed(holder);
    }
}
