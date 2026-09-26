/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.IComponentRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.ISystem;
import com.hypixel.hytale.component.system.RefSystem;
import dev.hytalemodding.blovien.relwind.compat.AllEntitiesQuerySystem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/// Clears the links of an entity Hytale is deleting. One instance serves every registry over
/// the same ComponentRegistry.
final class RelationshipDeletionSystem<ECS_TYPE> extends RefSystem<ECS_TYPE> implements AllEntitiesQuerySystem<ECS_TYPE> {
    private final List<RelationshipTypeRegistry<ECS_TYPE>> registries = new CopyOnWriteArrayList<>();
    private final Map<Store<ECS_TYPE>, Deletion<ECS_TYPE>> deletions = new ConcurrentHashMap<>();

    private RelationshipDeletionSystem() {
    }

    @Nonnull
    static <ECS_TYPE> RelationshipDeletionSystem<ECS_TYPE> install(
        ComponentRegistry<ECS_TYPE> registry,
        IComponentRegistry<ECS_TYPE> registrar,
        RelationshipTypeRegistry<ECS_TYPE> types
    ) {
        var system = getInstalled(registry);
        if (system == null) {
            var candidate = new RelationshipDeletionSystem<ECS_TYPE>();
            try {
                registrar.registerSystem(candidate);
                system = candidate;
            } catch (IllegalArgumentException failure) {
                system = getInstalled(registry);
                if (system == null) throw failure;
            }
        }
        system.registries.add(types);
        return system;
    }

