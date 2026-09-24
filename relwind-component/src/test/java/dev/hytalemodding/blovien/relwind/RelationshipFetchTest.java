/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Player;
import com.hypixel.hytale.component.StoreFixture.Position;
import com.hypixel.hytale.component.query.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Fetch results are borrowed for one callback and reused by later calls on the same Store.
class RelationshipFetchTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void fetchReturnsZeroOrManyMatchesAndReusesItsBorrowedView() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = types.registerRelationship(FollowData.class, RelationshipTraits.defaults());
            var alice = fixture.addEntity(new Position(1, 2), null);
            var query = RelationshipQuery.of(follows, fixture.positionType());
            var batches = new ArrayList<RelationshipResults<Object, FollowData>>();
            var rows = new ArrayList<RelationshipResult<Object, FollowData>>();

            relationships.fetch(alice, query, empty -> {
                assertTrue(empty.isEmpty());
                batches.add(empty);
                return null;
            });

            var bob = fixture.addEntity(new Position(3, 4), null);
            var carol = fixture.addEntity(new Position(5, 6), null);
            relationships.addTarget(fixture.store(), alice, follows, bob, new FollowData("near"));
            relationships.addTarget(fixture.store(), alice, follows, carol, new FollowData("far"));

            relationships.fetch(alice, query, matches -> {
                assertSame(batches.getFirst(), matches);
                assertEquals(2, matches.size());
                assertEquals(Set.of(bob, carol), targets(matches));
                rows.add(matches.get(0));
                return null;
            });
            assertThrows(NullPointerException.class, rows.getFirst()::getTarget);

            relationships.fetch(alice, query, repeated -> {
                assertSame(batches.getFirst(), repeated);
                assertSame(rows.getFirst(), repeated.get(0));
                assertEquals(Set.of(bob, carol), targets(repeated));
                return null;
            });
        }
    }

    @Test
    void rowsExpireBeforeTheNextFetchReusesStorageForFewerMatches() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = types.registerRelationship(FollowData.class, RelationshipTraits.defaults());
            var alice = fixture.addEntity(new Position(1, 2), null);
            var bob = fixture.addEntity(new Position(3, 4), null);
            var carol = fixture.addEntity(new Position(5, 6), null);
            relationships.addTarget(fixture.store(), alice, follows, bob, new FollowData("near"));
            relationships.addTarget(fixture.store(), alice, follows, carol, new FollowData("far"));
            var query = RelationshipQuery.of(follows, fixture.positionType());
            var rows = new ArrayList<RelationshipResult<Object, FollowData>>();

            relationships.fetch(alice, query, matches -> {
                assertEquals(2, matches.size());
                rows.add(matches.get(0));
                rows.add(matches.get(1));
                return null;
            });

            assertThrows(NullPointerException.class, rows.get(0)::getTarget);
            assertThrows(NullPointerException.class, rows.get(0)::getSource);
            assertNull(rows.get(0).getData());
            assertThrows(NullPointerException.class, rows.get(1)::getTarget);
            assertThrows(NullPointerException.class, rows.get(1)::getSource);
            assertNull(rows.get(1).getData());

            relationships.removeTarget(fixture.store(), alice, follows, carol);
            relationships.fetch(alice, query, matches -> {
                assertEquals(1, matches.size());
                assertSame(alice, matches.get(0).getSource());
                assertSame(bob, matches.get(0).getTarget());
                return null;
            });
            relationships.removeTarget(fixture.store(), alice, follows, bob);
            relationships.fetch(alice, query, matches -> {
                assertTrue(matches.isEmpty());
                return null;
            });
            assertThrows(NullPointerException.class, rows.get(0)::getTarget);
            assertNull(rows.get(0).getData());
            assertThrows(NullPointerException.class, rows.get(1)::getTarget);
            assertNull(rows.get(1).getData());
        }
    }

    private static HashSet<Ref<Object>> targets(RelationshipResults<Object, FollowData> results) {
        var targets = new HashSet<Ref<Object>>();
        for (var result : results) targets.add(result.getTarget());
        return targets;
    }

    @ParameterizedTest
    @EnumSource(QueryShape.class)
    void fetchReadsTheLinkDataOfASingleTargetTypeUnderEachQueryShape(QueryShape shape) {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var mounted = types.registerRelationship(Saddle.class, RelationshipTraits.defaults().exclusive());
            var rider = fixture.addEntity(new Position(1, 2), null);
            var mount = fixture.addEntity(new Position(3, 4), new Player("mount"));
            var saddle = new Saddle(1);
            relationships.addTarget(fixture.store(), rider, mounted, mount, saddle);

            relationships.fetch(rider, query(shape, fixture, mounted), matches -> {
                assertEquals(1, matches.size());
                assertSame(mount, matches.get(0).getTarget());
                assertSame(saddle, matches.get(0).getData());
                return null;
            });
        }
    }

    private static RelationshipQuery.Definition<Object, Saddle> query(
        QueryShape shape,
        StoreFixture fixture,
        GenericRelationshipType<Object, Object, Saddle> mounted
    ) {
        return switch (shape) {
            case UNRESTRICTED_TARGET -> RelationshipQuery.of(mounted, Query.any());
            case COMPONENT_TARGET -> RelationshipQuery.of(mounted, fixture.positionType());
            case COMPONENT_SOURCE -> RelationshipQuery.of(Query.and(fixture.positionType()), mounted, Query.any());
        };
    }

    private enum QueryShape {
        UNRESTRICTED_TARGET,
        COMPONENT_TARGET,
        COMPONENT_SOURCE
    }

    @Test
    void reachableFetchReusesDepthAndTruncationStorageWithoutOverwritingNestedReaders() {
        try (var fixture = new StoreFixture()) {
            var chain = chain(fixture, "relwind:test/reachable-reuse", 3);
            var start = chain.get(0);
            var shortQuery = RelationshipQuery.enumerateReachable(chain.type(),
                RelationshipQuery.Direction.OUTGOING, 1, fixture.positionType());
            var fullQuery = RelationshipQuery.enumerateReachable(chain.type(),
                RelationshipQuery.Direction.OUTGOING, 3, fixture.positionType());
            var batches = new ArrayList<RelationshipResults<Object, Void>>();
            var rows = new ArrayList<RelationshipResult<Object, Void>>();

            relationships.fetch(start, shortQuery, bounded -> {
                assertEquals(1, bounded.size());
                assertTrue(bounded.isTruncated());
                batches.add(bounded);
                rows.add(bounded.get(0));
                return null;
            });
            relationships.fetch(start, fullQuery, complete -> {
                assertSame(batches.getFirst(), complete);
                assertSame(rows.getFirst(), complete.get(0));
                assertEquals(3, complete.size());
                assertFalse(complete.isTruncated());
                assertEquals(List.of(1, 2, 3), depths(complete));
                return null;
            });
            relationships.fetch(start, shortQuery, repeated -> {
                assertSame(batches.getFirst(), repeated);
                assertEquals(1, repeated.size());
                assertEquals(1, repeated.get(0).getDepth());
                assertTrue(repeated.isTruncated());
                relationships.fetch(start, fullQuery, other -> {
                    assertNotSame(repeated, other);
                    assertEquals(3, other.size());
                    return null;
                });
                assertEquals(1, repeated.size());
                assertSame(chain.get(1), repeated.get(0).getTarget());
                assertTrue(repeated.isTruncated());
                return null;
            });
        }
    }

    @Test
    void reachableFetchDiscardsResultsAfterAFailureAndDoesNotMarkTheStoreProcessing() {
        try (var fixture = new StoreFixture()) {
            var chain = chain(fixture, "relwind:test/reachable-failure", 3);
            var start = chain.get(0);
            var failure = new IllegalStateException("linked entity query failure");
            var calls = new int[1];
            var reachedQuery = new Query<Object>() {
                @Override
                public boolean test(Archetype<Object> archetype) {
                    fixture.store().assertWriteProcessing();
                    if (++calls[0] == 2) throw failure;
                    return fixture.positionType().test(archetype);
                }

                @Override
                public boolean requiresComponentType(ComponentType<Object, ?> componentType) {
                    return componentType == fixture.positionType();
                }

                @Override
                public void validateRegistry(ComponentRegistry<Object> registry) {
                    fixture.positionType().validateRegistry(registry);
                }

                @Override
                public void validate() {
                    fixture.positionType().validate();
                }
            };
            var query = RelationshipQuery.enumerateReachable(chain.type(),
                RelationshipQuery.Direction.OUTGOING, 3, fixture.positionType());
            relationships.fetch(start, query, results -> {
                assertEquals(3, results.size());
                return null;
            });

            var failing = RelationshipQuery.enumerateReachable(chain.type(),
                RelationshipQuery.Direction.OUTGOING, 3, reachedQuery);
            assertSame(failure, assertThrows(IllegalStateException.class,
                () -> relationships.fetch(start, failing, results -> {
                    throw new AssertionError("A failed evaluation must not call the reader");
                })));

            relationships.fetch(start, query, results -> {
                assertEquals(3, results.size());
                assertFalse(results.isTruncated());
                return null;
            });
        }
    }

    private static Chain chain(StoreFixture fixture, String id, int links) {
        var types = new RelationshipTypeRegistry<>(fixture.registry());
        var type = types.registerRelationship(id, RelationshipTraits.defaults());
        var entities = new ArrayList<Ref<Object>>(links + 1);
        entities.add(fixture.addEntity(new Position(0, 0), null));
        for (int i = 1; i <= links; i++) {
            entities.add(fixture.addEntity(new Position(i, 0), null));
            relationships.addTarget(fixture.store(), entities.get(i - 1), type, entities.get(i));
        }
        return new Chain(type, List.copyOf(entities));
    }

    private static List<Integer> depths(RelationshipResults<Object, Void> results) {
        var depths = new ArrayList<Integer>(results.size());
        for (var result : results) {
            depths.add(result.getDepth());
        }
        return depths;
    }

    private record Chain(GenericRelationshipType<Object, Object, Void> type, List<Ref<Object>> entities) {
        private Ref<Object> get(int index) {
            return entities.get(index);
        }
    }

    private record FollowData(String distance) {
    }

    private record Saddle(int seat) {
    }
}
