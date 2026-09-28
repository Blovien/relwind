/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RelationshipMutationFailureTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void storageFailureReleasesProcessingAfterImmediateAddOrRemove(boolean remove) {
        try (var fixture = new StoreFixture()) {
            var relationships = new Relationships();
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = types.registerRelationship(RelationshipTraits.defaults().exclusive().retainSourceStorage());
            var failure = new FailOnSet(type.getSourceType());

            fixture.registry().registerSystem(failure);
            var source = fixture.addEntity(new StoreFixture.Position(1, 0), null);
            var target = fixture.addEntity(new StoreFixture.Position(2, 0), null);
            var nextSource = fixture.addEntity(new StoreFixture.Position(3, 0), null);

            relationships.addTarget(fixture.store(), source, type, target);
            if (!remove) {
                relationships.removeTarget(fixture.store(), source, type, target);
            }
            failure.armed = true;

            var thrown = assertThrows(IllegalStateException.class, () -> {
                if (remove) {
                    relationships.removeTarget(fixture.store(), source, type, target);
                } else {
                    relationships.addTarget(fixture.store(), source, type, target);
                }
            });
            assertSame(failure.exception, thrown);

            failure.armed = false;
            relationships.addTarget(fixture.store(), nextSource, type, target);

            assertSame(target, relationships.getFirstTarget(nextSource, type));
            assertEquals(remove ? 0 : 1, relationships.getTargetCount(source, type));
            assertEquals(remove ? 1 : 2, relationships.getIncomingCount(target, type));
        }
    }

    private static final class FailOnSet extends RefChangeSystem<Object, OutgoingLink<Object, Object>> {
        final ComponentType<Object, OutgoingLink<Object, Object>> type;
        final IllegalStateException exception = new IllegalStateException("deliberate component failure");
        boolean armed;

        private FailOnSet(ComponentType<Object, OutgoingLink<Object, Object>> type) {
            this.type = type;
        }

        @Override
        public ComponentType<Object, OutgoingLink<Object, Object>> componentType() {
            return type;
        }

        @Override
        public Query<Object> getQuery() {
            return type;
        }

        @Override
        public void onComponentAdded(Ref<Object> ref, OutgoingLink<Object, Object> component,
            Store<Object> store, CommandBuffer<Object> commands) {
        }

        @Override
        public void onComponentSet(Ref<Object> ref, OutgoingLink<Object, Object> previous,
            OutgoingLink<Object, Object> component, Store<Object> store, CommandBuffer<Object> commands) {
            if (armed) {
                throw exception;
            }
        }

        @Override
        public void onComponentRemoved(Ref<Object> ref, OutgoingLink<Object, Object> component,
            Store<Object> store, CommandBuffer<Object> commands) {
        }
    }
}
