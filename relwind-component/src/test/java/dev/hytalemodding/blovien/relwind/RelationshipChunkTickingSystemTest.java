/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;


import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Player;
import com.hypixel.hytale.component.StoreFixture.Position;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.ArchetypeTickingSystem;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The chunk tick delivers every qualifying link of the sources in its chunks and lends the
/// callback one result that expires when the tick is over.
class RelationshipChunkTickingSystemTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void realStoreDeliversEveryQualifyingLinkFromSeveralSourceChunks() {
        try (var fixture = new StoreFixture()) {
            var kindType = fixture.registry().registerComponent(Kind.class, Kind::new);
            var eligibleType = fixture.registry().registerComponent(Eligible.class, Eligible::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(
                types,
                "relwind:test/chunk-follows",
                false
            );
            var owns = register(
                types,
                "relwind:test/chunk-owns",
                false
            );
            var owned = RelationshipQuery.enumerate(owns, eligibleType);
            var system = new RecordingChunkSystem(
                Archetype.of(fixture.positionType()),
                follows,
                RelationshipQuery.and(owned),
                owned
            );
            fixture.registry().registerSystem(system);

            var firstTarget = addEntity(fixture.store(), Archetype.empty());
            var sleepingTarget = addEntity(
                fixture.store(),
                Archetype.of(fixture.registry().getNonTickingComponentType())
            );
            var rejectedTarget = addEntity(fixture.store(), Archetype.empty());
            var firstOwned = addEntity(fixture.store(), Archetype.of(eligibleType));
            var secondOwned = addEntity(fixture.store(), Archetype.of(eligibleType));
            var sleepingOwned = addEntity(fixture.store(), Archetype.of(eligibleType));
            var rejectedOwned = addEntity(fixture.store(), Archetype.empty());
            var firstSource = addEntity(fixture.store(), Archetype.of(fixture.positionType()));
            var sameChunkSource = addEntity(fixture.store(), Archetype.of(fixture.positionType()));
            var playerSource = fixture.addEntity(new Position(1, 2), new Player("player"));
            var kindSource = addEntity(fixture.store(), Archetype.of(fixture.positionType(), kindType));
            var sleepingSource = addEntity(
                fixture.store(),
                Archetype.of(fixture.positionType(), fixture.registry().getNonTickingComponentType())
            );
            var rejectedSource = addEntity(fixture.store(), Archetype.of(kindType));

            relationships.addTarget(fixture.store(), firstSource, follows, firstTarget, new Payload(1));
            relationships.addTarget(fixture.store(), firstSource, follows, sleepingTarget, new Payload(2));
            relationships.addTarget(fixture.store(), firstSource, follows, rejectedTarget, new Payload(3));
            relationships.addTarget(fixture.store(), sameChunkSource, follows, firstTarget, new Payload(4));
            relationships.addTarget(fixture.store(), playerSource, follows, sleepingTarget, new Payload(5));
            relationships.addTarget(fixture.store(), kindSource, follows, firstTarget, new Payload(6));
            relationships.addTarget(fixture.store(), sleepingSource, follows, firstTarget, new Payload(7));
            relationships.addTarget(fixture.store(), rejectedSource, follows, firstTarget, new Payload(8));
            relationships.addTarget(fixture.store(), firstTarget, owns, firstOwned, new Payload(10));
            relationships.addTarget(fixture.store(), firstTarget, owns, secondOwned, new Payload(11));
            relationships.addTarget(fixture.store(), firstTarget, owns, rejectedOwned, new Payload(12));
            relationships.addTarget(fixture.store(), sleepingTarget, owns, sleepingOwned, new Payload(13));

            fixture.tick(0.05f);

            assertEquals(8, system.callbacks.size());
            assertEquals(1, system.resultIdentities.size());
            assertEquals(3, system.sourceChunks.size());
            assertTrue(system.sourceIndexes.contains(1));
            assertSame(fixture.callingThread(), system.callbackThread);
            assertEquals(Set.of(
                new Match(firstSource, firstTarget, firstOwned, 1),
                new Match(firstSource, firstTarget, secondOwned, 1),
                new Match(firstSource, sleepingTarget, sleepingOwned, 2),
                new Match(sameChunkSource, firstTarget, firstOwned, 4),
                new Match(sameChunkSource, firstTarget, secondOwned, 4),
                new Match(playerSource, sleepingTarget, sleepingOwned, 5),
                new Match(kindSource, firstTarget, firstOwned, 6),
                new Match(kindSource, firstTarget, secondOwned, 6)
            ), new HashSet<>(system.callbacks));
            assertFalse(system.callbacks.stream().anyMatch(callback -> callback.source() == sleepingSource));
            assertFalse(system.callbacks.stream().anyMatch(callback -> callback.source() == rejectedSource));
            assertFalse(system.callbacks.stream().anyMatch(callback -> callback.target() == rejectedTarget));
        }
    }

