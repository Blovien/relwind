/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Position;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.ComponentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// An observer of a relationship type hears every addition and removal in both directions, with
/// the link data that is current when its turn runs.
class RelationshipChangeSystemTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void unregisteringOneTypeKeepsTheSharedEventUntilItsLastObserverIsRemoved() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var first = types.registerRelationship(StringBuilder.class, RelationshipRules.single());
            var second = types.registerRelationship(StringBuilder.class, RelationshipRules.single());
            var firstObserver = new RelationshipChangeSystem<Object, StringBuilder>(first) { };
            var secondObserver = new RecordingObserver(second);
            fixture.registry().registerSystem(firstObserver);
            fixture.registry().registerSystem(secondObserver);
            var eventType = fixture.registry().getWorldEventTypeForClass(RelationshipChangeSystem.ChangeEvent.class);
            assertNotNull(eventType);

            types.unregisterRelationship(first);

            assertSame(eventType, fixture.registry().getWorldEventTypeForClass(RelationshipChangeSystem.ChangeEvent.class));

            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            relationships.addTarget(fixture.store(), source, second, target, new StringBuilder("data"));

            assertEquals(1, secondObserver.deliveries.size());

            types.unregisterRelationship(second);

            assertNull(fixture.registry().getWorldEventTypeForClass(RelationshipChangeSystem.ChangeEvent.class));
            assertThrows(IllegalStateException.class, eventType::validate);
        }
    }

    @ParameterizedTest
    @EnumSource(RelationshipRules.SourceRetention.class)
    void mutationsEmitOnlyTheirLogicalEffects(RelationshipRules.SourceRetention retention) {
        try (var fixture = new StoreFixture()) {
            var type = new RelationshipTypeRegistry<>(fixture.registry())
                .registerRelationship(StringBuilder.class, multipleWith(retention));
            var source = fixture.addEntity(new Position(1, 2), null);
            var first = fixture.addEntity(new Position(3, 4), null);
            var second = fixture.addEntity(new Position(5, 6), null);
            var missing = fixture.addEntity(new Position(7, 8), null);
            var initial = new StringBuilder("initial");
            var replacement = new StringBuilder("replacement");
            var observer = new RecordingObserver(type);
            fixture.registry().registerSystem(observer);

            relationships.putTarget(fixture.store(), source, type, first, initial);
            assertTrue((boolean) relationships.fetch(source, RelationshipQuery.of(type, fixture.playerType()), results -> results.isEmpty()));
            fixture.store().replaceComponent(source, type.getSourceType(), fixture.store().getComponent(source, type.getSourceType()));
            assertThrows(IllegalStateException.class, () -> relationships.addTarget(fixture.store(), source, type, first, replacement));
            assertThrows(IllegalStateException.class, () -> relationships.removeTarget(fixture.store(), source, type, missing));
            assertThrows(IllegalStateException.class, () -> relationships.retarget(fixture.store(), source, type, missing, second));
            relationships.tryRemoveTarget(fixture.store(), source, type, missing);
            relationships.retarget(fixture.store(), source, type, first, first);
            relationships.putTarget(fixture.store(), source, type, first, replacement);
            relationships.putTarget(fixture.store(), source, type, first, replacement);
            replacement.append(" edited");
            relationships.putTarget(fixture.store(), source, type, first, replacement);
            relationships.retarget(fixture.store(), source, type, first, second);
            relationships.removeTarget(fixture.store(), source, type, second);
            relationships.tryRemoveTarget(fixture.store(), source, type, second);

            assertEquals(List.of(
                new Delivery("added", source, null, first, null, initial),
                new Delivery("set", source, null, first, initial, replacement),
                new Delivery("set", source, null, first, replacement, replacement),
                new Delivery("set", source, null, first, replacement, replacement),
                new Delivery("retargeted", source, first, second, null, replacement),
                new Delivery("removed", source, null, second, null, replacement)), observer.deliveries);
        }
    }

    private static RelationshipRules multipleWith(RelationshipRules.SourceRetention retention) {
        var rules = RelationshipRules.multiple();
        return retention == RelationshipRules.SourceRetention.RETAIN ? rules.retainSourceStorage() : rules;
    }

    @Test
    void callbackEditNotificationUsesTheDataCurrentWhenItsTurnExecutes() {
        try (var fixture = new StoreFixture()) {
            var type = new RelationshipTypeRegistry<>(fixture.registry()).registerRelationship(
                StringBuilder.class,
                RelationshipRules.single());
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            var original = new StringBuilder("original");
            var replacement = new StringBuilder("replacement");
            var observer = new RecordingObserver(type);
            fixture.registry().registerSystem(observer);
            fixture.registry().registerSystem(new RelationshipChangeSystem<Object, StringBuilder>(type) {
                @Override
                public Set<Dependency<Object>> getDependencies() {
                    return Set.of(new SystemDependency<>(Order.BEFORE, RecordingObserver.class));
                }

                @Override
                protected void onRelationshipAdded(
                    LinkedEntity<Object> from,
                    LinkedEntity<Object> to,
                    StringBuilder data,
                    Store<Object> store,
                    CommandBuffer<Object> buffer
                ) {
                    data.append(" edited");
                    relationships.putTarget(buffer, source, type, target, replacement);
                    assertSame(original, relationships.getData(source, type, target));
                }
            });

            relationships.addTarget(fixture.store(), source, type, target, original);

            assertEquals(List.of(
                new Delivery("added", source, null, target, null, original),
                new Delivery("set", source, null, target, original, replacement)), observer.deliveries);
            assertEquals("original edited", original.toString());
        }
    }

    @Test
    void additionObservesBothDirectionsWithLiveData() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = types.registerRelationship(StringBuilder.class, RelationshipRules.multiple());
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            var data = new StringBuilder("initial");
            var deliveries = new ArrayList<String>();
            fixture.registry().registerSystem(new RelationshipChangeSystem<Object, StringBuilder>(type) {
                @Override
                protected void onRelationshipAdded(
                    LinkedEntity<Object> from,
                    LinkedEntity<Object> to,
                    StringBuilder current,
                    Store<Object> store,
                    CommandBuffer<Object> buffer
                ) {
                    assertSame(fixture.store(), store);
                    assertSame(store, buffer.getStore());
                    assertSame(source, from.reference());
                    assertSame(target, to.reference());
                    assertNull(from.identity());
                    assertNull(to.identity());
                    assertSame(data, current);
                    assertSame(target, relationships.getFirstTarget(source, type));
                    assertSame(data, relationships.getData(source, type, target));
                    assertEquals(1, relationships.getTargetCount(source, type));
                    assertEquals(1, relationships.getIncomingCount(target, type));
                    deliveries.add("added");
                }
            });

            relationships.addTarget(fixture.store(), source, type, target, data);

            assertEquals(List.of("added"), deliveries);
        }
    }

    @Test
    void dataComponentSetsAndRetargetsCarryTheInstanceTheSourceAlreadyHolds() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var seatType = fixture.registry().registerComponent(Seat.class, Seat::new);
            var mounted = types.registerRelationship(seatType, new SeatDataObserver(), RelationshipRules.single());
            var observer = new SeatObserver(mounted, seatType);
            fixture.registry().registerSystem(observer);
            var rider = fixture.addEntity(new Position(1, 2), null);
            var mount = fixture.addEntity(new Position(3, 4), null);
            var spareMount = fixture.addEntity(new Position(5, 6), null);
            var saddle = new Seat();
            var replacement = new Seat();

            relationships.addTarget(fixture.store(), rider, mounted, mount, saddle);
            relationships.putTarget(fixture.store(), rider, mounted, mount, replacement);
            relationships.retarget(fixture.store(), rider, mounted, mount, spareMount);
            fixture.store().replaceComponent(rider, seatType, saddle);

            assertEquals(List.of("added", "set", "retargeted", "set"), observer.kinds);
            assertEquals(List.of(saddle, replacement, replacement, saddle), observer.data);
            assertEquals(List.of(saddle, replacement), observer.oldData);
        }
    }

    private record Delivery(String kind, Ref<Object> source, Ref<Object> oldTarget, Ref<Object> target,
        StringBuilder oldData, StringBuilder data) { }

    private static final class RecordingObserver extends RelationshipChangeSystem<Object, StringBuilder> {
        private final List<Delivery> deliveries = new ArrayList<>();

        private RecordingObserver(RelationshipType<Object, StringBuilder> type) {
            super(type);
        }

        @Override
        protected void onRelationshipAdded(
            LinkedEntity<Object> source,
            LinkedEntity<Object> target,
            StringBuilder data,
            Store<Object> store,
            CommandBuffer<Object> buffer
        ) {
            assertLinked(store, source.reference(), target.reference(), data);
            deliveries.add(new Delivery("added", source.reference(), null, target.reference(), null, data));
        }

        @Override
        protected void onRelationshipSet(
            LinkedEntity<Object> source,
            LinkedEntity<Object> target,
            StringBuilder oldData,
            StringBuilder data,
            Store<Object> store,
            CommandBuffer<Object> buffer
        ) {
            assertLinked(store, source.reference(), target.reference(), data);
            deliveries.add(new Delivery("set", source.reference(), null, target.reference(), oldData, data));
        }

        @Override
        protected void onRelationshipRetargeted(
            LinkedEntity<Object> source,
            LinkedEntity<Object> oldTarget,
            LinkedEntity<Object> target,
            StringBuilder data,
            Store<Object> store,
            CommandBuffer<Object> buffer
        ) {
            assertLinked(store, source.reference(), target.reference(), data);
            assertEquals(0, relationships.getIncomingCount(oldTarget.reference(), getRelationshipType()));
            deliveries.add(new Delivery("retargeted", source.reference(), oldTarget.reference(), target.reference(), null, data));
        }

        @Override
        protected void onRelationshipRemoved(
            LinkedEntity<Object> source,
            LinkedEntity<Object> target,
            StringBuilder data,
            Store<Object> store,
            CommandBuffer<Object> buffer
        ) {
            assertEquals(0, relationships.getTargetCount(source.reference(), getRelationshipType()));
            assertEquals(0, relationships.getIncomingCount(target.reference(), getRelationshipType()));
            deliveries.add(new Delivery("removed", source.reference(), null, target.reference(), null, data));
        }

        private void assertLinked(Store<Object> store, Ref<Object> source, Ref<Object> target, StringBuilder data) {
            assertSame(target, relationships.getFirstTarget(source, getRelationshipType()));
            assertSame(data, relationships.getData(source, getRelationshipType(), target));
            assertEquals(1, relationships.getTargetCount(source, getRelationshipType()));
            assertEquals(1, relationships.getIncomingCount(target, getRelationshipType()));
        }
    }
    private static final class Seat implements Component<Object> {
        @Override
        public Seat clone() {
            return new Seat();
        }
    }

    /// Checks that the source already carries the delivered link data when the observer runs.
    private static final class SeatObserver extends RelationshipChangeSystem<Object, Seat> {
        private final ComponentType<Object, Seat> seatType;
        private final List<String> kinds = new ArrayList<>();
        private final List<Seat> data = new ArrayList<>();
        private final List<Seat> oldData = new ArrayList<>();

        private SeatObserver(
            RelationshipType<Object, Seat> type,
            ComponentType<Object, Seat> seatType
        ) {
            super(type);
            this.seatType = seatType;
        }

        @Override
        protected void onRelationshipAdded(
            LinkedEntity<Object> source,
            LinkedEntity<Object> target,
            Seat seat,
            Store<Object> store,
            CommandBuffer<Object> buffer
        ) {
            record("added", store, source.reference(), seat);
        }

        @Override
        protected void onRelationshipSet(
            LinkedEntity<Object> source,
            LinkedEntity<Object> target,
            Seat previous,
            Seat seat,
            Store<Object> store,
            CommandBuffer<Object> buffer
        ) {
            oldData.add(previous);
            record("set", store, source.reference(), seat);
        }

        @Override
        protected void onRelationshipRetargeted(
            LinkedEntity<Object> source,
            LinkedEntity<Object> oldTarget,
            LinkedEntity<Object> target,
            Seat seat,
            Store<Object> store,
            CommandBuffer<Object> buffer
        ) {
            record("retargeted", store, source.reference(), seat);
        }

        private void record(String kind, Store<Object> store, Ref<Object> source, Seat seat) {
            assertSame(seat, store.getComponent(source, seatType));
            kinds.add(kind);
            data.add(seat);
        }
    }

    private static final class SeatDataObserver extends RelationshipDataObserver<Object, Seat> {
    }
}
