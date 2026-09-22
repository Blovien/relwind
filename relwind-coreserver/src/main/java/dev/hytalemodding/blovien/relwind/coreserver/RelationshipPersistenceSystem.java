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
import com.hypixel.hytale.component.NonTicking;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.ISystem;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.StoreSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.chunk.section.EntitySection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.RelationshipMetadata;
import dev.hytalemodding.blovien.relwind.RelationshipPersistence;
import dev.hytalemodding.blovien.relwind.StoreRuntime;
import dev.hytalemodding.blovien.relwind.compat.AllEntitiesQuerySystem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/// Restores the saved links of a source that loads in its Store. It runs after the transition
/// system of that Store kind has registered the load.
final class RelationshipPersistenceSystem<ECS_TYPE> extends RefSystem<ECS_TYPE> implements AllEntitiesQuerySystem<ECS_TYPE> {
    private final RelationshipPersistence<ECS_TYPE> persistence;
    private final Query<ECS_TYPE> query;
    private final Set<Dependency<ECS_TYPE>> dependencies;

    <T extends ISystem<ECS_TYPE>> RelationshipPersistenceSystem(
        RelationshipPersistence<ECS_TYPE> persistence,
        Query<ECS_TYPE> query,
        Class<T> transitions
    ) {
        this.persistence = persistence;
        this.query = query;
        dependencies = Set.of(new SystemDependency<>(Order.AFTER, transitions));
    }

    @Nonnull
    @Override
    public Query<ECS_TYPE> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<ECS_TYPE>> getDependencies() {
        return dependencies;
    }

    @Override
    public void onEntityAdded(Ref<ECS_TYPE> ref, AddReason reason, Store<ECS_TYPE> store, CommandBuffer<ECS_TYPE> commandBuffer) {
        persistence.restore(commandBuffer, ref);
    }

    @Override
    public void onEntityRemove(
        Ref<ECS_TYPE> ref,
        RemoveReason reason,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    ) {
    }

    /// Restores the sources a Store already holds when persistence is installed after it started.
    static final class RestoreSystem<ECS_TYPE> extends StoreSystem<ECS_TYPE> {
        private final RelationshipPersistence<ECS_TYPE> persistence;
        private final Query<ECS_TYPE> query;
        private final StoreRuntime<ECS_TYPE> runtime;
        private volatile boolean active = true;

        RestoreSystem(RelationshipPersistence<ECS_TYPE> persistence, Query<ECS_TYPE> query, StoreRuntime<ECS_TYPE> runtime) {
            this.persistence = persistence;
            this.query = query;
            this.runtime = runtime;
        }

        @Override
        public void onSystemAddedToStore(Store<ECS_TYPE> store) {
            if (store.getEntityCount() == 0) return;
            runtime.execute(store, () -> {
                if (!active || store.isShutdown()) return;
                store.forEachChunk(query, (chunk, commands) -> {
                    for (int index = 0; index < chunk.size(); index++) persistence.restore(commands, chunk.getReferenceTo(index));
                });
            });
        }

        @Override
        public void onSystemRemovedFromStore(Store<ECS_TYPE> store) {
        }

        @Override
        public void onSystemUnregistered() {
            active = false;
        }
    }

    /// Remembers which entity section Hytale parked a saved entity into. The entity runtime marks
    /// that section when the parked holder changes.
    static final class ParkedHolderSystem extends RefChangeSystem<ChunkStore, NonTicking<ChunkStore>> implements AllEntitiesQuerySystem<ChunkStore> {
        private final ComponentType<EntityStore, RelationshipMetadata<EntityStore>> metadataType;
        private final Query<ChunkStore> query = Query.and(
            EntitySection.getComponentType(),
            ChunkStore.REGISTRY.getNonTickingComponentType()
        );
        private final Set<Dependency<ChunkStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, EntitySection.EntitySectionLoadingSystem.class)
        );

        private final Map<UUID, WeakReference<EntitySection>> parkedSections;

        ParkedHolderSystem(
            Map<UUID, WeakReference<EntitySection>> parkedSections,
            ComponentType<EntityStore, RelationshipMetadata<EntityStore>> metadataType
        ) {
            this.metadataType = metadataType;
            this.parkedSections = parkedSections;
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
            var section = store.getComponent(ref, EntitySection.getComponentType());
            assert section != null;
            for (var holder : section.getEntityHolders()) {
                if (holder.getComponent(metadataType) == null) {
                    continue;
                }
                var identity = holder.getComponent(UUIDComponent.getComponentType());
                if (identity != null) {
                    parkedSections.put(identity.getUuid(), new WeakReference<>(section));
                }
            }
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

        @Override
        public void onComponentRemoved(
            Ref<ChunkStore> ref,
            NonTicking<ChunkStore> component,
            Store<ChunkStore> store,
            CommandBuffer<ChunkStore> commandBuffer
        ) {
        }
    }
}
