/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;


import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Player;
import com.hypixel.hytale.component.StoreFixture.Position;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import javax.annotation.Nonnull;

/// The Store tick calls the plugin once per matching link and lends it one result that stays
/// stable while a nested tick of another Store runs.
class RelationshipTickingSystemTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void tickingDefinitionUsesReachableWithoutEnumeratingThePath() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = types.registerRelationship(RelationshipRules.single());
            var delivered = new ArrayList<Ref<Object>>();
            var query = RelationshipQuery.of(RelationshipQuery.reachable(type,
                RelationshipQuery.Direction.INCOMING, 2, fixture.playerType()), type);
            fixture.registry().registerSystem(new RelationshipTickingSystem<Object, Void>() {
                @Nonnull
                @Override
                public RelationshipQuery.Definition<Object, Void> getQuery() {
                    return query;
                }

                @Override
                protected void tickRelationship(
                    float seconds,
                    RelationshipResult<Object, Void> result,
                    Store<Object> store,
                    CommandBuffer<Object> commandBuffer
                ) {
                    delivered.add(result.getSource());
                }
            });
            var start = fixture.addEntity(new Position(0, 0), new Player("start"));
            var middle = fixture.addEntity(new Position(1, 0), null);
            var end = fixture.addEntity(new Position(2, 0), null);
            var last = fixture.addEntity(new Position(3, 0), null);
            relationships.addTarget(fixture.store(), start, type, middle);
            relationships.addTarget(fixture.store(), middle, type, end);
            relationships.addTarget(fixture.store(), end, type, last);

            fixture.tick(0.05f);

            assertEquals(java.util.List.of(middle, end), delivered);

            delivered.clear();
            relationships.removeTarget(fixture.store(), start, type, middle);
            fixture.tick(0.05f);

            assertTrue(delivered.isEmpty());
        }
    }

    @Test
    void storeTickCallsThePluginOncePerMatchingLinkWithOneReusableResult() {
        try (var fixture = new StoreFixture()) {
            var follows = new RelationshipTypeRegistry<>(fixture.registry()).registerRelationship(
                FollowData.class,
                RelationshipRules.single());
            var system = new RecordingRelationshipSystem(
                Archetype.of(fixture.positionType(), fixture.playerType()),
                follows
            );
            fixture.registry().registerSystem(system);

            var firstTarget = fixture.addEntity(new Position(10, 20), null);
            var secondTarget = fixture.addEntity(new Position(30, 40), null);
            var firstSource = fixture.addEntity(new Position(1, 2), new Player("first"));
            var secondSource = fixture.addEntity(new Position(3, 4), new Player("second"));
            var missingSourceQuery = fixture.addEntity(new Position(5, 6), null);
            fixture.addEntity(new Position(7, 8), new Player("unlinked"));
            var firstData = new FollowData("near");
            var secondData = new FollowData("far");
            relationships.addTarget(fixture.store(), firstSource, follows, firstTarget, firstData);
            relationships.addTarget(fixture.store(), secondSource, follows, secondTarget, secondData);
            relationships.addTarget(fixture.store(), missingSourceQuery, follows, firstTarget, new FollowData("filtered"));

            fixture.tick(0.05f);
            fixture.tick(0.10f);

            assertEquals(4, system.callbacks.size());
            assertEquals(1, system.resultIdentities.size());
            assertEquals(
                java.util.List.of(
                    new Callback(firstSource, firstTarget, firstData, 0.05f),
                    new Callback(secondSource, secondTarget, secondData, 0.05f),
                    new Callback(firstSource, firstTarget, firstData, 0.10f),
                    new Callback(secondSource, secondTarget, secondData, 0.10f)
                ),
                system.callbacks
            );
            assertFalse(system.isParallel(1, 0));
            assertSame(fixture.callingThread(), fixture.lastTickThread());
            assertSame(fixture.store(), system.lastStore);
            assertSame(fixture.store(), system.lastCommandBuffer.getStore());
            assertEquals(6, fixture.store().getEntityCount());
        }
    }

    @Test
    void oneRegisteredSystemUsesTheStoreSuppliedByEachTick() {
        var registry = new ComponentRegistry<Object>();
        try {
            var positionType = registry.registerComponent(Position.class, Position::new);
            var follows = new RelationshipTypeRegistry<>(registry).registerRelationship(
                FollowData.class,
                RelationshipRules.single());
            var system = new RecordingRelationshipSystem(Archetype.of(positionType), follows);
            registry.registerSystem(system);
            var firstStore = registry.addStore(new Object(), EmptyResourceStorage.get());
            var secondStore = registry.addStore(new Object(), EmptyResourceStorage.get());

            addLink(firstStore, positionType, follows, "first");
            addLink(secondStore, positionType, follows, "second");
            firstStore.tick(0.05f);
            secondStore.tick(0.05f);

            assertEquals(1, system.stores.get(firstStore));
            assertEquals(1, system.stores.get(secondStore));
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void overlappingStoreTicksKeepEachCallbackResultStable() throws InterruptedException {
        var registry = new ComponentRegistry<Object>();
        var positionType = registry.registerComponent(Position.class, Position::new);
        var follows = new RelationshipTypeRegistry<>(registry).registerRelationship(
            FollowData.class,
            RelationshipRules.single());
        var system = new OverlappingRelationshipSystem(Archetype.of(positionType), follows);
        registry.registerSystem(system);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();

        var first = newStoreThread("relwind-store-1", registry, positionType, follows, system, ready, start, failure);
        var second = newStoreThread("relwind-store-2", registry, positionType, follows, system, ready, start, failure);
        first.start();
        second.start();
        assertTrue(ready.await(5, TimeUnit.SECONDS), "Stores did not become ready");
        start.countDown();
        first.join(5_000);
        second.join(5_000);

        try {
            assertFalse(first.isAlive(), "First Store tick did not finish");
            assertFalse(second.isAlive(), "Second Store tick did not finish");
            if (failure.get() != null) {
                fail("An overlapping Store tick failed", failure.get());
            }
            assertEquals(2, system.deliveredStores.size());
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void nestedStoreTickKeepsTheOuterCallbackResultStable() {
        var registry = new ComponentRegistry<Object>();
        try {
            var positionType = registry.registerComponent(Position.class, Position::new);
            var follows = new RelationshipTypeRegistry<>(registry).registerRelationship(
                FollowData.class,
                RelationshipRules.single());
            var system = new NestedRelationshipSystem(Archetype.of(positionType), follows);
            registry.registerSystem(system);
            var outerStore = registry.addStore(new Object(), EmptyResourceStorage.get());
            var nestedStore = registry.addStore(new Object(), EmptyResourceStorage.get());
            var outerTarget = Objects.requireNonNull(outerStore.addEntity(Archetype.empty(), AddReason.SPAWN));
            var outerSource = Objects.requireNonNull(
                outerStore.addEntity(Archetype.of(positionType), AddReason.SPAWN)
            );
            var nestedTarget = Objects.requireNonNull(nestedStore.addEntity(Archetype.empty(), AddReason.SPAWN));
            var nestedSource = Objects.requireNonNull(
                nestedStore.addEntity(Archetype.of(positionType), AddReason.SPAWN)
            );
            var outerData = new FollowData("outer");
            relationships.addTarget(outerStore, outerSource, follows, outerTarget, outerData);
            relationships.addTarget(nestedStore, nestedSource, follows, nestedTarget, new FollowData("nested"));
            system.outerStore = outerStore;
            system.nestedStore = nestedStore;

            outerStore.tick(0.05f);

            assertEquals(java.util.List.of(outerStore, nestedStore), system.deliveredStores);
            assertSame(outerSource, system.outerSourceAfterNestedTick);
            assertSame(outerTarget, system.outerTargetAfterNestedTick);
            assertSame(outerData, system.outerDataAfterNestedTick);
        } finally {
            registry.shutdown();
        }
    }

    private static Thread newStoreThread(
        String name,
        ComponentRegistry<Object> registry,
        com.hypixel.hytale.component.ComponentType<Object, Position> positionType,
        GenericRelationshipType<Object, Object, FollowData> type,
        OverlappingRelationshipSystem system,
        CountDownLatch ready,
        CountDownLatch start,
        AtomicReference<Throwable> failure
    ) {
        return new Thread(() -> {
            try {
                var store = registry.addStore(new Object(), EmptyResourceStorage.get());
                var target = Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
                var source = Objects.requireNonNull(store.addEntity(Archetype.of(positionType), AddReason.SPAWN));
                relationships.addTarget(store, source, type, target, new FollowData(name));
                system.expectedSources.put(store, source);
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Store ticks were not released");
                }
                store.tick(0.05f);
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, name);
    }

    private static void addLink(
        Store<Object> store,
        com.hypixel.hytale.component.ComponentType<Object, Position> positionType,
        GenericRelationshipType<Object, Object, FollowData> type,
        String data
    ) {
        var target = Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
        var source = Objects.requireNonNull(store.addEntity(Archetype.of(positionType), AddReason.SPAWN));
        relationships.addTarget(store, source, type, target, new FollowData(data));
    }

    private static final class RecordingRelationshipSystem extends RelationshipTickingSystem<Object, FollowData> {
        private final RelationshipQuery.Definition<Object, FollowData> query;
        private final ArrayList<Callback> callbacks = new ArrayList<>();
        private final IdentityHashMap<RelationshipResult<Object, FollowData>, Boolean> resultIdentities =
            new IdentityHashMap<>();
        private final IdentityHashMap<Store<Object>, Integer> stores = new IdentityHashMap<>();
        private Store<Object> lastStore;
        private CommandBuffer<Object> lastCommandBuffer;

        private RecordingRelationshipSystem(
            Archetype<Object> sourceQuery,
            GenericRelationshipType<Object, Object, FollowData> type
        ) {
            this.query = RelationshipQuery.of(sourceQuery, type);
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, FollowData> getQuery() {
            return query;
        }

        @Override
        protected void tickRelationship(
            float seconds,
            RelationshipResult<Object, FollowData> result,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            resultIdentities.put(result, Boolean.TRUE);
            callbacks.add(new Callback(result.getSource(), result.getTarget(), result.getData(), seconds));
            lastStore = store;
            lastCommandBuffer = commandBuffer;
            stores.merge(store, 1, Integer::sum);
        }
    }

    private static final class OverlappingRelationshipSystem extends RelationshipTickingSystem<Object, FollowData> {
        private final RelationshipQuery.Definition<Object, FollowData> query;
        private final ConcurrentHashMap<Store<Object>, Ref<Object>> expectedSources = new ConcurrentHashMap<>();
        private final CountDownLatch callbacks = new CountDownLatch(2);
        private final java.util.concurrent.ConcurrentLinkedQueue<Store<Object>> deliveredStores =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

        private OverlappingRelationshipSystem(
            Archetype<Object> sourceQuery,
            GenericRelationshipType<Object, Object, FollowData> type
        ) {
            this.query = RelationshipQuery.of(sourceQuery, type);
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, FollowData> getQuery() {
            return query;
        }

        @Override
        protected void tickRelationship(
            float seconds,
            RelationshipResult<Object, FollowData> result,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            var expectedSource = Objects.requireNonNull(expectedSources.get(store));
            callbacks.countDown();
            try {
                if (!callbacks.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Relationship callbacks did not overlap");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Relationship callback was interrupted", exception);
            }
            assertSame(expectedSource, result.getSource());
            deliveredStores.add(store);
        }
    }

    private static final class NestedRelationshipSystem extends RelationshipTickingSystem<Object, FollowData> {
        private final RelationshipQuery.Definition<Object, FollowData> query;
        private Store<Object> outerStore;
        private Store<Object> nestedStore;
        private Ref<Object> outerSourceAfterNestedTick;
        private Ref<Object> outerTargetAfterNestedTick;
        private FollowData outerDataAfterNestedTick;
        private final java.util.List<Store<Object>> deliveredStores = new java.util.ArrayList<>();

        private NestedRelationshipSystem(
            Archetype<Object> sourceQuery,
            GenericRelationshipType<Object, Object, FollowData> type
        ) {
            this.query = RelationshipQuery.of(sourceQuery, type);
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, FollowData> getQuery() {
            return query;
        }

        @Override
        protected void tickRelationship(
            float seconds,
            RelationshipResult<Object, FollowData> result,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            deliveredStores.add(store);
            if (store != outerStore) {
                return;
            }
            Objects.requireNonNull(nestedStore, "nestedStore").tick(seconds);
            outerSourceAfterNestedTick = result.getSource();
            outerTargetAfterNestedTick = result.getTarget();
            outerDataAfterNestedTick = result.getData();
        }
    }

    private record FollowData(String distance) {
    }

    private record Callback(Ref<Object> source, Ref<Object> target, FollowData data, float seconds) {
    }
}
