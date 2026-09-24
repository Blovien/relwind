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
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.query.Query;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// A change to a component the query watches is delivered with the relationship results matching
/// at that moment.
class RelationshipRefChangeSystemTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void nativeComponentChangesKeepTheirPhasesArgumentsAndMatchingResults() {
        try (var fixture = new StoreFixture()) {
            var signalType = fixture.registry().registerComponent(Signal.class, Signal::new);
            var follows = register(new RelationshipTypeRegistry<>(fixture.registry()));
            var system = new RecordingChangeSystem(signalType, follows, fixture.playerType());
            fixture.registry().registerSystem(system);
            var source = addEntity(fixture.store(), Archetype.empty());
            var matchingTarget = addEntity(fixture.store(), Archetype.of(fixture.playerType()));
            var otherTarget = addEntity(fixture.store(), Archetype.empty());
            relationships.addTarget(fixture.store(), source, follows, matchingTarget);
            relationships.addTarget(fixture.store(), source, follows, otherTarget);

            fixture.store().addComponent(source, signalType, new Signal(1));
            fixture.store().replaceComponent(source, signalType, new Signal(2));
            fixture.store().removeComponent(source, signalType);
            fixture.store().addComponent(otherTarget, signalType, new Signal(3));

            assertEquals(
                List.of(
                    new Delivery("add", null, 1, matchingTarget),
                    new Delivery("set", 1, 2, matchingTarget),
                    new Delivery("remove", 2, null, matchingTarget)
                ),
                system.deliveries
            );
            assertEquals(0, system.borrowed.size());
        }
    }

    @Test
    void relationshipSourceComponentRemovalUsesTheSuppliedComponent() {
        try (var fixture = new StoreFixture()) {
            var follows = register(new RelationshipTypeRegistry<>(fixture.registry()));
            var system = new MarkerChangeSystem(follows);
            fixture.registry().registerSystem(system);
            var source = addEntity(fixture.store(), Archetype.empty());
            var target = addEntity(fixture.store(), Archetype.empty());

            relationships.addTarget(fixture.store(), source, follows, target);
            relationships.putTarget(fixture.store(), source, follows, target);
            relationships.removeTarget(fixture.store(), source, follows, target);

            assertEquals(
                List.of(
                    new MarkerDelivery("add", target),
                    new MarkerDelivery("set", target),
                    new MarkerDelivery("remove", target)
                ),
                system.deliveries
            );
        }
    }

    @Test
    void removalCycleUsesSourcePhaseFactsInConditionsAndBindings() {
        try (var fixture = new StoreFixture()) {
            var follows = register(new RelationshipTypeRegistry<>(fixture.registry()));
            var source = addEntity(fixture.store(), Archetype.empty());
            var target = addEntity(fixture.store(), Archetype.empty());
            relationships.addTarget(fixture.store(), source, follows, target);
            relationships.addTarget(fixture.store(), target, follows, source);
            var onward = RelationshipQuery.enumerate(follows, Archetype.empty());
            var returning = RelationshipQuery.enumerate(follows,
                RelationshipQuery.and(follows.getSourceType(), onward));
            var system = new MarkerChangeSystem(follows, RelationshipQuery.or(returning, returning));
            fixture.registry().registerSystem(system);

            relationships.removeTarget(fixture.store(), source, follows, target);

            assertEquals(List.of(new MarkerDelivery("remove", target)), system.deliveries);
        }
    }

    @Test
    void relationshipSourceRemovalEvaluatesTheTargetsCurrentState() {
        try (var fixture = new StoreFixture()) {
            var follows = register(new RelationshipTypeRegistry<>(fixture.registry()));
            var targetQuery = RelationshipQuery.and(fixture.playerType());
            var system = new MarkerChangeSystem(follows, targetQuery);
            fixture.registry().registerSystem(system);
            var source = addEntity(fixture.store(), Archetype.empty());
            var target = addEntity(fixture.store(), Archetype.of(fixture.playerType()));

            relationships.addTarget(fixture.store(), source, follows, target);
            fixture.store().removeComponent(target, fixture.playerType());
            relationships.removeTarget(fixture.store(), source, follows, target);

            assertEquals(List.of(new MarkerDelivery("add", target)), system.deliveries);
        }
    }

    @Test
    void callbackMutationRunsAfterTheCurrentNativeComponentChange() {
        try (var fixture = new StoreFixture()) {
            var signalType = fixture.registry().registerComponent(Signal.class, Signal::new);
            var follows = register(new RelationshipTypeRegistry<>(fixture.registry()));
            var source = addEntity(fixture.store(), Archetype.empty());
            var target = addEntity(fixture.store(), Archetype.empty());
            relationships.addTarget(fixture.store(), source, follows, target);
            var system = new RemovingChangeSystem(signalType, follows, source, target);
            fixture.registry().registerSystem(system);

            fixture.store().addComponent(source, signalType, new Signal(1));

            assertSame(target, system.targetDuringCallback);
            assertSame(target, system.targetAfterQueuedRemoval);
            assertEquals(1, system.resultCount);
            assertEquals(0, (int) relationships.fetch(source, RelationshipQuery.of(follows, Query.any()), results -> results.size()));
        }
    }

    @Test
    void nestedStoreCallbackKeepsTheOuterBorrowedResultsStable() {
        try (var fixture = new StoreFixture()) {
            var signalType = fixture.registry().registerComponent(Signal.class, Signal::new);
            var follows = register(new RelationshipTypeRegistry<>(fixture.registry()));
            var nestedStore = fixture.registry().addStore(new Object(), EmptyResourceStorage.get());
            var outerSource = addEntity(fixture.store(), Archetype.empty());
            var outerTarget = addEntity(fixture.store(), Archetype.empty());
            var nestedSource = addEntity(nestedStore, Archetype.empty());
            var nestedTarget = addEntity(nestedStore, Archetype.empty());
            relationships.addTarget(fixture.store(), outerSource, follows, outerTarget);
            relationships.addTarget(nestedStore, nestedSource, follows, nestedTarget);
            var system = new NestedChangeSystem(
                signalType,
                follows,
                outerSource,
                outerTarget,
                nestedStore,
                nestedSource,
                nestedTarget
            );
            fixture.registry().registerSystem(system);

            fixture.store().addComponent(outerSource, signalType, new Signal(1));

            assertEquals(List.of("outer-before", "nested", "outer-after"), system.phases);
            assertNotSame(system.outerResults, system.nestedResults);
            assertEquals(0, system.outerResults.size());
            assertEquals(0, system.nestedResults.size());
        }
    }

    @Test
    void callbackFailureClearsBorrowedResultsBeforeTheNextComponentChange() {
        try (var fixture = new StoreFixture()) {
            var signalType = fixture.registry().registerComponent(Signal.class, Signal::new);
            var follows = register(new RelationshipTypeRegistry<>(fixture.registry()));
            var source = addEntity(fixture.store(), Archetype.empty());
            var target = addEntity(fixture.store(), Archetype.empty());
            relationships.addTarget(fixture.store(), source, follows, target);
            var system = new FailingChangeSystem(signalType, follows);
            fixture.registry().registerSystem(system);

            assertThrows(
                CallbackFailure.class,
                () -> fixture.store().addComponent(source, signalType, new Signal(1))
            );
            assertEquals(0, system.borrowed.size());
            assertThrows(NullPointerException.class, system.borrowedResult::getSource);

            fixture.store().replaceComponent(source, signalType, new Signal(2));

            assertEquals(2, system.calls);
            assertEquals(List.of(
                new Delivery("add", null, 1, target),
                new Delivery("set", 1, 2, target)), system.deliveries);
            assertEquals(0, system.borrowed.size());
        }
    }

    @Test
    void watchedComponentRemovalUnregistersTheDependentChangeAdapter() {
        try (var fixture = new StoreFixture()) {
            var signalType = fixture.registry().registerComponent(Signal.class, Signal::new);
            var follows = register(new RelationshipTypeRegistry<>(fixture.registry()));
            var system = new RecordingChangeSystem(signalType, follows, Archetype.empty());
            fixture.registry().registerSystem(system);

            fixture.registry().unregisterComponent(signalType);

            assertFalse(fixture.registry().hasSystem(system));
            assertEquals(1, system.unregistrationCount);
        }
    }

    private static Ref<Object> addEntity(Store<Object> store, Archetype<Object> archetype) {
        return Objects.requireNonNull(store.addEntity(archetype, AddReason.SPAWN));
    }

    private static GenericRelationshipType<Object, Object, Void> register(RelationshipTypeRegistry<Object> types) {
        return types.registerRelationship(RelationshipTraits.defaults());
    }

    private record Delivery(String phase, Integer oldValue, Integer newValue, Ref<Object> target) {
    }

    private record MarkerDelivery(String phase, Ref<Object> target) {
    }

    private static final class RecordingChangeSystem
        extends RelationshipRefChangeSystem<Object, Void, Signal> {
        private final RelationshipQuery.ComponentChange<Object, Void, Signal> query;
        private final ComponentType<Object, Signal> signalType;
        private final ArrayList<Delivery> deliveries = new ArrayList<>();
        private RelationshipResults<Object, Void> borrowed;
        private int unregistrationCount;

        private RecordingChangeSystem(
            ComponentType<Object, Signal> signalType,
            GenericRelationshipType<Object, Object, Void> follows,
            Query<Object> targetQuery
        ) {
            this.query = RelationshipQuery.of(Archetype.empty(), follows, RelationshipQuery.and(targetQuery), signalType);
            this.signalType = signalType;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.ComponentChange<Object, Void, Signal> getQuery() {
            return query;
        }

        @Override
        protected void onRelationshipSystemUnregistered() {
            unregistrationCount++;
        }

        @Override
        protected void onComponentAdded(
            RelationshipResults<Object, Void> results,
            Signal component,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            record("add", null, component.value, results);
        }

        @Override
        protected void onComponentSet(
            RelationshipResults<Object, Void> results,
            Signal oldComponent,
            Signal newComponent,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            record("set", oldComponent.value, newComponent.value, results);
        }

        @Override
        protected void onComponentRemoved(
            RelationshipResults<Object, Void> results,
            Signal component,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            record("remove", component.value, null, results);
        }

        private void record(String phase, Integer oldValue, Integer newValue, RelationshipResults<Object, Void> results) {
            borrowed = results;
            assertEquals(1, results.size());
            deliveries.add(new Delivery(phase, oldValue, newValue, results.get(0).getTarget()));
        }
    }

    private static final class MarkerChangeSystem
        extends RelationshipRefChangeSystem<Object, Void, OutgoingLink<Object, Object>> {
        private final RelationshipQuery.ComponentChange<Object, Void, OutgoingLink<Object, Object>> query;
        private final GenericRelationshipType<Object, Object, Void> follows;
        private final ArrayList<MarkerDelivery> deliveries = new ArrayList<>();

        private MarkerChangeSystem(GenericRelationshipType<Object, Object, Void> follows) {
            this(follows, null);
        }

        private MarkerChangeSystem(GenericRelationshipType<Object, Object, Void> follows, RelationshipQuery<Object> targetQuery) {
            this.query = RelationshipQuery.of(
                Archetype.empty(), follows, targetQuery == null ? Query.any() : targetQuery, follows.getSourceType());
            this.follows = follows;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.ComponentChange<Object, Void, OutgoingLink<Object, Object>> getQuery() {
            return query;
        }

        @Override
        protected void onComponentAdded(
            RelationshipResults<Object, Void> results,
            OutgoingLink<Object, Object> component,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            record("add", results);
        }

        @Override
        protected void onComponentSet(
            RelationshipResults<Object, Void> results,
            OutgoingLink<Object, Object> oldComponent,
            OutgoingLink<Object, Object> newComponent,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            record("set", results);
        }

        @Override
        protected void onComponentRemoved(
            RelationshipResults<Object, Void> results,
            OutgoingLink<Object, Object> component,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            record("remove", results);
        }

        private void record(String phase, RelationshipResults<Object, Void> results) {
            assertEquals(1, results.size());
            deliveries.add(new MarkerDelivery(phase, results.get(0).getTarget()));
        }
    }

    private static final class RemovingChangeSystem extends RelationshipRefChangeSystem<Object, Void, Signal> {
        private final RelationshipQuery.ComponentChange<Object, Void, Signal> query;
        private final ComponentType<Object, Signal> signalType;
        private final GenericRelationshipType<Object, Object, Void> follows;
        private final Ref<Object> source;
        private final Ref<Object> target;
        private Ref<Object> targetDuringCallback;
        private Ref<Object> targetAfterQueuedRemoval;
        private int resultCount;

        private RemovingChangeSystem(
            ComponentType<Object, Signal> signalType,
            GenericRelationshipType<Object, Object, Void> follows,
            Ref<Object> source,
            Ref<Object> target
        ) {
            this.query = RelationshipQuery.of(Archetype.empty(), follows, Query.any(), signalType);
            this.signalType = signalType;
            this.follows = follows;
            this.source = source;
            this.target = target;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.ComponentChange<Object, Void, Signal> getQuery() {
            return query;
        }

        @Override
        protected void onComponentAdded(
            RelationshipResults<Object, Void> results,
            Signal component,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            resultCount = results.size();
            targetDuringCallback = relationships.getFirstTarget(source, follows);
            relationships.removeTarget(commandBuffer, source, follows, target);
            targetAfterQueuedRemoval = relationships.getFirstTarget(source, follows);
        }

        @Override
        protected void onComponentSet(
            RelationshipResults<Object, Void> results,
            Signal oldComponent,
            Signal newComponent,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
        }

        @Override
        protected void onComponentRemoved(
            RelationshipResults<Object, Void> results,
            Signal component,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
        }
    }

    private static final class NestedChangeSystem extends RelationshipRefChangeSystem<Object, Void, Signal> {
        private final RelationshipQuery.ComponentChange<Object, Void, Signal> query;
        private final ComponentType<Object, Signal> signalType;
        private final Ref<Object> outerSource;
        private final Ref<Object> outerTarget;
        private final Store<Object> nestedStore;
        private final Ref<Object> nestedSource;
        private final Ref<Object> nestedTarget;
        private final ArrayList<String> phases = new ArrayList<>();
        private RelationshipResults<Object, Void> outerResults;
        private RelationshipResults<Object, Void> nestedResults;

        private NestedChangeSystem(
            ComponentType<Object, Signal> signalType,
            GenericRelationshipType<Object, Object, Void> follows,
            Ref<Object> outerSource,
            Ref<Object> outerTarget,
            Store<Object> nestedStore,
            Ref<Object> nestedSource,
            Ref<Object> nestedTarget
        ) {
            this.query = RelationshipQuery.of(Archetype.empty(), follows, Query.any(), signalType);
            this.signalType = signalType;
            this.outerSource = outerSource;
            this.outerTarget = outerTarget;
            this.nestedStore = nestedStore;
            this.nestedSource = nestedSource;
            this.nestedTarget = nestedTarget;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.ComponentChange<Object, Void, Signal> getQuery() {
            return query;
        }

        @Override
        protected void onComponentAdded(
            RelationshipResults<Object, Void> results,
            Signal component,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            if (results.get(0).getSource() == outerSource) {
                outerResults = results;
                assertSame(outerTarget, results.get(0).getTarget());
                phases.add("outer-before");
                nestedStore.addComponent(nestedSource, signalType, new Signal(2));
                assertSame(outerTarget, results.get(0).getTarget());
                phases.add("outer-after");
            } else {
                nestedResults = results;
                assertSame(nestedSource, results.get(0).getSource());
                assertSame(nestedTarget, results.get(0).getTarget());
                phases.add("nested");
            }
        }

        @Override
        protected void onComponentSet(
            RelationshipResults<Object, Void> results,
            Signal oldComponent,
            Signal newComponent,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
        }

        @Override
        protected void onComponentRemoved(
            RelationshipResults<Object, Void> results,
            Signal component,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
        }
    }

    private static final class FailingChangeSystem extends RelationshipRefChangeSystem<Object, Void, Signal> {
        private final RelationshipQuery.ComponentChange<Object, Void, Signal> query;
        private final ComponentType<Object, Signal> signalType;
        private final List<Delivery> deliveries = new ArrayList<>();
        private RelationshipResults<Object, Void> borrowed;
        private RelationshipResult<Object, Void> borrowedResult;
        private int calls;

        private FailingChangeSystem(
            ComponentType<Object, Signal> signalType,
            GenericRelationshipType<Object, Object, Void> follows
        ) {
            this.query = RelationshipQuery.of(Archetype.empty(), follows, Query.any(), signalType);
            this.signalType = signalType;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.ComponentChange<Object, Void, Signal> getQuery() {
            return query;
        }

        @Override
        protected void onComponentAdded(
            RelationshipResults<Object, Void> results,
            Signal component,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            borrowed = results;
            borrowedResult = results.get(0);
            deliveries.add(new Delivery("add", null, component.value, results.get(0).getTarget()));
            calls++;
            throw new CallbackFailure();
        }

        @Override
        protected void onComponentSet(
            RelationshipResults<Object, Void> results,
            Signal oldComponent,
            Signal newComponent,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            borrowed = results;
            borrowedResult = results.get(0);
            deliveries.add(new Delivery("set", oldComponent.value, newComponent.value, results.get(0).getTarget()));
            calls++;
        }

        @Override
        protected void onComponentRemoved(
            RelationshipResults<Object, Void> results,
            Signal component,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
        }
    }

    private static final class Signal implements Component<Object> {
        private final int value;

        private Signal() {
            this(0);
        }

        private Signal(int value) {
            this.value = value;
        }

        @Override
        public Signal clone() {
            return new Signal(value);
        }
    }

    private static final class CallbackFailure extends RuntimeException {
    }
}
