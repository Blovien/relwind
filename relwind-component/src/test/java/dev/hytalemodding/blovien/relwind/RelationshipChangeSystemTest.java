/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Position;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
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
            var first = types.registerRelationship(StringBuilder.class, RelationshipTraits.defaults().exclusive());
            var second = types.registerRelationship(StringBuilder.class, RelationshipTraits.defaults().exclusive());
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
    @EnumSource(RelationshipTraits.SourceRetention.class)
    void mutationsEmitOnlyTheirLogicalEffects(RelationshipTraits.SourceRetention retention) {
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

    private static RelationshipTraits multipleWith(RelationshipTraits.SourceRetention retention) {
        var traits = RelationshipTraits.defaults();
        return retention == RelationshipTraits.SourceRetention.RETAIN ? traits.retainSourceStorage() : traits;
    }

    @Test
    void callbackEditNotificationUsesTheDataCurrentWhenItsTurnExecutes() {
        try (var fixture = new StoreFixture()) {
            var type = new RelationshipTypeRegistry<>(fixture.registry()).registerRelationship(
                StringBuilder.class,
                RelationshipTraits.defaults().exclusive());
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
            var type = types.registerRelationship(StringBuilder.class, RelationshipTraits.defaults());
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
}
