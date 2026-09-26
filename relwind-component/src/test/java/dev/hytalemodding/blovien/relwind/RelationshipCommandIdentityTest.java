/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.BridgeStoreFixture;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Position;
import com.hypixel.hytale.component.system.WorldEventSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RelationshipCommandIdentityTest {
    private static final Relationships relationships = new Relationships();

    @ParameterizedTest
    @CsvSource({"false,2", "true,4"})
    void addThenRemoveReadsEachIdentityTwiceWithPersistenceAndEvents(boolean symmetric, int events) {
        try (var fixture = new Fixture(true, symmetric, false)) {

            relationships.addTarget(fixture.store(), fixture.source, fixture.type, fixture.target);
            relationships.removeTarget(fixture.store(), fixture.source, fixture.type, fixture.target);

            assertEquals(Map.of(fixture.source, 2, fixture.target, 2), fixture.reads);
            assertEquals(0, relationships.getTargetCount(fixture.source, fixture.type));
            assertEquals(events, fixture.observer.events.size());
            assertEquals("source", fixture.observer.events.getFirst().source.identity());
            assertEquals("target", fixture.observer.events.getFirst().target.identity());
        }
    }

    @ParameterizedTest
    @CsvSource({"ADD,false", "ADD,true", "PUT_NEW,false", "PUT_NEW,true", "SET,false", "SET,true",
        "REMOVE,false", "REMOVE,true", "TRY_REMOVE,false", "TRY_REMOVE,true", "RETARGET,false",
        "RETARGET,true", "CLEAR,false", "CLEAR,true", "REPLACE,false"})
    void loadedCommandReadsEachDistinctIdentityOnce(Command command, boolean symmetric) {
        try (var fixture = new Fixture(true, symmetric, command == Command.REPLACE)) {
            fixture.prepare(command);
            fixture.reads.clear();

            fixture.perform(command, fixture.store());

            assertEquals(fixture.expectedReads(command), fixture.reads);
        }
    }

    @ParameterizedTest
    @CsvSource({"ADD,false", "ADD,true", "SET,false", "SET,true", "REMOVE,false", "REMOVE,true",
        "RETARGET,false", "RETARGET,true", "CLEAR,false", "CLEAR,true"})
    void runtimeCommandReusesMissingIdentities(Command command, boolean symmetric) {
        try (var fixture = new Fixture(false, symmetric, false)) {
            fixture.identities.clear();
            fixture.prepare(command);
            fixture.reads.clear();

            fixture.perform(command, fixture.store());

            assertEquals(fixture.expectedReads(command), fixture.reads);
        }
    }

    @ParameterizedTest
    @CsvSource({"ADD,false", "ADD,true", "SET,false", "SET,true", "REMOVE,false", "REMOVE,true",
        "TRY_REMOVE,false", "TRY_REMOVE,true", "CLEAR,false", "CLEAR,true"})
    void aSelfLinkReadsItsIdentityOnce(Command command, boolean symmetric) {
        try (var fixture = new Fixture(true, symmetric, false)) {
            fixture.target = fixture.source;
            fixture.prepare(command);
            fixture.reads.clear();

            fixture.perform(command, fixture.store());

            assertEquals(Map.of(fixture.source, 1), fixture.reads);
        }
    }

    @ParameterizedTest
    @CsvSource({"true,false,false", "false,true,false", "false,false,true",
        "true,false,true", "false,true,true"})
    void retargetReusesTheIdentityOfAnEndpointThatIsAlsoTheSource(boolean oldIsSource,
        boolean newIsSource, boolean symmetric) {
        try (var fixture = new Fixture(true, symmetric, false)) {
            var oldTarget = fixture.endpoint(oldIsSource, fixture.target);
            var newTarget = fixture.endpoint(newIsSource, fixture.other);
            relationships.addTarget(fixture.store(), fixture.source, fixture.type, oldTarget);
            fixture.reads.clear();

            relationships.retarget(fixture.store(), fixture.source, fixture.type, oldTarget, newTarget);

            assertEquals(Fixture.once(fixture.source, oldTarget, newTarget), fixture.reads);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retargetToTheSameTargetReadsBothIdentitiesOnce(boolean symmetric) {
        try (var fixture = new Fixture(true, symmetric, false)) {
            relationships.addTarget(fixture.store(), fixture.source, fixture.type, fixture.target);
            fixture.reads.clear();

            relationships.retarget(fixture.store(), fixture.source, fixture.type, fixture.target, fixture.target);

            assertEquals(Map.of(fixture.source, 1, fixture.target, 1), fixture.reads);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clearSharesOneSourceIdentityAcrossLoadedTargetsAndASelfLink(boolean symmetric) {
        try (var fixture = new Fixture(true, symmetric, false)) {
            relationships.addTarget(fixture.store(), fixture.source, fixture.type, fixture.target);
            relationships.addTarget(fixture.store(), fixture.source, fixture.type, fixture.other);
            relationships.addTarget(fixture.store(), fixture.source, fixture.type, fixture.source);
            fixture.reads.clear();

            relationships.clearTargets(fixture.store(), fixture.source, fixture.type);

            assertEquals(Map.of(fixture.source, 1, fixture.target, 1, fixture.other, 1), fixture.reads);
            assertEquals(0, relationships.getTargetCount(fixture.source, fixture.type));
        }
    }

    @ParameterizedTest
    @EnumSource(Command.class)
    void queuedCommandsReadIdentitiesAtExecution(Command command) {
        try (var fixture = new Fixture(true, false, command == Command.REPLACE)) {
            fixture.prepare(command);
            fixture.reads.clear();

            fixture.perform(command, fixture.nativeStore.commandBuffer());
            assertEquals(Map.of(), fixture.reads);
            BridgeStoreFixture.consume(fixture.nativeStore.commandBuffer());

            assertEquals(fixture.expectedReads(command), fixture.reads);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aQueuedAddAnnouncesTheIdentitiesReadAtExecution(boolean symmetric) {
        try (var fixture = new Fixture(true, symmetric, false)) {
            relationships.addTarget(fixture.nativeStore.commandBuffer(), fixture.source, fixture.type, fixture.target);
            fixture.identities.put(fixture.source, "source at execution");
            fixture.identities.put(fixture.target, "target at execution");

            BridgeStoreFixture.consume(fixture.nativeStore.commandBuffer());

            assertEquals(Map.of(fixture.source, 1, fixture.target, 1), fixture.reads);
            assertEquals("source at execution", fixture.observer.events.getFirst().source.identity());
            assertEquals("target at execution", fixture.observer.events.getFirst().target.identity());
        }
    }

    @Test
    void bridgeAddAndRemoveReadEachInstallationsIdentityTwice() {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("world");
            var sourceTypes = new RelationshipTypeRegistry<>(fixture.entityRegistry());
            var targetTypes = new RelationshipTypeRegistry<>(fixture.blockRegistry());
            var reads = new IdentityHashMap<Ref<?>, Integer>();
            var sourceTracker = sourceTypes.installTracker(TestPersistenceIdentity.of(ref -> {
                reads.merge(ref, 1, Integer::sum);
                return "source";
            }, Codec.STRING), TestStoreRuntime.inline());
            var targetTracker = targetTypes.installTracker(new TestPersistenceIdentity<>((store, ref) -> {
                reads.merge(ref, 1, Integer::sum);
                return 7;
            }, Codec.INTEGER, "BLOCKS", (store, id) -> false, peer -> world.blockStore()),
                TestStoreRuntime.inline());
            sourceTypes.installPersistence(sourceTracker);
            targetTypes.installPersistence(targetTracker);
            var type = sourceTypes.registerRelationship("relwind:test/bridge-identity", targetTypes,
                RelationshipTraits.defaults());
            var source = fixture.addEntity(world);
            var target = fixture.addBlock(world);
            sourceTracker.onEntityLoaded("source", source);
            targetTracker.onEntityLoaded(7, target);
            reads.clear();

            relationships.addTarget(world.entityStore(), source, type, target);
            relationships.removeTarget(world.entityStore(), source, type, target);

            assertEquals(Map.of(source, 2, target, 2), reads);
            assertEquals(0, relationships.getTargetCount(source, type));
        }
    }

    private enum Command { ADD, PUT_NEW, SET, REMOVE, TRY_REMOVE, RETARGET, CLEAR, REPLACE }

    private static final class Fixture implements AutoCloseable {
        private final StoreFixture nativeStore = new StoreFixture();
        private final IdentityHashMap<Ref<Object>, String> identities = new IdentityHashMap<>();
        private final IdentityHashMap<Ref<Object>, Integer> reads = new IdentityHashMap<>();
        private final RelationshipTypeRegistry<Object> types = new RelationshipTypeRegistry<>(nativeStore.registry());
        private final RelationshipTracker<Object, String> tracker = types.installTracker(
            TestPersistenceIdentity.of(this::identity, Codec.STRING), TestStoreRuntime.inline());
        private final RelationshipType<Object, Void> type;
        private final Observer observer = new Observer();
        private final Ref<Object> source = add("source");
        private Ref<Object> target = add("target");
        private final Ref<Object> other = add("other");

        private Fixture(boolean persistent, boolean symmetric, boolean exclusive) {
            types.installPersistence(tracker);
            var traits = RelationshipTraits.defaults();
            if (symmetric) traits = traits.symmetric();
            if (exclusive) traits = traits.exclusive();
            type = persistent ? types.registerRelationship("relwind:test/identity", traits)
                : types.registerRelationship(traits);
            nativeStore.registry().registerSystem(observer);
            reads.clear();
        }

        private Ref<Object> endpoint(boolean self, Ref<Object> other) {
            return self ? source : other;
        }

        private Store<Object> store() {
            return nativeStore.store();
        }

        private Ref<Object> add(String identity) {
            var ref = nativeStore.addEntity(new Position(1, 1), null);
            identities.put(ref, identity);
            tracker.onEntityLoaded(identity, ref);
            return ref;
        }

        private String identity(Ref<Object> ref) {
            reads.merge(ref, 1, Integer::sum);
            return identities.get(ref);
        }

        private void prepare(Command command) {
            if (command != Command.ADD && command != Command.PUT_NEW) {
                relationships.addTarget(store(), source, type, target);
            }
            observer.events.clear();
        }

        private void perform(Command command, ComponentAccessor<Object> accessor) {
            switch (command) {
                case ADD -> relationships.addTarget(accessor, source, type, target);
                case PUT_NEW, SET -> relationships.putTarget(accessor, source, type, target);
                case REMOVE -> relationships.removeTarget(accessor, source, type, target);
                case TRY_REMOVE -> relationships.tryRemoveTarget(accessor, source, type, target);
                case RETARGET -> relationships.retarget(accessor, source, type, target, other);
                case CLEAR -> relationships.clearTargets(accessor, source, type);
                case REPLACE -> relationships.putTarget(accessor, source, type, other);
            }
        }

        private Map<Ref<Object>, Integer> expectedReads(Command command) {
            return command == Command.RETARGET || command == Command.REPLACE ? once(source, target, other)
                : once(source, target);
        }

        @SafeVarargs
        private static Map<Ref<Object>, Integer> once(Ref<Object>... refs) {
            var counts = new IdentityHashMap<Ref<Object>, Integer>();
            for (var ref : refs) counts.put(ref, 1);
            return counts;
        }

        @Override
        public void close() {
            tracker.close();
            nativeStore.close();
        }
    }

    private static final class Observer
        extends WorldEventSystem<Object, RelationshipChangeSystem.ChangeEvent<Object, Void>> {
        private final List<RelationshipChangeSystem.ChangeEvent<Object, Void>> events = new ArrayList<>();

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Observer() {
            super((Class) RelationshipChangeSystem.ChangeEvent.class);
        }

        @Override
        public void handle(Store<Object> store, CommandBuffer<Object> commands,
            RelationshipChangeSystem.ChangeEvent<Object, Void> event) {
            events.add(event);
        }
    }
}
