/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;


import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Position;
import com.hypixel.hytale.component.StoreFixture.Weapon;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.CancellableEcsEvent;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A relationship event system delivers every match of its query together with the event that triggered it,
/// and stays silent when nothing matches.
class RelationshipEventSystemTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void eventDeliversEveryMatchTogetherWithTheOriginalEvent() {
        try (var fixture = new StoreFixture()) {
            var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = RelationshipEventSystemTest.<Void>register(
                types,
                "relwind:test/follows",
                RelationshipRules.Cardinality.SINGLE_TARGET
            );
            var owns = RelationshipEventSystemTest.<Void>register(
                types,
                "relwind:test/owns",
                RelationshipRules.Cardinality.MULTIPLE_TARGETS
            );
            var weapon = RelationshipQuery.enumerate(owns, weaponType);
            var system = new RecordingEventSystem(
                Archetype.of(fixture.positionType()),
                follows,
                RelationshipQuery.and(weapon),
                weapon
            );
            fixture.registry().registerSystem(system);

            var alice = fixture.addEntity(new Position(1, 2), null);
            var bob = fixture.addEntity(new Position(3, 4), null);
            var sword = Objects.requireNonNull(
                fixture.store().addEntity(Archetype.of(weaponType), com.hypixel.hytale.component.AddReason.SPAWN)
            );
            var bow = Objects.requireNonNull(
                fixture.store().addEntity(Archetype.of(weaponType), com.hypixel.hytale.component.AddReason.SPAWN)
            );
            relationships.addTarget(fixture.store(), alice, follows, bob);
            relationships.addTarget(fixture.store(), bob, owns, sword);
            relationships.addTarget(fixture.store(), bob, owns, bow);
            var event = new TestEvent();
            assertTrue(system.getQuery().test(fixture.store().getArchetype(alice)));

            fixture.store().invoke(alice, event);

            assertEquals(1, system.calls);
            assertEquals(2, system.resultCount);
            assertSame(event, system.event);
            assertSame(alice, system.source);
            assertSame(bob, system.target);
            assertEquals(java.util.Set.of(sword, bow), system.weapons);
            assertTrue(event.isCancelled());
        }
    }

    @Test
    void anEventOnASourceWithoutATargetDoesNotInvokeTheCallback() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = RelationshipEventSystemTest.<Void>register(
                types,
                "relwind:test/follows",
                RelationshipRules.Cardinality.SINGLE_TARGET
            );
            var system = new RecordingEventSystem(
                Archetype.of(fixture.positionType()),
                follows,
                null,
                null
            );
            fixture.registry().registerSystem(system);
            var alice = fixture.addEntity(new Position(1, 2), null);

            fixture.store().invoke(alice, new TestEvent());

            assertEquals(0, system.calls);
        }
    }

    @Test
    void aCancelledEventDoesNotInvokeTheCallbackAndStaysCancelled() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = RelationshipEventSystemTest.<Void>register(
                types,
                "relwind:test/follows",
                RelationshipRules.Cardinality.SINGLE_TARGET
            );
            var system = new RecordingEventSystem(
                Archetype.of(fixture.positionType()),
                follows,
                null,
                null
            );
            fixture.registry().registerSystem(system);
            var alice = fixture.addEntity(new Position(1, 2), null);
            var bob = fixture.addEntity(new Position(3, 4), null);
            relationships.addTarget(fixture.store(), alice, follows, bob);
            var cancelled = new TestEvent();
            cancelled.setCancelled(true);

            fixture.store().invoke(alice, cancelled);

            assertEquals(0, system.calls);
            assertTrue(cancelled.isCancelled());
        }
    }

    @Test
    void linkedSourceWithRejectedTargetDoesNotInvokeCallback() {
        try (var fixture = new StoreFixture()) {
            var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = RelationshipEventSystemTest.<Void>register(
                types, "relwind:test/zero", RelationshipRules.Cardinality.SINGLE_TARGET);
            var system = new RecordingEventSystem(
                Archetype.of(fixture.positionType()), follows, RelationshipQuery.and(weaponType), null);
            fixture.registry().registerSystem(system);
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            relationships.addTarget(fixture.store(), source, follows, target);
            var event = new TestEvent();
            assertTrue(system.getQuery().test(fixture.store().getArchetype(source)));

            fixture.store().invoke(source, event);

            assertEquals(0, system.calls);
            org.junit.jupiter.api.Assertions.assertFalse(event.isCancelled());
        }
    }

    private static RelationshipType<Object, Void> register(
        RelationshipTypeRegistry<Object> types,
        String id,
        RelationshipRules.Cardinality cardinality
    ) {
        return types.registerRelationship(id, cardinality(cardinality));
    }

    public static final class RecordingEventSystem
        extends RelationshipEventSystem<Object, Void, TestEvent> {
        private final RelationshipQuery.Definition<Object, Void> query;
        private final RelationshipQuery.Binding<Object, Void> weapon;
        private final HashSet<Ref<Object>> weapons = new HashSet<>();
        private int calls;
        private int resultCount;
        private TestEvent event;
        private Ref<Object> source;
        private Ref<Object> target;

        private RecordingEventSystem(
            Archetype<Object> sourceQuery,
            GenericRelationshipType<Object, Object, Void> follows,
            RelationshipQuery<Object> targetQuery,
            RelationshipQuery.Binding<Object, Void> weapon
        ) {
            super(TestEvent.class);
            this.query = RelationshipQuery.of(sourceQuery, follows, targetQuery == null ? Query.any() : targetQuery);
            this.weapon = weapon;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Void> getQuery() {
            return query;
        }

        @Override
        protected void handleRelationship(
            RelationshipResults<Object, Void> results,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer,
            TestEvent event
        ) {
            calls++;
            this.event = event;
            event.setCancelled(true);
            resultCount = results.size();
            for (var result : results) {
                source = result.getSource();
                target = result.getTarget();
                if (weapon != null) {
                    weapons.add(Objects.requireNonNull(result.getTarget(weapon)));
                }
            }
        }
    }

    public static final class TestEvent extends CancellableEcsEvent {
    }

    private static RelationshipRules cardinality(RelationshipRules.Cardinality cardinality) {
        return cardinality == RelationshipRules.Cardinality.MULTIPLE_TARGETS
            ? RelationshipRules.multiple() : RelationshipRules.single();
    }
}
