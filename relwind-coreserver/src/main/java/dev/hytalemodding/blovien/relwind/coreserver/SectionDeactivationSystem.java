/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.NonTicking;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.chunk.section.EntitySection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import dev.hytalemodding.blovien.relwind.UnloadReason;
import dev.hytalemodding.blovien.relwind.compat.AllEntitiesQuerySystem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Objects;
import java.util.Set;

/// Classifies section entities as DEACTIVATION after native parking succeeds.
final class SectionDeactivationSystem extends RefChangeSystem<ChunkStore, NonTicking<ChunkStore>> implements AllEntitiesQuerySystem<ChunkStore> {
    private final CoreServerTracker tracker;
    private final Query<ChunkStore> query = Query.and(
        EntitySection.getComponentType(), ChunkStore.REGISTRY.getNonTickingComponentType()
    );
    private final Set<Dependency<ChunkStore>> dependencies = Set.of(
        new SystemDependency<>(Order.BEFORE, EntitySection.EntitySectionLoadingSystem.class)
    );

    SectionDeactivationSystem(CoreServerTracker tracker) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
    }

    @Nonnull
    @Override
    public Query<ChunkStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public ComponentType<ChunkStore, NonTicking<ChunkStore>> componentType() {
        return ChunkStore.REGISTRY.getNonTickingComponentType();
    }

    @Nonnull
    @Override
    public Set<Dependency<ChunkStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void onComponentAdded(
        Ref<ChunkStore> ref,
        NonTicking<ChunkStore> component,
        Store<ChunkStore> store,
        CommandBuffer<ChunkStore> commandBuffer
    ) {
        var section = Objects.requireNonNull(store.getComponent(ref, EntitySection.getComponentType()));
        for (var entityRef : section.getEntityReferences()) {
            var identity = entityRef.getStore().getComponent(entityRef, UUIDComponent.getComponentType());
            if (identity != null) {
                var id = identity.getUuid();
                // EntitySectionLoadingSystem removes these entities after this callback
                commandBuffer.run(ignored -> tracker.onUnloadResolved(id, entityRef, UnloadReason.DEACTIVATION));
            }
        }
    }

    @Override
    public void onComponentRemoved(
        Ref<ChunkStore> ref,
        NonTicking<ChunkStore> component,
        Store<ChunkStore> store,
        CommandBuffer<ChunkStore> commandBuffer
    ) {
    }

    @Override
    public void onComponentSet(
        Ref<ChunkStore> ref,
        @Nullable NonTicking<ChunkStore> oldComponent,
        NonTicking<ChunkStore> newComponent,
        Store<ChunkStore> store,
        CommandBuffer<ChunkStore> commandBuffer
    ) {
    }
}
