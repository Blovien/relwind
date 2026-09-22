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
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A native entity addition or removal is delivered with the relationship results that match it.
/// The borrowed results expire before the next callback runs.
class RelationshipRefSystemTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void nativeEntityRemovalAndAdditionKeepTheirPhasesAndMatchingResults() {
        try (var fixture = new StoreFixture()) {
            var follows = register(new RelationshipTypeRegistry<>(fixture.registry()));
            var system = new RecordingRefSystem(follows);
            fixture.registry().registerSystem(system);
            var source = addEntity(fixture.store());
            var firstTarget = addEntity(fixture.store());
            var secondTarget = addEntity(fixture.store());
            relationships.addTarget(fixture.store(), source, follows, firstTarget);
            relationships.addTarget(fixture.store(), source, follows, secondTarget);
            var holder = fixture.registry().newHolder();

            fixture.store().removeEntity(source, holder, RemoveReason.UNLOAD);
            var restored = fixture.store().addEntity(holder, source, AddReason.LOAD);

            assertSame(source, restored);
            assertEquals(
                List.of(
                    new Delivery("remove", RemoveReason.UNLOAD, List.of(firstTarget, secondTarget)),
                    new Delivery("add", AddReason.LOAD, List.of(firstTarget, secondTarget))
                ),
                system.deliveries
            );
            assertEquals(0, system.borrowed.size());
            assertThrows(NullPointerException.class, system.borrowedResult::getSource);
        }
    }

    @Test
    void callbackFailureClearsBorrowedResultsBeforeTheNextNativeRemoval() {
        try (var fixture = new StoreFixture()) {
            var follows = register(new RelationshipTypeRegistry<>(fixture.registry()));
            var system = new RecordingRefSystem(follows);
            fixture.registry().registerSystem(system);
            var source = addEntity(fixture.store());
            var target = addEntity(fixture.store());
            relationships.addTarget(fixture.store(), source, follows, target);
            var holder = fixture.registry().newHolder();
            system.fail = true;

            assertThrows(CallbackFailure.class, () ->
                fixture.store().removeEntity(source, holder, RemoveReason.REMOVE)
            );

            assertTrue(source.isValid());
            assertEquals(0, system.borrowed.size());
            assertThrows(NullPointerException.class, system.borrowedResult::getSource);

            system.fail = false;
            fixture.store().removeEntity(source, holder, RemoveReason.REMOVE);

            assertFalse(source.isValid());
            assertEquals(2, system.deliveries.size());
            assertEquals(0, system.borrowed.size());
        }
    }

    @Test
    void relationshipTypeRemovalUnregistersTheDependentRefAdapter() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types);
            var system = new RecordingRefSystem(follows);
            fixture.registry().registerSystem(system);

            types.unregisterRelationship(follows);

            assertFalse(fixture.registry().hasSystem(system));
            assertEquals(1, system.unregistrationCount);
        }
    }

    private static Ref<Object> addEntity(Store<Object> store) {
        return Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
    }

    private static GenericRelationshipType<Object, Object, Void> register(RelationshipTypeRegistry<Object> types) {
        return types.registerRelationship(RelationshipRules.multiple());
    }

    private record Delivery(String phase, Object reason, List<Ref<Object>> targets) {
    }

    private static final class RecordingRefSystem extends RelationshipRefSystem<Object, Void> {
        private final RelationshipQuery.Definition<Object, Void> query;
        private final ArrayList<Delivery> deliveries = new ArrayList<>();
        private RelationshipResults<Object, Void> borrowed;
        private RelationshipResult<Object, Void> borrowedResult;
        private boolean fail;
        private int unregistrationCount;

        private RecordingRefSystem(GenericRelationshipType<Object, Object, Void> follows) {
            this.query = RelationshipQuery.of(Archetype.empty(), follows);
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Void> getQuery() {
            return query;
        }

        @Override
        protected void onEntityAdded(
            RelationshipResults<Object, Void> results,
            AddReason reason,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            record("add", reason, results);
        }

        @Override
        protected void onEntityRemove(
            RelationshipResults<Object, Void> results,
            RemoveReason reason,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            record("remove", reason, results);
        }

        @Override
        protected void onRelationshipSystemUnregistered() {
            unregistrationCount++;
        }

        private void record(String phase, Object reason, RelationshipResults<Object, Void> results) {
            borrowed = results;
            borrowedResult = results.get(0);
            var targets = new ArrayList<Ref<Object>>();
            for (var result : results) {
                targets.add(result.getTarget());
            }
            deliveries.add(new Delivery(phase, reason, List.copyOf(targets)));
            if (fail) {
                throw new CallbackFailure();
            }
        }
    }

    private static final class CallbackFailure extends RuntimeException {
    }
}
