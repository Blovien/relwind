/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.CancellableEcsEvent;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/// The links retained for a source that left the Store answer queries, events and add callbacks
/// from its holder, whether that holder is still in memory or was decoded from a save.
class RetainedHolderQueryTest {
    private static final Relationships relationships = new Relationships();

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void holderEventsFollowReachablePathsAndKeepUnavailablePathsUnknown(
        boolean persistent, boolean decoded
    ) {
        try (var fixture = new Fixture(persistent)) {
            var playerType = fixture.registry.registerComponent(StoreFixture.Player.class, StoreFixture.Player::new);
            var anchor = fixture.entity();
            var source = fixture.entity();
            var middle = fixture.entity();
            var end = fixture.entity();
            fixture.store.addComponent(end, playerType, new StoreFixture.Player("match"));
            relationships.addTarget(fixture.store, source, fixture.follows, middle);
            relationships.addTarget(fixture.store, middle, fixture.follows, end);
            relationships.addTarget(fixture.store, source, fixture.owns, anchor);
            var holder = fixture.park(source, decoded);
            var reachable = RelationshipQuery.reachable(fixture.follows,
                RelationshipQuery.Direction.OUTGOING, 2, playerType);
            var control = RelationshipQuery.of(Query.any(), fixture.owns);
            var positive = RelationshipQuery.of(reachable, fixture.owns);
            var negative = RelationshipQuery.of(RelationshipQuery.not(reachable), fixture.owns);

            assertHolderEvent(fixture, holder, control, anchor, 1);
            assertHolderEvent(fixture, holder, positive, anchor, 1);
            assertHolderEvent(fixture, holder, negative, anchor, 0);

            fixture.park(middle, false);

            assertHolderEvent(fixture, holder, control, anchor, 1);
            assertHolderEvent(fixture, holder, positive, anchor, 0);
            assertHolderEvent(fixture, holder, negative, anchor, 0);
        }
    }

    private static void assertHolderEvent(Fixture fixture, Holder<Object> holder,
        RelationshipQuery.Definition<Object, Void> query, Ref<Object> target, int expectedCalls) {
        var adapter = new Events(query);
        int entityCount = fixture.store.getEntityCount();
        adapter.callback = results -> {
            assertEquals(1, results.size());
            assertSame(holder, results.get(0).getHolder());
            assertNull(results.get(0).getSource());
            assertSame(target, results.get(0).getTarget());
        };
        fixture.registry.registerSystem(adapter);
        try {
            var event = new Event();
            fixture.store.invoke(holder, event);
            assertEquals(expectedCalls, adapter.calls);
            assertEquals(expectedCalls != 0, event.isCancelled());
            assertEquals(entityCount, fixture.store.getEntityCount(), "holder query must not admit an entity");
        } finally {
            fixture.registry.unregisterSystem(Events.class);
        }
    }