    void release(ComponentRegistry<ECS_TYPE> registry, RelationshipTypeRegistry<ECS_TYPE> types) {
        registries.remove(types);
        if (!registries.isEmpty() || registry.isShutdown() || getInstalled(registry) != this) {
            return;
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<? extends ISystem<ECS_TYPE>> systemClass = (Class) RelationshipDeletionSystem.class;
        registry.unregisterSystem(systemClass);
    }

    @Nullable @SuppressWarnings("unchecked")
    private static <ECS_TYPE> RelationshipDeletionSystem<ECS_TYPE> getInstalled(ComponentRegistry<ECS_TYPE> registry) {
        var lock = registry.getDataUpdateLock().readLock();
        lock.lock();
        try {
            var data = registry._internal_getData();
            for (int index = 0; index < data.getSystemSize(); index++) {
                if (data.getSystem(index) instanceof RelationshipDeletionSystem<?> system) {
                    return (RelationshipDeletionSystem<ECS_TYPE>) system;
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Query<ECS_TYPE> getQuery() {
        return Query.any();
    }

    @Override
    public void onEntityAdded(
        Ref<ECS_TYPE> ref,
        AddReason reason,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    ) {
    }

    @Override
    public void onEntityRemove(
        Ref<ECS_TYPE> ref,
        RemoveReason reason,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    ) {
        if (reason == RemoveReason.UNLOAD) {
            // an unload is not a deletion
            return;
        }
        var captured = capture(store, ref);
        if (captured.isEmpty()) {
            return;
        }
        var deletion = begin(store);
        // Hytale is still removing the entity here
        commandBuffer.run(ignored -> cleanUp(store, ref, reason, captured, deletion));
    }

    private Deletion<ECS_TYPE> begin(Store<ECS_TYPE> store) {
        var deletion = deletions.get(store);
        if (deletion == null) {
            var command = RelationshipAccessSystem.forStoreCommand(store);
            command.assertNotProcessing();
            command.beginDeletion();
            deletion = new Deletion<>(command);
            deletions.put(store, deletion);
        }
        deletion.depth++;
        return deletion;
    }

    @Nonnull
    private List<CapturedLinks<ECS_TYPE>> capture(Store<ECS_TYPE> store, Ref<ECS_TYPE> deleted) {
        var captured = new ArrayList<CapturedLinks<ECS_TYPE>>();
        for (var types : registries) {
            for (var type : types.getRegisteredTypes()) {
                var outgoing = store.getComponent(deleted, type.getSourceType());
                if (type.getTargetRelationshipTypeRegistry() != types) {
                    // the targets live in another Store, which captures their side itself
                    if (outgoing != null) {
                        captured.add(new CapturedLinks<>(type, outgoing, null, getIdentity(types.getTracker(), deleted)));
                    }
                    continue;
                }
                @SuppressWarnings("unchecked")
                var same = (GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?>) type;
                var incoming = store.getComponent(deleted, same.getIncomingType());
                if (outgoing != null || incoming != null) {
                    captured.add(new CapturedLinks<>(same, outgoing, incoming,
                        getIdentity(types.getTracker(), deleted)));
                }
            }
            for (var type : types.getIncomingFromOtherRegistries()) {
                var stored = store.getComponent(deleted, type.getIncomingType());
                if (stored != null) {
                    captured.add(new CapturedLinks<>(type, null, stored, null));
                }
            }
        }
        return captured;
    }

    private void cleanUp(
        Store<ECS_TYPE> store,
        Ref<ECS_TYPE> deleted,
        RemoveReason reason,
        List<CapturedLinks<ECS_TYPE>> captured,
        Deletion<ECS_TYPE> deletion
    ) {
        boolean completed = false;
        try {
            // every registry over this ComponentRegistry shares one tracker
            var tracker = RelationshipAccessSystem.install(store.getRegistry()).getCurrentTracker();
            for (var links : captured) {
                releaseTargets(store, tracker, deleted, links, deletion.removals);
            }
            for (var links : captured) {
                releaseSources(store, tracker, deleted, links, deletion);
            }
            // a long chain of removals must not nest
            if (deletion.depth == 1) {
                drainCascade(store, reason, deletion);
            }
            completed = true;
        } finally {
            end(store, deletion, completed);
        }
    }

    /// Deletes the sources collected in this Store first, then the sources in other Stores.
    private void drainCascade(Store<ECS_TYPE> store, RemoveReason reason, Deletion<ECS_TYPE> deletion) {
        while (!deletion.cascading.isEmpty() || !deletion.cascadingAcrossStores.isEmpty()) {
            while (!deletion.cascading.isEmpty()) {
                var source = deletion.cascading.removeFirst();
                if (source.isValid()) store.removeEntity(source, reason);
            }
            if (!deletion.cascadingAcrossStores.isEmpty()) {
                deletion.cascadingAcrossStores.removeFirst().delete(reason);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <ECS_TYPE> void releaseOutgoingAcrossStores(
        GenericRelationshipType<?, ?, ?> type,
        Ref<ECS_TYPE> deleted,
        CapturedLinks<ECS_TYPE> links
    ) {
        var outgoing = links.outgoing();
        if (outgoing == null) {
            return;
        }
        releaseTargetsAcrossStores((GenericRelationshipType) type, (Ref) deleted, (OutgoingLink) outgoing,
            links.deletedIdentity());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <ECS_TYPE> void releaseIncomingAcrossStores(
        GenericRelationshipType<?, ?, ?> type,
        Ref<ECS_TYPE> deleted,
        CapturedLinks<ECS_TYPE> links,
        Deletion<?> deletion
    ) {
        var incoming = links.incoming();
        if (incoming == null) {
            return;
        }
        releaseSourcesAcrossStores((GenericRelationshipType) type, (Ref) deleted, (IncomingLinks) incoming,
            deletion);
    }

    /// Repairs each target. The deleted source's own outgoing component goes with the entity.
    private static <SOURCE, TARGET> void releaseTargetsAcrossStores(
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<SOURCE> deleted,
        OutgoingLink<SOURCE, TARGET> outgoing,
        @Nullable Object sourceIdentity
    ) {
        var tracker = type.getRelationshipTypeRegistry().getTracker();
        for (int index = 0; index < outgoing.size(); index++) {
            var target = outgoing.getTarget(index);
            if (tracker != null) tracker.onLinkDeleted(type, deleted, target, sourceIdentity);
            if (target.isValid()) {
                RelationshipLifecycle.releaseDeletedSource(type, deleted, target);
            }
        }
        outgoing.clear();
    }

    /// Every cascading source lives in an entity store, because registration rejects a chunk
    /// store source that cascades.
    private static <SOURCE, TARGET> void releaseSourcesAcrossStores(
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<TARGET> deleted,
        IncomingLinks<SOURCE, TARGET> incoming,
        Deletion<?> deletion
    ) {
        var sources = new ArrayList<Ref<SOURCE>>(incoming.size());
        incoming.forEach(sources::add);
        incoming.clear();
        boolean cascade = type.getDescriptor().getOnDeleteTarget()
            == RelationshipTraits.OnDeleteTarget.DELETE;
        var tracker = type.getRelationshipTypeRegistry().getTracker();
        for (var source : sources) {
            if (!source.isValid()) continue;
            var sourceIdentity = getIdentity(tracker, source);
            if (tracker != null) tracker.onLinkDeleted(type, source, deleted, sourceIdentity);
            if (!RelationshipLifecycle.releaseDeletedTarget(type, source, deleted)) continue;
            if (cascade) {
                deletion.cascadeSourceAcrossStores(source);
            }
        }
    }

    private void end(Store<ECS_TYPE> store, Deletion<ECS_TYPE> deletion, boolean completed) {
        deletion.failed |= !completed;
        if (--deletion.depth > 0) {
            return;
        }
        deletions.remove(store);
        deletion.command.endDeletion();
        // the partial removals of a failed deletion stay unobserved
        if (deletion.failed) {
            return;
        }
        for (var removal : deletion.removals) RelationshipChangeSystem.dispatch(store, removal);
    }

    /// The deleted source's record goes with the entity. This only repairs each target's list.
    private static <ECS_TYPE> void releaseTargets(
        Store<ECS_TYPE> store,
        @Nullable RelationshipTracker<ECS_TYPE, ?> tracker,
        Ref<ECS_TYPE> deleted,
        CapturedLinks<ECS_TYPE> links,
        ArrayList<RelationshipChangeSystem.ChangeEvent<ECS_TYPE, ?>> removals
    ) {
        var capturedOutgoing = links.outgoing();
        if (capturedOutgoing == null) {
            return;
        }
        if (links.type().getTargetRelationshipTypeRegistry() != links.type().getRelationshipTypeRegistry()) {
            releaseOutgoingAcrossStores(links.type(), deleted, links);
            return;
        }
        @SuppressWarnings("unchecked")
        var type = (GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?>) links.type();
        @SuppressWarnings("unchecked")
        var outgoing = (OutgoingLink<ECS_TYPE, ECS_TYPE>) capturedOutgoing;
        // the removed source has no record left to update
        for (int index = 0; index < outgoing.size(); index++) {
            var target = outgoing.getTarget(index);
            if (!target.isValid()) {
                continue;
            }
            removals.add(RelationshipChangeSystem.newRemoval(type, deleted, links.deletedIdentity(),
                target, getIdentity(tracker, target), outgoing.getData(index, Object.class)));
            var incoming = store.getComponent(target, type.getIncomingType());
            if (incoming != null) incoming.remove(deleted);
            if (tracker != null) tracker.onLinkDeleted(type, deleted, target, links.deletedIdentity());
        }
        outgoing.clear();
    }

    private static <ECS_TYPE> void releaseSources(
        Store<ECS_TYPE> store,
        @Nullable RelationshipTracker<ECS_TYPE, ?> tracker,
        Ref<ECS_TYPE> deleted,
        CapturedLinks<ECS_TYPE> links,
        Deletion<ECS_TYPE> deletion
    ) {
        var capturedIncoming = links.incoming();
        if (capturedIncoming == null || capturedIncoming.size() == 0) {
            return;
        }
        if (links.type().getTargetRelationshipTypeRegistry() != links.type().getRelationshipTypeRegistry()) {
            releaseIncomingAcrossStores(links.type(), deleted, links, deletion);
            return;
        }
        @SuppressWarnings("unchecked")
        var type = (GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?>) links.type();
        @SuppressWarnings("unchecked")
        var incoming = (IncomingLinks<ECS_TYPE, ECS_TYPE>) capturedIncoming;
        var persistence = type.getRelationshipTypeRegistry().getPersistence();
        var sources = new ArrayList<Ref<ECS_TYPE>>(incoming.size());
        incoming.forEach(sources::add);
        incoming.clear();
        for (var source : sources) {
            if (!source.isValid()) {
                continue;
            }
            var outgoing = store.getComponent(source, type.getSourceType());
            if (outgoing == null || !outgoing.contains(deleted)) {
                continue;
            }
            // this source survives and keeps its records
            if (tracker != null && persistence != null) {
                persistence.validateMutation(type, source, links.deletedIdentity());
            }
            deletion.removals.add(RelationshipChangeSystem.newRemoval(type, source, getIdentity(tracker, source),
                deleted, links.deletedIdentity(), outgoing.getData(deleted, Object.class)));
            if (tracker != null) tracker.onLinkDeleted(type, source, deleted, getIdentity(tracker, source));
            RelationshipLifecycle.detachLinkedEntity(store, type, source, deleted, deleted);
            if (tracker != null) {
                if (persistence != null) {
                    persistence.synchronize(type, source, links.deletedIdentity(), false, null);
                }
            }
            if (
                type.getDescriptor().getOnDeleteTarget()
                    == RelationshipTraits.OnDeleteTarget.DELETE
                && deletion.scheduled.add(source)
            ) {
                deletion.cascading.addLast(source);
            }
        }
    }

    @Nullable
    private static <ECS_TYPE> Object getIdentity(@Nullable RelationshipTracker<ECS_TYPE, ?> tracker, Ref<ECS_TYPE> ref) {
        return tracker == null ? null : tracker.getIdentity(ref);
    }

    private record CascadingSource<SOURCE>(Store<SOURCE> store, Ref<SOURCE> source) {
        void delete(RemoveReason reason) {
            if (source.isValid()) {
                store.removeEntity(source, reason);
            }
        }
    }

    /// Hytale moves a removed entity's components into its holder.
    private record CapturedLinks<ECS_TYPE>(
        GenericRelationshipType<?, ?, ?> type,
        @Nullable OutgoingLink<ECS_TYPE, ?> outgoing,
        @Nullable IncomingLinks<?, ECS_TYPE> incoming,
        @Nullable Object deletedIdentity
    ) {
    }

    /// One confirmed deletion and the cascade it started, kept while the Store removes them.
    private static final class Deletion<ECS_TYPE> {
        private final RelationshipProcessingTracker command;
        private final ArrayList<RelationshipChangeSystem.ChangeEvent<ECS_TYPE, ?>> removals = new ArrayList<>();
        private final ArrayDeque<Ref<ECS_TYPE>> cascading = new ArrayDeque<>();
        private final ArrayDeque<CascadingSource<?>> cascadingAcrossStores = new ArrayDeque<>();
        private final Set<Ref<?>> scheduled =
            Collections.newSetFromMap(new IdentityHashMap<>());
        private int depth;
        private boolean failed;

        private Deletion(RelationshipProcessingTracker command) {
            this.command = command;
        }

        private <SOURCE> void cascadeSourceAcrossStores(Ref<SOURCE> source) {
            if (scheduled.add(source)) {
                cascadingAcrossStores.addLast(new CascadingSource<>(source.getStore(), source));
            }
        }
    }
}