    @Test
    void concreteNativeOrderingObservesCommandsAfterTheChunkPhaseCommits() {
        try (var fixture = new StoreFixture()) {
            var follows = register(
                new RelationshipTypeRegistry<>(fixture.registry()),
                "relwind:test/chunk-ordering",
                true
            );
            var sequence = new ArrayList<String>();
            var adapter = new MutatingChunkSystem(follows, sequence);
            fixture.registry().registerSystem(adapter);
            fixture.registry().registerSystem(new AfterChunkSystem(follows, sequence));
            fixture.registry().registerSystem(new BeforeChunkSystem(follows, sequence));
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            relationships.addTarget(fixture.store(), source, follows, target, new Payload(1));

            fixture.tick(0.05f);

            assertEquals(List.of("before:1", "adapter:1", "after:2"), sequence);
            assertEquals(2, relationships.getData(source, follows, target).value());
        }
    }

    @Test
    void aNestedTickKeepsTheOuterResultStableAndExpiresBothBorrowedResults() {
        var registry = new com.hypixel.hytale.component.ComponentRegistry<Object>();
        try {
            var positionType = registry.registerComponent(Position.class, Position::new);
            var follows = register(
                new RelationshipTypeRegistry<>(registry),
                "relwind:test/chunk-borrowing",
                true
            );
            var system = new NestedChunkSystem(positionType, follows);
            registry.registerSystem(system);
            var outerStore = registry.addStore(new Object(), EmptyResourceStorage.get());
            var nestedStore = registry.addStore(new Object(), EmptyResourceStorage.get());
            var outerSource = addEntity(outerStore, Archetype.of(positionType));
            var outerTarget = addEntity(outerStore, Archetype.empty());
            var nestedSource = addEntity(nestedStore, Archetype.of(positionType));
            var nestedTarget = addEntity(nestedStore, Archetype.empty());
            relationships.addTarget(outerStore, outerSource, follows, outerTarget, new Payload(1));
            relationships.addTarget(nestedStore, nestedSource, follows, nestedTarget, new Payload(2));
            system.outerStore = outerStore;
            system.nestedStore = nestedStore;

            outerStore.tick(0.05f);

            assertSame(outerSource, system.outerSourceAfterNestedTick);
            assertSame(outerTarget, system.outerTargetAfterNestedTick);
            assertEquals(1, system.outerDataAfterNestedTick.value());
            assertThrows(NullPointerException.class, system.outerBorrowed::getSource);
            assertThrows(NullPointerException.class, system.nestedBorrowed::getSource);
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void aFailedCallbackExpiresItsBorrowedResultAndTheNextTickLendsTheSameOne() {
        var registry = new com.hypixel.hytale.component.ComponentRegistry<Object>();
        try {
            var positionType = registry.registerComponent(Position.class, Position::new);
            var follows = register(
                new RelationshipTypeRegistry<>(registry),
                "relwind:test/chunk-borrowing-failure",
                true
            );
            var system = new NestedChunkSystem(positionType, follows);
            registry.registerSystem(system);
            var outerStore = registry.addStore(new Object(), EmptyResourceStorage.get());
            var outerSource = addEntity(outerStore, Archetype.of(positionType));
            var outerTarget = addEntity(outerStore, Archetype.empty());
            relationships.addTarget(outerStore, outerSource, follows, outerTarget, new Payload(1));
            system.outerStore = outerStore;
            system.nested = false;
            system.fail = true;

            assertThrows(CallbackFailure.class, () -> outerStore.tick(0.05f));

            var failedBorrow = system.outerBorrowed;
            assertThrows(NullPointerException.class, failedBorrow::getSource);

            system.fail = false;
            outerStore.tick(0.05f);

            assertSame(failedBorrow, system.outerBorrowed);
            assertSame(outerSource, system.lastSource);
            assertThrows(NullPointerException.class, system.outerBorrowed::getSource);
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void relationshipTypeRemovalUnregistersTheChunkAdapterThroughItsLifecycle() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(
                types,
                "relwind:test/chunk-unregistration",
                true
            );
            var system = new LifecycleChunkSystem(follows);

            fixture.registry().registerSystem(system);

            assertEquals(1, system.registered);
            assertTrue(fixture.registry().hasSystem(system));

            types.unregisterRelationship(follows);

            assertEquals(1, system.unregistered);
            assertFalse(fixture.registry().hasSystem(system));
        }
    }

    private static GenericRelationshipType<Object, Object, Payload> register(
        RelationshipTypeRegistry<Object> types,
        String id,
        boolean exclusive
    ) {
        return types.registerRelationship(id, Payload.class, null, traits(exclusive));
    }

    private static Ref<Object> addEntity(Store<Object> store, Archetype<Object> archetype) {
        return Objects.requireNonNull(store.addEntity(archetype, AddReason.SPAWN));
    }

    private static final class RecordingChunkSystem extends RelationshipChunkTickingSystem<Object, Payload> {
        private final RelationshipQuery.Definition<Object, Payload> query;
        private final ArrayList<Match> callbacks = new ArrayList<>();
        private final Set<ArchetypeChunk<Object>> sourceChunks =
            Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<RelationshipResult<Object, Payload>> resultIdentities =
            Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Integer> sourceIndexes = new HashSet<>();
        private final RelationshipQuery.Binding<Object, Payload> owned;
        private Thread callbackThread;

        private RecordingChunkSystem(
            Query<Object> sourceQuery,
            GenericRelationshipType<Object, Object, Payload> type,
            RelationshipQuery<Object> targetQuery,
            RelationshipQuery.Binding<Object, Payload> owned
        ) {
            this.query = RelationshipQuery.of(sourceQuery, type, targetQuery);
            this.owned = owned;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Payload> getQuery() {
            return query;
        }

        @Override
        protected void tickRelationship(
            float seconds,
            int sourceIndex,
            ArchetypeChunk<Object> sourceChunk,
            RelationshipResult<Object, Payload> result,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            assertSame(result.getSource(), sourceChunk.getReferenceTo(sourceIndex));
            assertSame(store, result.getSource().getStore());
            assertSame(store, commandBuffer.getStore());
            callbacks.add(new Match(
                result.getSource(),
                result.getTarget(),
                result.getTarget(owned),
                result.getData().value()
            ));
            sourceChunks.add(sourceChunk);
            sourceIndexes.add(sourceIndex);
            resultIdentities.add(result);
            callbackThread = Thread.currentThread();
        }
    }

    private static final class MutatingChunkSystem extends RelationshipChunkTickingSystem<Object, Payload> {
        private final RelationshipQuery.Definition<Object, Payload> query;
        private final GenericRelationshipType<Object, Object, Payload> type;
        private final ArrayList<String> sequence;

        private MutatingChunkSystem(GenericRelationshipType<Object, Object, Payload> type, ArrayList<String> sequence) {
            this.query = RelationshipQuery.of(Archetype.of(type.getSourceType()), type);
            this.type = type;
            this.sequence = sequence;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Payload> getQuery() {
            return query;
        }

        @Override
        protected void tickRelationship(
            float seconds,
            int sourceIndex,
            ArchetypeChunk<Object> sourceChunk,
            RelationshipResult<Object, Payload> result,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            sequence.add("adapter:" + result.getData().value());
            relationships.putTarget(commandBuffer, result.getSource(), type, result.getTarget(), new Payload(2));
        }
    }

    public static final class BeforeChunkSystem extends ArchetypeTickingSystem<Object> {
        private final GenericRelationshipType<Object, Object, Payload> type;
        private final ArrayList<String> sequence;

        private BeforeChunkSystem(GenericRelationshipType<Object, Object, Payload> type, ArrayList<String> sequence) {
            this.type = type;
            this.sequence = sequence;
        }

        @Override
        public Query<Object> getQuery() {
            return Archetype.of(type.getSourceType());
        }

        @Override
        public Set<Dependency<Object>> getDependencies() {
            return Set.of(new SystemDependency<>(Order.BEFORE, MutatingChunkSystem.class));
        }

        @Override
        public void tick(
            float seconds,
            @Nonnull ArchetypeChunk<Object> chunk,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            var source = chunk.getReferenceTo(0);
            sequence.add("before:" + relationships.getData(source, type, firstTarget(type, chunk)).value());
        }
    }

    public static final class AfterChunkSystem extends ArchetypeTickingSystem<Object> {
        private final GenericRelationshipType<Object, Object, Payload> type;
        private final ArrayList<String> sequence;

        private AfterChunkSystem(GenericRelationshipType<Object, Object, Payload> type, ArrayList<String> sequence) {
            this.type = type;
            this.sequence = sequence;
        }

        @Override
        public Query<Object> getQuery() {
            return Archetype.of(type.getSourceType());
        }

        @Override
        public Set<Dependency<Object>> getDependencies() {
            return Set.of(new SystemDependency<>(Order.AFTER, MutatingChunkSystem.class));
        }

        @Override
        public void tick(
            float seconds,
            @Nonnull ArchetypeChunk<Object> chunk,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            var source = chunk.getReferenceTo(0);
            sequence.add("after:" + relationships.getData(source, type, firstTarget(type, chunk)).value());
        }
    }

    private static Ref<Object> firstTarget(GenericRelationshipType<Object, Object, Payload> type, ArchetypeChunk<Object> chunk) {
        return chunk.getComponent(0, type.getSourceType()).getTarget();
    }

    private static final class NestedChunkSystem extends RelationshipChunkTickingSystem<Object, Payload> {
        private final RelationshipQuery.Definition<Object, Payload> query;
        private Store<Object> outerStore;
        private Store<Object> nestedStore;
        private RelationshipResult<Object, Payload> outerBorrowed;
        private RelationshipResult<Object, Payload> nestedBorrowed;
        private Ref<Object> outerSourceAfterNestedTick;
        private Ref<Object> outerTargetAfterNestedTick;
        private Payload outerDataAfterNestedTick;
        private Ref<Object> lastSource;
        private boolean nested = true;
        private boolean fail;

        private NestedChunkSystem(
            ComponentType<Object, Position> positionType,
            GenericRelationshipType<Object, Object, Payload> type
        ) {
            this.query = RelationshipQuery.of(Archetype.of(positionType), type);
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Payload> getQuery() {
            return query;
        }

        @Override
        protected void tickRelationship(
            float seconds,
            int sourceIndex,
            ArchetypeChunk<Object> sourceChunk,
            RelationshipResult<Object, Payload> result,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            if (store == nestedStore) {
                nestedBorrowed = result;
                return;
            }
            outerBorrowed = result;
            lastSource = result.getSource();
            if (nested) {
                nested = false;
                Objects.requireNonNull(nestedStore).tick(seconds);
                outerSourceAfterNestedTick = result.getSource();
                outerTargetAfterNestedTick = result.getTarget();
                outerDataAfterNestedTick = result.getData();
            }
            if (fail) {
                throw new CallbackFailure();
            }
        }
    }

    private static final class LifecycleChunkSystem extends RelationshipChunkTickingSystem<Object, Payload> {
        private final RelationshipQuery.Definition<Object, Payload> query;
        private int registered;
        private int unregistered;

        private LifecycleChunkSystem(GenericRelationshipType<Object, Object, Payload> type) {
            this.query = RelationshipQuery.of(Archetype.empty(), type);
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Payload> getQuery() {
            return query;
        }

        @Override
        protected void onRelationshipSystemRegistered() {
            registered++;
        }

        @Override
        protected void onRelationshipSystemUnregistered() {
            unregistered++;
        }

        @Override
        protected void tickRelationship(
            float seconds,
            int sourceIndex,
            ArchetypeChunk<Object> sourceChunk,
            RelationshipResult<Object, Payload> result,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
        }
    }

    private record Payload(int value) {
    }

    private record Match(Ref<Object> source, Ref<Object> target, Ref<Object> owned, int value) {
    }

    private static final class Kind implements Component<Object> {
        @Override
        public Kind clone() {
            return new Kind();
        }
    }

    private static final class Eligible implements Component<Object> {
        @Override
        public Eligible clone() {
            return new Eligible();
        }
    }

    private static final class CallbackFailure extends RuntimeException {
    }

    private static RelationshipTraits traits(boolean exclusive) {
        return exclusive
            ? RelationshipTraits.defaults().exclusive() : RelationshipTraits.defaults();
    }
}