    @Test
    void cleanupOfAFullyUnavailableSourceDoesNotReplayOnLoad() {
        try (var fixture = new Fixture()) {
            var type = fixture.types.registerRelationship(
                "test:no-replay",
                RelationshipRules.single().retainOnDeactivation());
            var source = fixture.entity();
            var target = fixture.entity();
            var sourceId = fixture.ids.get(source);
            relationships.addTarget(fixture.store, source, type, target);
            var events = new ArrayList<String>();
            fixture.registry.registerSystem(new RelationshipChangeSystem<Object, Void>(type) {
                @Override
                protected void onRelationshipAdded(
                    LinkedEntity<Object> from,
                    LinkedEntity<Object> to,
                    Void data,
                    Store<Object> store,
                    CommandBuffer<Object> buffer
                ) { events.add("added"); }

                @Override
                protected void onRelationshipRemoved(
                    LinkedEntity<Object> from,
                    LinkedEntity<Object> to,
                    Void data,
                    Store<Object> store,
                    CommandBuffer<Object> buffer
                ) { events.add("removed"); }
            });
            fixture.tracker.onEntityUnloaded(sourceId, source,
                UnloadReason.DEACTIVATION);
            var holder = fixture.store.removeEntity(source, RemoveReason.UNLOAD);
            fixture.tracker.onEntityUnloaded(fixture.ids.get(target), target,
                UnloadReason.TRANSFER);
            assertFalse(fixture.tracker.contains(type, sourceId, fixture.ids.get(target)));
            assertTrue(events.isEmpty());
            var restored = fixture.store.addEntity(holder, AddReason.LOAD);
            fixture.ids.put(restored, sourceId);

            fixture.tracker.onEntityLoaded(sourceId, restored);

            assertNull(relationships.getFirstTarget(restored, type));
            assertNull(fixture.store.getComponent(restored, fixture.persistence.getComponentType()));
            assertTrue(events.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parkedAndDecodedHolderEventsEnumerateAccessibleTargets(boolean decoded) {
        try (var fixture = new Fixture()) {
            var source = fixture.entity();
            var target = fixture.entity();
            var nestedTarget = fixture.entity();
            var unavailable = fixture.entity();
            relationships.addTarget(fixture.store, source, fixture.follows, target);
            relationships.addTarget(fixture.store, source, fixture.follows, unavailable);
            relationships.addTarget(fixture.store, target, fixture.owns, nestedTarget);
            var holder = fixture.park(source, decoded);
            fixture.park(unavailable, false);
            var binding = RelationshipQuery.enumerate(fixture.owns, Query.any());
            var query = RelationshipQuery.of(Query.any(), fixture.follows, Query.and(binding));
            var event = new Event();
            var adapter = new Events(query);
            int count = fixture.store.getEntityCount();
            adapter.callback = results -> {
                assertEquals(count, fixture.store.getEntityCount(), "query must not admit the holder");
                assertEquals(1, results.size());
                var row = results.get(0);
                assertSame(holder, row.getHolder());
                assertNull(row.getSource(), "holder must not receive a fabricated Ref");
                assertSame(target, row.getTarget());
                assertSame(target, row.getSource(binding));
                assertSame(nestedTarget, row.getTarget(binding));
            };
            fixture.registry.registerSystem(adapter);

            fixture.store.invoke(holder, event);

            assertEquals(1, adapter.calls, "accessible retained association must deliver one event callback");
            assertSame(event, adapter.event);
            assertTrue(event.isCancelled());
            assertEquals(count, fixture.store.getEntityCount());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parkedAndDecodedHolderAddCallbacksRunBeforeAdmission(boolean decoded) {
        try (var fixture = new Fixture()) {
            var source = fixture.entity();
            var target = fixture.entity();
            var unavailable = fixture.entity();
            relationships.addTarget(fixture.store, source, fixture.follows, target);
            relationships.addTarget(fixture.store, source, fixture.follows, unavailable);
            var holder = fixture.park(source, decoded);
            fixture.park(unavailable, false);
            int count = fixture.store.getEntityCount();
            var adapter = new Adds(RelationshipQuery.of(Query.any(), fixture.follows, Query.any()));
            adapter.callback = results -> {
                assertEquals(count, fixture.store.getEntityCount(), "native add callback precedes admission");
                assertEquals(1, results.size());
                assertSame(holder, results.get(0).getHolder());
                assertNull(results.get(0).getSource());
                assertSame(target, results.get(0).getTarget());
            };
            fixture.registry.registerSystem(adapter);

            fixture.store.addEntity(holder, AddReason.LOAD);

            assertEquals(1, adapter.calls, "retained association must be visible before admission");
            assertEquals(count + 1, fixture.store.getEntityCount());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableSourceConditionStaysUnknownUnderNegation(boolean decoded) {
        try (var fixture = new Fixture()) {
            var source = fixture.entity();
            var target = fixture.entity();
            var unavailable = fixture.entity();
            relationships.addTarget(fixture.store, source, fixture.follows, target);
            relationships.addTarget(fixture.store, source, fixture.owns, unavailable);
            var holder = fixture.park(source, decoded);
            fixture.park(unavailable, false);
            var positive = new Events(RelationshipQuery.of(Query.any(), fixture.follows, Query.any()));
            fixture.registry.registerSystem(positive);

            fixture.store.invoke(holder, new Event());

            assertEquals(1, positive.calls, "positive control must establish holder evaluation before testing NOT");

            fixture.registry.unregisterSystem(Events.class);
            var negated = new Events(RelationshipQuery.of(
                Query.not(RelationshipQuery.exists(fixture.owns, Query.any())), fixture.follows, Query.any()));
            fixture.registry.registerSystem(negated);
            var event = new Event();

            fixture.store.invoke(holder, event);

            assertEquals(0, negated.calls, "NOT unknown must not become true");
            assertFalse(event.isCancelled());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nestedHolderBindingsKeepTheirCombinationsWithoutDuplicatingAlternatives(boolean decoded) {
        try (var fixture = new Fixture()) {
            var source = fixture.entity();
            var first = fixture.entity();
            var second = fixture.entity();
            var left = fixture.entity();
            var right = fixture.entity();
            relationships.addTarget(fixture.store, source, fixture.follows, first);
            relationships.addTarget(fixture.store, source, fixture.follows, second);
            relationships.addTarget(fixture.store, source, fixture.owns, left);
            relationships.addTarget(fixture.store, source, fixture.owns, right);
            var holder = fixture.park(source, decoded);
            var binding = RelationshipQuery.enumerate(fixture.owns, Query.any());
            var adapter = new Events(RelationshipQuery.of(Query.or(binding, binding), fixture.follows, Query.any()));
            adapter.callback = results -> {
                var combinations = new HashSet<List<Ref<Object>>>();
                for (var row : results) {
                    assertSame(holder, row.getHolder());
                    assertNull(row.getSource());
                    assertNull(row.getSource(binding));
                    assertTrue(combinations.add(List.of(row.getTarget(), row.getTarget(binding))));
                }
                assertEquals(Set.of(List.of(first, left), List.of(first, right),
                    List.of(second, left), List.of(second, right)), combinations);
            };
            fixture.registry.registerSystem(adapter);

            for (int i = 0; i < 3; i++) fixture.store.invoke(holder, new Event());

            assertEquals(3, adapter.calls);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        final Store<Object> store = registry.addStore(new Object(), EmptyResourceStorage.get());
        final Map<Ref<Object>, UUID> ids = new IdentityHashMap<>();
        final RelationshipInstallation<UUID> installation = RelationshipInstallation.on(registry, ids::get, Codec.UUID_BINARY)
            .persistence((s, r) -> { }, holder -> { }, id -> false)
            .install();
        final RelationshipTracker<Object, UUID> tracker = installation.tracker();
        final RelationshipTypeRegistry<Object> types = installation.types();
        final RelationshipPersistence<Object> persistence = installation.persistence();
        final RelationshipType<Object, Void> follows;
        final RelationshipType<Object, Void> owns;

        Fixture() {
            this(true);
        }

        Fixture(boolean persistent) {
            follows = type("follows", persistent);
            owns = type("owns", persistent);
        }

        RelationshipType<Object, Void> type(String name, boolean persistent) {
            var rules = RelationshipRules.multiple().retainOnTransfer().retainOnDeactivation();
            return persistent
                ? types.registerRelationship("relwind:retained-holder/" + name, rules)
                : types.registerRelationship(rules);
        }

        Ref<Object> entity() {
            var ref = Objects.requireNonNull(store.addEntity(registry.newHolder(), AddReason.SPAWN));
            var id = UUID.randomUUID();
            ids.put(ref, id);
            tracker.onEntityLoaded(id, ref);
            return ref;
        }

        Holder<Object> park(Ref<Object> ref, boolean decoded) {
            var document = decoded ? registry.serialize(store.copySerializableEntity(ref)) : null;
            var holder = store.removeEntity(ref, RemoveReason.UNLOAD);
            tracker.onEntityUnloaded(ids.get(ref), ref,
                UnloadReason.DEACTIVATION, holder);
            assertFalse(ref.isValid());
            assertNull(holder.getComponent(follows.getSourceType()));
            assertNull(holder.getComponent(owns.getSourceType()));
            if (!decoded) return holder;
            var restored = registry.deserialize(document);
            assertNotSame(holder, restored);
            assertNotNull(restored.getComponent(persistence.getComponentType()));
            assertNull(restored.getComponent(follows.getSourceType()));
            return restored;
        }

        public void close() {
            tracker.close();
            registry.shutdown();
        }
    }

    public static final class Event extends CancellableEcsEvent {}

    public static final class Events extends RelationshipHolderEventSystem<Object, Void, Event> {
        final RelationshipQuery.Definition<Object, Void> query;
        java.util.function.Consumer<RelationshipResults<Object, Void>> callback = rows -> {};
        int calls;
        Event event;
        Events(RelationshipQuery.Definition<Object, Void> query) { super(Event.class); this.query = query; }
        @NonNullDecl
        public RelationshipQuery.Definition<Object, Void> getQuery() { return query; }
        protected void handleRelationship(
            RelationshipResults<Object, Void> rows,
            Store<Object> store,
            CommandBuffer<Object> commands,
            Event event
        ) {
            calls++;
            this.event = event;
            callback.accept(rows);
            event.setCancelled(true);
        }
    }

    public static final class Adds extends RelationshipHolderSystem<Object, Void> {
        final RelationshipQuery.Definition<Object, Void> query;
        java.util.function.Consumer<RelationshipResults<Object, Void>> callback;
        int calls;
        Adds(RelationshipQuery.Definition<Object, Void> query) { this.query = query; }
        @NonNullDecl
        public RelationshipQuery.Definition<Object, Void> getQuery() { return query; }
        protected void onEntityAdd(RelationshipResults<Object, Void> rows, AddReason reason, Store<Object> store) {
            assertSame(AddReason.LOAD, reason);
            calls++;
            callback.accept(rows);
        }
        protected void onEntityRemoved(RelationshipResults<Object, Void> rows, RemoveReason reason, Store<Object> store) {}
    }
}
