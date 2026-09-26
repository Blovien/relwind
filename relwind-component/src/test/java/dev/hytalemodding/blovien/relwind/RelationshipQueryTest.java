/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;


import com.hypixel.hytale.component.BridgeStoreFixture;
import com.hypixel.hytale.component.BridgeStoreFixture.Blocks;

import java.util.List;

import static dev.hytalemodding.blovien.relwind.RelationshipTestFixtures.*;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.NonTicking;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Player;
import com.hypixel.hytale.component.StoreFixture.Position;
import com.hypixel.hytale.component.StoreFixture.Weapon;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentType;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Collections;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/// A query follows links directly and recursively, reports each binding once, and keeps an
/// linked entity it cannot read unknown under negation and composition.
class RelationshipQueryTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void fetchReleasesBindingsFromSuccessiveQueryDefinitions() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = types.registerRelationship(RelationshipTraits.defaults());
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            relationships.addTarget(fixture.store(), source, type, target);

            var bindings = fetchWithSuccessiveQueryDefinitions(source, target, type);

            assertReleased(bindings, "idle fetch batches");
        }
    }

    /// Only the weak references leave this frame, and nothing but the idle batches can still hold
    /// the query bindings.
    private static List<WeakReference<RelationshipQuery.Binding<Object, Void>>> fetchWithSuccessiveQueryDefinitions(
        Ref<Object> source,
        Ref<Object> target,
        GenericRelationshipType<Object, Object, Void> type
    ) {
        var bindings = new ArrayList<WeakReference<RelationshipQuery.Binding<Object, Void>>>();
        for (int i = 0; i < 100; i++) {
            var query = RelationshipQuery.of(type, Query.any());
            var binding = query.getBinding();
            bindings.add(new WeakReference<>(binding));
            var batch = relationships.fetch(source, query, results -> {
                assertEquals(1, results.size());
                assertSame(target, results.get(0).getTarget(binding));
                return results;
            });
            assertTrue(batch.isEmpty());
        }
        return bindings;
    }

    /// The collector clears a reference only once nothing holds the object. The wait is bounded,
    /// and bindings that are still held fail the test.
    private static void assertReleased(
        List<WeakReference<RelationshipQuery.Binding<Object, Void>>> bindings,
        String what
    ) {
        for (int attempt = 0; attempt < 100; attempt++) {
            System.gc();
            if (bindings.stream().allMatch(reference -> reference.get() == null)) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail(bindings.stream().filter(reference -> reference.get() != null).count()
            + " of " + bindings.size() + " bindings of " + what + " are still reachable");
    }

    @Test
    void reachableReleasesVisitedLinkedEntitiesAfterFailureAndDoesNotMarkTheStoreProcessing() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = register(types, "relwind:test/recovery", true);
            var start = fixture.addEntity(new Position(0, 0), null);
            var middle = fixture.addEntity(new Position(1, 0), null);
            var end = fixture.addEntity(new Position(2, 0), new Player("match"));
            relationships.addTarget(fixture.store(), start, type, middle);
            relationships.addTarget(fixture.store(), middle, type, end);
            var failure = new IllegalStateException("linked entity query failure");
            var calls = new int[1];
            var linkedEntity = new Query<Object>() {
                @Override
                public boolean test(Archetype<Object> archetype) {
                    fixture.store().assertWriteProcessing();
                    if (++calls[0] == 2) throw failure;
                    return fixture.playerType().test(archetype);
                }

                @Override
                public boolean requiresComponentType(ComponentType<Object, ?> componentType) {
                    return componentType == fixture.playerType();
                }

                @Override
                public void validateRegistry(ComponentRegistry<Object> registry) {
                    fixture.playerType().validateRegistry(registry);
                }

                @Override
                public void validate() {
                    fixture.playerType().validate();
                }
            };
            var query = RelationshipQuery.of(RelationshipQuery.reachable(type,
                RelationshipQuery.Direction.OUTGOING, 2, linkedEntity), type);

            assertSame(failure, assertThrows(IllegalStateException.class, () -> relationships.fetch(start, query, results -> null)));
            assertEquals(1, (int) relationships.fetch(start, query, results -> results.size()));
            assertEquals(1, (int) relationships.fetch(start, query, results -> results.size()));
        }
    }

    @ParameterizedTest
    @EnumSource(RelationshipQuery.Direction.class)
    void reachableUsesTheShortestPathAndAllowsFiniteConditionsInBothFilters(RelationshipQuery.Direction direction) {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = register(types, "relwind:test/paths", false);
            var permission = register(types, "relwind:test/permission", true);
            var marker = register(types, "relwind:test/marker", true);
            var outer = register(types, "relwind:test/outer", true);
            var anchor = fixture.addEntity(new Position(-1, 0), null);
            var start = fixture.addEntity(new Position(0, 0), null);
            var longPath = fixture.addEntity(new Position(1, 0), null);
            var longerPath = fixture.addEntity(new Position(2, 0), null);
            var join = fixture.addEntity(new Position(3, 0), null);
            var end = fixture.addEntity(new Position(4, 0), null);
            var player = fixture.addEntity(new Position(5, 0), new Player("match"));
            link(direction, fixture.store(), type, start, longPath);
            link(direction, fixture.store(), type, longPath, longerPath);
            link(direction, fixture.store(), type, longerPath, join);
            link(direction, fixture.store(), type, start, join);
            link(direction, fixture.store(), type, join, end);
            relationships.addTarget(fixture.store(), longPath, permission, player);
            relationships.addTarget(fixture.store(), longerPath, permission, player);
            relationships.addTarget(fixture.store(), join, permission, player);
            relationships.addTarget(fixture.store(), end, marker, player);
            relationships.addTarget(fixture.store(), anchor, outer, start);
            var linkedEntity = RelationshipQuery.enumerate(marker, fixture.playerType());
            var condition = RelationshipQuery.reachable(type, direction, 3,
                RelationshipQuery.exists(permission, Query.any()), linkedEntity);

            assertEquals(1, (int) relationships.fetch(anchor, RelationshipQuery.of(outer, condition), results -> results.size()));
            assertFalse((boolean) relationships.fetch(anchor, RelationshipQuery.of(outer, condition), results -> results.get(0).has(linkedEntity)));

            relationships.removeTarget(fixture.store(), join, permission, player);

            assertEquals(0, (int) relationships.fetch(anchor, RelationshipQuery.of(outer, condition), results -> results.size()));
        }
    }

    @ParameterizedTest
    @EnumSource(RelationshipQuery.Direction.class)
    void repeatedReachableSearchesDoNotAllocatePerHopAfterVisitedStorageGrows(RelationshipQuery.Direction direction) {
        int iterations = 2_000;

        long small = allocatedReachableSearches(direction, 8, iterations);
        long large = allocatedReachableSearches(direction, 256, iterations);

        assertTrue(large <= small + 128L * iterations,
            "allocation must not grow per hop: small=" + small + ", large=" + large);
    }

    private static long allocatedReachableSearches(RelationshipQuery.Direction direction, int hops, int iterations) {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var identity = identity();
            var tracker = types.installTracker(identity, TestStoreRuntime.inline());
            try {
                var type = register(types, "relwind:test/allocation", true);
                var outer = register(types, "relwind:test/allocation-outer", true);
                var anchor = fixture.addEntity(new Position(-1, 0), null);
                var start = fixture.addEntity(new Position(0, 0), null);
                tracker.onEntityLoaded(identity.getIdentity(start.getStore(), start), start);
                var previous = start;
                for (int i = 0; i < hops; i++) {
                    var linkedEntity = fixture.addEntity(new Position(i, 0), null);
                    tracker.onEntityLoaded(identity.getIdentity(linkedEntity.getStore(), linkedEntity), linkedEntity);
                    link(direction, fixture.store(), type, previous, linkedEntity);
                    previous = linkedEntity;
                }
                fixture.store().addComponent(previous, fixture.playerType(), new Player("last"));
                relationships.addTarget(fixture.store(), anchor, outer, start);

                var query = RelationshipQuery.of(outer,
                    RelationshipQuery.reachable(type, direction, hops, fixture.playerType()));
                runReachableSearches(anchor, query, 10_000);
                var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
                assertTrue(bean.isThreadAllocatedMemorySupported());
                bean.setThreadAllocatedMemoryEnabled(true);
                long thread = Thread.currentThread().threadId();
                long before = bean.getThreadAllocatedBytes(thread);
                runReachableSearches(anchor, query, iterations);
                return bean.getThreadAllocatedBytes(thread) - before;
            } finally {
                tracker.close();
            }
        }
    }

    private static void runReachableSearches(
        Ref<Object> anchor,
        RelationshipQuery.Definition<Object, Void> query,
        int iterations
    ) {
        for (int i = 0; i < iterations; i++) {
            assertEquals(1, (int) relationships.fetch(anchor, query, results -> results.size()));
        }
    }

    @ParameterizedTest
    @EnumSource(DeclarationWrapper.class)
    void reachableRejectsRecursionNestedInItsMatchCondition(DeclarationWrapper wrapper) {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = register(types, "relwind:test/nested", true);
            var recursive = RelationshipQuery.reachable(type, RelationshipQuery.Direction.OUTGOING, 1, Query.any());
            var nested = wrap(wrapper, type, recursive);

            var failure = assertThrows(IllegalArgumentException.class, () ->
                RelationshipQuery.reachable(type, RelationshipQuery.Direction.OUTGOING, 3, nested));

            assertTrue(failure.getMessage().contains("recursive"));
        }
    }

    @ParameterizedTest
    @EnumSource(DeclarationWrapper.class)
    void reachableRejectsRecursionNestedInItsThroughCondition(DeclarationWrapper wrapper) {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = register(types, "relwind:test/nested", true);
            var recursive = RelationshipQuery.reachable(type, RelationshipQuery.Direction.OUTGOING, 1, Query.any());
            var nested = wrap(wrapper, type, recursive);

            var failure = assertThrows(IllegalArgumentException.class, () ->
                RelationshipQuery.reachable(type, RelationshipQuery.Direction.INCOMING, 3, nested, Query.any()));

            assertTrue(failure.getMessage().contains("recursive"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void reachableRejectsAMaxDepthBelowOne(int depth) {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = register(types, "relwind:test/nested", true);

            var failure = assertThrows(IllegalArgumentException.class, () ->
                RelationshipQuery.reachable(type, RelationshipQuery.Direction.OUTGOING, depth, Query.any()));

            assertTrue(failure.getMessage().contains("maxDepth must be at least one"));
        }
    }

    @ParameterizedTest
    @EnumSource(RelationshipQuery.Direction.class)
    void reachableAndItsNegationKeepARetainedUnresolvedLinkedEntityUnknown(RelationshipQuery.Direction direction) {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var identity = identity();
            var tracker = types.installTracker(identity, TestStoreRuntime.inline());
            try {
                var type = types.registerRelationship(
                    RelationshipTraits.defaults().retainOnTransfer().retainOnDeactivation());
                var outer = register(types, "relwind:test/outer", true);
                var anchor = fixture.addEntity(new Position(0, 0), null);
                var start = fixture.addEntity(new Position(1, 0), null);
                var middle = fixture.addEntity(new Position(2, 0), null);
                var unavailable = fixture.addEntity(new Position(3, 0), null);
                tracker.onEntityLoaded(identity.getIdentity(start.getStore(), start), start);
                tracker.onEntityLoaded(identity.getIdentity(middle.getStore(), middle), middle);
                tracker.onEntityLoaded(identity.getIdentity(unavailable.getStore(), unavailable), unavailable);
                relationships.addTarget(fixture.store(), anchor, outer, start);
                link(direction, fixture.store(), type, start, middle);
                link(direction, fixture.store(), type, middle, unavailable);
                var condition = RelationshipQuery.reachable(type, direction, 2, fixture.playerType());

                assertEquals(0, (int) relationships.fetch(anchor, RelationshipQuery.of(outer, condition), results -> results.size()));
                assertEquals(1, (int) relationships.fetch(anchor, RelationshipQuery.of(outer, RelationshipQuery.not(condition)), results -> results.size()));

                tracker.onEntityUnloaded(identity.getIdentity(unavailable.getStore(), unavailable), unavailable,
                    UnloadReason.DEACTIVATION);

                assertEquals(0, (int) relationships.fetch(anchor, RelationshipQuery.of(outer, condition), results -> results.size()));
                assertEquals(0, (int) relationships.fetch(anchor, RelationshipQuery.of(outer, RelationshipQuery.not(condition)), results -> results.size()));
                assertEquals(1, (int) relationships.fetch(anchor, RelationshipQuery.of(outer, RelationshipQuery.not(
                    RelationshipQuery.reachable(type, direction, 1, fixture.playerType()))), results -> results.size()));

                fixture.store().addComponent(middle, fixture.playerType(), new Player("match"));

                assertEquals(1, (int) relationships.fetch(anchor, RelationshipQuery.of(outer, condition), results -> results.size()));
                assertEquals(0, (int) relationships.fetch(anchor, RelationshipQuery.of(outer, RelationshipQuery.not(condition)), results -> results.size()));
            } finally {
                tracker.close();
            }
        }
    }

    @Test
    void reachableThroughFiltersExpansionButNotTheStartOrMatchingLinkedEntity() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = register(types, "relwind:test/through", false);
            var start = fixture.addEntity(new Position(0, 0), null);
            var middle = fixture.addEntity(new Position(1, 0), new Player("middle"));
            var end = addWeapon(fixture, fixture.registry().registerComponent(Weapon.class, Weapon::new));
            relationships.addTarget(fixture.store(), start, type, middle);
            relationships.addTarget(fixture.store(), middle, type, end);

            var condition = RelationshipQuery.reachable(type, RelationshipQuery.Direction.OUTGOING, 2,
                fixture.playerType(), Query.not(fixture.positionType()));
            assertEquals(1, (int) relationships.fetch(start, RelationshipQuery.of(condition, type), results -> results.size()));
            fixture.store().removeComponent(middle, fixture.playerType());
            assertEquals(0, (int) relationships.fetch(start, RelationshipQuery.of(condition, type), results -> results.size()));
            relationships.addTarget(fixture.store(), start, type, end);
            assertEquals(2, (int) relationships.fetch(start, RelationshipQuery.of(condition, type), results -> results.size()));
        }
    }

    @Test
    void reachableHonorsTheDepthLimitInEitherDirection() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = register(types, "relwind:test/reaches", false);
            var start = fixture.addEntity(new Position(0, 0), new Player("start"));
            var middle = fixture.addEntity(new Position(1, 0), null);
            var end = fixture.addEntity(new Position(2, 0), null);
            relationships.addTarget(fixture.store(), start, type, middle);
            relationships.addTarget(fixture.store(), middle, type, end);
            relationships.addTarget(fixture.store(), end, type, start);

            assertEquals(1, (int) relationships.fetch(middle, RelationshipQuery.of(
                RelationshipQuery.reachable(type, RelationshipQuery.Direction.OUTGOING, 2, fixture.playerType()), type), results -> results.size()));
            assertEquals(0, (int) relationships.fetch(middle, RelationshipQuery.of(
                RelationshipQuery.reachable(type, RelationshipQuery.Direction.OUTGOING, 1, fixture.playerType()), type), results -> results.size()));
            assertEquals(1, (int) relationships.fetch(end, RelationshipQuery.of(
                RelationshipQuery.reachable(type, RelationshipQuery.Direction.INCOMING, 2, fixture.playerType()), type), results -> results.size()));
            assertEquals(0, (int) relationships.fetch(end, RelationshipQuery.of(
                RelationshipQuery.reachable(type, RelationshipQuery.Direction.INCOMING, 1, fixture.playerType()), type), results -> results.size()));
        }
    }

    @Test
    void reachableExcludesTheStartOfACycleAtEveryDepth() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = register(types, "relwind:test/reaches", false);
            var start = fixture.addEntity(new Position(0, 0), new Player("start"));
            var middle = fixture.addEntity(new Position(1, 0), null);
            var end = fixture.addEntity(new Position(2, 0), null);
            relationships.addTarget(fixture.store(), start, type, middle);
            relationships.addTarget(fixture.store(), middle, type, end);
            relationships.addTarget(fixture.store(), end, type, start);

            assertEquals(1, (int) relationships.fetch(middle, RelationshipQuery.of(
                RelationshipQuery.reachable(type, RelationshipQuery.Direction.OUTGOING, 2, fixture.playerType()), type), results -> results.size()));
            assertEquals(0, (int) relationships.fetch(start, RelationshipQuery.of(
                RelationshipQuery.reachable(type, RelationshipQuery.Direction.OUTGOING, 2, fixture.playerType()), type), results -> results.size()));
            assertEquals(0, (int) relationships.fetch(start, RelationshipQuery.of(
                RelationshipQuery.reachable(type, RelationshipQuery.Direction.OUTGOING, Integer.MAX_VALUE, fixture.playerType()), type), results -> results.size()));
        }
    }

    @ParameterizedTest
    @EnumSource(RelationshipQuery.Direction.class)
    void enumerateReachableReportsEachLinkedEntityOnceInBreadthFirstOrderWithItsFirstFoundDepth(
        RelationshipQuery.Direction direction
    ) {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = register(types, "relwind:test/enumerate-reaches", false);
            var start = fixture.addEntity(new Position(0, 0), null);
            var near = fixture.addEntity(new Position(1, 0), null);
            var alsoNear = fixture.addEntity(new Position(2, 0), null);
            var join = fixture.addEntity(new Position(3, 0), null);
            link(direction, fixture.store(), type, start, near);
            link(direction, fixture.store(), type, start, alsoNear);
            link(direction, fixture.store(), type, near, join);
            link(direction, fixture.store(), type, alsoNear, join);
            link(direction, fixture.store(), type, join, start);

            relationships.fetch(start, RelationshipQuery.enumerateReachable(type, direction, 2, fixture.positionType()), results -> {
                assertEquals(3, results.size());
                assertSame(near, results.get(0).getTarget());
                assertSame(alsoNear, results.get(1).getTarget());
                assertSame(join, results.get(2).getTarget());
                assertEquals(1, results.get(0).getDepth());
                assertEquals(1, results.get(1).getDepth());
                assertEquals(2, results.get(2).getDepth());
                assertSame(start, results.get(0).getSource());
                assertSame(start, results.get(1).getSource());
                assertSame(start, results.get(2).getSource());
                return null;
            });
        }
    }

    @Test
    void enumerateReachableExcludesTheStartWhenACycleReachesIt() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = register(types, "relwind:test/enumerate-cycle", false);
            var start = fixture.addEntity(new Position(0, 0), null);
            var middle = fixture.addEntity(new Position(1, 0), null);
            var end = fixture.addEntity(new Position(2, 0), null);
            relationships.addTarget(fixture.store(), start, type, middle);
            relationships.addTarget(fixture.store(), middle, type, end);
            relationships.addTarget(fixture.store(), end, type, start);

            relationships.fetch(start, RelationshipQuery.enumerateReachable(type, RelationshipQuery.Direction.OUTGOING, 1, fixture.positionType()), bounded -> {
                assertEquals(List.of(middle), targets(bounded));
                return null;
            });
            relationships.fetch(start, RelationshipQuery.enumerateReachable(type, RelationshipQuery.Direction.OUTGOING, Integer.MAX_VALUE, fixture.positionType()), complete -> {
                assertEquals(List.of(middle, end), targets(complete));
                return null;
            });
            relationships.fetch(start, RelationshipQuery.enumerateReachable(type, RelationshipQuery.Direction.INCOMING, Integer.MAX_VALUE, fixture.positionType()), incoming -> {
                assertEquals(List.of(end, middle), targets(incoming));
                return null;
            });
        }
    }

    @Test
    void enumerateReachableThroughFiltersExpansionButNotTheStartOrReportedLinkedEntities() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = register(types, "relwind:test/enumerate-through", false);
            var start = fixture.addEntity(new Position(0, 0), null);
            var open = fixture.addEntity(new Position(1, 0), new Player("open"));
            var closed = fixture.addEntity(new Position(2, 0), null);
            var behindOpen = fixture.addEntity(new Position(3, 0), null);
            var behindClosed = fixture.addEntity(new Position(4, 0), null);
            relationships.addTarget(fixture.store(), start, type, open);
            relationships.addTarget(fixture.store(), start, type, closed);
            relationships.addTarget(fixture.store(), open, type, behindOpen);
            relationships.addTarget(fixture.store(), closed, type, behindClosed);

            relationships.fetch(start, RelationshipQuery.enumerateReachable(
                type, RelationshipQuery.Direction.OUTGOING, 3, fixture.playerType(), fixture.positionType()), results -> {
                assertEquals(List.of(open, closed, behindOpen), targets(results));
                assertEquals(2, results.get(2).getDepth());
                assertFalse(results.isTruncated());
                return null;
            });
            fixture.store().addComponent(behindOpen, fixture.playerType(), new Player("behind"));
            relationships.addTarget(fixture.store(), behindOpen, type, fixture.addEntity(new Position(5, 0), null));
            relationships.fetch(start, RelationshipQuery.enumerateReachable(
                type, RelationshipQuery.Direction.OUTGOING, 2, fixture.playerType(), fixture.positionType()), limited -> {
                assertEquals(List.of(open, closed, behindOpen), targets(limited));
                assertTrue(limited.isTruncated());
                return null;
            });
        }
    }

    @Test
    void enumerateReachableIsTruncatedOnlyWhenTheDepthLimitOmitsAnUnvisitedLinkedEntity() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = register(types, "relwind:test/enumerate-cut", false);
            var start = fixture.addEntity(new Position(0, 0), null);
            var middle = fixture.addEntity(new Position(1, 0), null);
            var end = fixture.addEntity(new Position(2, 0), null);
            var leaf = fixture.addEntity(new Position(3, 0), null);
            relationships.addTarget(fixture.store(), start, type, middle);
            relationships.addTarget(fixture.store(), middle, type, end);
            relationships.addTarget(fixture.store(), end, type, start);
            relationships.addTarget(fixture.store(), start, type, leaf);

            assertTrue((boolean) relationships.fetch(start, RelationshipQuery.enumerateReachable(type, RelationshipQuery.Direction.OUTGOING, 1, fixture.positionType()), results -> results.isTruncated()));
            assertFalse((boolean) relationships.fetch(start, RelationshipQuery.enumerateReachable(type, RelationshipQuery.Direction.OUTGOING, 2, fixture.positionType()), results -> results.isTruncated()));
            assertFalse((boolean) relationships.fetch(start, RelationshipQuery.enumerateReachable(type, RelationshipQuery.Direction.OUTGOING, Integer.MAX_VALUE, fixture.positionType()), results -> results.isTruncated()));
            assertFalse((boolean) relationships.fetch(leaf, RelationshipQuery.enumerateReachable(type, RelationshipQuery.Direction.OUTGOING, 1, fixture.positionType()), results -> results.isTruncated()));
        }
    }

    @Test
    void enumerateReachableReportsAnUnresolvedCutWithAnEmptyMatchCollection() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var identity = identity();
            var tracker = types.installTracker(identity, TestStoreRuntime.inline());
            try {
                var type = types.registerRelationship(
                    RelationshipTraits.defaults().retainOnTransfer().retainOnDeactivation());
                var start = fixture.addEntity(new Position(0, 0), null);
                var middle = fixture.addEntity(new Position(1, 0), null);
                var end = fixture.addEntity(new Position(2, 0), null);
                tracker.onEntityLoaded(identity.getIdentity(start.getStore(), start), start);
                tracker.onEntityLoaded(identity.getIdentity(middle.getStore(), middle), middle);
                tracker.onEntityLoaded(identity.getIdentity(end.getStore(), end), end);
                relationships.addTarget(fixture.store(), start, type, middle);
                relationships.addTarget(fixture.store(), middle, type, end);

                relationships.fetch(start, RelationshipQuery.enumerateReachable(type, RelationshipQuery.Direction.OUTGOING, Integer.MAX_VALUE, fixture.playerType()), complete -> {
                    assertEquals(0, complete.size());
                    assertFalse(complete.isTruncated());
                    return null;
                });
                tracker.onEntityUnloaded(identity.getIdentity(end.getStore(), end), end,
                    UnloadReason.DEACTIVATION);
                relationships.fetch(start, RelationshipQuery.enumerateReachable(type, RelationshipQuery.Direction.OUTGOING, Integer.MAX_VALUE, fixture.playerType()), cut -> {
                    assertEquals(0, cut.size());
                    assertTrue(cut.isTruncated());
                    return null;
                });
            } finally {
                tracker.close();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(RelationshipQuery.Direction.class)
    void enumerateReachableExposesDepthOneLinkDataAndNullBeyondIt(RelationshipQuery.Direction direction) {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = types.registerRelationship(FollowData.class, RelationshipTraits.defaults());
            var start = fixture.addEntity(new Position(0, 0), null);
            var near = fixture.addEntity(new Position(1, 0), null);
            var far = fixture.addEntity(new Position(2, 0), null);
            link(direction, fixture.store(), type, start, near, new FollowData("near"));
            link(direction, fixture.store(), type, near, far, new FollowData("far"));

            relationships.fetch(start, RelationshipQuery.enumerateReachable(type, direction, 2, fixture.positionType()), results -> {
                assertEquals(List.of(near, far), targets(results));
                assertEquals(new FollowData("near"), results.get(0).getData());
                assertNull(results.get(1).getData());
                return null;
            });
        }
    }

    @Test
    void enumerateReachableReportsALinkedEntityOnceWhenANestedConditionMatchesSeveralTimes() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = register(types, "relwind:test/enumerate-nested", true);
            var owns = register(types, "relwind:test/enumerate-owned", false);
            var start = fixture.addEntity(new Position(0, 0), null);
            var hub = fixture.addEntity(new Position(1, 0), null);
            var owned = fixture.addEntity(new Position(2, 0), null);
            var alsoOwned = fixture.addEntity(new Position(3, 0), null);
            relationships.addTarget(fixture.store(), start, type, hub);
            relationships.addTarget(fixture.store(), hub, owns, owned);
            relationships.addTarget(fixture.store(), hub, owns, alsoOwned);
            var ownedBinding = RelationshipQuery.enumerate(owns, fixture.positionType());

            relationships.fetch(start, RelationshipQuery.enumerateReachable(
                type, RelationshipQuery.Direction.OUTGOING, 1, ownedBinding), results -> {
                assertEquals(List.of(hub), targets(results));
                assertTrue(results.get(0).has(ownedBinding));
                assertSame(owned, results.get(0).getTarget(ownedBinding));
                return null;
            });
        }
    }

    @ParameterizedTest
    @EnumSource(DeclarationWrapper.class)
    void enumerateReachableRejectsRecursionNestedInItsMatchCondition(DeclarationWrapper wrapper) {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = register(types, "relwind:test/enumerate-declaration", true);
            var recursive = RelationshipQuery.reachable(type, RelationshipQuery.Direction.OUTGOING, 1, Query.any());
            var nested = wrap(wrapper, type, recursive);

            var failure = assertThrows(IllegalArgumentException.class, () ->
                RelationshipQuery.enumerateReachable(type, RelationshipQuery.Direction.OUTGOING, 3, nested));

            assertTrue(failure.getMessage().contains("recursive"));
        }
    }

    @ParameterizedTest
    @EnumSource(DeclarationWrapper.class)
    void enumerateReachableRejectsRecursionNestedInItsThroughCondition(DeclarationWrapper wrapper) {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = register(types, "relwind:test/enumerate-declaration", true);
            var recursive = RelationshipQuery.reachable(type, RelationshipQuery.Direction.OUTGOING, 1, Query.any());
            var nested = wrap(wrapper, type, recursive);

            var failure = assertThrows(IllegalArgumentException.class, () ->
                RelationshipQuery.enumerateReachable(type, RelationshipQuery.Direction.INCOMING, 3, nested, Query.any()));

            assertTrue(failure.getMessage().contains("recursive"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void enumerateReachableRejectsAMaxDepthBelowOne(int depth) {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = register(types, "relwind:test/enumerate-declaration", true);

            var failure = assertThrows(IllegalArgumentException.class, () ->
                RelationshipQuery.enumerateReachable(type, RelationshipQuery.Direction.OUTGOING, depth, Query.any()));

            assertTrue(failure.getMessage().contains("maxDepth must be at least one"));
        }
    }

    @Test
    void existenceFiltersWithoutExpandingZeroOneOrTwoNestedTargets() {
        try (var fixture = new StoreFixture()) {
            var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/follows", true);
            var owns = register(types, "relwind:test/owns", false);
            var ownsWeapon = RelationshipQuery.exists(owns, weaponType);
            var system = new RecordingSystem(
                Archetype.of(fixture.positionType()),
                follows,
                RelationshipQuery.and(fixture.playerType(), ownsWeapon)
            );
            fixture.registry().registerSystem(system);

            var alice = fixture.addEntity(new Position(1, 2), null);
            var bob = fixture.addEntity(new Position(3, 4), new Player("Bob"));
            relationships.addTarget(fixture.store(), alice, follows, bob);

            fixture.tick(0.05f);
            assertEquals(0, system.callbacks.size());

            relationships.addTarget(fixture.store(), bob, owns, addWeapon(fixture, weaponType));
            fixture.tick(0.05f);
            assertEquals(List.of(new Callback(alice, bob)), system.callbacks);

            relationships.addTarget(fixture.store(), bob, owns, addWeapon(fixture, weaponType));
            fixture.tick(0.05f);
            assertEquals(List.of(new Callback(alice, bob), new Callback(alice, bob)), system.callbacks);
        }
    }

    @Test
    void enumerationReturnsEachNestedTargetAsASeparateBinding() {
        try (var fixture = new StoreFixture()) {
            var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/follows", true);
            var owns = register(types, "relwind:test/owns", false);
            var weapon = RelationshipQuery.enumerate(owns, weaponType);
            var system = new BindingSystem(
                Archetype.of(fixture.positionType()),
                follows,
                RelationshipQuery.and(fixture.playerType(), weapon),
                weapon
            );
            fixture.registry().registerSystem(system);

            var alice = fixture.addEntity(new Position(1, 2), null);
            var bob = fixture.addEntity(new Position(3, 4), new Player("Bob"));
            relationships.addTarget(fixture.store(), alice, follows, bob);

            fixture.tick(0.05f);
            assertEquals(List.of(), system.callbacks);

            var sword = addWeapon(fixture, weaponType);
            relationships.addTarget(fixture.store(), bob, owns, sword);
            fixture.tick(0.05f);
            assertEquals(List.of(new BoundCallback(alice, bob, sword)), system.callbacks);

            system.callbacks.clear();
            var bow = addWeapon(fixture, weaponType);
            relationships.addTarget(fixture.store(), bob, owns, bow);
            fixture.tick(0.05f);
            assertEquals(
                Set.of(new BoundCallback(alice, bob, sword), new BoundCallback(alice, bob, bow)),
                new HashSet<>(system.callbacks)
            );
            assertEquals(2, system.callbacks.size());
        }
    }

    @Test
    void booleanAlternativesDoNotDuplicateTheSameCompleteBinding() {
        try (var fixture = new StoreFixture()) {
            var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/follows", true);
            var owns = register(types, "relwind:test/owns", false);
            var weapon = RelationshipQuery.enumerate(owns, weaponType);
            var system = new BindingSystem(
                Archetype.of(fixture.positionType()),
                follows,
                RelationshipQuery.and(fixture.playerType(), RelationshipQuery.or(weapon, weapon)),
                weapon
            );
            fixture.registry().registerSystem(system);

            var alice = fixture.addEntity(new Position(1, 2), null);
            var bob = fixture.addEntity(new Position(3, 4), new Player("Bob"));
            var sword = addWeapon(fixture, weaponType);
            var bow = addWeapon(fixture, weaponType);
            relationships.addTarget(fixture.store(), alice, follows, bob);
            relationships.addTarget(fixture.store(), bob, owns, sword);
            relationships.addTarget(fixture.store(), bob, owns, bow);

            fixture.tick(0.05f);

            assertEquals(
                Set.of(new BoundCallback(alice, bob, sword), new BoundCallback(alice, bob, bow)),
                new HashSet<>(system.callbacks)
            );
            assertEquals(2, system.callbacks.size());
        }
    }

    @Test
    void repeatedBindingKeepsItsEnclosingAssignmentAcrossBooleanAlternatives() {
        try (var fixture = new StoreFixture()) {
            var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/follows", true);
            var owns = register(types, "relwind:test/owns", false);
            var weapon = RelationshipQuery.enumerate(owns, weaponType);
            var condition = RelationshipQuery.and(
                weapon,
                RelationshipQuery.or(weapon, RelationshipQuery.and(Query.any()))
            );
            var system = new BindingSystem(
                Archetype.of(fixture.positionType()),
                follows,
                condition,
                weapon
            );
            fixture.registry().registerSystem(system);

            var alice = fixture.addEntity(new Position(1, 2), null);
            var bob = fixture.addEntity(new Position(3, 4), new Player("Bob"));
            var sword = addWeapon(fixture, weaponType);
            var bow = addWeapon(fixture, weaponType);
            relationships.addTarget(fixture.store(), alice, follows, bob);
            relationships.addTarget(fixture.store(), bob, owns, sword);
            relationships.addTarget(fixture.store(), bob, owns, bow);

            fixture.tick(0.05f);

            assertEquals(
                Set.of(new BoundCallback(alice, bob, sword), new BoundCallback(alice, bob, bow)),
                new HashSet<>(system.callbacks)
            );
            assertEquals(2, system.callbacks.size());
        }
    }

    @Test
    void repeatedBindingRejectsAnAssignmentFromAnotherSource() {
        try (var fixture = new StoreFixture()) {
            var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/follows", true);
            var owns = register(types, "relwind:test/owns", false);
            var weapon = RelationshipQuery.enumerate(owns, weaponType);
            var nestedOwner = RelationshipQuery.enumerate(owns, weapon);
            var system = new BindingSystem(
                Archetype.of(fixture.positionType()),
                follows,
                RelationshipQuery.and(weapon, nestedOwner),
                weapon
            );
            fixture.registry().registerSystem(system);

            var alice = fixture.addEntity(new Position(1, 2), null);
            var bob = fixture.addEntity(new Position(3, 4), new Player("Bob"));
            var sword = addWeapon(fixture, weaponType);
            var gem = addWeapon(fixture, weaponType);
            relationships.addTarget(fixture.store(), alice, follows, bob);
            relationships.addTarget(fixture.store(), bob, owns, sword);
            relationships.addTarget(fixture.store(), sword, owns, gem);

            fixture.tick(0.05f);

            assertEquals(List.of(), system.callbacks);
        }
    }

    @Test
    void negationKeepsAnUnavailableNestedTargetUnknown() {
        try (var fixture = new StoreFixture()) {
            var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/follows", true);
            var owns = register(types, "relwind:test/owns", false);
            var system = new RecordingSystem(
                Archetype.of(fixture.positionType()),
                follows,
                RelationshipQuery.not(RelationshipQuery.exists(owns, weaponType))
            );
            fixture.registry().registerSystem(system);

            var alice = fixture.addEntity(new Position(1, 2), null);
            var bob = fixture.addEntity(new Position(3, 4), new Player("Bob"));
            relationships.addTarget(fixture.store(), alice, follows, bob);

            fixture.tick(0.05f);
            assertEquals(List.of(new Callback(alice, bob)), system.callbacks);

            var unavailableWeapon = addWeapon(fixture, weaponType);
            relationships.addTarget(fixture.store(), bob, owns, unavailableWeapon);
            fixture.store().removeEntity(unavailableWeapon, RemoveReason.UNLOAD);
            fixture.tick(0.05f);

            assertEquals(List.of(new Callback(alice, bob)), system.callbacks);
        }
    }

    @Test
    void booleanCompositionUsesThreeValuedAndAndOrRules() {
        try (var fixture = new StoreFixture()) {
            var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
            var shieldType = fixture.registry().registerComponent(Shield.class, Shield::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/follows", true);
            var owns = register(types, "relwind:test/owns", false);
            var unknown = RelationshipQuery.exists(owns, weaponType);
            var condition = RelationshipQuery.and(
                RelationshipQuery.not(RelationshipQuery.and(unknown, RelationshipQuery.and(shieldType))),
                RelationshipQuery.or(unknown, RelationshipQuery.and(fixture.playerType()))
            );
            var system = new RecordingSystem(Archetype.of(fixture.positionType()), follows, condition);
            fixture.registry().registerSystem(system);

            var alice = fixture.addEntity(new Position(1, 2), null);
            var bob = fixture.addEntity(new Position(3, 4), new Player("Bob"));
            var unavailableWeapon = addWeapon(fixture, weaponType);
            relationships.addTarget(fixture.store(), alice, follows, bob);
            relationships.addTarget(fixture.store(), bob, owns, unavailableWeapon);
            fixture.store().removeEntity(unavailableWeapon, RemoveReason.UNLOAD);

            fixture.tick(0.05f);

            assertEquals(List.of(new Callback(alice, bob)), system.callbacks);
        }
    }

    @Test
    void nativeNonTickingSkipsSourcesButAllowsTargetsThatMatchTheirQuery() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/follows", false);
            var system = new RecordingSystem(
                Query.any(),
                follows,
                RelationshipQuery.and(fixture.playerType())
            );
            fixture.registry().registerSystem(system);

            var alice = fixture.addEntity(new Position(1, 2), null);
            var sleepingAlice = fixture.addEntity(new Position(2, 3), null);
            var bob = fixture.addEntity(new Position(3, 4), new Player("Bob"));
            var nonTickingType = fixture.registry().getNonTickingComponentType();
            fixture.store().addComponent(sleepingAlice, nonTickingType, NonTicking.get());
            fixture.store().addComponent(bob, nonTickingType, NonTicking.get());
            relationships.addTarget(fixture.store(), alice, follows, bob);
            relationships.addTarget(fixture.store(), sleepingAlice, follows, bob);

            fixture.tick(0.05f);

            assertEquals(List.of(new Callback(alice, bob)), system.callbacks);
        }
    }

    @Test
    void nativeTargetQueryCanExcludeNonTickingTargets() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/follows", true);
            var nonTickingType = fixture.registry().getNonTickingComponentType();
            var system = new RecordingSystem(
                Query.any(),
                follows,
                RelationshipQuery.and(Query.and(fixture.playerType(), Query.not(nonTickingType)))
            );
            fixture.registry().registerSystem(system);

            var alice = fixture.addEntity(new Position(1, 2), null);
            var bob = fixture.addEntity(new Position(3, 4), new Player("Bob"));
            fixture.store().addComponent(bob, nonTickingType, NonTicking.get());
            relationships.addTarget(fixture.store(), alice, follows, bob);

            fixture.tick(0.05f);

            assertEquals(List.of(), system.callbacks);
        }
    }

    @Test
    void removingANestedTargetComponentUnregistersTheNativeSystem() {
        try (var fixture = new StoreFixture()) {
            var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/follows", true);
            var owns = register(types, "relwind:test/owns", false);
            var system = new RecordingSystem(
                Query.any(),
                follows,
                RelationshipQuery.exists(owns, weaponType)
            );
            fixture.registry().registerSystem(system);
            assertTrue(fixture.registry().hasSystem(system));

            fixture.registry().unregisterComponent(weaponType);

            assertFalse(fixture.registry().hasSystem(system));
        }
    }

    @Test
    void multipleEnumeratedConditionsReturnTheirMatchingCombinations() {
        try (var fixture = new StoreFixture()) {
            var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
            var shieldType = fixture.registry().registerComponent(Shield.class, Shield::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/follows", true);
            var owns = register(types, "relwind:test/owns", false);
            var equips = register(types, "relwind:test/equips", false);
            var weapon = RelationshipQuery.enumerate(owns, weaponType);
            var shield = RelationshipQuery.enumerate(equips, shieldType);
            var system = new CombinationSystem(
                Archetype.of(fixture.positionType()),
                follows,
                RelationshipQuery.and(fixture.playerType(), weapon, shield),
                weapon,
                shield
            );
            fixture.registry().registerSystem(system);

            var alice = fixture.addEntity(new Position(1, 2), null);
            var bob = fixture.addEntity(new Position(3, 4), new Player("Bob"));
            var sword = addWeapon(fixture, weaponType);
            var bow = addWeapon(fixture, weaponType);
            var roundShield = addEntity(fixture, shieldType);
            var towerShield = addEntity(fixture, shieldType);
            relationships.addTarget(fixture.store(), alice, follows, bob);
            relationships.addTarget(fixture.store(), bob, owns, sword);
            relationships.addTarget(fixture.store(), bob, owns, bow);
            relationships.addTarget(fixture.store(), bob, equips, roundShield);
            relationships.addTarget(fixture.store(), bob, equips, towerShield);

            fixture.tick(0.05f);

            assertEquals(
                Set.of(
                    new Combination(sword, roundShield),
                    new Combination(sword, towerShield),
                    new Combination(bow, roundShield),
                    new Combination(bow, towerShield)
                ),
                new HashSet<>(system.combinations)
            );
            assertEquals(4, system.combinations.size());
            assertTrue(system.allBindingsPresent);
            assertEquals(Set.of(bob), system.bindingSources);
        }
    }

    @Test
    void explicitFiniteNestingTerminatesOnACyclicEntityGraph() {
        try (var fixture = new StoreFixture()) {
            var destinationType = fixture.registry().registerComponent(Destination.class, Destination::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/follows", true);
            var next = register(types, "relwind:test/next", false);
            var depthThree = RelationshipQuery.exists(
                next,
                RelationshipQuery.exists(next, RelationshipQuery.exists(next, destinationType))
            );
            var system = new RecordingSystem(Archetype.of(fixture.positionType()), follows, depthThree);
            fixture.registry().registerSystem(system);

            var alice = fixture.addEntity(new Position(1, 2), null);
            var first = fixture.addEntity(new Position(2, 3), null);
            var second = fixture.addEntity(new Position(3, 4), null);
            var third = fixture.addEntity(new Position(4, 5), null);
            var destination = addEntity(fixture, destinationType);
            relationships.addTarget(fixture.store(), alice, follows, first);
            relationships.addTarget(fixture.store(), first, next, second);
            relationships.addTarget(fixture.store(), second, next, third);
            relationships.addTarget(fixture.store(), third, next, first);
            relationships.addTarget(fixture.store(), third, next, destination);

            fixture.tick(0.05f);

            assertEquals(List.of(new Callback(alice, first)), system.callbacks);
        }
    }

    @Test
    void enumeratedBindingExposesTypedLinkDataDuringTheCallback() {
        try (var fixture = new StoreFixture()) {
            var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/follows", true);
            var owns = types.registerRelationship(Ownership.class, RelationshipTraits.defaults());
            var weapon = RelationshipQuery.enumerate(owns, weaponType);
            var system = new DataBindingSystem(
                Archetype.of(fixture.positionType()),
                follows,
                weapon,
                weapon
            );
            fixture.registry().registerSystem(system);

            var alice = fixture.addEntity(new Position(1, 2), null);
            var bob = fixture.addEntity(new Position(3, 4), new Player("Bob"));
            var sword = addWeapon(fixture, weaponType);
            var ownership = new Ownership("equipped");
            relationships.addTarget(fixture.store(), alice, follows, bob);
            relationships.addTarget(fixture.store(), bob, owns, sword, ownership);

            fixture.tick(0.05f);

            assertEquals(new DataBinding(bob, sword, ownership), system.binding);
        }
    }

    @Test
    void nestedStoreTickKeepsTheOuterEnumeratedBindingStable() {
        var registry = new ComponentRegistry<Object>();
        try {
            var weaponType = registry.registerComponent(Weapon.class, Weapon::new);
            var types = new RelationshipTypeRegistry<>(registry);
            var follows = register(types, "relwind:test/follows", true);
            var owns = register(types, "relwind:test/owns", true);
            var weapon = RelationshipQuery.enumerate(owns, weaponType);
            var system = new NestedBindingSystem(follows, weapon);
            registry.registerSystem(system);
            var outerStore = registry.addStore(new Object(), EmptyResourceStorage.get());
            var nestedStore = registry.addStore(new Object(), EmptyResourceStorage.get());
            var outerTarget = addEntity(outerStore, Archetype.empty());
            var outerSource = addEntity(outerStore, Archetype.empty());
            var outerWeapon = addEntity(outerStore, Archetype.of(weaponType));
            var nestedTarget = addEntity(nestedStore, Archetype.empty());
            var nestedSource = addEntity(nestedStore, Archetype.empty());
            var nestedWeapon = addEntity(nestedStore, Archetype.of(weaponType));
            relationships.addTarget(outerStore, outerSource, follows, outerTarget);
            relationships.addTarget(outerStore, outerTarget, owns, outerWeapon);
            relationships.addTarget(nestedStore, nestedSource, follows, nestedTarget);
            relationships.addTarget(nestedStore, nestedTarget, owns, nestedWeapon);
            system.outerStore = outerStore;
            system.nestedStore = nestedStore;

            outerStore.tick(0.05f);

            assertEquals(outerWeapon, system.outerWeaponAfterNestedTick);
        } finally {
            registry.shutdown();
        }
    }

    private static List<Ref<Object>> targets(RelationshipResults<Object, ?> results) {
        var reached = new ArrayList<Ref<Object>>(results.size());
        for (var result : results) {
            reached.add(result.getTarget());
        }
        return reached;
    }

    private static void link(
        RelationshipQuery.Direction direction,
        Store<Object> store,
        GenericRelationshipType<Object, Object, Void> type,
        Ref<Object> from,
        Ref<Object> to
    ) {
        if (direction == RelationshipQuery.Direction.OUTGOING) relationships.addTarget(store, from, type, to);
        else relationships.addTarget(store, to, type, from);
    }

    private static <DATA> void link(
        RelationshipQuery.Direction direction,
        Store<Object> store,
        GenericRelationshipType<Object, Object, DATA> type,
        Ref<Object> from,
        Ref<Object> to,
        DATA data
    ) {
        if (direction == RelationshipQuery.Direction.OUTGOING) relationships.addTarget(store, from, type, to, data);
        else relationships.addTarget(store, to, type, from, data);
    }

    private static Query<Object> wrap(
        DeclarationWrapper wrapper,
        GenericRelationshipType<Object, Object, Void> type,
        RelationshipQuery<Object> recursive
    ) {
        return switch (wrapper) {
            case BARE -> recursive;
            case RELATIONSHIP_AND -> RelationshipQuery.and(recursive);
            case RELATIONSHIP_OR -> RelationshipQuery.or(recursive);
            case RELATIONSHIP_NOT -> RelationshipQuery.not(recursive);
            case EXISTS -> RelationshipQuery.exists(type, recursive);
            case ENUMERATE -> RelationshipQuery.enumerate(type, recursive);
            case DEFINITION_SOURCE -> RelationshipQuery.of(recursive, type);
            case DEFINITION_TARGET -> RelationshipQuery.of(type, recursive);
            case NATIVE_AND -> Query.and(Query.any(), recursive);
            case NATIVE_OR -> Query.or(Query.any(), recursive);
            case NATIVE_NOT -> Query.not(recursive);
        };
    }

    private enum DeclarationWrapper {
        BARE, RELATIONSHIP_AND, RELATIONSHIP_OR, RELATIONSHIP_NOT, EXISTS, ENUMERATE,
        DEFINITION_SOURCE, DEFINITION_TARGET, NATIVE_AND, NATIVE_OR, NATIVE_NOT
    }

    private static GenericRelationshipType<Object, Object, Void> register(
        RelationshipTypeRegistry<Object> types,
        String id,
        boolean exclusive
    ) {
        return types.registerRelationship(id,
            !exclusive
                ? RelationshipTraits.defaults() : RelationshipTraits.defaults().exclusive());
    }

    private static Ref<Object> addWeapon(
        StoreFixture fixture,
        ComponentType<Object, Weapon> weaponType
    ) {
        return Objects.requireNonNull(fixture.store().addEntity(Archetype.of(weaponType), AddReason.SPAWN));
    }

    private static <T extends Component<Object>> Ref<Object> addEntity(
        StoreFixture fixture,
        ComponentType<Object, T> componentType
    ) {
        return Objects.requireNonNull(fixture.store().addEntity(Archetype.of(componentType), AddReason.SPAWN));
    }

    private static Ref<Object> addEntity(Store<Object> store, Archetype<Object> archetype) {
        return Objects.requireNonNull(store.addEntity(archetype, AddReason.SPAWN));
    }

    private static final class RecordingSystem extends RelationshipTickingSystem<Object, Void> {
        private final RelationshipQuery.Definition<Object, Void> query;
        private final ArrayList<Callback> callbacks = new ArrayList<>();

        private RecordingSystem(
            Query<Object> sourceQuery,
            GenericRelationshipType<Object, Object, Void> type,
            RelationshipQuery<Object> targetQuery
        ) {
            this.query = RelationshipQuery.of(sourceQuery, type, targetQuery);
        }

        @NonNullDecl
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
            callbacks.add(new Callback(result.getSource(), result.getTarget()));
        }
    }

    private static final class BindingSystem extends RelationshipTickingSystem<Object, Void> {
        private final RelationshipQuery.Definition<Object, Void> query;
        private final ArrayList<BoundCallback> callbacks = new ArrayList<>();
        private final RelationshipQuery.Binding<Object, Void> binding;

        private BindingSystem(
            Query<Object> sourceQuery,
            GenericRelationshipType<Object, Object, Void> type,
            RelationshipQuery<Object> targetQuery,
            RelationshipQuery.Binding<Object, Void> binding
        ) {
            this.query = RelationshipQuery.of(sourceQuery, type, targetQuery);
            this.binding = binding;
        }

        @NonNullDecl
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
            callbacks.add(new BoundCallback(result.getSource(), result.getTarget(), result.getTarget(binding)));
        }
    }

    private static final class CombinationSystem extends RelationshipTickingSystem<Object, Void> {
        private final RelationshipQuery.Definition<Object, Void> query;
        private final ArrayList<Combination> combinations = new ArrayList<>();
        private final Set<Ref<Object>> bindingSources =
            Collections.newSetFromMap(new IdentityHashMap<>());
        private final RelationshipQuery.Binding<Object, Void> weapon;
        private final RelationshipQuery.Binding<Object, Void> shield;
        private boolean allBindingsPresent = true;

        private CombinationSystem(
            Query<Object> sourceQuery,
            GenericRelationshipType<Object, Object, Void> type,
            RelationshipQuery<Object> targetQuery,
            RelationshipQuery.Binding<Object, Void> weapon,
            RelationshipQuery.Binding<Object, Void> shield
        ) {
            this.query = RelationshipQuery.of(sourceQuery, type, targetQuery);
            this.weapon = weapon;
            this.shield = shield;
        }

        @NonNullDecl
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
            allBindingsPresent &= result.has(weapon) && result.has(shield);
            bindingSources.add(result.getSource(weapon));
            bindingSources.add(result.getSource(shield));
            combinations.add(new Combination(result.getTarget(weapon), result.getTarget(shield)));
        }
    }

    private static final class DataBindingSystem extends RelationshipTickingSystem<Object, Void> {
        private final RelationshipQuery.Definition<Object, Void> query;
        private final RelationshipQuery.Binding<Object, Ownership> bindingQuery;
        private DataBinding binding;

        private DataBindingSystem(
            Query<Object> sourceQuery,
            GenericRelationshipType<Object, Object, Void> type,
            RelationshipQuery<Object> targetQuery,
            RelationshipQuery.Binding<Object, Ownership> bindingQuery
        ) {
            this.query = RelationshipQuery.of(sourceQuery, type, targetQuery);
            this.bindingQuery = bindingQuery;
        }

        @NonNullDecl
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
            binding = new DataBinding(
                result.getSource(bindingQuery),
                result.getTarget(bindingQuery),
                result.getData(bindingQuery)
            );
        }
    }

    private static final class NestedBindingSystem extends RelationshipTickingSystem<Object, Void> {
        private final RelationshipQuery.Definition<Object, Void> query;
        private final RelationshipQuery.Binding<Object, Void> weapon;
        private Store<Object> outerStore;
        private Store<Object> nestedStore;
        private Ref<Object> outerWeaponAfterNestedTick;

        private NestedBindingSystem(
            GenericRelationshipType<Object, Object, Void> follows,
            RelationshipQuery.Binding<Object, Void> weapon
        ) {
            this.query = RelationshipQuery.of(Query.any(), follows, weapon);
            this.weapon = weapon;
        }

        @NonNullDecl
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
            if (store != outerStore) {
                return;
            }
            Objects.requireNonNull(nestedStore, "nestedStore").tick(seconds);
            outerWeaponAfterNestedTick = result.getTarget(weapon);
        }
    }

    private static final class Shield implements Component<Object> {
        @Override
        public Shield clone() {
            return new Shield();
        }
    }

    private static final class Destination implements Component<Object> {
        @Override
        public Destination clone() {
            return new Destination();
        }
    }

    private record Callback(Ref<Object> source, Ref<Object> target) {
    }

    private record BoundCallback(Ref<Object> source, Ref<Object> target, Ref<Object> weapon) {
    }

    private record Combination(Ref<Object> weapon, Ref<Object> shield) {
    }

    private record FollowData(String distance) {
    }

    private record Ownership(String state) {
    }

    private record DataBinding(Ref<Object> source, Ref<Object> target, Ownership data) {
    }
    private static PersistenceIdentity<Object, Integer> identity() {
        var ids = new IdentityHashMap<Ref<Object>, Integer>();
        Function<Ref<Object>, Integer> assign = ignored -> ids.size();
        return TestPersistenceIdentity.of(ref -> ids.computeIfAbsent(ref, assign),
            Codec.INTEGER);
    }

    @Test
    void existsEvaluatesABridgeSourceAgainstANativeQueryOnItsTargets() {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("overworld");
            var entityTypes = entityTypes(fixture);
            var blockTypes = blockTypes(fixture);
            var anchoredTo = entityTypes.registerRelationship(blockTypes, RelationshipTraits.defaults());
            var follows = entityTypes.registerRelationship(RelationshipTraits.defaults());

            var anchoredToSolid = fixture.addEntity(world);
            var anchoredToLoose = fixture.addEntity(world);
            var companion = fixture.addEntity(world);
            relationships.addTarget(world.entityStore(), anchoredToSolid, follows, companion);
            relationships.addTarget(world.entityStore(), anchoredToLoose, follows, companion);
            relationships.addTarget(world.entityStore(), anchoredToSolid, anchoredTo, fixture.addSolidBlock(world));
            relationships.addTarget(world.entityStore(), anchoredToLoose, anchoredTo, fixture.addBlock(world));

            var definition = RelationshipQuery.of(
                RelationshipQuery.exists(anchoredTo, fixture.solidType()), follows);
            int solidMatches = relationships.fetch(anchoredToSolid, definition, RelationshipResults::size);
            int looseMatches = relationships.fetch(anchoredToLoose, definition, RelationshipResults::size);
            assertEquals(1, solidMatches);
            assertEquals(0, looseMatches);
        }
    }

    @Test
    void aBridgeExistsConditionRejectsARelationshipConditionOnItsTarget() {
        try (var fixture = new BridgeStoreFixture()) {
            fixture.addWorld("overworld");
            var entityTypes = entityTypes(fixture);
            var blockTypes = blockTypes(fixture);
            var anchoredTo = entityTypes.registerRelationship(blockTypes, RelationshipTraits.defaults().exclusive());
            var touches = blockTypes.registerRelationship(RelationshipTraits.defaults());
            Query<Blocks> nested = RelationshipQuery.exists(touches, Query.any());

            var direct = assertThrows(IllegalArgumentException.class,
                () -> RelationshipQuery.exists(anchoredTo, nested));
            assertTrue(direct.getMessage().contains("takes a native target query"), direct.getMessage());

            // a condition buried in a native combinator is the same nesting
            assertThrows(IllegalArgumentException.class,
                () -> RelationshipQuery.exists(anchoredTo, Query.and(fixture.solidType(), nested)));
            assertThrows(IllegalArgumentException.class,
                () -> RelationshipQuery.exists(anchoredTo, Query.not(nested)));

            // a native target query is accepted
            RelationshipQuery.exists(anchoredTo, Query.and(fixture.solidType(), Query.any()));
        }
    }

}
