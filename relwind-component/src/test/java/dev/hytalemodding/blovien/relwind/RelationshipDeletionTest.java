/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;


import com.hypixel.hytale.component.BridgeStoreFixture;
import com.hypixel.hytale.component.BridgeStoreFixture.Entities;

import static dev.hytalemodding.blovien.relwind.RelationshipTestFixtures.*;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.codec.Codec;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Deleting a linked entity removes every link that named it and cascades into the sources whose rules
/// ask for it, reaching each entity once.
class RelationshipDeletionTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void cascadeRemovalReportsEachPairAfterRuntimeAndTrackerCleanup() {
        try (var fixture = new Fixture()) {
            var type = fixture.types.registerRelationship(
                StringBuilder.class,
                RelationshipRules.single().retainOnTransfer().retainOnDeactivation().cascadeSource());
            var store = fixture.store();
            var tracker = fixture.tracker;
            var first = fixture.entity("first");
            var second = fixture.entity("second");
            var third = fixture.entity("third");
            var firstData = new StringBuilder("first link");
            var secondData = new StringBuilder("second link");
            relationships.addTarget(store, first, type, second, firstData);
            relationships.addTarget(store, second, type, third, secondData);
            var events = new ArrayList<String>();
            var expectedData = Map.of("first:second", firstData, "second:third", secondData);
            fixture.registry.registerSystem(new RelationshipChangeSystem<Object, StringBuilder>(type) {
                @Override
                protected void onRelationshipRemoved(
                    LinkedEntity<Object> source,
                    LinkedEntity<Object> target,
                    StringBuilder data,
                    Store<Object> context,
                    CommandBuffer<Object> buffer
                ) {
                    assertSame(store, context);
                    assertEquals(0, store.getEntityCount());
                    assertNull(source.reference());
                    assertNull(target.reference());
                    assertNotNull(source.identity());
                    assertNotNull(target.identity());
                    assertFalse(tracker.contains(type, "first", "second"));
                    assertFalse(tracker.contains(type, "second", "third"));
                    var pair = source.identity() + ":" + target.identity();
                    assertSame(expectedData.get(pair), data);
                    events.add(pair);
                }
            });

            store.removeEntity(third, RemoveReason.REMOVE);

            assertEquals(Set.of("first:second", "second:third"), new HashSet<>(events));
            assertEquals(2, events.size());
        }
    }

    @Test
    void confirmedDeletionRemovesEveryAvailableLinkAndPreservesSourcesByDefault() {
        try (var fixture = new Fixture()) {
            var follows = register(fixture.types, "follows");
            var owns = register(fixture.types, "owns");
            var store = fixture.store();
            var target = fixture.entity("target");
            var firstSource = fixture.entity("first source");
            var secondSource = fixture.entity("second source");
            relationships.addTarget(store, firstSource, follows, target);
            relationships.addTarget(store, secondSource, owns, target);

            store.removeEntity(target, RemoveReason.REMOVE);

            assertFalse(target.isValid());
            assertTrue(firstSource.isValid());
            assertTrue(secondSource.isValid());
            assertNull(relationships.getFirstTarget(firstSource, follows));
            assertNull(relationships.getFirstTarget(secondSource, owns));
            assertEquals(2, store.getEntityCount());
        }
    }

    @Test
    void confirmedSourceDeletionClearsReferencesFromTheRetainedIncomingLinks() {
        try (var fixture = new Fixture()) {
            var follows = register(fixture.types, "follows");
            var store = fixture.store();
            var target = fixture.entity("target");
            var source = fixture.entity("source");
            relationships.addTarget(store, source, follows, target);

            store.removeEntity(source, RemoveReason.REMOVE);

            assertFalse(source.isValid());
            assertTrue(target.isValid());
            assertEquals(0, relationships.getIncomingCount(target, follows));
            var incoming = new ArrayList<Ref<Object>>();
            relationships.forEachIncomingSource(target, follows, incoming::add);
            assertTrue(incoming.isEmpty());
            assertTrue(store.getArchetype(target).contains(follows.getIncomingType()));
        }
    }

    @Test
    void confirmedTargetDeletionClearsEveryReferenceFromAnArrayBackedHead() {
        try (var fixture = new Fixture()) {
            var follows = register(fixture.types, "follows");
            var store = fixture.store();
            var target = fixture.entity("target");
            var firstSource = fixture.entity("first source");
            var secondSource = fixture.entity("second source");
            var thirdSource = fixture.entity("third source");
            relationships.addTarget(store, firstSource, follows, target);
            relationships.addTarget(store, secondSource, follows, target);
            relationships.addTarget(store, thirdSource, follows, target);
            var head = store.getComponent(target, follows.getIncomingType());

            store.removeEntity(target, RemoveReason.REMOVE);

            assertEquals(0, head.size());
            assertNull(relationships.getFirstTarget(firstSource, follows));
            assertNull(relationships.getFirstTarget(secondSource, follows));
            assertNull(relationships.getFirstTarget(thirdSource, follows));
        }
    }

    @Test
    void cascadingTargetDeletionDeletesTheSourceChain() {
        try (var fixture = new Fixture()) {
            var owns = registerCascade(fixture.types, "owns");
            var store = fixture.store();
            var first = fixture.entity("first");
            var second = fixture.entity("second");
            var third = fixture.entity("third");
            relationships.addTarget(store, first, owns, second);
            relationships.addTarget(store, second, owns, third);

            store.removeEntity(third, RemoveReason.REMOVE);

            assertFalse(first.isValid());
            assertFalse(second.isValid());
            assertFalse(third.isValid());
            assertEquals(0, store.getEntityCount());
        }
    }

    @Test
    void builderToolsUndoIsAConfirmedDeletionAndCascades() {
        try (var fixture = new Fixture()) {
            var owns = registerCascade(fixture.types, "owns");
            var store = fixture.store();
            var source = fixture.entity("source");
            var target = fixture.entity("target");
            relationships.addTarget(store, source, owns, target);

            store.removeEntity(target, RemoveReason.BUILDER_TOOLS_UNDO);

            assertFalse(source.isValid());
            assertFalse(target.isValid());
            assertEquals(0, store.getEntityCount());
        }
    }

    @Test
    void reentrantRestoreIsRejectedDuringDeletionAndCannotReviveADeletedTarget() {
        try (var fixture = new Fixture()) {
            var follows = register(fixture.types, "follows");
            var restore = new RestoreOnRemoval(follows);
            fixture.registry.registerSystem(restore);
            var store = fixture.store();
            var source = fixture.entity("source");
            var target = fixture.entity("target");
            var spare = fixture.entity("spare");
            relationships.addTarget(store, source, follows, target);
            restore.target = target;
            restore.liveTarget = spare;
            restore.armed = true;

            store.removeEntity(target, RemoveReason.REMOVE);

            assertTrue(restore.attempted);
            assertNotNull(restore.rejection);
            assertNotNull(restore.deletedRejection);
            assertTrue(source.isValid());
            assertFalse(target.isValid());
            assertNull(relationships.getFirstTarget(source, follows));
            assertEquals(0, relationships.getIncomingCount(spare, follows));
        }
    }

    @Test
    void failedNativeRemovalCallbackRejectsItsMutationAndAbandonsTheDeletion() {
        try (var fixture = new Fixture()) {
            var follows = register(fixture.types, "follows");
            var listener = new MutateThenFailOnRemoval(follows);
            fixture.registry.registerSystem(listener);
            var store = fixture.store();
            var source = fixture.entity("source");
            var deletedTarget = fixture.entity("deleted target");
            var abandonedTarget = fixture.entity("abandoned target");
            var traversalSource = fixture.entity("traversal source");
            var traversalTarget = fixture.entity("traversal target");
            relationships.addTarget(store, source, follows, deletedTarget);
            relationships.addTarget(store, traversalSource, follows, traversalTarget);
            listener.source = source;
            listener.target = abandonedTarget;
            listener.armed = true;

            var failure = assertThrows(
                RemovalFailure.class,
                () -> store.removeEntity(deletedTarget, RemoveReason.REMOVE)
            );

            assertSame(listener.failure, failure);
            assertNotNull(listener.rejection);

            relationships.forEachTarget(traversalSource, follows, ignored -> {
            });

            assertNull(relationships.getFirstTarget(source, follows));
            assertEquals(0, relationships.getIncomingCount(abandonedTarget, follows));
            assertSame(traversalTarget, relationships.getFirstTarget(traversalSource, follows));
            assertEquals(1, relationships.getIncomingCount(traversalTarget, follows));
        }
    }

    @Test
    void cascadingCyclesAndRepeatedRequestsRemoveEachEntityOnce() {
        try (var fixture = new Fixture()) {
            var removals = new RemovalCounter();
            fixture.registry.registerSystem(removals);
            var firstType = registerCascade(fixture.types, "first");
            var secondType = registerCascade(fixture.types, "second");
            var store = fixture.store();
            var first = fixture.entity("first");
            var second = fixture.entity("second");
            relationships.addTarget(store, first, firstType, second);
            relationships.addTarget(store, first, secondType, second);
            relationships.addTarget(store, second, firstType, first);

            store.removeEntity(second, RemoveReason.REMOVE);

            assertEquals(0, store.getEntityCount());
            assertEquals(1, removals.counts.get(first));
            assertEquals(1, removals.counts.get(second));
        }
    }

    @Test
    void unloadingDoesNotConfirmPermanentDeletionButRemovalDoes() {
        try (var fixture = new Fixture()) {
            var owns = registerCascade(fixture.types, "owns");
            var store = fixture.store();
            var unloadedSource = fixture.entity("unloaded source");
            var unloaded = fixture.entity("unloaded");
            var source = fixture.entity("source");
            var target = fixture.entity("target");
            relationships.addTarget(store, unloadedSource, owns, unloaded);
            relationships.addTarget(store, source, owns, target);

            store.removeEntity(unloaded, RemoveReason.UNLOAD);

            assertTrue(unloadedSource.isValid());
            assertFalse(unloaded.isValid());
            assertSame(unloaded, relationships.getFirstTarget(unloadedSource, owns));

            store.removeEntity(target, RemoveReason.REMOVE);

            assertFalse(source.isValid());
            assertFalse(target.isValid());
        }
    }

    @Test
    void deletionRespectsTheTargetFromTheLatestCommittedRetarget() {
        try (var fixture = new Fixture()) {
            var owns = registerCascade(fixture.types, "owns");
            var store = fixture.store();
            var source = fixture.entity("source");
            var oldTarget = fixture.entity("old target");
            var currentTarget = fixture.entity("current target");
            relationships.addTarget(store, source, owns, oldTarget);
            relationships.retarget(store, source, owns, oldTarget, currentTarget);

            store.removeEntity(oldTarget, RemoveReason.REMOVE);

            assertTrue(source.isValid());
            assertFalse(oldTarget.isValid());
            assertTrue(currentTarget.isValid());
            assertSame(currentTarget, relationships.getFirstTarget(source, owns));
            assertEquals(1, relationships.getIncomingCount(currentTarget, owns));
        }
    }

    @Test
    void seededCascadesAgreeWithAnIndependentGraphModel() {
        try (var fixture = new Fixture()) {
            var owns = registerCascade(fixture.types, "owns");
            var store = fixture.store();
            var refs = new ArrayList<Ref<Object>>();
            for (int i = 0; i < 48; i++) {
                refs.add(fixture.entity("entity " + i));
            }
            var expected = new IdentityHashMap<Ref<Object>, Ref<Object>>();
            var random = new Random(912_704);
            for (var source : refs) {
                var target = refs.get(random.nextInt(refs.size()));
                relationships.addTarget(store, source, owns, target);
                expected.put(source, target);
            }

            while (!expected.isEmpty()) {
                var remaining = expected.keySet().stream().filter(Ref::isValid).toList();
                var deleted = remaining.get(random.nextInt(remaining.size()));
                cascadeModel(expected, deleted);

                store.removeEntity(deleted, RemoveReason.REMOVE);

                for (var ref : refs) {
                    assertEquals(expected.containsKey(ref), ref.isValid());
                }
                assertModel(owns, refs, expected);
            }
            assertEquals(0, store.getEntityCount());
        }
    }

    private static void cascadeModel(IdentityHashMap<Ref<Object>, Ref<Object>> expected, Ref<Object> initial) {
        var deleted = Collections.newSetFromMap(new IdentityHashMap<Ref<Object>, Boolean>());
        var pending = new ArrayDeque<Ref<Object>>();
        deleted.add(initial);
        pending.add(initial);
        while (!pending.isEmpty()) {
            var target = pending.removeFirst();
            expected.remove(target);
            var iterator = expected.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getValue() == target) {
                    var source = entry.getKey();
                    iterator.remove();
                    if (deleted.add(source)) {
                        pending.addLast(source);
                    }
                }
            }
        }
    }

    private static void assertModel(
        GenericRelationshipType<Object, Object, Void> type,
        List<Ref<Object>> refs,
        IdentityHashMap<Ref<Object>, Ref<Object>> expected
    ) {
        for (var source : refs) {
            if (source.isValid()) {
                assertSame(expected.get(source), relationships.getFirstTarget(source, type));
            }
        }
        for (var target : refs) {
            if (!target.isValid()) {
                continue;
            }
            var expectedSources = new HashSet<Ref<Object>>();
            expected.forEach((source, linkedTarget) -> {
                if (linkedTarget == target) {
                    expectedSources.add(source);
                }
            });
            var actualSources = new HashSet<Ref<Object>>();
            relationships.forEachIncomingSource(target, type, actualSources::add);
            assertEquals(expectedSources.size(), relationships.getIncomingCount(target, type));
            assertEquals(expectedSources, actualSources);
        }
    }

    private static GenericRelationshipType<Object, Object, Void> register(RelationshipTypeRegistry<Object> types, String id) {
        return types.registerRelationship("relwind:test/" + id, RelationshipRules.single());
    }

    private static GenericRelationshipType<Object, Object, Void> registerCascade(
        RelationshipTypeRegistry<Object> types,
        String id
    ) {
        return types.registerRelationship("relwind:test/" + id, RelationshipRules.single().cascadeSource());
    }

    @Test
    void aCascadingDeletionOfASourceAnnouncesItsRemovedDataAndNoDataChange() {
        try (var fixture = new Fixture()) {
            var mounted = fixture.types.registerRelationship(Saddle.class, RelationshipRules.single().cascadeSource());
            var store = fixture.store();
            var rider = fixture.entity("rider");
            var mount = fixture.entity("mount");
            var saddle = new Saddle(1);
            relationships.addTarget(fixture.store(), rider, mounted, mount, saddle);

            var removed = new ArrayList<Saddle>();
            var sets = new ArrayList<Saddle>();
            fixture.registry.registerSystem(new RelationshipChangeSystem<Object, Saddle>(mounted) {
                @Override
                protected void onRelationshipRemoved(
                    LinkedEntity<Object> source,
                    LinkedEntity<Object> target,
                    Saddle data,
                    Store<Object> context,
                    CommandBuffer<Object> buffer
                ) {
                    removed.add(data);
                }

                @Override
                protected void onRelationshipSet(
                    LinkedEntity<Object> source,
                    LinkedEntity<Object> target,
                    Saddle oldData,
                    Saddle data,
                    Store<Object> context,
                    CommandBuffer<Object> buffer
                ) {
                    sets.add(data);
                }
            });

            store.removeEntity(mount, RemoveReason.REMOVE);

            assertFalse(rider.isValid());
            assertFalse(mount.isValid());
            assertEquals(0, store.getEntityCount());
            assertEquals(List.of(), sets);
            assertEquals(List.of(saddle), removed);
        }
    }

    @Test
    void aCascadingDeletionReachesASourceThatIsAwayAsAHolder() {
        try (var fixture = new Fixture()) {
            var mounted = fixture.types.registerRelationship(
                Saddle.class,
                RelationshipRules.single().retainOnDeactivation().cascadeSource());
            var store = fixture.store();
            var rider = fixture.entity("rider");
            var mount = fixture.entity("mount");
            var saddle = new Saddle(1);
            relationships.addTarget(fixture.store(), rider, mounted, mount, saddle);

            var parked = store.removeEntity(rider, RemoveReason.UNLOAD);
            fixture.tracker.onEntityUnloaded("rider", rider,
                UnloadReason.DEACTIVATION, parked);
            var mountHolder = store.removeEntity(mount, RemoveReason.REMOVE);
            fixture.tracker.onEntityDeleted("mount", mount, mountHolder);

            assertEquals(0, store.getEntityCount());

            var returned = Objects.requireNonNull(store.addEntity(parked, AddReason.LOAD));
            fixture.identities.put(returned, "rider");
            fixture.tracker.onEntityLoaded("rider", returned);

            assertFalse(returned.isValid());
            assertEquals(0, store.getEntityCount());
        }
    }

    private record Saddle(int seat) {
    }

    private static Ref<Object> addEntity(Store<Object> store) {
        return Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
    }

    /// One Store with the relationship installation. Deletion runs through the native removal systems.
    private static final class Fixture implements AutoCloseable {
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final RelationshipTypeRegistry<Object> types = new RelationshipTypeRegistry<>(registry);
        private final IdentityHashMap<Ref<Object>, String> identities = new IdentityHashMap<>();
        private final RelationshipTracker<Object, String> tracker = types.installTracker(
            TestPersistenceIdentity.of(identities::get, Codec.STRING), TestStoreRuntime.inline());
        private Store<Object> store;

        private Store<Object> store() {
            if (store == null) {
                store = registry.addStore(new Object(), EmptyResourceStorage.get());
            }
            return store;
        }

        private Ref<Object> entity(String identity) {
            var ref = addEntity(store());
            identities.put(ref, identity);
            tracker.onEntityLoaded(identity, ref);
            return ref;
        }

        @Override
        public void close() {
            tracker.close();
            registry.shutdown();
        }
    }

    private static final class RemovalCounter extends RefSystem<Object> {
        private final IdentityHashMap<Ref<Object>, Integer> counts = new IdentityHashMap<>();

        @Override
        public Query<Object> getQuery() {
            return Query.any();
        }

        @Override
        public void onEntityAdded(
            @Nonnull Ref<Object> ref,
            @Nonnull AddReason reason,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
        }

        @Override
        public void onEntityRemove(
            @Nonnull Ref<Object> ref,
            @Nonnull RemoveReason reason,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            counts.merge(ref, 1, Integer::sum);
        }
    }

    private static final class RestoreOnRemoval
        extends RefChangeSystem<Object, OutgoingLink<Object, Object>> {
        private final GenericRelationshipType<Object, Object, Void> type;
        private Ref<Object> target;
        private Ref<Object> liveTarget;
        private boolean armed;
        private boolean attempted;
        private IllegalStateException rejection;
        private IllegalStateException deletedRejection;

        private RestoreOnRemoval(GenericRelationshipType<Object, Object, Void> type) {
            this.type = type;
        }

        @Override
        public ComponentType<Object, OutgoingLink<Object, Object>> componentType() {
            return type.getSourceType();
        }

        @Override
        public Query<Object> getQuery() {
            return Query.any();
        }

        @Override
        public void onComponentAdded(
            @Nonnull Ref<Object> ref,
            @Nonnull OutgoingLink<Object, Object> component,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
        }

        @Override
        public void onComponentSet(
            @Nonnull Ref<Object> ref,
            OutgoingLink<Object, Object> oldComponent,
            @Nonnull OutgoingLink<Object, Object> newComponent,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
        }

        @Override
        public void onComponentRemoved(
            @Nonnull Ref<Object> ref,
            @Nonnull OutgoingLink<Object, Object> component,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            if (!armed) {
                return;
            }
            armed = false;
            attempted = true;
            rejection = assertThrows(IllegalStateException.class,
                () -> relationships.addTarget(store, ref, type, liveTarget));
            deletedRejection = assertThrows(IllegalStateException.class,
                () -> relationships.addTarget(store, ref, type, target));
        }
    }

    private static final class MutateThenFailOnRemoval
        extends RefChangeSystem<Object, OutgoingLink<Object, Object>> {
        private final GenericRelationshipType<Object, Object, Void> type;
        private final RemovalFailure failure = new RemovalFailure();
        private Ref<Object> source;
        private Ref<Object> target;
        private boolean armed;
        private IllegalStateException rejection;

        private MutateThenFailOnRemoval(GenericRelationshipType<Object, Object, Void> type) {
            this.type = type;
        }

        @Override
        public ComponentType<Object, OutgoingLink<Object, Object>> componentType() {
            return type.getSourceType();
        }

        @Override
        public Query<Object> getQuery() {
            return Query.any();
        }

        @Override
        public void onComponentAdded(
            @Nonnull Ref<Object> ref,
            @Nonnull OutgoingLink<Object, Object> component,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
        }

        @Override
        public void onComponentSet(
            @Nonnull Ref<Object> ref,
            OutgoingLink<Object, Object> oldComponent,
            @Nonnull OutgoingLink<Object, Object> newComponent,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
        }

        @Override
        public void onComponentRemoved(
            @Nonnull Ref<Object> ref,
            @Nonnull OutgoingLink<Object, Object> component,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            if (armed && ref == source) {
                armed = false;
                rejection = assertThrows(IllegalStateException.class,
                    () -> relationships.addTarget(store, source, type, target));
                throw failure;
            }
        }
    }

    private static final class RemovalFailure extends RuntimeException {
    }

    @Test
    void deletingABridgeTargetClearsTheOutgoingSlotsOfItsSources() {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("overworld");
            var entityTypes = entityTypes(fixture);
            var blockTypes = blockTypes(fixture);
            var anchoredTo = entityTypes.registerRelationship(blockTypes, RelationshipRules.multiple());

            var first = fixture.addEntity(world);
            var second = fixture.addEntity(world);
            var block = fixture.addBlock(world);
            var otherBlock = fixture.addBlock(world);
            relationships.addTarget(world.entityStore(), first, anchoredTo, block);
            relationships.addTarget(world.entityStore(), second, anchoredTo, block);
            relationships.addTarget(world.entityStore(), first, anchoredTo, otherBlock);

            world.blockStore().removeEntity(block, RemoveReason.REMOVE);

            assertTrue(first.isValid());
            assertTrue(second.isValid());
            assertEquals(1, relationships.getTargetCount(first, anchoredTo));
            assertSame(otherBlock, relationships.getFirstTarget(first, anchoredTo));
            assertEquals(0, relationships.getTargetCount(second, anchoredTo));
            assertFalse(world.entityStore().getArchetype(second).contains(anchoredTo.getSourceType()));

            // unloading a target is not a deletion
            world.blockStore().removeEntity(otherBlock, RemoveReason.UNLOAD);
            assertEquals(1, relationships.getTargetCount(first, anchoredTo));
        }
    }

    @Test
    void deletingABridgeSourceClearsTheIncomingEntriesOfItsTargets() {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("overworld");
            var entityTypes = entityTypes(fixture);
            var blockTypes = blockTypes(fixture);
            var anchoredTo = entityTypes.registerRelationship(blockTypes, RelationshipRules.multiple());

            var unloading = fixture.addEntity(world);
            var staying = fixture.addEntity(world);
            var block = fixture.addBlock(world);
            var otherBlock = fixture.addBlock(world);
            relationships.addTarget(world.entityStore(), unloading, anchoredTo, block);
            relationships.addTarget(world.entityStore(), unloading, anchoredTo, otherBlock);
            relationships.addTarget(world.entityStore(), staying, anchoredTo, block);

            world.entityStore().removeEntity(unloading, RemoveReason.REMOVE);

            assertEquals(1, relationships.getIncomingCount(block, anchoredTo));
            var sources = new ArrayList<Ref<Entities>>();
            relationships.forEachIncomingSource(block, anchoredTo, sources::add);
            assertEquals(List.of(staying), sources);
            assertEquals(0, relationships.getIncomingCount(otherBlock, anchoredTo));

            // unloading a source is not a deletion
            world.entityStore().removeEntity(staying, RemoveReason.UNLOAD);
            assertEquals(1, relationships.getIncomingCount(block, anchoredTo));
        }
    }

    @Test
    void bridgeCascadeDeletesTheEntitySourcesOfADeletedBlock() {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("overworld");
            var entityTypes = entityTypes(fixture);
            var blockTypes = blockTypes(fixture);
            var anchoredTo = entityTypes.registerRelationship(
                blockTypes,
                RelationshipRules.multiple().cascadeSource());
            var carries = blockTypes.registerRelationship(entityTypes, RelationshipRules.multiple());


            var anchored = fixture.addEntity(world);
            var chained = fixture.addEntity(world);
            var carrier = fixture.addBlock(world);
            var block = fixture.addBlock(world);
            relationships.addTarget(world.entityStore(), anchored, anchoredTo, block);
            relationships.addTarget(world.entityStore(), chained, anchoredTo, block);
            relationships.addTarget(world.blockStore(), carrier, carries, anchored);

            world.blockStore().removeEntity(block, RemoveReason.REMOVE);

            assertFalse(anchored.isValid());
            assertFalse(chained.isValid());
            // the carrier survives, and its link to the deleted entity is gone
            assertTrue(carrier.isValid());
            assertEquals(0, relationships.getTargetCount(carrier, carries));
        }
    }

    @Test
    void aDeletedEntityTargetLeavesItsChunkStoreSourcesInPlace() {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("overworld");
            var entityTypes = entityTypes(fixture);
            var blockTypes = blockTypes(fixture);
            var carries = blockTypes.registerRelationship(entityTypes, RelationshipRules.multiple());

            var carrier = fixture.addBlock(world);
            var passenger = fixture.addEntity(world);
            var other = fixture.addEntity(world);
            relationships.addTarget(world.blockStore(), carrier, carries, passenger);
            relationships.addTarget(world.blockStore(), carrier, carries, other);

            world.entityStore().removeEntity(passenger, RemoveReason.REMOVE);

            assertTrue(carrier.isValid());
            assertEquals(1, relationships.getTargetCount(carrier, carries));
            assertSame(other, relationships.getFirstTarget(carrier, carries));
        }
    }
}
