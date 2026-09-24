/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;


import com.hypixel.hytale.component.BridgeStoreFixture;
import com.hypixel.hytale.component.BridgeStoreFixture.Blocks;
import com.hypixel.hytale.component.BridgeStoreFixture.Entities;
import com.hypixel.hytale.component.RemoveReason;

import static dev.hytalemodding.blovien.relwind.RelationshipTestFixtures.*;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Position;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A command takes its Store from the accessor it is given and leaves the forward and reverse
/// views of the link consistent, or fails and changes neither.
class RelationshipsTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void aCommandTakesItsStoreFromTheAccessor() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = types.registerRelationship(FollowData.class, RelationshipTraits.defaults().exclusive());
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            var otherStore = fixture.registry().addStore(new Object(), EmptyResourceStorage.get());
            var foreign = Objects.requireNonNull(otherStore.addEntity(Archetype.empty(), AddReason.SPAWN));
            var data = new FollowData("near");

            relationships.addTarget(fixture.store(), source, follows, target, data);

            assertSame(target, relationships.getFirstTarget(source, follows));

            assertThrows(IllegalArgumentException.class,
                () -> relationships.tryRemoveTarget(fixture.store(), foreign, follows, foreign));
            assertThrows(IllegalArgumentException.class,
                () -> relationships.tryRemoveTarget(otherStore, source, follows, target));
            assertThrows(IllegalArgumentException.class,
                () -> relationships.tryRemoveTarget(unknownAccessor(), source, follows, target));
            assertSame(target, relationships.getFirstTarget(source, follows));
        }
    }

    /// One type reads each Store independently.
    @Test
    void aReadTakesItsStoreFromTheRef() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = types.registerRelationship(FollowData.class, RelationshipTraits.defaults().exclusive());
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            var otherStore = fixture.registry().addStore(new Object(), EmptyResourceStorage.get());
            var otherSource = Objects.requireNonNull(otherStore.addEntity(Archetype.empty(), AddReason.SPAWN));
            var otherTarget = Objects.requireNonNull(otherStore.addEntity(Archetype.empty(), AddReason.SPAWN));

            relationships.addTarget(fixture.store(), source, follows, target, new FollowData("near"));
            relationships.addTarget(otherStore, otherSource, follows, otherTarget, new FollowData("far"));

            assertSame(target, relationships.getFirstTarget(source, follows));
            assertSame(otherTarget, relationships.getFirstTarget(otherSource, follows));
            assertEquals(1, relationships.getIncomingCount(target, follows));
            assertEquals(1, relationships.getIncomingCount(otherTarget, follows));
        }
    }

    /// A type registered on one registry rejects linked entities and accessors of another registry.
    @Test
    void aLinkedEntityFromAnotherRegistryIsRejected() {
        var registry = new ComponentRegistry<Object>();
        try {
            var types = new RelationshipTypeRegistry<>(registry);
            var follows = types.registerRelationship(FollowData.class, RelationshipTraits.defaults().exclusive());
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var source = Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
            var target = Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
            try (var fixture = new StoreFixture()) {
                var foreign = fixture.addEntity(new Position(1, 2), null);

                assertThrows(IllegalArgumentException.class,
                    () -> relationships.addTarget(fixture.store(), source, follows, target, new FollowData("near")));
                assertThrows(IllegalArgumentException.class, () -> relationships.getFirstTarget(foreign, follows));
            }
        } finally {
            registry.shutdown();
        }
    }

    /// A command rejects an accessor that is neither a Store nor a CommandBuffer before calling it.
    @SuppressWarnings("unchecked")
    private static ComponentAccessor<Object> unknownAccessor() {
        return (ComponentAccessor<Object>) Proxy.newProxyInstance(
            ComponentAccessor.class.getClassLoader(),
            new Class<?>[] {ComponentAccessor.class},
            (proxy, method, arguments) -> {
                throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test
    void commandsFollowTheStrictAndLenientPreconditionTable() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = types.registerRelationship(FollowData.class, RelationshipTraits.defaults().exclusive());
            var watches = types.registerRelationship(FollowData.class, RelationshipTraits.defaults());
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            var otherTarget = fixture.addEntity(new Position(5, 6), null);
            var initial = new FollowData("near");
            var replacement = new FollowData("far");

            relationships.putTarget(fixture.store(), source, follows, target, initial);
            assertForwardAndReverseLink(follows, source, target, initial);

            assertThrows(IllegalStateException.class, () -> relationships.addTarget(fixture.store(), source, follows, target, replacement));
            assertForwardAndReverseLink(follows, source, target, initial);

            assertThrows(IllegalStateException.class, () -> relationships.addTarget(fixture.store(), source, follows, otherTarget, replacement));
            assertForwardAndReverseLink(follows, source, target, initial);
            assertEquals(0, relationships.getIncomingCount(otherTarget, follows));

            // putting the instance already stored is accepted
            relationships.putTarget(fixture.store(), source, follows, target, initial);
            assertForwardAndReverseLink(follows, source, target, initial);
            relationships.putTarget(fixture.store(), source, follows, target, replacement);
            assertForwardAndReverseLink(follows, source, target, replacement);

            relationships.retarget(fixture.store(), source, follows, target, target);
            assertForwardAndReverseLink(follows, source, target, replacement);

            assertThrows(IllegalStateException.class,
                () -> relationships.retarget(fixture.store(), source, follows, otherTarget, target));
            assertForwardAndReverseLink(follows, source, target, replacement);

            relationships.addTarget(fixture.store(), source, watches, target, initial);
            relationships.addTarget(fixture.store(), source, watches, otherTarget, replacement);
            assertThrows(IllegalStateException.class,
                () -> relationships.retarget(fixture.store(), source, watches, target, otherTarget));
            assertSame(initial, relationships.getData(source, watches, target));
            assertSame(replacement, relationships.getData(source, watches, otherTarget));

            assertThrows(IllegalStateException.class, () -> relationships.removeTarget(fixture.store(), source, follows, otherTarget));
            assertForwardAndReverseLink(follows, source, target, replacement);
            relationships.tryRemoveTarget(fixture.store(), source, follows, otherTarget);
            assertForwardAndReverseLink(follows, source, target, replacement);

            relationships.removeTarget(fixture.store(), source, follows, target);
            assertNull(relationships.getFirstTarget(source, follows));
            assertEquals(0, relationships.getIncomingCount(target, follows));
            relationships.tryRemoveTarget(fixture.store(), source, follows, target);
            relationships.tryRemoveTarget(fixture.store(), source, watches, target);
            assertEquals(1, relationships.getTargetCount(source, watches));
            assertSame(replacement, relationships.getData(source, watches, otherTarget));
        }
    }

    @Test
    void addExposesOneForwardAndReverseLinkThroughTheStore() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = types.registerRelationship(FollowData.class, RelationshipTraits.defaults().exclusive());
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            var data = new FollowData("near");

            relationships.addTarget(fixture.store(), source, follows, target, data);

            assertSame(target, relationships.getFirstTarget(source, follows));
            assertSame(data, relationships.getData(source, follows, target));
            var incoming = new ArrayList<>();
            relationships.forEachIncomingSource(target, follows, incoming::add);
            assertEquals(1, relationships.getIncomingCount(target, follows));
            assertEquals(List.of(source), incoming);

            relationships.removeTarget(fixture.store(), source, follows, target);

            // a trim run on the Store reports whether it released the storage
            assertTrue(relationships.trimIncomingLinks(fixture.store(), target, follows));
            assertFalse(relationships.trimIncomingLinks(fixture.store(), target, follows));
        }
    }

    @Test
    void mutationSequenceKeepsForwardAndReverseViewsConsistent() {
        try (var fixture = new StoreFixture()) {
            var follows = registerFollows(fixture);
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            var otherTarget = fixture.addEntity(new Position(5, 6), null);
            var initial = new FollowData("near");
            var carried = new FollowData("far");

            relationships.addTarget(fixture.store(), source, follows, target, initial);
            assertThrows(IllegalStateException.class, () -> relationships.addTarget(fixture.store(), source, follows, target, carried));
            assertSame(initial, relationships.getData(source, follows, target));

            assertThrows(
                IllegalStateException.class,
                () -> relationships.addTarget(fixture.store(), source, follows, otherTarget, carried)
            );
            assertForwardAndReverseLink(follows, source, target, initial);
            assertEquals(0, relationships.getIncomingCount(otherTarget, follows));

            relationships.putTarget(fixture.store(), source, follows, target, carried);
            assertForwardAndReverseLink(follows, source, target, carried);
            relationships.putTarget(fixture.store(), source, follows, otherTarget, initial);
            assertForwardAndReverseLink(follows, source, otherTarget, initial);
            assertEquals(0, relationships.getIncomingCount(target, follows));

            relationships.tryRemoveTarget(fixture.store(), source, follows, target);
            relationships.retarget(fixture.store(), source, follows, otherTarget, target);
            assertForwardAndReverseLink(follows, source, target, initial);
            relationships.retarget(fixture.store(), source, follows, target, otherTarget);
            assertForwardAndReverseLink(follows, source, otherTarget, initial);
            assertEquals(0, relationships.getIncomingCount(target, follows));
            relationships.retarget(fixture.store(), source, follows, otherTarget, otherTarget);
            assertForwardAndReverseLink(follows, source, otherTarget, initial);

            relationships.removeTarget(fixture.store(), source, follows, otherTarget);
            assertNull(relationships.getFirstTarget(source, follows));
            assertNull(relationships.getData(source, follows, otherTarget));
            assertEquals(0, relationships.getIncomingCount(otherTarget, follows));
            relationships.tryRemoveTarget(fixture.store(), source, follows, otherTarget);

            relationships.putTarget(fixture.store(), source, follows, target, initial);
            assertForwardAndReverseLink(follows, source, target, initial);
        }
    }

    @Test
    void incomingHeadUsesDenseSourcesAndRemainsUntilExplicitTrim() {
        try (var fixture = new StoreFixture()) {
            var follows = registerFollows(fixture);
            var target = fixture.addEntity(new Position(7, 8), null);
            var first = fixture.addEntity(new Position(1, 1), null);
            var middle = fixture.addEntity(new Position(2, 2), null);
            var last = fixture.addEntity(new Position(3, 3), null);

            relationships.addTarget(fixture.store(), first, follows, target, new FollowData("first"));
            relationships.addTarget(fixture.store(), middle, follows, target, new FollowData("middle"));
            relationships.addTarget(fixture.store(), last, follows, target, new FollowData("last"));
            assertFalse(relationships.trimIncomingLinks(fixture.store(), target, follows));

            relationships.removeTarget(fixture.store(), middle, follows, target);
            var remaining = new ArrayList<>();
            relationships.forEachIncomingSource(target, follows, remaining::add);
            assertEquals(Set.of(first, last), new HashSet<>(remaining));

            relationships.removeTarget(fixture.store(), first, follows, target);
            relationships.removeTarget(fixture.store(), last, follows, target);
            assertEquals(0, relationships.getIncomingCount(target, follows));
            assertTrue(relationships.trimIncomingLinks(fixture.store(), target, follows));
            assertFalse(relationships.trimIncomingLinks(fixture.store(), target, follows));
        }
    }

    @Test
    void aRejectedAddLeavesBothDirectionsUnchanged() {
        var registry = new ComponentRegistry<Object>();
        try {
            var follows = registerFollows(registry);
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var target = Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
            var existingSource = Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
            var rejectedSource = Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
            relationships.addTarget(store, existingSource, follows, target, new FollowData("existing"));

            assertThrows(
                IllegalStateException.class,
                () -> store.forEachChunk((chunk, commandBuffer) -> {
                    relationships.addTarget(store, rejectedSource, follows, target, new FollowData("rejected"));
                })
            );

            assertSame(target, relationships.getFirstTarget(existingSource, follows));
            assertNull(relationships.getFirstTarget(rejectedSource, follows));
            assertEquals(1, relationships.getIncomingCount(target, follows));
            var incoming = new ArrayList<Ref<Object>>();
            relationships.forEachIncomingSource(target, follows, incoming::add);
            assertEquals(List.of(existingSource), incoming);
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void aRejectedRemoveLeavesBothDirectionsUnchanged() {
        var registry = new ComponentRegistry<Object>();
        try {
            var follows = registerFollows(registry);
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var target = Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
            var source = Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
            var data = new FollowData("existing");
            relationships.addTarget(store, source, follows, target, data);

            assertThrows(
                IllegalStateException.class,
                () -> store.forEachChunk((chunk, commandBuffer) -> {
                    relationships.removeTarget(store, source, follows, target);
                })
            );

            assertForwardAndReverseLink(follows, source, target, data);
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void aFailedStorageStepPropagatesAndLeavesTrackerAndPersistenceOnTheLastCommand() {
        try (var fixture = new LinkFixture()) {
            int recorded = fixture.synchronizations[0];
            fixture.failingStorage.armed = true;

            assertSame(fixture.storageFailure, assertThrows(IllegalStateException.class,
                () -> relationships.putTarget(fixture.store, fixture.source, fixture.follows, fixture.target, "second")));

            // a failed command is not rolled back
            assertEquals("second", relationships.getData(fixture.source, fixture.follows, fixture.target));
            assertTrue(fixture.tracker.contains(fixture.follows, fixture.sourceId, fixture.targetId));
            assertTrue(fixture.tracker.isResolved(fixture.follows, fixture.sourceId, fixture.targetId));
            assertEquals(recorded, fixture.synchronizations[0]);
            assertEquals("first", fixture.savedLinkData());
        }
    }

    @Test
    void aFailedObserverPropagatesAndKeepsWhatItsOwnCommandAlreadyRecorded() {
        try (var fixture = new LinkFixture()) {
            int recorded = fixture.synchronizations[0];
            fixture.failingObserver.armed = true;

            assertSame(fixture.observerFailure, assertThrows(IllegalStateException.class,
                () -> relationships.putTarget(fixture.store, fixture.source, fixture.follows, fixture.target, "second")));

            // the announcement is the last step of a command
            assertEquals("second", relationships.getData(fixture.source, fixture.follows, fixture.target));
            assertTrue(fixture.tracker.contains(fixture.follows, fixture.sourceId, fixture.targetId));
            assertTrue(fixture.tracker.isResolved(fixture.follows, fixture.sourceId, fixture.targetId));
            assertEquals(recorded + 1, fixture.synchronizations[0]);
            assertEquals("second", fixture.savedLinkData());
            // the failed command still released processing
            relationships.removeTarget(fixture.store, fixture.source, fixture.follows, fixture.target);
        }
    }

    @Test
    void seededMutationsAgreeWithAnIndependentSingleTargetModel() {
        try (var fixture = new StoreFixture()) {
            var follows = registerFollows(fixture);
            var refs = new ArrayList<Ref<Object>>();
            for (int i = 0; i < 32; i++) {
                refs.add(fixture.addEntity(new Position(i, -i), null));
            }
            var expected = new IdentityHashMap<Ref<Object>, ExpectedLink>();
            var random = new Random(48291);

            for (int step = 0; step < 1_000; step++) {
                var source = refs.get(random.nextInt(refs.size()));
                var target = refs.get(random.nextInt(refs.size()));
                var otherTarget = refs.get(random.nextInt(refs.size()));
                var data = new FollowData("step-" + step);
                var current = expected.get(source);
                switch (random.nextInt(4)) {
                    case 0 -> {
                        if (current == null) {
                            relationships.addTarget(fixture.store(), source, follows, target, data);
                            expected.put(source, new ExpectedLink(target, data));
                        } else {
                            assertThrows(
                                IllegalStateException.class,
                                () -> relationships.addTarget(fixture.store(), source, follows, target, data)
                            );
                        }
                    }
                    case 1 -> {
                        relationships.putTarget(fixture.store(), source, follows, target, data);
                        expected.put(source, new ExpectedLink(target, data));
                    }
                    case 2 -> {
                        if (current != null && current.target == target) {
                            relationships.removeTarget(fixture.store(), source, follows, target);
                            expected.remove(source);
                        } else {
                            assertThrows(
                                IllegalStateException.class,
                                () -> relationships.removeTarget(fixture.store(), source, follows, target)
                            );
                            relationships.tryRemoveTarget(fixture.store(), source, follows, target);
                        }
                    }
                    case 3 -> {
                        if (target == otherTarget) {
                            relationships.retarget(fixture.store(), source, follows, target, otherTarget);
                        } else if (current == null || current.target != target) {
                            assertThrows(
                                IllegalStateException.class,
                                () -> relationships.retarget(fixture.store(), source, follows, target, otherTarget)
                            );
                        } else {
                            relationships.retarget(fixture.store(), source, follows, target, otherTarget);
                            expected.put(source, new ExpectedLink(otherTarget, current.data));
                        }
                    }
                    default -> throw new AssertionError("Unexpected operation");
                }
                assertModel(follows, refs, expected);
            }
        }
    }

    private static GenericRelationshipType<Object, Object, FollowData> registerFollows(StoreFixture fixture) {
        return registerFollows(fixture.registry());
    }

    private static GenericRelationshipType<Object, Object, FollowData> registerFollows(ComponentRegistry<Object> registry) {
        return new RelationshipTypeRegistry<>(registry).registerRelationship(
            FollowData.class,
            RelationshipTraits.defaults().exclusive());
    }

    private static void assertForwardAndReverseLink(
        GenericRelationshipType<Object, Object, FollowData> type,
        Ref<Object> source,
        Ref<Object> target,
        FollowData data
    ) {
        assertSame(target, relationships.getFirstTarget(source, type));
        assertSame(data, relationships.getData(source, type, target));
        var incoming = new ArrayList<>();
        relationships.forEachIncomingSource(target, type, incoming::add);
        assertEquals(List.of(source), incoming);
    }

    private static void assertModel(
        GenericRelationshipType<Object, Object, FollowData> type,
        List<Ref<Object>> refs,
        IdentityHashMap<Ref<Object>, ExpectedLink> expected
    ) {
        for (var source : refs) {
            var link = expected.get(source);
            assertSame(link == null ? null : link.target, relationships.getFirstTarget(source, type));
            if (link != null) {
                assertSame(link.data, relationships.getData(source, type, link.target));
            }
        }
        for (var target : refs) {
            var expectedSources = new HashSet<Ref<Object>>();
            expected.forEach((source, link) -> {
                if (link.target == target) {
                    expectedSources.add(source);
                }
            });
            var actualSources = new HashSet<Ref<Object>>();
            relationships.forEachIncomingSource(target, type, actualSources::add);
            assertEquals(expectedSources.size(), relationships.getIncomingCount(target, type));
            assertEquals(expectedSources, actualSources);
        }
    }

    @Test
    void linkDataOfAPairWithoutALinkIsNull() {
        var registry = new ComponentRegistry<Object>();
        try {
            var types = new RelationshipTypeRegistry<>(registry);
            var mounted = types.registerRelationship(Seat.class, RelationshipTraits.defaults().exclusive());
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var rider = spawned(store);
            var mount = spawned(store);
            var walker = spawned(store);
            var seat = new Seat();

            relationships.addTarget(store, rider, mounted, mount, seat);

            assertNull(relationships.getData(rider, mounted, walker));
            assertNull(relationships.getData(walker, mounted, mount));
        } finally {
            registry.shutdown();
        }
    }

    private static Ref<Object> spawned(Store<Object> store) {
        return Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
    }

    private static Ref<Object> identified(
        ComponentRegistry<Object> registry,
        Store<Object> store,
        RelationshipTracker<Object, UUID> tracker,
        ComponentType<Object, Identity> identityType,
        UUID id
    ) {
        var holder = registry.newHolder();
        holder.putComponent(identityType, new Identity(id));
        var ref = Objects.requireNonNull(store.addEntity(holder, AddReason.LOAD));
        tracker.onEntityLoaded(id, ref);
        return ref;
    }

    /// A persistent link type that already holds one link, whose data is "first".
    private static final class LinkFixture implements AutoCloseable {
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final int[] synchronizations = new int[1];
        private final RuntimeException storageFailure = new IllegalStateException("deliberate storage failure");
        private final RuntimeException observerFailure = new IllegalStateException("deliberate observer failure");
        private final RelationshipTracker<Object, UUID> tracker;
        private final RelationshipPersistence<Object> persistence;
        private final RelationshipType<Object, String> follows;
        private final Store<Object> store;
        private final UUID sourceId = UUID.randomUUID();
        private final UUID targetId = UUID.randomUUID();
        private final Ref<Object> source;
        private final Ref<Object> target;
        private final FailOnSourceChange failingStorage;
        private final FailOnSet failingObserver;

        private LinkFixture() {
            var identityType = registry.registerComponent(Identity.class, "RelwindTestIdentity", Identity.CODEC);
            var installation = RelationshipInstallation
                .on(registry, ref -> ref.getStore().getComponent(ref, identityType).id, Codec.UUID_BINARY)
                .persistence((ignoredStore, ignoredRef) -> synchronizations[0]++, ignored -> { }, ignored -> false)
                .install();
            tracker = installation.tracker();
            persistence = installation.persistence();
            follows = installation.types().registerRelationship(
                "relwind:test/follows",
                String.class,
                Codec.STRING,
                RelationshipTraits.defaults().exclusive());
            failingStorage = new FailOnSourceChange(follows, storageFailure);
            failingObserver = new FailOnSet(follows, observerFailure);
            registry.registerSystem(failingStorage);
            registry.registerSystem(failingObserver);
            store = registry.addStore(new Object(), EmptyResourceStorage.get());
            source = identified(registry, store, tracker, identityType, sourceId);
            target = identified(registry, store, tracker, identityType, targetId);
            relationships.addTarget(store, source, follows, target, "first");
        }

        private Object savedLinkData() {
            var metadata = store.getComponent(source, persistence.getComponentType());
            assertNotNull(metadata);
            for (var link : metadata.readLinks(follows)) {
                if (targetId.equals(link.target())) return link.data();
            }
            return null;
        }

        @Override
        public void close() {
            tracker.close();
            registry.shutdown();
        }
    }

    /// Fails the announcement that a command replaced the link data.
    private static final class FailOnSet extends RelationshipChangeSystem<Object, String> {
        private final RuntimeException failure;
        private boolean armed;

        private FailOnSet(RelationshipType<Object, String> type, RuntimeException failure) {
            super(type);
            this.failure = failure;
        }

        @Override
        protected void onRelationshipSet(
            LinkedEntity<Object> source,
            LinkedEntity<Object> target,
            String oldData,
            String data,
            Store<Object> store,
            CommandBuffer<Object> buffer
        ) {
            if (armed) throw failure;
        }
    }

    /// Fails the storage step of a command.
    private static final class FailOnSourceChange extends RefChangeSystem<Object, OutgoingLink<Object, Object>> {
        private final GenericRelationshipType<Object, Object, String> type;
        private final RuntimeException failure;
        private boolean armed;

        private FailOnSourceChange(GenericRelationshipType<Object, Object, String> type, RuntimeException failure) {
            this.type = type;
            this.failure = failure;
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
            Ref<Object> ref,
            OutgoingLink<Object, Object> component,
            Store<Object> store,
            CommandBuffer<Object> commands
        ) { }

        @Override
        public void onComponentSet(
            Ref<Object> ref,
            OutgoingLink<Object, Object> oldComponent,
            OutgoingLink<Object, Object> component,
            Store<Object> store,
            CommandBuffer<Object> commands
        ) {
            if (armed) throw failure;
        }

        @Override
        public void onComponentRemoved(
            Ref<Object> ref,
            OutgoingLink<Object, Object> component,
            Store<Object> store,
            CommandBuffer<Object> commands
        ) { }
    }

    private static final class Identity implements Component<Object> {
        private static final BuilderCodec<Identity> CODEC = BuilderCodec.builder(Identity.class, Identity::new)
            .append(new KeyedCodec<>("UUID", Codec.UUID_BINARY), (identity, id) -> identity.id = id, identity -> identity.id)
            .add()
            .build();

        private UUID id;

        private Identity() {
        }

        private Identity(UUID id) {
            this.id = id;
        }

        @Override
        public Component<Object> clone() {
            return new Identity(id);
        }
    }

    private record Seat() {
    }

    private record FollowData(String distance) {
    }

    private record ExpectedLink(Ref<Object> target, FollowData data) {
    }

    @Test
    void aPeerThatTheTargetIdentityCannotPlaceIsRejected() {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("overworld");
            var sources = entityTypes(fixture);
            var targets = new RelationshipTypeRegistry<>(fixture.blockRegistry());
            targets.installTracker(new TestPersistenceIdentity<>((store, ref) -> null, Codec.INTEGER,
                "CHUNK_POSITIONS", (store, id) -> false, peer -> {
                    throw new IllegalStateException("Unknown world");
                }), TestStoreRuntime.inline());

            var source = fixture.addEntity(world);
            var type = sources.registerRelationship(targets, RelationshipTraits.defaults().exclusive());
            var block = fixture.addBlock(world);

            var failure = assertThrows(IllegalArgumentException.class,
                () -> relationships.addTarget(world.entityStore(), source, type, block));

            assertTrue(failure.getMessage().contains("cannot place the source Store"), failure.getMessage());
        }
    }

    @Test
    void aBridgeCommandTakesOneSourceAccessorThatRunsItNowOrQueuesIt() {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("overworld");
            var entityTypes = entityTypes(fixture);
            var blockTypes = blockTypes(fixture);
            var anchoredTo = entityTypes.registerRelationship(
                blockTypes,
                Anchor.class,
                RelationshipTraits.defaults().exclusive());

            var source = fixture.addEntity(world);
            var block = fixture.addBlock(world);
            var buffer = fixture.entityCommandBuffer(world);
            var anchor = new Anchor("north");

            relationships.addTarget(world.entityStore(), source, anchoredTo, block, anchor);

            RelationshipTestFixtures.assertLink(anchoredTo, source, block, anchor);

            relationships.tryRemoveTarget(buffer, source, anchoredTo, block);
            assertEquals(1, relationships.getTargetCount(source, anchoredTo));
            BridgeStoreFixture.consume(buffer);

            assertEquals(0, relationships.getTargetCount(source, anchoredTo));
        }
    }

    @Test
    void bridgeCommandsFollowTheStrictAndLenientPreconditionTable() {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("overworld");
            var entityTypes = entityTypes(fixture);
            var blockTypes = blockTypes(fixture);
            var anchoredTo = entityTypes.registerRelationship(
                blockTypes,
                Anchor.class,
                RelationshipTraits.defaults().exclusive());

            var source = fixture.addEntity(world);
            var block = fixture.addBlock(world);
            var otherBlock = fixture.addBlock(world);
            var initial = new Anchor("north");
            var replacement = new Anchor("south");

            relationships.putTarget(world.entityStore(), source, anchoredTo, block, initial);
            RelationshipTestFixtures.assertLink(anchoredTo, source, block, initial);

            assertThrows(IllegalStateException.class, () -> relationships.addTarget(world.entityStore(), source, anchoredTo, block, replacement));
            RelationshipTestFixtures.assertLink(anchoredTo, source, block, initial);

            assertThrows(IllegalStateException.class, () -> relationships.addTarget(world.entityStore(), source, anchoredTo, otherBlock, replacement));
            assertEquals(0, relationships.getIncomingCount(otherBlock, anchoredTo));

            relationships.putTarget(world.entityStore(), source, anchoredTo, block, replacement);
            RelationshipTestFixtures.assertLink(anchoredTo, source, block, replacement);

            relationships.retarget(world.entityStore(), source, anchoredTo, block, otherBlock);
            RelationshipTestFixtures.assertLink(anchoredTo, source, otherBlock, replacement);
            assertEquals(0, relationships.getIncomingCount(block, anchoredTo));

            assertThrows(IllegalStateException.class, () -> relationships.retarget(world.entityStore(), source, anchoredTo, block, otherBlock));
            relationships.retarget(world.entityStore(), source, anchoredTo, otherBlock, otherBlock);
            RelationshipTestFixtures.assertLink(anchoredTo, source, otherBlock, replacement);

            assertThrows(IllegalStateException.class, () -> relationships.removeTarget(world.entityStore(), source, anchoredTo, block));
            relationships.tryRemoveTarget(world.entityStore(), source, anchoredTo, block);
            RelationshipTestFixtures.assertLink(anchoredTo, source, otherBlock, replacement);

            relationships.removeTarget(world.entityStore(), source, anchoredTo, otherBlock);
            assertEquals(0, relationships.getTargetCount(source, anchoredTo));
            assertEquals(0, relationships.getIncomingCount(otherBlock, anchoredTo));
            assertNull(relationships.getFirstTarget(source, anchoredTo));
            assertNull(relationships.getData(source, anchoredTo, otherBlock));
        }
    }

    @Test
    void incomingTraversalReadsTheSourcesOfATargetInTheOtherStore() {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("overworld");
            var entityTypes = entityTypes(fixture);
            var blockTypes = blockTypes(fixture);
            var anchoredTo = entityTypes.registerRelationship(blockTypes, RelationshipTraits.defaults());

            var first = fixture.addEntity(world);
            var second = fixture.addEntity(world);
            var block = fixture.addBlock(world);
            var otherBlock = fixture.addBlock(world);

            relationships.addTarget(world.entityStore(), first, anchoredTo, block);
            relationships.addTarget(world.entityStore(), second, anchoredTo, block);
            relationships.addTarget(world.entityStore(), first, anchoredTo, otherBlock);

            assertEquals(2, relationships.getIncomingCount(block, anchoredTo));
            var sources = new ArrayList<Ref<Entities>>();
            relationships.forEachIncomingSource(block, anchoredTo, sources::add);
            assertEquals(List.of(first, second), sources);

            var targets = new ArrayList<Ref<Blocks>>();
            relationships.forEachTarget(first, anchoredTo, targets::add);
            assertEquals(2, targets.size());
            assertTrue(targets.contains(block));
            assertTrue(targets.contains(otherBlock));

            relationships.removeTarget(world.entityStore(), second, anchoredTo, block);
            assertFalse(relationships.trimIncomingLinks(world.entityStore(), block, anchoredTo));
            relationships.removeTarget(world.entityStore(), first, anchoredTo, block);
            assertTrue(relationships.trimIncomingLinks(world.entityStore(), block, anchoredTo));
            relationships.removeTarget(world.entityStore(), first, anchoredTo, otherBlock);
            assertFalse(relationships.trimSourceStorage(world.entityStore(), first, anchoredTo));
        }
    }

    @Test
    void bufferedBridgeCommandsApplyOnTheSourceBufferAndSkipInvalidLinkedEntities() {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("overworld");
            var entityTypes = entityTypes(fixture);
            var blockTypes = blockTypes(fixture);
            var anchoredTo = entityTypes.registerRelationship(
                blockTypes,
                Anchor.class,
                RelationshipTraits.defaults());

            var source = fixture.addEntity(world);
            var unloading = fixture.addEntity(world);
            var block = fixture.addBlock(world);
            var buffer = fixture.entityCommandBuffer(world);
            var anchor = new Anchor("north");

            relationships.addTarget(buffer, source, anchoredTo, block, anchor);
            relationships.addTarget(buffer, unloading, anchoredTo, block, anchor);
            assertEquals(0, relationships.getTargetCount(source, anchoredTo));

            world.entityStore().removeEntity(unloading, RemoveReason.REMOVE);
            BridgeStoreFixture.consume(buffer);

            RelationshipTestFixtures.assertLink(anchoredTo, source, block, anchor);
            assertEquals(1, relationships.getIncomingCount(block, anchoredTo));

            relationships.tryRemoveTarget(buffer, source, anchoredTo, block);
            BridgeStoreFixture.consume(buffer);
            assertEquals(0, relationships.getTargetCount(source, anchoredTo));

            var otherWorld = fixture.addWorld("nether");
            var otherBuffer = fixture.entityCommandBuffer(otherWorld);
            assertThrows(IllegalArgumentException.class,
                () -> relationships.addTarget(otherBuffer, source, anchoredTo, block, anchor));
            otherBuffer.validateEmpty();

            // a buffered retarget checks the store of its new target when it is submitted
            var foreignBlock = fixture.addBlock(otherWorld);
            assertThrows(IllegalArgumentException.class,
                () -> relationships.retarget(buffer, source, anchoredTo, block, foreignBlock));
            buffer.validateEmpty();
        }
    }

    @Test
    void aBridgeCommandRejectsStoresOfDifferentWorlds() {
        try (var fixture = new BridgeStoreFixture()) {
            var overworld = fixture.addWorld("overworld");
            var nether = fixture.addWorld("nether");
            var sources = entityTypes(fixture);
            var targets = blockTypes(fixture);
            var type = sources.registerRelationship(targets, RelationshipTraits.defaults().exclusive());
            var source = fixture.addEntity(overworld);

            var failure = assertThrows(IllegalArgumentException.class,
                () -> relationships.addTarget(overworld.entityStore(), source, type, fixture.addBlock(nether)));

            assertTrue(failure.getMessage().contains("must live in one world"), failure.getMessage());
            assertThrows(IllegalArgumentException.class,
                () -> relationships.addTarget(nether.entityStore(), source, type, fixture.addBlock(overworld)));
            relationships.addTarget(overworld.entityStore(), source, type, fixture.addBlock(overworld));
        }
    }

    @Test
    void aBridgeCommandIsRejectedWhileEitherModuleIsProcessing() {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("overworld");
            var entityTypes = entityTypes(fixture);
            var blockTypes = blockTypes(fixture);
            var anchoredTo = entityTypes.registerRelationship(blockTypes, RelationshipTraits.defaults());

            var source = fixture.addEntity(world);
            var otherSource = fixture.addEntity(world);
            var block = fixture.addBlock(world);
            var otherBlock = fixture.addBlock(world);
            relationships.addTarget(world.entityStore(), source, anchoredTo, block);

            // the source module is processing while its outgoing links are traversed
            var duringTargets = new ArrayList<RuntimeException>();
            relationships.forEachTarget(source, anchoredTo, target -> duringTargets.add(
                assertThrows(IllegalStateException.class,
                    () -> relationships.addTarget(world.entityStore(), otherSource, anchoredTo, otherBlock))));
            assertEquals(1, duringTargets.size());

            // the target module is processing while its incoming links are traversed
            var duringIncoming = new ArrayList<RuntimeException>();
            relationships.forEachIncomingSource(block, anchoredTo, ignored -> duringIncoming.add(
                assertThrows(IllegalStateException.class,
                    () -> relationships.addTarget(world.entityStore(), otherSource, anchoredTo, otherBlock))));
            assertEquals(1, duringIncoming.size());

            // both modules are free again afterwards
            relationships.addTarget(world.entityStore(), otherSource, anchoredTo, otherBlock);
            assertEquals(1, relationships.getTargetCount(otherSource, anchoredTo));
        }
    }

    @Test
    void aBridgeCommandHoldsTheSourceModuleAndTheTargetModuleWhileItWrites() {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("overworld");
            var entityTypes = entityTypes(fixture);
            var blockTypes = blockTypes(fixture);
            var anchoredTo = entityTypes.registerRelationship(blockTypes, RelationshipTraits.defaults());
            var follows = entityTypes.registerRelationship(RelationshipTraits.defaults());
            var touches = blockTypes.registerRelationship(RelationshipTraits.defaults());

            var source = fixture.addEntity(world);
            var otherSource = fixture.addEntity(world);
            var block = fixture.addBlock(world);
            var otherBlock = fixture.addBlock(world);
            var observer = new IncomingWrites(anchoredTo.getIncomingType(), anchoredTo.getSourceType(), source,
                world.entityStore(), follows, otherSource, source,
                world.blockStore(), touches, otherBlock, block);
            fixture.blockRegistry().registerSystem(observer);

            relationships.addTarget(world.entityStore(), source, anchoredTo, block);

            assertEquals(1, observer.sourceModuleRejections.size());
            assertTrue(observer.sourceModuleRejections.get(0).contains("Relationships are currently processing"),
                observer.sourceModuleRejections.get(0));
            assertEquals(1, observer.targetModuleRejections.size());
            assertTrue(observer.targetModuleRejections.get(0).contains("Relationships are currently processing"),
                observer.targetModuleRejections.get(0));
            // the source module is entered before the command writes either Store
            assertEquals(List.of(Boolean.TRUE), observer.sourceStoreUnwritten);

            // both modules are released once the command returns
            relationships.addTarget(world.entityStore(), otherSource, follows, source);
            relationships.addTarget(world.blockStore(), otherBlock, touches, block);
            assertEquals(1, relationships.getTargetCount(otherSource, follows));
        }
    }
}
