/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;


import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Position;
import com.hypixel.hytale.component.StoreFixture.Weapon;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.system.EcsEvent;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.AddReason;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Relwind registers the native adapters a relationship type needs with the type and removes them
/// when the type goes. A failure while unregistering leaves the type usable for a retry.
class RelationshipAdapterRegistrationTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void aSecondObserverOfTheSameConcreteClassIsRejectedWhenItSelectsAnotherRelationshipType() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "test:follows");
            var callbacks = new ArrayList<String>();
            fixture.registry().registerSystem(new LogicalObserver<>(follows, callbacks));
            var dataType = types.registerRelationship(String.class, RelationshipRules.single());

            assertThrows(IllegalArgumentException.class,
                () -> fixture.registry().registerSystem(new LogicalObserver<>(dataType, callbacks)));
        }
    }

    @Test
    void unregisteringATypeDropsOnlyItsOwnObserverAndFreesTheIdForAReplacement() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "test:follows");
            var owns = register(new RelationshipTypeRegistry<>(fixture.registry()), "test:owns");
            var callbacks = new ArrayList<String>();
            var selected = new LogicalObserver<Void>(follows, callbacks);
            var other = new OtherLogicalObserver(owns, callbacks);
            fixture.registry().registerSystem(selected);
            fixture.registry().registerSystem(other);
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            relationships.addTarget(fixture.store(), source, follows, target);
            relationships.addTarget(fixture.store(), source, owns, target);
            assertEquals(List.of("test:follows", "test:owns"), callbacks);

            types.unregisterRelationship(follows);

            assertFalse(fixture.registry().hasSystem(selected));
            assertTrue(fixture.registry().hasSystem(other));
            assertTrue(selected.unregistered);
            assertEquals(List.of("test:follows", "test:owns"), callbacks);

            relationships.putTarget(fixture.store(), source, owns, target);
            var replacement = register(types, "test:follows");
            fixture.registry().registerSystem(new LogicalObserver<>(replacement, callbacks));
            relationships.addTarget(fixture.store(), source, replacement, target);

            assertEquals(List.of("test:follows", "test:owns", "test:follows"), callbacks);
        }
    }

    private static class LogicalObserver<T> extends RelationshipChangeSystem<Object, T> {
        private final ArrayList<String> callbacks;
        private boolean unregistered;

        private LogicalObserver(RelationshipType<Object, T> type, ArrayList<String> callbacks) {
            super(type);
            this.callbacks = callbacks;
        }

        @Override
        protected void onRelationshipSystemUnregistered() {
            unregistered = true;
        }

        @Override
        protected void onRelationshipAdded(
            LinkedEntity<Object> source,
            LinkedEntity<Object> target,
            T data,
            Store<Object> store,
            CommandBuffer<Object> buffer
        ) {
            callbacks.add(getRelationshipType().getDescriptor().id());
        }

        @Override
        protected void onRelationshipRemoved(
            LinkedEntity<Object> source,
            LinkedEntity<Object> target,
            T data,
            Store<Object> store,
            CommandBuffer<Object> buffer
        ) {
            callbacks.add("removed");
        }
    }

    private static final class OtherLogicalObserver extends LogicalObserver<Void> {
        private OtherLogicalObserver(RelationshipType<Object, Void> type, ArrayList<String> callbacks) {
            super(type, callbacks);
        }
    }

    @Test
    void nativeRegistrationRejectsAnotherAdapterOfTheSameConcreteClass() {
        try (var fixture = new StoreFixture()) {
            var follows = register(new RelationshipTypeRegistry<>(fixture.registry()), "relwind:test/follows");
            var first = new DuplicateTickingAdapter(follows);
            fixture.registry().registerSystem(first);
            int registeredSystems = fixture.registry().getData().getSystemSize();

            assertThrows(
                IllegalArgumentException.class,
                () -> fixture.registry().registerSystem(new DuplicateTickingAdapter(follows))
            );

            assertTrue(fixture.registry().hasSystem(first));
            assertEquals(registeredSystems, fixture.registry().getData().getSystemSize());
        }
    }

    @Test
    void nativeDependencyOrdersConcreteAdaptersAheadOfRegistrationOrder() {
        try (var fixture = new StoreFixture()) {
            var follows = register(new RelationshipTypeRegistry<>(fixture.registry()), "relwind:test/follows");
            var callbacks = new ArrayList<String>();
            fixture.registry().registerSystem(new InitiallyRegisteredAdapter(follows, callbacks));
            fixture.registry().registerSystem(new OrderedBeforeAdapter(follows, callbacks));
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            relationships.addTarget(fixture.store(), source, follows, target);

            fixture.tick(0.05f);

            assertEquals(List.of("ordered-before", "initially-registered"), callbacks);
        }
    }

    @Test
    void unregisteringANestedRelationshipRemovesItsLinksAndDependentAdapters() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var nestedTypes = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/follows");
            var owns = register(types, "relwind:test/owns");
            var equips = register(nestedTypes, "relwind:test/equips");
            var targetQuery = RelationshipQuery.exists(
                owns,
                RelationshipQuery.exists(equips, Archetype.empty())
            );
            var ticking = new NestedTickingAdapter(follows, targetQuery);
            var event = new NestedEventAdapter(follows, targetQuery);
            fixture.registry().registerSystem(ticking);
            fixture.registry().registerSystem(event);

            var alice = fixture.addEntity(new Position(1, 2), null);
            var bob = fixture.addEntity(new Position(3, 4), null);
            var sword = fixture.addEntity(new Position(5, 6), null);
            var gem = fixture.addEntity(new Position(7, 8), null);
            relationships.addTarget(fixture.store(), alice, follows, bob);
            relationships.addTarget(fixture.store(), bob, owns, sword);
            relationships.addTarget(fixture.store(), sword, equips, gem);
            assertTrue(fixture.store().getArchetype(sword).contains(equips.getSourceType()));
            assertTrue(fixture.store().getArchetype(gem).contains(equips.getIncomingType()));

            nestedTypes.unregisterRelationship(equips);

            assertFalse(fixture.registry().hasSystem(ticking));
            assertFalse(fixture.registry().hasSystem(event));
            assertFalse(fixture.store().getArchetype(sword).contains(equips.getSourceType()));
            assertFalse(fixture.store().getArchetype(gem).contains(equips.getIncomingType()));
            assertThrows(IllegalStateException.class, equips.getSourceType()::validate);
            assertThrows(IllegalStateException.class, equips.getIncomingType()::validate);

            var replacement = register(nestedTypes, "relwind:test/equips");

            assertNotSame(equips.getSourceType(), replacement.getSourceType());
            assertTrue((boolean) relationships.fetch(sword, RelationshipQuery.of(replacement, Query.any()), results -> results.isEmpty()));

            relationships.addTarget(fixture.store(), sword, replacement, gem);

            assertSame(gem, relationships.fetch(sword, RelationshipQuery.of(replacement, Query.any()), results -> results.get(0).getTarget()));
            assertEquals(sword, relationships.fetch(bob, RelationshipQuery.of(owns, Query.any()), results -> results.get(0).getTarget()));
            assertEquals(bob, relationships.fetch(alice, RelationshipQuery.of(follows, Query.any()), results -> results.get(0).getTarget()));
        }
    }

    @Test
    void removingAComponentUsedOnlyByADeepTargetUnregistersDependentAdapters() {
        try (var fixture = new StoreFixture()) {
            ComponentType<Object, Weapon> weaponType = fixture.registry().registerComponent(
                Weapon.class,
                Weapon::new
            );
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/follows");
            var owns = register(types, "relwind:test/owns");
            var equips = register(types, "relwind:test/equips");
            var targetQuery = RelationshipQuery.exists(
                owns,
                RelationshipQuery.exists(equips, weaponType)
            );
            var ticking = new NestedTickingAdapter(follows, targetQuery);
            var event = new NestedEventAdapter(follows, targetQuery);
            fixture.registry().registerSystem(ticking);
            fixture.registry().registerSystem(event);

            fixture.registry().unregisterComponent(weaponType);

            assertFalse(fixture.registry().hasSystem(ticking));
            assertFalse(fixture.registry().hasSystem(event));
        }
    }

    @Test
    void throwingUnregistrationCallbackLeavesTheTypeUsableAndRetryCleansEveryStore() {
        var registry = new ComponentRegistry<Object>();
        try {
            var types = new RelationshipTypeRegistry<>(registry);
            var follows = register(types, "relwind:test/follows");
            var marker = follows.getSourceType();
            registry.registerSystem(new ThrowingUnregistrationAdapter(follows));
            var firstStore = registry.addStore(new Object(), EmptyResourceStorage.get());
            var secondStore = registry.addStore(new Object(), EmptyResourceStorage.get());
            var firstLink = addLink(firstStore, follows);
            var secondLink = addLink(secondStore, follows);

            assertThrows(CallbackFailure.class, () -> types.unregisterRelationship(follows));

            assertSame(marker, follows.getSourceType());
            marker.validate();
            assertEquals(1, (int) relationships.fetch(firstLink.source(), RelationshipQuery.of(follows, Query.any()), results -> results.size()));
            assertEquals(1, (int) relationships.fetch(secondLink.source(), RelationshipQuery.of(follows, Query.any()), results -> results.size()));

            types.unregisterRelationship(follows);

            assertFalse(firstStore.getArchetype(firstLink.source()).contains(marker));
            assertFalse(firstStore.getArchetype(firstLink.target()).contains(follows.getIncomingType()));
            assertFalse(secondStore.getArchetype(secondLink.source()).contains(marker));
            assertFalse(secondStore.getArchetype(secondLink.target()).contains(follows.getIncomingType()));
            assertThrows(IllegalStateException.class, marker::validate);
        } finally {
            registry.shutdown();
        }
    }

    private enum OtherTypeUse {
        SOURCE_MARKER,
        TARGET_MARKER,
        NESTED_RELATIONSHIP
    }

    private record AdapterQueries(Query<Object> source, RelationshipQuery<Object> target) {
    }

    @ParameterizedTest
    @EnumSource(OtherTypeUse.class)
    void aTypeAnAdapterSelectsThroughSurvivesCallbackFailureUntilRetry(OtherTypeUse use) {
        try (var fixture = new StoreFixture()) {
            var primaryTypes = new RelationshipTypeRegistry<>(fixture.registry());
            var otherTypes = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(primaryTypes, "relwind:test/follows");
            var other = register(otherTypes, "relwind:test/other");
            var marker = other.getSourceType();
            var link = addLink(fixture.store(), other);
            var primaryLink = addLink(fixture.store(), follows);
            var queries = linkThroughOtherType(use, fixture, primaryTypes, other, link, primaryLink);
            var adapter = new ThrowingUnregistrationAdapter(follows, queries.source(), queries.target());
            fixture.registry().registerSystem(adapter);
            assertTrue(adapter.getQuery().test(fixture.store().getArchetype(primaryLink.source())));
            assertEquals(
                1,
                (int) relationships.fetch(primaryLink.source(), RelationshipQuery.of(follows, queries.target()), results -> results.size())
            );

            assertThrows(CallbackFailure.class, () -> otherTypes.unregisterRelationship(other));

            assertFalse(fixture.registry().hasSystem(adapter));
            assertSame(marker, other.getSourceType());
            marker.validate();
            other.getIncomingType().validate();
            var replacement = register(otherTypes, "relwind:test/replacement");
            assertNotEquals(marker.getIndex(), replacement.getSourceType().getIndex());
            assertNotEquals(marker.getIndex(), replacement.getIncomingType().getIndex());

            assertSame(link.target(), relationships.fetch(link.source(), RelationshipQuery.of(other, Query.any()), results -> results.get(0).getTarget()));
            var added = addLink(fixture.store(), other);
            assertSame(added.target(), relationships.fetch(added.source(), RelationshipQuery.of(other, Query.any()), results -> results.get(0).getTarget()));

            otherTypes.unregisterRelationship(other);

            assertFalse(fixture.store().getArchetype(link.source()).contains(marker));
            assertFalse(fixture.store().getArchetype(link.target()).contains(other.getIncomingType()));
            assertFalse(fixture.store().getArchetype(added.source()).contains(marker));
            assertFalse(fixture.store().getArchetype(added.target()).contains(other.getIncomingType()));
            assertThrows(IllegalStateException.class, marker::validate);
            assertThrows(IllegalStateException.class, other.getIncomingType()::validate);
            assertSame(primaryLink.target(), relationships.fetch(primaryLink.source(), RelationshipQuery.of(follows, Query.any()), results -> results.get(0).getTarget()));
            var restored = register(otherTypes, "relwind:test/other");
            assertTrue((boolean) relationships.fetch(link.source(), RelationshipQuery.of(restored, Query.any()), results -> results.isEmpty()));

            relationships.addTarget(fixture.store(), link.source(), restored, link.target());

            assertSame(link.target(), relationships.fetch(link.source(), RelationshipQuery.of(restored, Query.any()), results -> results.get(0).getTarget()));
        }
    }

    private static AdapterQueries linkThroughOtherType(
        OtherTypeUse use,
        StoreFixture fixture,
        RelationshipTypeRegistry<Object> primaryTypes,
        RelationshipType<Object, Void> other,
        Link link,
        Link primaryLink
    ) {
        var marker = other.getSourceType();
        return switch (use) {
            case SOURCE_MARKER -> {
                relationships.addTarget(fixture.store(), primaryLink.source(), other, link.target());
                yield new AdapterQueries(Query.and(marker), RelationshipQuery.and(Archetype.empty()));
            }
            case TARGET_MARKER -> {
                relationships.addTarget(fixture.store(), primaryLink.target(), other, link.target());
                yield new AdapterQueries(Archetype.empty(), RelationshipQuery.and(Query.or(marker)));
            }
            case NESTED_RELATIONSHIP -> {
                var owns = register(primaryTypes, "relwind:test/owns");
                relationships.addTarget(fixture.store(), primaryLink.target(), owns, link.source());
                yield new AdapterQueries(
                    Archetype.empty(),
                    RelationshipQuery.exists(owns, RelationshipQuery.exists(other, Archetype.empty())));
            }
        };
    }

    @Test
    void registeringATypeFromAnUnregistrationCallbackIsRejectedBeforeNativeReentry() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/follows");
            var adapter = new ReentrantRegistrationAdapter(types, follows);
            fixture.registry().registerSystem(adapter);

            types.unregisterRelationship(follows);

            assertTrue(adapter.failure instanceof IllegalStateException);
            assertTrue(adapter.failure.getMessage().contains("relationship system callback"));
        }
    }

    @Test
    void unregisteringATypeFromAnUnregistrationCallbackIsRejectedBeforeNativeReentry() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "relwind:test/follows");
            var adapter = new ReentrantUnregistrationAdapter(types, follows);
            fixture.registry().registerSystem(adapter);

            types.unregisterRelationship(follows);

            assertTrue(adapter.failure instanceof IllegalStateException);
            assertTrue(adapter.failure.getMessage().contains("relationship system callback"));
        }
    }

    private static Link addLink(Store<Object> store, GenericRelationshipType<Object, Object, Void> type) {
        var source = Objects.requireNonNull(
            store.addEntity(Archetype.empty(), AddReason.SPAWN)
        );
        var target = Objects.requireNonNull(
            store.addEntity(Archetype.empty(), AddReason.SPAWN)
        );
        relationships.addTarget(store, source, type, target);
        return new Link(source, target);
    }

    private record Link(Ref<Object> source, Ref<Object> target) {
    }

    private static RelationshipType<Object, Void> register(RelationshipTypeRegistry<Object> types, String id) {
        return types.registerRelationship(id, RelationshipRules.single());
    }

    private abstract static class RecordingTickingAdapter extends RelationshipTickingSystem<Object, Void> {
        private final RelationshipQuery.Definition<Object, Void> query;
        private final String name;
        private final ArrayList<String> callbacks;

        private RecordingTickingAdapter(
            GenericRelationshipType<Object, Object, Void> follows,
            String name,
            ArrayList<String> callbacks
        ) {
            this.query = RelationshipQuery.of(Archetype.empty(), follows);
            this.name = name;
            this.callbacks = callbacks;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Void> getQuery() {
            return query;
        }

        @Override
        protected final void tickRelationship(
            float seconds,
            RelationshipResult<Object, Void> result,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            callbacks.add(name);
        }
    }

    private static final class InitiallyRegisteredAdapter extends RecordingTickingAdapter {
        private InitiallyRegisteredAdapter(GenericRelationshipType<Object, Object, Void> follows, ArrayList<String> callbacks) {
            super(follows, "initially-registered", callbacks);
        }
    }

    private static final class OrderedBeforeAdapter extends RecordingTickingAdapter {
        private OrderedBeforeAdapter(GenericRelationshipType<Object, Object, Void> follows, ArrayList<String> callbacks) {
            super(follows, "ordered-before", callbacks);
        }

        @Override
        public Set<Dependency<Object>> getDependencies() {
            return Set.of(new SystemDependency<>(Order.BEFORE, InitiallyRegisteredAdapter.class));
        }
    }

    private static final class DuplicateTickingAdapter extends RelationshipTickingSystem<Object, Void> {
        private final RelationshipQuery.Definition<Object, Void> query;
        private DuplicateTickingAdapter(GenericRelationshipType<Object, Object, Void> follows) {
            this.query = RelationshipQuery.of(Archetype.empty(), follows);
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Void> getQuery() {
            return query;
        }

        @Override
        protected void tickRelationship(
            float seconds,
            RelationshipResult<Object, Void> result,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
        }
    }

    public static final class ThrowingUnregistrationAdapter
        extends RelationshipEventSystem<Object, Void, TestEvent> {
        private final RelationshipQuery.Definition<Object, Void> query;
        private ThrowingUnregistrationAdapter(GenericRelationshipType<Object, Object, Void> follows) {
            this(follows, Archetype.empty(), null);
        }

        private ThrowingUnregistrationAdapter(
            GenericRelationshipType<Object, Object, Void> follows,
            Query<Object> sourceQuery,
            RelationshipQuery<Object> targetQuery
        ) {
            super(TestEvent.class);
            this.query = RelationshipQuery.of(sourceQuery, follows, targetQuery == null ? Query.any() : targetQuery);
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Void> getQuery() {
            return query;
        }

        @Override
        protected void onRelationshipSystemUnregistered() {
            throw new CallbackFailure();
        }

        @Override
        protected void handleRelationship(
            RelationshipResults<Object, Void> results,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer,
            TestEvent event
        ) {
        }
    }

    private static final class ReentrantRegistrationAdapter extends RelationshipTickingSystem<Object, Void> {
        private final RelationshipQuery.Definition<Object, Void> query;
        private final RelationshipTypeRegistry<Object> types;
        private RuntimeException failure;

        private ReentrantRegistrationAdapter(
            RelationshipTypeRegistry<Object> types,
            GenericRelationshipType<Object, Object, Void> follows
        ) {
            this.query = RelationshipQuery.of(Archetype.empty(), follows);
            this.types = types;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Void> getQuery() {
            return query;
        }

        @Override
        protected void onRelationshipSystemUnregistered() {
            try {
                register(types, "relwind:test/reentrant");
            } catch (RuntimeException rejected) {
                failure = rejected;
            }
        }

        @Override
        protected void tickRelationship(
            float seconds,
            RelationshipResult<Object, Void> result,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
        }
    }

    private static final class ReentrantUnregistrationAdapter extends RelationshipTickingSystem<Object, Void> {
        private final RelationshipQuery.Definition<Object, Void> query;
        private final RelationshipTypeRegistry<Object> types;
        private final GenericRelationshipType<Object, Object, Void> type;
        private RuntimeException failure;

        private ReentrantUnregistrationAdapter(
            RelationshipTypeRegistry<Object> types,
            GenericRelationshipType<Object, Object, Void> follows
        ) {
            this.query = RelationshipQuery.of(Archetype.empty(), follows);
            this.types = types;
            type = follows;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Void> getQuery() {
            return query;
        }

        @Override
        protected void onRelationshipSystemUnregistered() {
            try {
                types.unregisterRelationship(type);
            } catch (RuntimeException rejected) {
                failure = rejected;
            }
        }

        @Override
        protected void tickRelationship(
            float seconds,
            RelationshipResult<Object, Void> result,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
        }
    }

    private static final class NestedTickingAdapter extends RelationshipTickingSystem<Object, Void> {
        private final RelationshipQuery.Definition<Object, Void> query;
        private NestedTickingAdapter(
            GenericRelationshipType<Object, Object, Void> follows,
            RelationshipQuery<Object> targetQuery
        ) {
            this.query = RelationshipQuery.of(Archetype.empty(), follows, targetQuery);
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Void> getQuery() {
            return query;
        }

        @Override
        protected void tickRelationship(
            float seconds,
            RelationshipResult<Object, Void> result,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
        }
    }

    public static final class NestedEventAdapter extends RelationshipEventSystem<Object, Void, TestEvent> {
        private final RelationshipQuery.Definition<Object, Void> query;
        private NestedEventAdapter(GenericRelationshipType<Object, Object, Void> follows, RelationshipQuery<Object> targetQuery) {
            super(TestEvent.class);
            this.query = RelationshipQuery.of(Archetype.empty(), follows, targetQuery);
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
        }
    }

    public static final class TestEvent extends EcsEvent {
    }

    private static final class CallbackFailure extends RuntimeException {
    }
}
