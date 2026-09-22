/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A deferred cleanup runs through the Store runtime and marks its source or holder for saving.
/// Installing a tracker registers the transition RefSystem, and closing the registry unregisters it.
class StoreRuntimeInstallationTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void deferredSourceCleanupRunsThroughTheRuntimeExecutorAndMarksTheSource() {
        var fixture = new RuntimeFixture();
        try (fixture) {
            var type = fixture.types.registerRelationship(
                "test:runtime-deferred",
                RelationshipRules.single().retainOnDeactivation());
            var source = fixture.add();
            var target = fixture.add();
            relationships.addTarget(fixture.store, source.ref(), type, target.ref());
            fixture.marks.clear();

            fixture.tracker.onEntityUnloaded(target.id(), target.ref(),
                UnloadReason.TRANSFER);

            assertTrue(fixture.marks.isEmpty(), "the cleanup waits for the runtime executor");
            assertEquals(1, fixture.queued.size());

            fixture.runQueued();

            assertEquals(1, fixture.marks.size());
            assertEquals(new RuntimeFixture.Mark(source.id(), RuntimeFixture.Kind.REF), fixture.marks.getFirst());
            assertSame(fixture.store, fixture.markAccessors.getFirst());
            assertSame(source.ref(), fixture.markRefs.getFirst());
        }
    }

    @Test
    void cleanupOfAParkedSourceMarksTheHolderThroughTheRuntime() {
        var fixture = new RuntimeFixture();
        try (fixture) {
            var type = fixture.types.registerRelationship(
                "test:runtime-holder",
                RelationshipRules.single().retainOnDeactivation());
            var source = fixture.add();
            var target = fixture.add();
            relationships.addTarget(fixture.store, source.ref(), type, target.ref());
            var parked = fixture.park(source);
            fixture.marks.clear();

            fixture.tracker.onEntityUnloaded(target.id(), target.ref(),
                UnloadReason.TRANSFER);

            assertEquals(List.of(new RuntimeFixture.Mark(source.id(), RuntimeFixture.Kind.HOLDER)), fixture.marks);
            assertSame(parked, fixture.markHolders.getFirst());
        }
    }

    @Test
    void installTrackerRegistersTheTransitionSystemTheRuntimeReturned() {
        var fixture = new RuntimeFixture();
        try (fixture) {
            assertSame(fixture.tracker, fixture.runtime.transitionsTracker);
            assertTrue(fixture.registry.hasSystem(fixture.transitions));
        }
    }

    @Test
    void closingTheTypeRegistryUnregistersTheTransitionSystem() {
        var fixture = new RuntimeFixture();
        try (fixture) {
            assertTrue(fixture.registry.hasSystem(fixture.transitions));

            fixture.types.close();

            assertFalse(fixture.registry.hasSystem(fixture.transitions));
        }
    }

    private static final class RuntimeFixture implements AutoCloseable {
        enum Kind { REF, HOLDER }

        record Mark(UUID id, Kind kind) {
        }

        record LinkedEntity(UUID id, Ref<Object> ref) {
        }

        final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        final Store<Object> store = registry.addStore(new Object(), EmptyResourceStorage.get());
        final Map<Ref<Object>, UUID> ids = new IdentityHashMap<>();
        final List<Runnable> queued = new ArrayList<>();
        final List<Mark> marks = new ArrayList<>();
        final List<ComponentAccessor<Object>> markAccessors = new ArrayList<>();
        final List<Ref<Object>> markRefs = new ArrayList<>();
        final List<Holder<Object>> markHolders = new ArrayList<>();
        final RecordingRuntime runtime = new RecordingRuntime();
        final Transitions transitions = new Transitions();
        final RelationshipTypeRegistry<Object> types = new RelationshipTypeRegistry<>(registry);
        final RelationshipTracker<Object, UUID> tracker =
            types.installTracker(TestPersistenceIdentity.of(ids::get, Codec.UUID_BINARY), runtime);

        RuntimeFixture() {
            types.installPersistence(tracker);
        }

        private final Map<Holder<Object>, Ref<Object>> parked = new IdentityHashMap<>();

        final class Transitions extends RefSystem<Object> {
            @Override
            public Query<Object> getQuery() {
                return Query.any();
            }

            @Override
            public void onEntityAdded(
                @Nonnull Ref<Object> ref,
                @Nonnull AddReason reason,
                @Nonnull Store<Object> runtimeStore,
                @Nonnull CommandBuffer<Object> buffer
            ) {
            }

            @Override
            public void onEntityRemove(
                @Nonnull Ref<Object> ref,
                @Nonnull RemoveReason reason,
                @Nonnull Store<Object> runtimeStore,
                @Nonnull CommandBuffer<Object> buffer
            ) {
            }
        }

        private class RecordingRuntime implements StoreRuntime<Object> {
            @Nullable RefSystem<Object> returnedTransitions;
            @Nullable RelationshipTracker<Object, ?> transitionsTracker;

            @Override
            public void execute(Store<Object> runtimeStore, Runnable action) {
                assertSame(store, runtimeStore);
                queued.add(action);
            }

            @Override
            public void markNeedsSaving(ComponentAccessor<Object> accessor, Ref<Object> ref) {
                markAccessors.add(accessor);
                markRefs.add(ref);
                marks.add(new Mark(ids.get(ref), Kind.REF));
            }

            @Override
            public void markNeedsSaving(Holder<Object> holder) {
                markHolders.add(holder);
                marks.add(new Mark(ids.get(parked.get(holder)), Kind.HOLDER));
            }

            @Override
            public boolean isDeletionSupported() {
                return true;
            }

            @Nonnull
            @Override
            public RefSystem<Object> getTransitionSystem(RelationshipTracker<Object, ?> installed) {
                transitionsTracker = installed;
                returnedTransitions = transitions;
                return transitions;
            }
        }

        LinkedEntity add() {
            var ref = store.addEntity(Archetype.empty(), AddReason.SPAWN);
            var id = UUID.randomUUID();
            ids.put(ref, id);
            tracker.onEntityLoaded(id, ref);
            return new LinkedEntity(id, ref);
        }

        Holder<Object> park(LinkedEntity linkedEntity) {
            var holder = store.removeEntity(linkedEntity.ref(), RemoveReason.UNLOAD);
            tracker.onEntityUnloaded(linkedEntity.id(), linkedEntity.ref(),
                UnloadReason.DEACTIVATION, holder);
            parked.put(holder, linkedEntity.ref());
            return holder;
        }

        void runQueued() {
            var actions = List.copyOf(queued);
            queued.clear();
            actions.forEach(Runnable::run);
        }

        @Override
        public void close() {
            types.close();
            registry.shutdown();
        }
    }
}
