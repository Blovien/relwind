/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Position;
import com.hypixel.hytale.component.StoreFixture.Player;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.Ref;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RelationshipExecutionTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void storeIterationRejectsAnEnclosingRelationshipTraversalBeforeCallingTheConsumer() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = types.registerRelationship(RelationshipTraits.defaults());
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            relationships.addTarget(fixture.store(), source, follows, target);
            var query = RelationshipQuery.of(follows, Query.any());

            relationships.forEachTarget(source, follows, linked ->
                assertThrows(IllegalStateException.class,
                    () -> relationships.forEach(fixture.store(), query, (result, commands) ->
                        fail("Store iteration must not start inside a relationship traversal"))));
        }
    }

    @Test
    void nestedFetchesPreserveOuterBindingsWhenTheInnerReaderFails() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = types.registerRelationship(String.class, RelationshipTraits.defaults());
            var owns = types.registerRelationship(Integer.class, RelationshipTraits.defaults());
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            var item = fixture.addEntity(new Position(5, 6), null);
            relationships.addTarget(fixture.store(), source, follows, target, "near");
            relationships.addTarget(fixture.store(), target, owns, item, 7);
            var binding = RelationshipQuery.enumerate(owns, Query.any());
            var outerQuery = RelationshipQuery.of(follows, binding);
            var innerQuery = RelationshipQuery.of(owns, Query.any());
            var borrowed = new ArrayList<RelationshipResult<Object, Integer>>();
            var failure = new IllegalStateException("reader failed");

            relationships.fetch(source, outerQuery, outer -> {
                var row = outer.get(0);
                assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> relationships.fetch(target, innerQuery, inner -> {
                        assertSame(item, inner.get(0).getTarget());
                        assertEquals(7, inner.get(0).getData());
                        borrowed.add(inner.get(0));
                        throw failure;
                    })));
                assertThrows(NullPointerException.class, borrowed.getFirst()::getTarget);
                assertSame(source, row.getSource());
                assertSame(target, row.getTarget());
                assertEquals("near", row.getData());
                assertSame(item, row.getTarget(binding));
                assertEquals(7, row.getData(binding));
                return null;
            });

            relationships.fetch(target, innerQuery, results -> {
                assertEquals(1, results.size());
                assertSame(item, results.get(0).getTarget());
                return null;
            });
        }
    }

    @Test
    void fetchUsesCommittedStateWithoutConsumingTheEnclosingCommandBuffer() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = types.registerRelationship(RelationshipTraits.defaults());
            var source = fixture.addEntity(new Position(1, 2), new Player("source"));
            var first = fixture.addEntity(new Position(3, 4), null);
            var second = fixture.addEntity(new Position(5, 6), null);
            var query = RelationshipQuery.of(follows, Query.any());

            fixture.store().forEachChunk(fixture.playerType(), (chunk, commands) -> {
                relationships.addTarget(commands, source, follows, first);
                relationships.fetch(source, query, results -> {
                    assertTrue(results.isEmpty());
                    relationships.addTarget(commands, source, follows, second);
                    return null;
                });
                assertEquals(0, relationships.getTargetCount(source, follows));
                assertThrows(IllegalStateException.class,
                    () -> relationships.forEach(fixture.store(), query, (result, nested) -> fail()));
            });

            assertEquals(2, relationships.getTargetCount(source, follows));
        }
    }

    @Test
    void fetchAllowsImmediateChangesAfterItHasCollectedTheResults() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = types.registerRelationship(RelationshipTraits.defaults());
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            relationships.addTarget(fixture.store(), source, follows, target);
            var query = RelationshipQuery.of(follows, Query.any());

            relationships.fetch(source, query, results -> {
                relationships.removeTarget(fixture.store(), source, follows, target);
                fixture.store().putComponent(target, fixture.playerType(), new Player("updated"));
                assertEquals(1, results.size());
                assertSame(target, results.get(0).getTarget());
                return null;
            });

            assertEquals(0, relationships.getTargetCount(source, follows));
            assertNotNull(fixture.store().getComponent(target, fixture.playerType()));
        }
    }

    @Test
    void storeIterationExpiresItsBorrowedResultAfterACallbackFailure() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = types.registerRelationship(RelationshipTraits.defaults());
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            relationships.addTarget(fixture.store(), source, follows, target);
            var query = RelationshipQuery.of(follows, Query.any());
            var borrowed = new ArrayList<RelationshipResult<Object, Void>>();
            var failure = new IllegalStateException("callback failed");

            assertSame(failure, assertThrows(IllegalStateException.class,
                () -> relationships.forEach(fixture.store(), query, (result, commands) -> {
                    borrowed.add(result);
                    throw failure;
                })));

            assertThrows(NullPointerException.class, borrowed.getFirst()::getTarget);

            relationships.removeTarget(fixture.store(), source, follows, target);
            relationships.addTarget(fixture.store(), target, follows, source);
            var visited = new ArrayList<Ref<Object>>();

            relationships.forEach(fixture.store(), query, (result, commands) -> visited.add(result.getTarget()));

            assertEquals(List.of(source), visited);
        }
    }

    @Test
    void storeIterationFiltersSourcesAndTargetsAndDefersCommandsUntilIterationEnds() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = types.registerRelationship(RelationshipTraits.defaults());
            var source = fixture.addEntity(new Position(1, 2), new Player("source"));
            var rejectedSource = fixture.addEntity(new Position(2, 3), null);
            var target = fixture.addEntity(new Position(3, 4), new Player("target"));
            var rejectedTarget = fixture.addEntity(new Position(4, 5), null);
            relationships.addTarget(fixture.store(), source, follows, target);
            relationships.addTarget(fixture.store(), source, follows, rejectedTarget);
            relationships.addTarget(fixture.store(), rejectedSource, follows, target);
            var query = RelationshipQuery.of(fixture.playerType(), follows, fixture.playerType());
            var delivered = new ArrayList<RelationshipResult<Object, Void>>();

            relationships.forEach(fixture.store(), query, (result, commands) -> {
                assertSame(source, result.getSource());
                assertSame(target, result.getTarget());
                assertSame(fixture.store(), commands.getStore());
                delivered.add(result);
                assertThrows(IllegalStateException.class,
                    () -> relationships.removeTarget(fixture.store(), source, follows, target));
                relationships.removeTarget(commands, source, follows, target);
                assertEquals(2, relationships.getTargetCount(source, follows));
            });

            assertEquals(1, delivered.size());
            assertThrows(NullPointerException.class, delivered.getFirst()::getTarget);
            assertSame(rejectedTarget, relationships.getFirstTarget(source, follows));
            assertSame(target, relationships.getFirstTarget(rejectedSource, follows));
        }
    }

    @Test
    void reachableFetchReportsTruncationEvenWhenNoEntityMatches() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = types.registerRelationship(RelationshipTraits.defaults());
            var start = fixture.addEntity(new Position(1, 2), null);
            var middle = fixture.addEntity(new Position(2, 3), null);
            var end = fixture.addEntity(new Position(3, 4), new Player("end"));
            relationships.addTarget(fixture.store(), start, follows, middle);
            relationships.addTarget(fixture.store(), middle, follows, end);
            var query = RelationshipQuery.enumerateReachable(
                follows, RelationshipQuery.Direction.OUTGOING, 1, fixture.playerType());

            boolean truncated = relationships.fetch(start, query, results -> {
                assertTrue(results.isEmpty());
                return results.isTruncated();
            });

            assertTrue(truncated);
        }
    }

    @Test
    void fetchReturnsTheReadersValueAndExpiresItsBorrowedResults() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = types.registerRelationship(RelationshipTraits.defaults());
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            relationships.addTarget(fixture.store(), source, follows, target);
            var query = RelationshipQuery.of(follows, Query.any());
            var borrowed = new ArrayList<RelationshipResult<Object, Void>>();

            var found = relationships.fetch(source, query, results -> {
                assertEquals(1, results.size());
                borrowed.add(results.get(0));
                return results.get(0).getTarget();
            });

            assertSame(target, found);
            assertThrows(NullPointerException.class, borrowed.getFirst()::getTarget);
        }
    }
}
