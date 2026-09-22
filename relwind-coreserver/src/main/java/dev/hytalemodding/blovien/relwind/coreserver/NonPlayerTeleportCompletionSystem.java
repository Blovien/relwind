/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entity.teleport.TeleportSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.UnloadReason;
import dev.hytalemodding.blovien.relwind.compat.AllEntitiesQuerySystem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Objects;
import java.util.Set;

/// A nonplayer teleport counts as a TRANSFER only once TeleportSystems.MoveSystem has removed the Ref.
final class NonPlayerTeleportCompletionSystem extends RefChangeSystem<EntityStore, Teleport> implements AllEntitiesQuerySystem<EntityStore> {
    private final CoreServerTracker tracker;
    private final Query<EntityStore> query = Query.and(
        UUIDComponent.getComponentType(), Teleport.getComponentType(), Query.not(PlayerRef.getComponentType())
    );
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
        new SystemDependency<>(Order.AFTER, TeleportSystems.MoveSystem.class)
    );

    NonPlayerTeleportCompletionSystem(CoreServerTracker tracker) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public ComponentType<EntityStore, Teleport> componentType() {
        return Teleport.getComponentType();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void onComponentAdded(
        Ref<EntityStore> ref,
        Teleport teleport,
        Store<EntityStore> store,
        CommandBuffer<EntityStore> commandBuffer
    ) {
        if (teleport.getWorld() == null || teleport.getWorld() == store.getExternalData().getWorld()) {
            return;
        }
        var id = Objects.requireNonNull(store.getComponent(ref, UUIDComponent.getComponentType())).getUuid();
        commandBuffer.run(ignored -> {
            if (!ref.isValid()) {
                tracker.onUnloadResolved(id, ref, UnloadReason.TRANSFER);
            }
        });
    }

    @Override
    public void onComponentSet(
        Ref<EntityStore> ref,
        @Nullable Teleport oldComponent,
        Teleport newComponent,
        Store<EntityStore> store,
        CommandBuffer<EntityStore> commandBuffer
    ) {
    }

    @Override
    public void onComponentRemoved(
        Ref<EntityStore> ref,
        Teleport component,
        Store<EntityStore> store,
        CommandBuffer<EntityStore> commandBuffer
    ) {
    }
}
