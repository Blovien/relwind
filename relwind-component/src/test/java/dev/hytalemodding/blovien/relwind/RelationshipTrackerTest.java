/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;



import static dev.hytalemodding.blovien.relwind.RelationshipTestFixtures.*;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.util.IdentityHashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// A link survives the transfer or the temporary unavailability of a linked entity as far as its traits
/// allow, and resolves again when that linked entity comes back.
class RelationshipTrackerTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void retainedLinksUseTheIdentityAdapterWithTheRefsStore() {
        var registry = new ComponentRegistry<Object>();
        var store = registry.addStore(new Object(), EmptyResourceStorage.get());
        var ids = new IdentityHashMap<Ref<Object>, String>();
        var identified = new java.util.HashSet<Ref<Object>>();
        var types = new RelationshipTypeRegistry<>(registry);
        try {
            var tracker = types.installTracker(new TestPersistenceIdentity<>((context, ref) -> {
                assertSame(store, context);
                identified.add(ref);
                return ids.get(ref);
            }, com.hypixel.hytale.codec.Codec.STRING, "ENTITIES", (context, id) -> false, peer -> null),
                TestStoreRuntime.inline());
            var type = types.registerRelationship(RelationshipTraits.defaults().exclusive().retainOnDeactivation());
            var source = store.addEntity(Archetype.empty(), AddReason.SPAWN);
            var target = store.addEntity(Archetype.empty(), AddReason.SPAWN);
            var unnamed = store.addEntity(Archetype.empty(), AddReason.SPAWN);
            ids.put(source, "source");
            ids.put(target, "target");
            tracker.onEntityLoaded("source", source);
            tracker.onEntityLoaded("target", target);
            relationships.addTarget(store, source, type, target);

            assertTrue(tracker.contains(type, "source", "target"));
            assertEquals(java.util.Set.of(source, target), identified);
            assertThrows(IllegalStateException.class, () -> relationships.addTarget(store, unnamed, type, target));
        } finally {
            types.close();
            registry.shutdown();
        }
    }

    @Test
    void policyObserversFinishBeforeTheirBufferedReactions() {
        try (var fixture = new Fixture()) {
            var type = fixture.registerRuntime(
                RelationshipTraits.Survival.REMOVE, RelationshipTraits.Survival.RETAIN);
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            var replacement = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());
            var events = new java.util.ArrayList<String>();
            fixture.registry.registerSystem(new SecondPolicyObserver(type, (store, buffer) -> {
                events.add("second");
                assertEquals(0, relationships.getTargetCount(source.ref(), type));
                assertEquals(0, relationships.getIncomingCount(target.ref(), type));
                assertEquals(0, relationships.getIncomingCount(replacement.ref(), type));
            }));
            fixture.registry.registerSystem(new FirstPolicyObserver(type, (store, buffer) -> {
                assertFalse(fixture.tracker.contains(type, source.id(), target.id()));
                events.add("first");
                assertThrows(IllegalStateException.class,
                    () -> relationships.addTarget(fixture.firstStore, source.ref(), type, replacement.ref()));
                relationships.addTarget(buffer, source.ref(), type, replacement.ref());
                buffer.run(ignored -> {
                    assertSame(replacement.ref(), relationships.getFirstTarget(source.ref(), type));
                    events.add("reaction");
                });
            }));

            fixture.tracker.onEntityUnloaded(target.id(), target.ref(), UnloadReason.TRANSFER);

            assertFalse(fixture.tracker.contains(type, source.id(), target.id()));
            assertEquals(java.util.List.of("first", "second", "reaction"), events);
            assertSame(replacement.ref(), relationships.getFirstTarget(source.ref(), type));
        }
    }

    @Test
    void anObserverFailureKeepsTheRemovalCommittedAndCancelsTheLaterObservers() {
        try (var fixture = new Fixture()) {
            var type = fixture.registerRuntime(
                RelationshipTraits.Survival.REMOVE, RelationshipTraits.Survival.RETAIN);
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            var replacement = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());
            var events = new java.util.ArrayList<String>();
            var failure = new IllegalStateException("observer failure");
            fixture.registry.registerSystem(new SecondPolicyObserver(type, (store, buffer) -> {
                events.add("second");
                assertEquals(0, relationships.getTargetCount(source.ref(), type));
                assertEquals(0, relationships.getIncomingCount(target.ref(), type));
                assertEquals(0, relationships.getIncomingCount(replacement.ref(), type));
            }));
            fixture.registry.registerSystem(new FirstPolicyObserver(type, (store, buffer) -> {
                assertFalse(fixture.tracker.contains(type, source.id(), target.id()));
                events.add("first");
                assertThrows(IllegalStateException.class,
                    () -> relationships.addTarget(fixture.firstStore, source.ref(), type, replacement.ref()));
                relationships.addTarget(buffer, source.ref(), type, replacement.ref());
                buffer.run(ignored -> {
                    assertSame(replacement.ref(), relationships.getFirstTarget(source.ref(), type));
                    events.add("reaction");
                });
                throw failure;
            }));

            var thrown = assertThrows(IllegalStateException.class,
                () -> fixture.tracker.onEntityUnloaded(target.id(), target.ref(), UnloadReason.TRANSFER));

            assertSame(failure, thrown);
            assertFalse(fixture.tracker.contains(type, source.id(), target.id()));
            assertEquals(java.util.List.of("first"), events);
            assertNull(relationships.getFirstTarget(source.ref(), type));
        }
    }

    @ParameterizedTest
    @CsvSource({
        "UNREGISTER,false", "UNREGISTER,true",
        "CLOSE,false", "CLOSE,true",
        "SHUTDOWN,false", "SHUTDOWN,true"
    })
    void administrativeTeardownIsSilentAndCancelsQueuedDeliveryWithoutCascades(
        TeardownAction action,
        boolean queuedRemoval
    ) {
        try (var scene = new AdministrativeScene()) {
            queueRemoval(queuedRemoval, scene);

            scene.apply(action);

            assertTrue(scene.events.isEmpty(), action.toString());
            assertFalse(scene.tracker.hasPendingDeletion("source", scene.store));
            assertFalse(scene.tracker.hasPendingDeletion("target", scene.store));
            assertRefsValidUnlessTheStoreShutDown(action, scene.source, scene.target);
        }
    }

    private static void queueRemoval(boolean queuedRemoval, AdministrativeScene scene) {
        if (!queuedRemoval) return;
        scene.tracker.onEntityUnloaded("target", scene.target, UnloadReason.TRANSFER);
        assertEquals(1, scene.tasks.size());
    }

    private static void assertRefsValidUnlessTheStoreShutDown(
        TeardownAction action,
        Ref<Object> source,
        Ref<Object> target
    ) {
        if (action == TeardownAction.SHUTDOWN) return;
        assertTrue(source.isValid());
        assertTrue(target.isValid());
    }

    private enum TeardownAction {
        UNREGISTER, CLOSE, SHUTDOWN
    }

    private static final class AdministrativeScene implements AutoCloseable {
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final IdentityHashMap<Ref<Object>, String> ids = new IdentityHashMap<>();
        private final java.util.List<Runnable> tasks = new java.util.ArrayList<>();
        private final RelationshipTypeRegistry<Object> types = new RelationshipTypeRegistry<>(registry);
        private final RelationshipTracker<Object, String> tracker = types.installTracker(
            TestPersistenceIdentity.of(ids::get, com.hypixel.hytale.codec.Codec.STRING),
            TestStoreRuntime.executing((store, task) -> tasks.add(task)));
        private final RelationshipType<Object, Void> type = types.registerRelationship(
            RelationshipTraits.defaults().exclusive().retainOnDeactivation().onDeleteTarget(RelationshipTraits.OnDeleteTarget.DELETE));
        private final Store<Object> store = registry.addStore(new Object(), EmptyResourceStorage.get());
        private final Ref<Object> source = addEntity("source");
        private final Ref<Object> target = addEntity("target");
        private final java.util.List<String> events = new java.util.ArrayList<>();

        private AdministrativeScene() {
            relationships.addTarget(store, source, type, target);
            registry.registerSystem(new SecondPolicyObserver(type, (context, buffer) -> events.add("removed")));
        }

        private Ref<Object> addEntity(String id) {
            var ref = store.addEntity(Archetype.empty(), AddReason.SPAWN);
            ids.put(ref, id);
            tracker.onEntityLoaded(id, ref);
            return ref;
        }

        private void apply(TeardownAction action) {
            switch (action) {
                case UNREGISTER -> types.unregisterRelationship(type);
                case CLOSE -> tracker.close();
                case SHUTDOWN -> store.shutdown();
            }
            tasks.forEach(Runnable::run);
        }

        @Override
        public void close() {
            tracker.close();
            registry.shutdown();
        }
    }

    private static class SecondPolicyObserver extends RelationshipChangeSystem<Object, Void> {
        private final java.util.function.BiConsumer<Store<Object>, CommandBuffer<Object>> callback;
        private SecondPolicyObserver(
            RelationshipType<Object, Void> type,
            java.util.function.BiConsumer<Store<Object>, CommandBuffer<Object>> callback
        ) {
            super(type);
            this.callback = callback;
        }
        @Override
        protected void onRelationshipRemoved(
            LinkedEntity<Object> source,
            LinkedEntity<Object> target,
            Void data,
            Store<Object> store,
            CommandBuffer<Object> buffer
        ) {
            callback.accept(store, buffer);
        }
    }

    private static final class FirstPolicyObserver extends SecondPolicyObserver {
        private FirstPolicyObserver(
            RelationshipType<Object, Void> type,
            java.util.function.BiConsumer<Store<Object>, CommandBuffer<Object>> callback
        ) { super(type, callback); }
        @Override
        public java.util.Set<com.hypixel.hytale.component.dependency.Dependency<Object>> getDependencies() {
            return java.util.Set.of(new com.hypixel.hytale.component.dependency.SystemDependency<>(
                com.hypixel.hytale.component.dependency.Order.BEFORE, SecondPolicyObserver.class));
        }
    }

    @Test
    void classifiedPolicyRemovalReportsUnavailableTargetAfterBookkeeping() {
        try (var fixture = new Fixture()) {
            var type = fixture.types.registerRelationship(
                StringBuilder.class,
                RelationshipTraits.defaults().exclusive().retainOnTransfer());
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            var data = new StringBuilder("retained");
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref(), data);
            var events = new java.util.ArrayList<String>();
            fixture.registry.registerSystem(new RelationshipChangeSystem<Object, StringBuilder>(type) {
                @Override
                protected void onRelationshipRemoved(
                    LinkedEntity<Object> from,
                    LinkedEntity<Object> to,
                    StringBuilder removed,
                    Store<Object> store,
                    CommandBuffer<Object> buffer
                ) {
                    assertSame(fixture.firstStore, store);
                    assertSame(store, buffer.getStore());
                    assertSame(source.ref(), from.reference());
                    assertEquals(source.id(), from.identity());
                    assertNull(to.reference());
                    assertEquals(target.id(), to.identity());
                    assertSame(data, removed);
                    assertEquals("retained edited", removed.toString());
                    assertFalse(fixture.tracker.contains(type, source.id(), target.id()));
                    assertFalse(relationships.hasUnresolvedTargets(source.ref(), type));
                    assertEquals(0, relationships.getTargetCount(source.ref(), type));
                    events.add("removed");
                }

                @Override
                protected void onRelationshipAdded(
                    LinkedEntity<Object> from,
                    LinkedEntity<Object> to,
                    StringBuilder added,
                    Store<Object> store,
                    CommandBuffer<Object> buffer
                ) {
                    events.add("added");
                }
            });
            fixture.tracker.onEntityUnloaded(target.id(), target.ref(), UnloadReason.PENDING);
            fixture.firstStore.removeEntity(target.ref(), RemoveReason.UNLOAD);
            data.append(" edited");
            assertTrue(relationships.hasUnresolvedTargets(source.ref(), type));
            assertTrue(events.isEmpty());

            assertTrue(fixture.tracker.onUnloadResolved(target.id(), target.ref(), UnloadReason.DEACTIVATION));

            assertEquals(java.util.List.of("removed"), events);
            assertFalse(fixture.tracker.onUnloadResolved(target.id(), target.ref(), UnloadReason.DEACTIVATION));
            assertEquals(java.util.List.of("removed"), events);
        }
    }

    @Test
    void declarationsPreserveUnknownWhenRetainedLinksHaveNoLiveMarker() {
        try (var fixture = new Fixture()) {
            var follows = fixture.registerRuntime(
                RelationshipTraits.Survival.RETAIN, RelationshipTraits.Survival.RETAIN);
            var owns = fixture.registerRuntime(
                RelationshipTraits.Survival.RETAIN, RelationshipTraits.Survival.RETAIN);
            var alice = fixture.add(fixture.firstStore);
            var bob = fixture.add(fixture.firstStore);
            var weapon = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, alice.ref(), follows, bob.ref());
            relationships.addTarget(fixture.firstStore, bob.ref(), owns, weapon.ref());
            fixture.unload(weapon, UnloadReason.DEACTIVATION);
            assertNull(fixture.firstStore.getComponent(bob.ref(), owns.getSourceType()));
            var unknown = RelationshipQuery.exists(owns, Query.any());
            assertTrue(unknown.test(fixture.firstStore.getArchetype(bob.ref())));
            assertTrue(RelationshipQuery.of(owns, Query.any()).test(fixture.firstStore.getArchetype(bob.ref())));
            var query = RelationshipQuery.of(follows, Query.not(unknown));
            var calls = new java.util.ArrayList<Ref<Object>>();
            fixture.registry.registerSystem(new dev.hytalemodding.blovien.relwind.RelationshipTickingSystem<Object, Void>() {
                @NonNullDecl
                @Override
                public RelationshipQuery.Definition<Object, Void> getQuery() { return query; }

                @Override
                protected void tickRelationship(
                    float seconds,
                    RelationshipResult<Object, Void> result,
                    Store<Object> store,
                    CommandBuffer<Object> commands
                ) {
                    calls.add(result.getSource());
                }
            });
            fixture.firstStore.tick(0.05f);
            assertTrue(calls.isEmpty());

            assertEquals(0, (int) relationships.fetch(alice.ref(), query, results -> results.size()));
            assertEquals(1, (int) relationships.fetch(alice.ref(), RelationshipQuery.of(follows, Query.or(unknown, Query.any())), results -> results.size()));
            assertEquals(0, (int) relationships.fetch(alice.ref(), RelationshipQuery.of(follows, Query.and(unknown, Query.not(Query.any()))), results -> results.size()));
            assertEquals(0, (int) relationships.fetch(alice.ref(), RelationshipQuery.of(follows, Query.or(unknown, Query.not(unknown))), results -> results.size()));
        }
    }

    @Test
    void aNegatedConditionTicksASourceWithoutTheLinkAndSkipsASourceWhoseReleasedLinkIsUnresolved() {
        try (var fixture = new Fixture()) {
            var follows = fixture.types.registerRelationship(RelationshipTraits.defaults());
            var owns = fixture.types.registerRelationship(RelationshipTraits.defaults().retainOnDeactivation());
            var leader = fixture.add(fixture.firstStore);
            var unarmed = fixture.add(fixture.firstStore);
            var armed = fixture.add(fixture.firstStore);
            var weapon = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, unarmed.ref(), follows, leader.ref());
            relationships.addTarget(fixture.firstStore, armed.ref(), follows, leader.ref());
            relationships.addTarget(fixture.firstStore, armed.ref(), owns, weapon.ref());
            var query = RelationshipQuery.of(
                Query.not(RelationshipQuery.exists(owns, Query.any())), follows, Query.any());
            var ticked = new java.util.ArrayList<Ref<Object>>();
            fixture.registry.registerSystem(new RelationshipTickingSystem<Object, Void>() {
                @Nonnull @Override
                public RelationshipQuery.Definition<Object, Void> getQuery() {
                    return query;
                }

                @Override
                protected void tickRelationship(
                    float seconds,
                    RelationshipResult<Object, Void> result,
                    Store<Object> store,
                    CommandBuffer<Object> commands
                ) {
                    ticked.add(result.getSource());
                }
            });
            fixture.unload(weapon, UnloadReason.DEACTIVATION);

            fixture.firstStore.tick(0.05f);

            assertEquals(java.util.List.of(unarmed.ref()), ticked);
        }
    }

    @Test
    void retargetCannotOverwriteAnExistingPendingDestination() {
        try (var fixture = new Fixture()) {
            var type = fixture.registerRuntime(
                RelationshipTraits.Survival.RETAIN, RelationshipTraits.Survival.RETAIN);
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            var other = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());
            relationships.addTarget(fixture.firstStore, source.ref(), type, other.ref());
            fixture.tracker.onEntityUnloaded(target.id(), target.ref(), UnloadReason.PENDING);
            var holder = fixture.firstStore.removeEntity(target.ref(), RemoveReason.UNLOAD);
            var returned = fixture.load(target.id(), holder, fixture.firstStore);

            assertThrows(IllegalStateException.class,
                () -> relationships.retarget(fixture.firstStore, source.ref(), type, other.ref(), returned));
            assertSame(other.ref(), relationships.getFirstTarget(source.ref(), type));
            assertEquals(1, relationships.getIncomingCount(other.ref(), type));
            assertEquals(0, relationships.getIncomingCount(returned, type));
            assertTrue(relationships.hasUnresolvedTargets(source.ref(), type));
            assertTrue(fixture.tracker.onUnloadResolved(target.id(), target.ref(), UnloadReason.TRANSFER));
            assertEquals(2, relationships.getTargetCount(source.ref(), type));
            assertEquals(1, relationships.getIncomingCount(returned, type));
        }
    }

    @ParameterizedTest
    @EnumSource(LinkCommand.class)
    void aPendingLinkStaysUnresolvedAndIsDroppedWhenTransferDoesNotRetain(LinkCommand command) {
        try (var fixture = new Fixture()) {
            var type = fixture.types.registerRelationship(
                Object.class, RelationshipTraits.defaults().exclusive().retainOnDeactivation());
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            var originalData = new Object();
            var replacementData = new Object();
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref(), originalData);
            fixture.tracker.onEntityUnloaded(target.id(), target.ref(), UnloadReason.PENDING);
            var holder = fixture.firstStore.removeEntity(target.ref(), RemoveReason.UNLOAD);
            var returned = fixture.load(target.id(), holder, fixture.firstStore);

            assertThrows(IllegalStateException.class,
                () -> relationships.addTarget(fixture.firstStore, source.ref(), type, returned, new Object()));
            applyPendingCommand(command, fixture.firstStore, source.ref(), type, returned,
                originalData, replacementData);
            assertThrows(IllegalStateException.class,
                () -> relationships.addTarget(fixture.firstStore, source.ref(), type, returned, new Object()));
            assertNull(relationships.getFirstTarget(source.ref(), type));
            assertEquals(0, relationships.getIncomingCount(returned, type));
            assertTrue(relationships.hasUnresolvedTargets(source.ref(), type));
            assertFalse(fixture.tracker.isResolved(type, source.id(), target.id()));

            assertTrue(fixture.tracker.onUnloadResolved(target.id(), target.ref(), UnloadReason.TRANSFER));

            assertFalse(relationships.hasUnresolvedTargets(source.ref(), type));
            assertNull(relationships.getFirstTarget(source.ref(), type));
            assertEquals(0, relationships.getIncomingCount(returned, type));
            assertFalse(fixture.tracker.contains(type, source.id(), target.id()));
        }
    }

    @ParameterizedTest
    @EnumSource(LinkCommand.class)
    void aPendingLinkStaysUnresolvedAndReconnectsWhenTransferRetains(LinkCommand command) {
        try (var fixture = new Fixture()) {
            var type = fixture.types.registerRelationship(
                Object.class, RelationshipTraits.defaults().exclusive().retainOnDeactivation().retainOnTransfer());
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            var originalData = new Object();
            var replacementData = new Object();
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref(), originalData);
            fixture.tracker.onEntityUnloaded(target.id(), target.ref(), UnloadReason.PENDING);
            var holder = fixture.firstStore.removeEntity(target.ref(), RemoveReason.UNLOAD);
            var returned = fixture.load(target.id(), holder, fixture.firstStore);

            assertThrows(IllegalStateException.class,
                () -> relationships.addTarget(fixture.firstStore, source.ref(), type, returned, new Object()));
            var expectedData = applyPendingCommand(command, fixture.firstStore, source.ref(), type, returned,
                originalData, replacementData);
            assertThrows(IllegalStateException.class,
                () -> relationships.addTarget(fixture.firstStore, source.ref(), type, returned, new Object()));
            assertNull(relationships.getFirstTarget(source.ref(), type));
            assertEquals(0, relationships.getIncomingCount(returned, type));
            assertTrue(relationships.hasUnresolvedTargets(source.ref(), type));
            assertFalse(fixture.tracker.isResolved(type, source.id(), target.id()));

            assertTrue(fixture.tracker.onUnloadResolved(target.id(), target.ref(), UnloadReason.TRANSFER));

            assertFalse(relationships.hasUnresolvedTargets(source.ref(), type));
            assertSame(returned, relationships.getFirstTarget(source.ref(), type));
            assertEquals(1, relationships.getIncomingCount(returned, type));
            assertSame(expectedData, relationships.getData(source.ref(), type, returned));
        }
    }

    @Test
    void addRejectsANewTargetWhileAnExclusiveTargetIsAway() {
        try (var fixture = new Fixture()) {
            var type = fixture.types.registerRelationship(
                RelationshipTraits.defaults().exclusive().retainOnTransfer().retainOnDeactivation());
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            var other = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());
            var holder = fixture.unload(target, UnloadReason.DEACTIVATION);

            assertThrows(IllegalStateException.class,
                () -> relationships.addTarget(fixture.firstStore, source.ref(), type, other.ref()));

            assertNull(relationships.getFirstTarget(source.ref(), type));
            assertEquals(0, relationships.getIncomingCount(other.ref(), type));
            assertTrue(relationships.hasUnresolvedTargets(source.ref(), type));
            var returned = fixture.load(target.id(), holder, fixture.firstStore);
            assertSame(returned, relationships.getFirstTarget(source.ref(), type));
            assertEquals(1, relationships.getIncomingCount(returned, type));
            assertEquals(0, relationships.getIncomingCount(other.ref(), type));
            relationships.retarget(fixture.firstStore, source.ref(), type, returned, other.ref());
            assertSame(other.ref(), relationships.getFirstTarget(source.ref(), type));
            assertEquals(0, relationships.getIncomingCount(returned, type));
        }
    }

    @Test
    void putWithoutDataReplacesAnExclusiveAwayTargetAndKeepsItRemovedOnReturn() {
        try (var fixture = new Fixture()) {
            var type = fixture.types.registerRelationship(Saddle.class,
                RelationshipTraits.defaults().exclusive().retainOnDeactivation());
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            var replacement = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref(), new Saddle(1));
            var holder = fixture.unload(target, UnloadReason.DEACTIVATION);

            relationships.putTarget(fixture.firstStore, source.ref(), type, replacement.ref());

            assertSame(replacement.ref(), relationships.getFirstTarget(source.ref(), type));
            assertEquals(null, relationships.getData(source.ref(), type, replacement.ref()));
            assertEquals(false, relationships.hasUnresolvedTargets(source.ref(), type));
            var returned = fixture.load(target.id(), holder, fixture.firstStore);
            assertEquals(1, relationships.getTargetCount(source.ref(), type));
            assertEquals(0, relationships.getIncomingCount(returned, type));
            assertEquals(1, relationships.getIncomingCount(replacement.ref(), type));
        }
    }

    @Test
    void putRejectsAReplacementWithoutIdentityAndKeepsTheAwayTarget() {
        try (var fixture = new Fixture()) {
            var type = fixture.types.registerRelationship(
                RelationshipTraits.defaults().exclusive().retainOnDeactivation());
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            var replacement = fixture.firstStore.addEntity(Archetype.empty(), AddReason.SPAWN);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());
            var holder = fixture.unload(target, UnloadReason.DEACTIVATION);

            assertThrows(IllegalStateException.class,
                () -> relationships.putTarget(fixture.firstStore, source.ref(), type, replacement));

            assertEquals(0, relationships.getIncomingCount(replacement, type));
            assertEquals(true, relationships.hasUnresolvedTargets(source.ref(), type));
            var returned = fixture.load(target.id(), holder, fixture.firstStore);
            assertSame(returned, relationships.getFirstTarget(source.ref(), type));
        }
    }

    private enum LinkCommand {
        ADD, SET
    }

    private static Object applyPendingCommand(
        LinkCommand command,
        Store<Object> store,
        Ref<Object> source,
        GenericRelationshipType<Object, Object, Object> type,
        Ref<Object> target,
        Object original,
        Object replacement
    ) {
        if (command == LinkCommand.SET) {
            relationships.putTarget(store, source, type, target, replacement);
            return replacement;
        }
        return original;
    }

    @Test
    void aNamedTypeWithoutPersistenceKeepsItsRecordWhileBothEndsAreAway() {
        try (var fixture = new Fixture()) {
            var type = fixture.registerPersistent("without-persistence",
                RelationshipTraits.Survival.RETAIN, RelationshipTraits.Survival.RETAIN);
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());

            var sourceHolder = fixture.park(source, UnloadReason.DEACTIVATION);
            var targetHolder = fixture.park(target, UnloadReason.DEACTIVATION);

            assertEquals(true, fixture.tracker.hasRecordedLinks());
            assertEquals(true, fixture.tracker.contains(type, source.id(), target.id()));
            var returnedTarget = fixture.load(target.id(), targetHolder, fixture.firstStore);
            var returnedSource = fixture.load(source.id(), sourceHolder, fixture.firstStore);
            assertSame(returnedTarget, relationships.getFirstTarget(returnedSource, type));
        }
    }

    @Test
    void removalFromAReconnectCallbackIsRejectedAndKeepsTheRestoredAssociation() {
        try (var fixture = new Fixture()) {
            var type = fixture.registerRuntime(
                RelationshipTraits.Survival.RETAIN, RelationshipTraits.Survival.RETAIN);
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());
            var holder = fixture.unload(source, UnloadReason.TRANSFER);
            fixture.registry.registerSystem(new RemoveOnAdd(type, target.ref()));

            var rejected = assertThrows(IllegalStateException.class,
                () -> fixture.load(source.id(), holder, fixture.firstStore));

            assertTrue(rejected.getMessage().contains("CommandBuffer"), rejected.getMessage());
            assertTrue(fixture.tracker.contains(type, source.id(), target.id()));
        }
    }

    @Test
    void staleUnloadDoesNotDetachTheCurrentLinkedEntity() {
        try (var fixture = new Fixture()) {
            var type = fixture.registerRuntime(
                RelationshipTraits.Survival.RETAIN, RelationshipTraits.Survival.REMOVE);
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());
            var holder = fixture.unload(source, UnloadReason.TRANSFER);
            var restored = fixture.load(source.id(), holder, fixture.firstStore);

            fixture.tracker.onEntityUnloaded(source.id(), source.ref(),
                UnloadReason.DEACTIVATION);

            assertSame(target.ref(), relationships.getFirstTarget(restored, type));
            assertTrue(fixture.tracker.contains(type, source.id(), target.id()));
            fixture.tracker.onEntityDeleted(source.id(), source.ref());
            assertSame(restored, fixture.tracker.getRef(source.id()));
            assertTrue(fixture.tracker.contains(type, source.id(), target.id()));
        }
    }

    @Test
    void resolvingAnUnloadAsPendingIsRejectedAndLeavesItPending() {
        try (var fixture = new Fixture()) {
            var type = fixture.registerRuntime(
                RelationshipTraits.Survival.REMOVE, RelationshipTraits.Survival.REMOVE);
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());
            fixture.tracker.onEntityUnloaded(target.id(), target.ref(), UnloadReason.PENDING);

            assertThrows(IllegalArgumentException.class,
                () -> fixture.tracker.onUnloadResolved(target.id(), target.ref(), UnloadReason.PENDING));

            assertTrue(fixture.tracker.contains(type, source.id(), target.id()));
            assertTrue(fixture.tracker.onUnloadResolved(target.id(), target.ref(), UnloadReason.TRANSFER));
            assertFalse(fixture.tracker.contains(type, source.id(), target.id()));
        }
    }

    @Test
    void removalFromAnAddCallbackIsRejectedAndTheFailedAddIsNotRecorded() {
        try (var fixture = new Fixture()) {
            var type = fixture.registerRuntime(
                RelationshipTraits.Survival.RETAIN, RelationshipTraits.Survival.RETAIN);
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            fixture.registry.registerSystem(new RemoveOnAdd(type, target.ref()));

            var rejected = assertThrows(IllegalStateException.class,
                () -> relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref()));

            assertTrue(rejected.getMessage().contains("CommandBuffer"), rejected.getMessage());
            assertEquals(1, relationships.getTargetCount(source.ref(), type));
            assertFalse(fixture.tracker.hasRecordedLinks());
        }
    }

    @Test
    void unloadCallbackDoesNotMoveTheEntityWhileStoreIsRemovingIt() {
        var fixture = new Fixture();
        try {
            var type = fixture.registerRuntime(
                RelationshipTraits.Survival.RETAIN,
                RelationshipTraits.Survival.REMOVE
            );
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            var secondTarget = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());
            relationships.addTarget(fixture.firstStore, source.ref(), type, secondTarget.ref());
            fixture.registry.registerSystem(
                new UnloadPreparationSystem(fixture.tracker, source.id())
            );

            var holder = fixture.firstStore.removeEntity(source.ref(), RemoveReason.UNLOAD);
            fixture.tracker.onEntityUnloaded(
                source.id(),
                source.ref(),
                UnloadReason.TRANSFER,
                holder
            );

            assertFalse(source.ref().isValid());
            assertTrue(target.ref().isValid());
            assertTrue(fixture.tracker.contains(type, source.id(), target.id()));
            assertFalse(fixture.tracker.isResolved(type, source.id(), target.id()));
            assertEquals(0, relationships.getIncomingCount(target.ref(), type));
            assertEquals(0, relationships.getIncomingCount(secondTarget.ref(), type));
            assertNull(holder.getComponent(type.getSourceType()));

            var restoredSource = fixture.load(source.id(), holder, fixture.firstStore);
            assertSame(target.ref(), relationships.getFirstTarget(restoredSource, type));
            assertEquals(2, relationships.getTargetCount(restoredSource, type));
        } finally {
            fixture.close();
        }
    }

    @Test
    void sourceTransferStaysUnresolvedWhileTheTargetRemainsInAnotherStore() {
        var fixture = new Fixture();
        try {
            var type = fixture.registerRuntime(
                RelationshipTraits.Survival.RETAIN,
                RelationshipTraits.Survival.REMOVE
            );
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());

            var holder = fixture.unload(source, UnloadReason.TRANSFER);
            var newSource = fixture.load(source.id(), holder, fixture.secondStore);

            assertTrue(fixture.tracker.contains(type, source.id(), target.id()));
            assertFalse(fixture.tracker.isResolved(type, source.id(), target.id()));
            assertNull(relationships.getFirstTarget(newSource, type));
            assertSame(fixture.firstStore, fixture.tracker.getRef(target.id()).getStore());
        } finally {
            fixture.close();
        }
    }

    @Test
    void targetDeactivationReconnectsWithoutChangingTheSourceReference() {
        var fixture = new Fixture();
        try {
            var type = fixture.registerRuntime(
                RelationshipTraits.Survival.REMOVE,
                RelationshipTraits.Survival.RETAIN
            );
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());

            var holder = fixture.unload(target, UnloadReason.DEACTIVATION);
            assertNull(relationships.getFirstTarget(source.ref(), type));
            assertTrue(relationships.hasUnresolvedTargets(source.ref(), type));
            var newTarget = fixture.load(target.id(), holder, fixture.firstStore);

            assertSame(newTarget, relationships.getFirstTarget(source.ref(), type));
            assertFalse(relationships.hasUnresolvedTargets(source.ref(), type));
        } finally {
            fixture.close();
        }
    }

    @Test
    void transientLinkRetainedAcrossBothLinkedEntityTransfersReconnectsInTheDestinationStore() {
        var fixture = new Fixture();
        try {
            var type = fixture.registerRuntime(
                RelationshipTraits.Survival.RETAIN,
                RelationshipTraits.Survival.REMOVE
            );
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());

            var sourceHolder = fixture.unload(source, UnloadReason.TRANSFER);
            assertFalse(source.ref().isValid());
            assertTrue(fixture.tracker.contains(type, source.id(), target.id()));
            assertFalse(fixture.tracker.isResolved(type, source.id(), target.id()));

            var targetHolder = fixture.unload(target, UnloadReason.TRANSFER);
            var newSource = fixture.load(source.id(), sourceHolder, fixture.secondStore);
            assertFalse(fixture.tracker.isResolved(type, source.id(), target.id()));
            var newTarget = fixture.load(target.id(), targetHolder, fixture.secondStore);

            assertTrue(fixture.tracker.isResolved(type, source.id(), target.id()));
            assertSame(newTarget, relationships.getFirstTarget(newSource, type));
        } finally {
            fixture.close();
        }
    }

    @Test
    void transferAndDeactivationPoliciesAreIndependentOfSaving() {
        var fixture = new Fixture();
        try {
            var transientTransfer = fixture.registerRuntime(
                RelationshipTraits.Survival.RETAIN,
                RelationshipTraits.Survival.REMOVE
            );
            var persistentDeactivation = fixture.registerPersistent(
                "saved-deactivation",
                RelationshipTraits.Survival.REMOVE,
                RelationshipTraits.Survival.RETAIN
            );
            var source = fixture.add(fixture.firstStore);
            var firstTarget = fixture.add(fixture.firstStore);
            var secondTarget = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), transientTransfer, firstTarget.ref());
            relationships.addTarget(fixture.firstStore, source.ref(), persistentDeactivation, secondTarget.ref());

            fixture.tracker.onEntityUnloaded(
                source.id(),
                source.ref(),
                UnloadReason.TRANSFER
            );

            assertTrue(fixture.tracker.contains(transientTransfer, source.id(), firstTarget.id()));
            assertFalse(fixture.tracker.contains(persistentDeactivation, source.id(), secondTarget.id()));
        } finally {
            fixture.close();
        }
    }

    @Test
    void linksWithoutSurvivalAreRemovedAfterClassifiedUnload() {
        var fixture = new Fixture();
        try {
            var type = fixture.registerPersistent(
                "not-retained",
                RelationshipTraits.Survival.REMOVE,
                RelationshipTraits.Survival.REMOVE
            );
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);

            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());

            assertSame(target.ref(), relationships.getFirstTarget(source.ref(), type));
            fixture.unload(source, UnloadReason.TRANSFER);
            assertFalse(fixture.tracker.contains(type, source.id(), target.id()));
        } finally {
            fixture.close();
        }
    }

    @Test
    void confirmedLinkedEntityRemovalForgetsRetainedLinks() {
        var fixture = new Fixture();
        try {
            var type = fixture.registerPersistent(
                "confirmed-removal",
                RelationshipTraits.Survival.RETAIN,
                RelationshipTraits.Survival.RETAIN
            );
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());

            fixture.tracker.onEntityDeleted(source.id(), source.ref());

            assertFalse(fixture.tracker.contains(type, source.id(), target.id()));
        } finally {
            fixture.close();
        }
    }

    @Test
    void unregisteringATypeForgetsItsRetainedAssociations() {
        var fixture = new Fixture();
        try {
            var type = fixture.registerRuntime(
                RelationshipTraits.Survival.RETAIN,
                RelationshipTraits.Survival.RETAIN
            );
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());

            fixture.types.unregisterRelationship(type);

            assertFalse(fixture.tracker.contains(type, source.id(), target.id()));
        } finally {
            fixture.close();
        }
    }

    @Test
    void unclassifiedUnloadKeepsTheLinkUntilClassificationAppliesItsPolicy() {
        var fixture = new Fixture();
        try {
            var type = fixture.registerRuntime(
                RelationshipTraits.Survival.RETAIN,
                RelationshipTraits.Survival.REMOVE
            );
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());

            fixture.tracker.onEntityUnloaded(source.id(), source.ref(), UnloadReason.PENDING);
            assertTrue(fixture.tracker.contains(type, source.id(), target.id()));

            fixture.tracker.onUnloadResolved(source.id(), source.ref(), UnloadReason.DEACTIVATION);
            assertFalse(fixture.tracker.contains(type, source.id(), target.id()));
        } finally {
            fixture.close();
        }
    }

    @Test
    void loadKeepsTheLinkUnresolvedUntilClassification() {
        var fixture = new Fixture();
        try {
            var type = fixture.registerRuntime(
                RelationshipTraits.Survival.RETAIN,
                RelationshipTraits.Survival.REMOVE
            );
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref());

            fixture.tracker.onEntityUnloaded(source.id(), source.ref(), UnloadReason.PENDING);
            var holder = fixture.firstStore.removeEntity(source.ref(), RemoveReason.UNLOAD);
            var restoredSource = fixture.load(source.id(), holder, fixture.firstStore);

            assertFalse(fixture.tracker.isResolved(type, source.id(), target.id()));
            assertNull(relationships.getFirstTarget(restoredSource, type));
            assertTrue(fixture.tracker.onUnloadResolved(source.id(), source.ref(), UnloadReason.TRANSFER));
            assertTrue(fixture.tracker.isResolved(type, source.id(), target.id()));
            assertSame(target.ref(), relationships.getFirstTarget(restoredSource, type));
            assertFalse(fixture.tracker.onUnloadResolved(source.id(), source.ref(), UnloadReason.DEACTIVATION));
        } finally {
            fixture.close();
        }
    }

    @Test
    void aRetainedLinkKeepsItsDataAndResolutionReusesThatInstance() {
        try (var fixture = new Fixture()) {
            var type = fixture.types.registerRelationship(Saddle.class, RelationshipTraits.defaults().exclusive().retainOnDeactivation());
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            var saddle = new Saddle(1);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref(), saddle);

            var holder = fixture.park(target, UnloadReason.DEACTIVATION);

            assertEquals(0, relationships.getTargetCount(source.ref(), type));
            assertSame(saddle, fixture.tracker.getUnresolvedLinkData(type, source.ref(), target.ref()));

            var returned = fixture.load(target.id(), holder, fixture.firstStore);

            assertEquals(1, relationships.getTargetCount(source.ref(), type));
            assertSame(saddle, relationships.getData(source.ref(), type, returned));
        }
    }

    @Test
    void aPolicyRemovalAnnouncesTheRemovedLinkData() {
        try (var fixture = new Fixture()) {
            var type = fixture.types.registerRelationship(Saddle.class, RelationshipTraits.defaults().exclusive().retainOnTransfer());
            var source = fixture.add(fixture.firstStore);
            var target = fixture.add(fixture.firstStore);
            var saddle = new Saddle(1);
            relationships.addTarget(fixture.firstStore, source.ref(), type, target.ref(), saddle);
            var removed = new java.util.ArrayList<Saddle>();
            fixture.registry.registerSystem(new RelationshipChangeSystem<Object, Saddle>(type) {
                @Override
                protected void onRelationshipRemoved(
                    LinkedEntity<Object> from,
                    LinkedEntity<Object> to,
                    Saddle data,
                    Store<Object> store,
                    CommandBuffer<Object> buffer
                ) {
                    removed.add(data);
                }
            });

            fixture.park(target, UnloadReason.DEACTIVATION);

            assertFalse(fixture.tracker.contains(type, source.id(), target.id()));
            assertEquals(java.util.List.of(saddle), removed);
        }
    }

    private record Saddle(int seat) {
    }

    private static final class RemoveOnAdd extends com.hypixel.hytale.component.system.RefChangeSystem<Object, OutgoingLink<Object, Object>> {
        private final GenericRelationshipType<Object, Object, Void> type;
        private final Ref<Object> target;

        private RemoveOnAdd(GenericRelationshipType<Object, Object, Void> type, Ref<Object> target) {
            this.type = type;
            this.target = target;
        }

        @Override
        public com.hypixel.hytale.component.ComponentType<Object, OutgoingLink<Object, Object>> componentType() {
            return type.getSourceType();
        }

        @Override
        public Query<Object> getQuery() { return Query.any(); }

        @Override
        public void onComponentAdded(
            Ref<Object> ref,
            OutgoingLink<Object, Object> component,
            Store<Object> store,
            CommandBuffer<Object> commands
        ) {
            relationships.removeTarget(store, ref, type, target);
        }

        @Override
        public void onComponentSet(
            Ref<Object> ref,
            OutgoingLink<Object, Object> oldComponent,
            OutgoingLink<Object, Object> component,
            Store<Object> store,
            CommandBuffer<Object> commands
        ) { }

        @Override
        public void onComponentRemoved(
            Ref<Object> ref,
            OutgoingLink<Object, Object> component,
            Store<Object> store,
            CommandBuffer<Object> commands
        ) { }
    }

    private static final class Fixture implements AutoCloseable {
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final RelationshipTypeRegistry<Object> types = new RelationshipTypeRegistry<>(registry);
        private final IdentityHashMap<Ref<Object>, UUID> identities = new IdentityHashMap<>();
        private final RelationshipTracker<Object, UUID> tracker = types.installTracker(
            TestPersistenceIdentity.of(identities::get, com.hypixel.hytale.codec.Codec.UUID_BINARY),
            TestStoreRuntime.inline());
        private final com.hypixel.hytale.component.Store<Object> firstStore =
            registry.addStore(new Object(), EmptyResourceStorage.get());
        private final com.hypixel.hytale.component.Store<Object> secondStore =
            registry.addStore(new Object(), EmptyResourceStorage.get());

        /// A runtime type takes no id.
        private RelationshipType<Object, Void> registerRuntime(
            RelationshipTraits.Survival transfer,
            RelationshipTraits.Survival deactivation
        ) {
            return types.registerRelationship(traits(transfer, deactivation));
        }

        private RelationshipType<Object, Void> registerPersistent(
            String name,
            RelationshipTraits.Survival transfer,
            RelationshipTraits.Survival deactivation
        ) {
            return types.registerRelationship("relwind:test/" + name, traits(transfer, deactivation));
        }

        private static RelationshipTraits traits(RelationshipTraits.Survival transfer, RelationshipTraits.Survival deactivation) {
            var traits = RelationshipTraits.defaults();
            if (transfer == RelationshipTraits.Survival.RETAIN) traits = traits.retainOnTransfer();
            if (deactivation == RelationshipTraits.Survival.RETAIN) traits = traits.retainOnDeactivation();
            return traits;
        }

        private Entity add(com.hypixel.hytale.component.Store<Object> store) {
            var ref = store.addEntity(Archetype.empty(), AddReason.SPAWN);
            var id = UUID.randomUUID();
            identities.put(ref, id);
            tracker.onEntityLoaded(id, ref);
            return new Entity(id, ref);
        }

        private com.hypixel.hytale.component.Holder<Object> unload(Entity entity, UnloadReason reason) {
            tracker.onEntityUnloaded(entity.id(), entity.ref(), reason);
            return entity.ref().getStore().removeEntity(entity.ref(), RemoveReason.UNLOAD);
        }

        /// The server removes the entity before it reports the unload, and the reference is
        /// already invalid by then.
        private com.hypixel.hytale.component.Holder<Object> park(Entity entity, UnloadReason reason) {
            var holder = entity.ref().getStore().removeEntity(entity.ref(), RemoveReason.UNLOAD);
            tracker.onEntityUnloaded(entity.id(), entity.ref(), reason, holder);
            return holder;
        }

        private Ref<Object> load(
            UUID id,
            com.hypixel.hytale.component.Holder<Object> holder,
            com.hypixel.hytale.component.Store<Object> store
        ) {
            var ref = new Ref<>(store);
            identities.put(ref, id);
            store.addEntity(holder, ref, AddReason.LOAD);
            tracker.onEntityLoaded(id, ref);
            return ref;
        }

        @Override
        public void close() {
            tracker.close();
            registry.shutdown();
        }
    }

    private record Entity(UUID id, Ref<Object> ref) {
    }

    private static final class UnloadPreparationSystem extends RefSystem<Object> {
        private final RelationshipTracker<Object, UUID> tracker;
        private final UUID id;

        private UnloadPreparationSystem(RelationshipTracker<Object, UUID> tracker, UUID id) {
            this.tracker = tracker;
            this.id = id;
        }

        @Nonnull
        @Override
        public Query<Object> getQuery() {
            return Archetype.empty();
        }

        @Override
        public void onEntityAdded(
            @Nonnull Ref<Object> ref,
            @Nonnull AddReason reason,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            tracker.onEntityLoaded(commandBuffer, id, ref);
        }

        @Override
        public void onEntityRemove(
            @Nonnull Ref<Object> ref,
            @Nonnull RemoveReason reason,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            tracker.onEntityUnloading(commandBuffer, id, ref);
        }
    }

    @Test
    void anUnloadingBridgeLinkedEntityAppliesTheTypePolicyThroughTheTransitionsOfItsOwnSide() {
        try (var linked = new LinkedInstallations()) {
            var anchoredTo = linked.entityTypes.registerRelationship(
                linked.blockTypes,
                RelationshipTraits.defaults().retainOnDeactivation());
            var tetheredTo = linked.entityTypes.registerRelationship(
                linked.blockTypes,
                RelationshipTraits.defaults());
            var source = linked.entity("source");
            var block = linked.block(1);
            relationships.addTarget(linked.world.entityStore(), source, anchoredTo, block);
            relationships.addTarget(linked.world.entityStore(), source, tetheredTo, block);

            linked.unloadBlock(1, block);

            // the block unloaded and the entity store carries the repair
            assertFalse(linked.entityTasks.isEmpty());
            assertTrue(linked.blockTasks.isEmpty());
            linked.runTransitions();
            assertEquals(0, relationships.getTargetCount(source, anchoredTo));
            assertEquals(0, relationships.getTargetCount(source, tetheredTo));

            // the anchored condition is unknown while the block is away, and so is its negation
            var follows = linked.entityTypes.registerRelationship(RelationshipTraits.defaults());
            relationships.addTarget(linked.world.entityStore(), source, follows, linked.entity("companion"));
            var anchored = RelationshipQuery.exists(anchoredTo, Query.any());
            int anchoredMatches = relationships.fetch(source,
                RelationshipQuery.of(anchored, follows), RelationshipResults::size);
            int negatedMatches = relationships.fetch(source,
                RelationshipQuery.of(RelationshipQuery.not(anchored), follows), RelationshipResults::size);
            assertEquals(0, anchoredMatches);
            assertEquals(0, negatedMatches);

            // only the retained link returns with its linked entity
            var returned = linked.block(1);
            linked.runTransitions();
            assertEquals(1, relationships.getTargetCount(source, anchoredTo));
            assertSame(returned, relationships.getFirstTarget(source, anchoredTo));
            assertEquals(0, relationships.getTargetCount(source, tetheredTo));
        }
    }

    @Test
    void anUnresolvedBridgeLinkRetainsTheSourceDataUntilBothLinkedEntitiesAreBack() {
        try (var linked = new LinkedInstallations()) {
            var anchoredTo = linked.entityTypes.registerRelationship(
                linked.blockTypes,
                Anchor.class,
                RelationshipTraits.defaults().retainOnDeactivation());
            var source = linked.entity("source");
            var block = linked.block(1);
            var anchor = new Anchor("north");
            relationships.addTarget(linked.world.entityStore(), source, anchoredTo, block, anchor);

            // the source unloaded and the block store carries the repair
            linked.unloadEntity("source", source);
            assertFalse(linked.blockTasks.isEmpty());
            assertTrue(linked.entityTasks.isEmpty());
            linked.runTransitions();
            assertEquals(0, relationships.getIncomingCount(block, anchoredTo));

            var returned = linked.entity("source");
            linked.runTransitions();
            assertEquals(1, relationships.getTargetCount(returned, anchoredTo));
            assertSame(block, relationships.getFirstTarget(returned, anchoredTo));
            assertSame(anchor, relationships.getData(returned, anchoredTo, block));
            assertEquals(1, relationships.getIncomingCount(block, anchoredTo));
        }
    }
}
