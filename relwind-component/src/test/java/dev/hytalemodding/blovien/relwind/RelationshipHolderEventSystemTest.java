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
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Player;
import com.hypixel.hytale.component.StoreFixture.Position;
import com.hypixel.hytale.component.StoreFixture.Weapon;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.CancellableEcsEvent;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// An event on an entity that is not in the Store is answered from the holder carrying its
/// retained links, without admitting that holder.
class RelationshipHolderEventSystemTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void holderEventDeliversNestedMatchesTogetherAndPreservesTheNativeEvent() {
        try (var fixture = new StoreFixture()) {
            var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/holder-event-follows");
            var owns = register(types, "relwind:test/holder-event-owns");
            var weapon = RelationshipQuery.enumerate(owns, Query.and(weaponType));
            var system = new RecordingHolderEventSystem(
                fixture.positionType(),
                fixture.playerType(),
                follows,
                weapon
            );
            fixture.registry().registerSystem(system);
            var source = fixture.addEntity(new Position(1, 2), null);
            var firstTarget = fixture.addEntity(new Position(3, 4), new Player("first"));
            var secondTarget = fixture.addEntity(new Position(5, 6), new Player("second"));
            var sword = add(fixture, weaponType);
            var bow = add(fixture, weaponType);
            var wand = add(fixture, weaponType);
            relationships.addTarget(fixture.store(), source, follows, firstTarget);
            relationships.addTarget(fixture.store(), source, follows, secondTarget);
            relationships.addTarget(fixture.store(), firstTarget, owns, sword);
            relationships.addTarget(fixture.store(), firstTarget, owns, bow);
            relationships.addTarget(fixture.store(), secondTarget, owns, wand);
            var holder = fixture.store().removeEntity(source, RemoveReason.UNLOAD);
            var event = new TestEvent();

            fixture.store().invoke(holder, event);

            assertEquals(1, system.calls);
            assertSame(event, system.event);
            assertTrue(event.isCancelled());
            assertEquals(Set.of(
                new Match(firstTarget, sword),
                new Match(firstTarget, bow),
                new Match(secondTarget, wand)
            ), system.matches);
            assertSame(holder, system.holder);
            assertNull(system.source);
        }
    }

    @Test
    void theResultsLentToAHolderEventCallbackExpireWhenItReturns() {
        try (var fixture = new StoreFixture()) {
            var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/holder-event-follows");
            var owns = register(types, "relwind:test/holder-event-owns");
            var weapon = RelationshipQuery.enumerate(owns, Query.and(weaponType));
            var system = new RecordingHolderEventSystem(
                fixture.positionType(),
                fixture.playerType(),
                follows,
                weapon
            );
            fixture.registry().registerSystem(system);
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), new Player("target"));
            var sword = add(fixture, weaponType);
            relationships.addTarget(fixture.store(), source, follows, target);
            relationships.addTarget(fixture.store(), target, owns, sword);
            var holder = fixture.store().removeEntity(source, RemoveReason.UNLOAD);

            fixture.store().invoke(holder, new TestEvent());

            assertEquals(1, system.calls);
            assertEquals(0, system.borrowed.size());
            assertThrows(NullPointerException.class, system.borrowedResult::getHolder);
        }
    }

    @Test
    void aCancelledHolderEventAddsNoCallbackAfterADeliveredOne() {
        try (var fixture = new StoreFixture()) {
            var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/holder-event-follows");
            var owns = register(types, "relwind:test/holder-event-owns");
            var weapon = RelationshipQuery.enumerate(owns, Query.and(weaponType));
            var system = new RecordingHolderEventSystem(
                fixture.positionType(),
                fixture.playerType(),
                follows,
                weapon
            );
            fixture.registry().registerSystem(system);
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), new Player("target"));
            var sword = add(fixture, weaponType);
            relationships.addTarget(fixture.store(), source, follows, target);
            relationships.addTarget(fixture.store(), target, owns, sword);
            var holder = fixture.store().removeEntity(source, RemoveReason.UNLOAD);

            fixture.store().invoke(holder, new TestEvent());

            assertEquals(1, system.calls);

            var cancelled = new TestEvent();
            cancelled.setCancelled(true);
            fixture.store().invoke(holder, cancelled);

            assertEquals(1, system.calls);
        }
    }

    @Test
    void aHolderEventReadsTheLinkDataFromTheHolderThatCarriesTheOutgoingLink() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var mounted = types.registerRelationship(Saddle.class, RelationshipTraits.defaults().exclusive());
            var system = new MountedHolderEventSystem(fixture.positionType(), fixture.playerType(), mounted);
            fixture.registry().registerSystem(system);
            var rider = fixture.addEntity(new Position(1, 2), null);
            var mount = fixture.addEntity(new Position(3, 4), new Player("mount"));
            var saddle = new Saddle(1);
            relationships.addTarget(fixture.store(), rider, mounted, mount, saddle);
            var holder = fixture.store().removeEntity(rider, RemoveReason.UNLOAD);

            fixture.store().invoke(holder, new TestEvent());

            assertEquals(1, system.calls);
            assertSame(holder, system.holder);
            assertSame(mount, system.target);
            assertSame(saddle, system.data);
        }
    }

    @Test
    void inaccessibleNestedLinkedEntityRemainsUnknownUnderNativeNegation() {
        try (var fixture = new StoreFixture()) {
            var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/holder-unknown-follows");
            var owns = register(types, "relwind:test/holder-unknown-owns");
            var system = new UnknownHolderEventSystem(
                fixture.positionType(),
                follows,
                RelationshipQuery.exists(owns, Query.and(weaponType))
            );
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), new Player("target"));
            var unavailable = add(fixture, weaponType);
            relationships.addTarget(fixture.store(), source, follows, target);
            relationships.addTarget(fixture.store(), target, owns, unavailable);
            var holder = fixture.store().removeEntity(source, RemoveReason.UNLOAD);
            fixture.store().removeEntity(unavailable, RemoveReason.UNLOAD);
            var positive = new PlainHolderEventSystem(fixture.positionType(), follows);
            fixture.registry().registerSystem(positive);

            fixture.store().invoke(holder, new TestEvent());

            assertEquals(1, positive.calls, "positive control must establish holder evaluation before testing NOT");

            fixture.registry().unregisterSystem(PlainHolderEventSystem.class);
            fixture.registry().registerSystem(system);
            var event = new TestEvent();

            fixture.store().invoke(holder, event);

            assertEquals(0, system.calls);
            assertFalse(event.isCancelled());
        }
    }

    @Test
    void callbackFailureExpiresBorrowedResultsBeforeTheNextHolderEvent() {
        try (var fixture = new StoreFixture()) {
            var follows = register(
                new RelationshipTypeRegistry<>(fixture.registry()),
                "relwind:test/holder-event-failure"
            );
            var system = new ThrowOnceHolderEventSystem(fixture.positionType(), follows);
            fixture.registry().registerSystem(system);
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            relationships.addTarget(fixture.store(), source, follows, target);
            var holder = fixture.store().removeEntity(source, RemoveReason.UNLOAD);

            assertThrows(CallbackFailure.class, () -> fixture.store().invoke(holder, new TestEvent()));

            assertEquals(0, system.borrowed.size());
            assertThrows(NullPointerException.class, system.borrowedResult::getHolder);

            fixture.store().invoke(holder, new TestEvent());

            assertEquals(2, system.calls);
        }
    }

    private static Ref<Object> add(StoreFixture fixture, ComponentType<Object, Weapon> weaponType) {
        return Objects.requireNonNull(fixture.store().addEntity(Archetype.of(weaponType), AddReason.SPAWN));
    }

    private static GenericRelationshipType<Object, Object, Void> register(RelationshipTypeRegistry<Object> types, String id) {
        return types.registerRelationship(
            id,
            RelationshipTraits.defaults().retainOnTransfer().retainOnDeactivation());
    }

    public static final class RecordingHolderEventSystem
        extends RelationshipHolderEventSystem<Object, Void, TestEvent> {
        private final ComponentType<Object, Position> positionType;
        private final ComponentType<Object, Player> playerType;
        private final GenericRelationshipType<Object, Object, Void> follows;
        private final RelationshipQuery.Binding<Object, Void> weapon;
        private final HashSet<Match> matches = new HashSet<>();
        private int calls;
        private TestEvent event;
        private Holder<Object> holder;
        private Ref<Object> source;
        private RelationshipResults<Object, Void> borrowed;
        private RelationshipResult<Object, Void> borrowedResult;

        private RecordingHolderEventSystem(
            ComponentType<Object, Position> positionType,
            ComponentType<Object, Player> playerType,
            GenericRelationshipType<Object, Object, Void> follows,
            RelationshipQuery.Binding<Object, Void> weapon
        ) {
            super(TestEvent.class);
            this.positionType = positionType;
            this.playerType = playerType;
            this.follows = follows;
            this.weapon = weapon;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Void> getQuery() {
            return RelationshipQuery.of(
                Query.and(positionType),
                follows,
                Query.and(playerType, RelationshipQuery.and(weapon))
            );
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
            borrowed = results;
            borrowedResult = results.get(0);
            for (var result : results) {
                holder = result.getHolder();
                source = result.getSource();
                matches.add(new Match(result.getTarget(), Objects.requireNonNull(result.getTarget(weapon))));
            }
        }
    }

    public static final class UnknownHolderEventSystem
        extends RelationshipHolderEventSystem<Object, Void, TestEvent> {
        private final ComponentType<Object, Position> positionType;
        private final GenericRelationshipType<Object, Object, Void> follows;
        private final RelationshipQuery<Object> unknown;
        private int calls;

        private UnknownHolderEventSystem(
            ComponentType<Object, Position> positionType,
            GenericRelationshipType<Object, Object, Void> follows,
            RelationshipQuery<Object> unknown
        ) {
            super(TestEvent.class);
            this.positionType = positionType;
            this.follows = follows;
            this.unknown = unknown;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Void> getQuery() {
            return RelationshipQuery.of(
                Query.and(positionType),
                follows,
                Query.not(RelationshipQuery.and(unknown))
            );
        }

        @Override
        protected void handleRelationship(
            RelationshipResults<Object, Void> results,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer,
            TestEvent event
        ) {
            calls++;
        }
    }

    private static final class PlainHolderEventSystem
        extends RelationshipHolderEventSystem<Object, Void, TestEvent> {
        private final ComponentType<Object, Position> positionType;
        private final GenericRelationshipType<Object, Object, Void> follows;
        private int calls;

        private PlainHolderEventSystem(
            ComponentType<Object, Position> positionType,
            GenericRelationshipType<Object, Object, Void> follows
        ) {
            super(TestEvent.class);
            this.positionType = positionType;
            this.follows = follows;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Void> getQuery() {
            return RelationshipQuery.of(Query.and(positionType), follows, Query.any());
        }

        @Override
        protected void handleRelationship(
            RelationshipResults<Object, Void> results,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer,
            TestEvent event
        ) {
            calls++;
            event.setCancelled(true);
        }
    }

    public static final class ThrowOnceHolderEventSystem
        extends RelationshipHolderEventSystem<Object, Void, TestEvent> {
        private final ComponentType<Object, Position> positionType;
        private final GenericRelationshipType<Object, Object, Void> follows;
        private int calls;
        private RelationshipResults<Object, Void> borrowed;
        private RelationshipResult<Object, Void> borrowedResult;

        private ThrowOnceHolderEventSystem(
            ComponentType<Object, Position> positionType,
            GenericRelationshipType<Object, Object, Void> follows
        ) {
            super(TestEvent.class);
            this.positionType = positionType;
            this.follows = follows;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Void> getQuery() {
            return RelationshipQuery.of(Query.and(positionType), follows, Query.any());
        }

        @Override
        protected void handleRelationship(
            RelationshipResults<Object, Void> results,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer,
            TestEvent event
        ) {
            calls++;
            borrowed = results;
            borrowedResult = results.get(0);
            if (calls == 1) {
                throw new CallbackFailure();
            }
        }
    }

    public static final class MountedHolderEventSystem
        extends RelationshipHolderEventSystem<Object, Saddle, TestEvent> {
        private final ComponentType<Object, Position> positionType;
        private final ComponentType<Object, Player> playerType;
        private final GenericRelationshipType<Object, Object, Saddle> mounted;
        private int calls;
        private Holder<Object> holder;
        private Ref<Object> target;
        private Saddle data;

        private MountedHolderEventSystem(
            ComponentType<Object, Position> positionType,
            ComponentType<Object, Player> playerType,
            GenericRelationshipType<Object, Object, Saddle> mounted
        ) {
            super(TestEvent.class);
            this.positionType = positionType;
            this.playerType = playerType;
            this.mounted = mounted;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Saddle> getQuery() {
            return RelationshipQuery.of(Query.and(positionType), mounted, Query.and(playerType));
        }

        @Override
        protected void handleRelationship(
            RelationshipResults<Object, Saddle> results,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer,
            TestEvent event
        ) {
            calls++;
            for (var result : results) {
                holder = result.getHolder();
                target = result.getTarget();
                data = result.getData();
            }
        }
    }

    public static final class TestEvent extends CancellableEcsEvent {
    }

    private static final class CallbackFailure extends RuntimeException {
    }

    public record Saddle(int seat) {
    }

    private record Match(Ref<Object> target, Ref<Object> weapon) {
    }
}
