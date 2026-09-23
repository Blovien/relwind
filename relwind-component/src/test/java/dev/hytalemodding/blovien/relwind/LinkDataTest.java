/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;


import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A read hands back the stored link data itself, not a copy. Only putTarget announces a field
/// change to observers.
class LinkDataTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void aReadHandsBackTheStoredDataInstance() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            var data = new MutableData(4);
            relationships.addTarget(fixture.store, source, fixture.type, target, data);

            var read = relationships.getData(source, fixture.type, target);

            assertSame(data, read);
            assertSame(data, relationships.getData(source, fixture.type, target));
        }
    }

    @Test
    void onlyThePutAnnouncesFieldEditsMadeThroughARead() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            var data = new MutableData(4);
            relationships.addTarget(fixture.store, source, fixture.type, target, data);
            fixture.changes.clear();

            var read = relationships.getData(source, fixture.type, target);
            read.distance = 5;

            assertEquals(0, fixture.changes.setSources.size());

            relationships.putTarget(fixture.store, source, fixture.type, target, read);

            assertEquals(List.of(source), fixture.changes.setSources);
        }
    }

    @Test
    void addingATargetTwiceIsRefusedAndKeepsTheStoredData() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            var initial = new MutableData(1);
            var ignored = new MutableData(2);
            relationships.addTarget(fixture.store, source, fixture.type, target, initial);
            assertEquals(List.of(source), fixture.changes.addedSources);
            fixture.changes.clear();

            assertThrows(IllegalStateException.class,
                () -> relationships.addTarget(fixture.store, source, fixture.type, target, ignored));

            assertSame(initial, relationships.getData(source, fixture.type, target));
            assertEquals(0, fixture.changes.total());
        }
    }

    @Test
    void retargetingALinkToItsOwnTargetAnnouncesNothing() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            relationships.addTarget(fixture.store, source, fixture.type, target, new MutableData(1));
            assertEquals(List.of(source), fixture.changes.addedSources);
            fixture.changes.clear();

            relationships.retarget(fixture.store, source, fixture.type, target, target);

            assertEquals(0, fixture.changes.total());
        }
    }

    @Test
    void removingATargetThatIsNotLinkedAnnouncesNothing() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            var absent = fixture.addEntity();
            relationships.addTarget(fixture.store, source, fixture.type, target, new MutableData(1));
            assertEquals(List.of(source), fixture.changes.addedSources);
            fixture.changes.clear();

            relationships.tryRemoveTarget(fixture.store, source, fixture.type, absent);

            assertEquals(0, fixture.changes.total());
        }
    }

    @Test
    void editingDataThatAPutReplacedLeavesTheLinkUnchanged() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            var initial = new MutableData(1);
            var replacement = new MutableData(2);
            relationships.addTarget(fixture.store, source, fixture.type, target, initial);
            relationships.putTarget(fixture.store, source, fixture.type, target, replacement);
            assertEquals(List.of(source), fixture.changes.setSources);
            fixture.changes.clear();

            initial.distance = 10;

            assertSame(replacement, relationships.getData(source, fixture.type, target));
            assertEquals(0, fixture.changes.total());
        }
    }

    @Test
    void theSourceLinkIsSetOnEveryChangeAndRemovedWithTheLastTarget() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var firstTarget = fixture.addEntity();
            var secondTarget = fixture.addEntity();
            var movedTarget = fixture.addEntity();
            relationships.addTarget(fixture.store, source, fixture.type, firstTarget, new MutableData(1));
            fixture.changes.clear();

            relationships.putTarget(fixture.store, source, fixture.type, secondTarget, new MutableData(3));

            assertEquals(List.of(source), fixture.changes.setSources);
            fixture.changes.clear();

            relationships.retarget(fixture.store, source, fixture.type, secondTarget, movedTarget);

            assertEquals(List.of(source), fixture.changes.setSources);
            fixture.changes.clear();

            relationships.removeTarget(fixture.store, source, fixture.type, movedTarget);

            assertEquals(List.of(source), fixture.changes.setSources);
            fixture.changes.clear();

            relationships.removeTarget(fixture.store, source, fixture.type, firstTarget);

            assertEquals(List.of(source), fixture.changes.removedSources);
        }
    }

    @Test
    void sharedMutableDataRequiresAPutForEveryAffectedLink() {
        try (var fixture = new Fixture()) {
            var firstSource = fixture.addEntity();
            var firstTarget = fixture.addEntity();
            var secondSource = fixture.addEntity();
            var secondTarget = fixture.addEntity();
            var shared = new MutableData(4);
            relationships.addTarget(fixture.store, firstSource, fixture.type, firstTarget, shared);
            relationships.addTarget(fixture.store, secondSource, fixture.type, secondTarget, shared);
            fixture.changes.clear();

            assertSame(shared, relationships.getData(secondSource, fixture.type, secondTarget));
            assertEquals(0, fixture.changes.total());

            relationships.putTarget(fixture.store, firstSource, fixture.type, firstTarget, shared);
            relationships.putTarget(fixture.store, secondSource, fixture.type, secondTarget, shared);

            assertEquals(List.of(firstSource, secondSource), fixture.changes.setSources);
        }
    }

    @Test
    void putInFinallyPreservesAndAnnouncesAPartialEdit() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            var data = new MutableData(4);
            relationships.addTarget(fixture.store, source, fixture.type, target, data);
            fixture.changes.clear();

            var failure = assertThrows(IllegalStateException.class, () -> {
                try {
                    data.distance = 5;
                    throw new IllegalStateException("plugin edit failed");
                } finally {
                    relationships.putTarget(fixture.store, source, fixture.type, target, data);
                }
            });

            assertEquals("plugin edit failed", failure.getMessage());
            assertEquals(5, relationships.getData(source, fixture.type, target).distance);
            assertEquals(List.of(source), fixture.changes.setSources);
        }
    }

    @Test
    void theSetCallbackCannotMutateTheGraphWhileTheChangeIsAnnounced() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            var data = new MutableData(4);
            relationships.addTarget(fixture.store, source, fixture.type, target, data);
            fixture.changes.clear();
            fixture.changes.removeDuringSet(source, target);

            relationships.putTarget(fixture.store, source, fixture.type, target, data);

            assertEquals(1, fixture.changes.targetCountAfterRejectedRemoval);
            assertEquals(1, fixture.changes.incomingCountAfterRejectedRemoval);
            assertEquals(1, relationships.getTargetCount(source, fixture.type));
            assertEquals(1, relationships.getIncomingCount(target, fixture.type));
        }
    }

    @Test
    void logicalMutationCallbacksObserveCompleteForwardReverseAndPayloadState() {
        var registry = new ComponentRegistry<Object>();
        try {
            var types = new RelationshipTypeRegistry<>(registry);
            var type = types.registerRelationship(
                "relwind:test/logical-notifications",
                MutableData.class,
                null,
                RelationshipRules.multiple().retainSourceStorage());
            var oracle = new CallbackStateOracle(type);
            registry.registerSystem(oracle);
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var source = addEntity(store);
            var first = addEntity(store);
            var second = addEntity(store);
            var moved = addEntity(store);
            var rejected = addEntity(store);
            oracle.observe(source, List.of(first, second, moved, rejected));
            var expected = new IdentityHashMap<Ref<Object>, MutableData>();
            var firstData = new MutableData(1);
            var secondData = new MutableData(2);
            var rejectedData = new MutableData(3);

            expected.put(first, firstData);
            oracle.expect(expected);
            relationships.addTarget(store, source, type, first, firstData);

            expected.put(second, secondData);
            oracle.expect(expected);
            oracle.setDuringNextCallback(rejected, rejectedData);
            relationships.addTarget(store, source, type, second, secondData);
            assertGraph(type, source, expected);

            var replacement = new MutableData(10);
            expected.put(first, replacement);
            oracle.expect(expected);
            relationships.putTarget(store, source, type, first, replacement);

            expected.remove(second);
            expected.put(moved, secondData);
            oracle.expect(expected);
            relationships.retarget(store, source, type, second, moved);

            expected.remove(moved);
            oracle.expect(expected);
            relationships.removeTarget(store, source, type, moved);

            expected.remove(first);
            oracle.expect(expected);
            relationships.removeTarget(store, source, type, first);

            oracle.assertComplete();
            assertGraph(type, source, expected);
            assertNotNull(store.getComponent(source, type.getSourceType()));
        } finally {
            registry.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(RelationshipRules.Cardinality.class)
    void putAnnouncesEveryDataChangeEvenForTheSameInstance(RelationshipRules.Cardinality cardinality) {
        try (var fixture = new SignalFixture(rules(cardinality))) {
            var source = fixture.add();
            var target = fixture.add();
            var initial = signalData(1);
            relationships.addTarget(fixture.store, source, fixture.type, target, initial);
            fixture.observer.sets.clear();
            int marks = fixture.persistenceMarks;

            initial.value = 2;
            relationships.putTarget(fixture.store, source, fixture.type, target, initial);

            assertEquals(1, fixture.observer.sets.size());
            var edited = fixture.observer.sets.get(0);
            assertSame(source, edited.source());
            assertSame(target, edited.target());
            assertSame(initial, edited.oldData());
            assertSame(initial, edited.data());
            assertEquals(marks + 1, fixture.persistenceMarks);

            var replacement = signalData(3);
            relationships.putTarget(fixture.store, source, fixture.type, target, replacement);

            assertEquals(2, fixture.observer.sets.size());
            var replaced = fixture.observer.sets.get(1);
            assertSame(initial, replaced.oldData());
            assertSame(replacement, replaced.data());
            assertSame(replacement, relationships.getData(source, fixture.type, target));
            assertEquals(marks + 2, fixture.persistenceMarks);
        }
    }

    private static RelationshipRules rules(RelationshipRules.Cardinality cardinality) {
        return cardinality == RelationshipRules.Cardinality.SINGLE_TARGET
            ? RelationshipRules.single()
            : RelationshipRules.multiple();
    }

    @Test
    void addWithoutDataAnnouncesTheAdditionWithoutData() {
        try (var fixture = new MountFixture()) {
            var walker = fixture.add();
            var mount = fixture.add();

            relationships.addTarget(fixture.store, walker, fixture.type, mount);

            assertNotNull(fixture.store.getComponent(walker, fixture.type.getSourceType()));
            assertEquals(List.of("added"), fixture.observer.kinds());
            assertNull(fixture.observer.deliveries.get(0).data());
        }
    }

    @Test
    void retargetKeepsTheLinkDataInstanceAndAnnouncesOneRetarget() {
        try (var fixture = new MountFixture()) {
            var rider = fixture.add();
            var mount = fixture.add();
            var spareMount = fixture.add();
            var saddle = new MountData(1);
            relationships.addTarget(fixture.store, rider, fixture.type, mount, saddle);
            fixture.observer.deliveries.clear();

            relationships.retarget(fixture.store, rider, fixture.type, mount, spareMount);

            assertSame(saddle, relationships.getData(rider, fixture.type, spareMount));
            assertNull(relationships.getData(rider, fixture.type, mount));
            assertEquals(List.of("retargeted"), fixture.observer.kinds());
            assertSame(saddle, fixture.observer.deliveries.get(0).data());
        }
    }

    @ParameterizedTest
    @EnumSource(Removal.class)
    void removingTheTargetDetachesTheOutgoingLinkAndAnnouncesItsData(Removal removal) {
        try (var fixture = new MountFixture()) {
            var rider = fixture.add();
            var mount = fixture.add();
            var saddle = new MountData(1);
            relationships.addTarget(fixture.store, rider, fixture.type, mount, saddle);
            fixture.observer.deliveries.clear();

            remove(removal, fixture, rider, mount);

            assertNull(fixture.store.getComponent(rider, fixture.type.getSourceType()));
            assertEquals(List.of("removed"), fixture.observer.kinds());
            assertSame(saddle, fixture.observer.deliveries.get(0).data());
        }
    }

    private enum Removal {
        REMOVE_TARGET,
        TRY_REMOVE_TARGET
    }

    private static void remove(Removal removal, MountFixture fixture, Ref<Object> rider, Ref<Object> mount) {
        if (removal == Removal.REMOVE_TARGET) {
            relationships.removeTarget(fixture.store, rider, fixture.type, mount);
        } else {
            relationships.tryRemoveTarget(fixture.store, rider, fixture.type, mount);
        }
    }

    @Test
    void retainedSourceStorageKeepsAnEmptyOutgoingLink() {
        try (var fixture = new MountFixture(RelationshipRules.single().retainSourceStorage())) {
            var rider = fixture.add();
            var mount = fixture.add();
            relationships.addTarget(fixture.store, rider, fixture.type, mount, new MountData(1));

            relationships.removeTarget(fixture.store, rider, fixture.type, mount);

            var outgoing = fixture.store.getComponent(rider, fixture.type.getSourceType());
            assertNotNull(outgoing);
            assertEquals(0, outgoing.size());
        }
    }

    private static SignalData signalData(int value) {
        var data = new SignalData();
        data.value = value;
        return data;
    }

    private static Ref<Object> addEntity(Store<Object> store) {
        return Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
    }

    private static void assertGraph(
        GenericRelationshipType<Object, Object, MutableData> type,
        Ref<Object> source,
        IdentityHashMap<Ref<Object>, MutableData> expected
    ) {
        assertEquals(expected.size(), relationships.getTargetCount(source, type));
        expected.forEach((target, data) -> {
            assertSame(data, relationships.getData(source, type, target));
            var incoming = new ArrayList<Ref<Object>>();
            relationships.forEachIncomingSource(target, type, incoming::add);
            assertEquals(List.of(source), incoming);
        });
    }

    private static final class Fixture implements AutoCloseable {
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final GenericRelationshipType<Object, Object, MutableData> type;
        private final DataChanges changes;
        private final Store<Object> store;

        private Fixture() {
            var types = new RelationshipTypeRegistry<>(registry);
            type = types.registerRelationship(
                "relwind:test/link-data",
                MutableData.class,
                null,
                RelationshipRules.multiple());
            changes = new DataChanges(type);
            registry.registerSystem(changes);
            store = registry.addStore(new Object(), EmptyResourceStorage.get());
        }

        private Ref<Object> addEntity() {
            return Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
        }

        @Override
        public void close() {
            registry.shutdown();
        }
    }

    private record SetDelivery(
        Ref<Object> source, Ref<Object> target, SignalData oldData, SignalData data
    ) {
    }

    private static final class SetRecorder extends RelationshipChangeSystem<Object, SignalData> {
        private final List<SetDelivery> sets = new ArrayList<>();

        private SetRecorder(RelationshipType<Object, SignalData> type) {
            super(type);
        }

        @Override
        protected void onRelationshipSet(
            LinkedEntity<Object> source,
            LinkedEntity<Object> target,
            SignalData oldData,
            SignalData data,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            sets.add(new SetDelivery(source.reference(), target.reference(), oldData, data));
        }
    }

    static final class SignalData {
        static final BuilderCodec<SignalData> CODEC = BuilderCodec.builder(SignalData.class, SignalData::new)
            .versioned()
            .codecVersion(1)
            .append(new KeyedCodec<>("Value", Codec.INTEGER), (data, value) -> data.value = value, data -> data.value)
            .setVersionRange(0, 1)
            .add()
            .build();

        int value;
    }

    private static final class SignalFixture implements AutoCloseable {
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final IdentityHashMap<Ref<Object>, UUID> identities = new IdentityHashMap<>();
        private final RelationshipInstallation<UUID> installation;
        private final RelationshipType<Object, SignalData> type;
        private final SetRecorder observer;
        private final Store<Object> store;
        private int persistenceMarks;

        private SignalFixture(RelationshipRules cardinality) {
            installation = RelationshipInstallation.on(registry, identities::get, Codec.UUID_BINARY)
                .persistence((ignoredStore, ignoredSource) -> persistenceMarks++,
                    ignoredHolder -> { }, ignoredId -> false)
                .install();
            type = installation.types().registerRelationship(
                "relwind:test/put-signal", SignalData.class, SignalData.CODEC, cardinality);
            observer = new SetRecorder(type);
            registry.registerSystem(observer);
            store = registry.addStore(new Object(), EmptyResourceStorage.get());
        }

        private Ref<Object> add() {
            var ref = Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
            var id = UUID.randomUUID();
            identities.put(ref, id);
            installation.tracker().onEntityLoaded(id, ref);
            return ref;
        }

        @Override
        public void close() {
            installation.tracker().close();
            registry.shutdown();
        }
    }

    private record MountData(int seat) {
    }

    private record MountDelivery(String kind, Ref<Object> source, Ref<Object> target, MountData oldData, MountData data) {
    }

    private static final class MountRecorder extends RelationshipChangeSystem<Object, MountData> {
        private final List<MountDelivery> deliveries = new ArrayList<>();

        private MountRecorder(RelationshipType<Object, MountData> type) {
            super(type);
        }

        private List<String> kinds() {
            return deliveries.stream().map(MountDelivery::kind).toList();
        }

        @Override
        protected void onRelationshipAdded(
            LinkedEntity<Object> source,
            LinkedEntity<Object> target,
            MountData data,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            deliveries.add(new MountDelivery("added", source.reference(), target.reference(), null, data));
        }

        @Override
        protected void onRelationshipSet(
            LinkedEntity<Object> source,
            LinkedEntity<Object> target,
            MountData oldData,
            MountData data,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            deliveries.add(new MountDelivery("set", source.reference(), target.reference(), oldData, data));
        }

        @Override
        protected void onRelationshipRetargeted(
            LinkedEntity<Object> source,
            LinkedEntity<Object> oldTarget,
            LinkedEntity<Object> target,
            MountData data,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            deliveries.add(new MountDelivery("retargeted", source.reference(), target.reference(), null, data));
        }

        @Override
        protected void onRelationshipRemoved(
            LinkedEntity<Object> source,
            LinkedEntity<Object> target,
            MountData data,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            deliveries.add(new MountDelivery("removed", source.reference(), target.reference(), null, data));
        }
    }

    private static final class MountFixture implements AutoCloseable {
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final RelationshipType<Object, MountData> type;
        private final MountRecorder observer;
        private final Store<Object> store;

        private MountFixture() {
            this(RelationshipRules.single());
        }

        private MountFixture(RelationshipRules policies) {
            type = new RelationshipTypeRegistry<>(registry).registerRelationship(MountData.class, policies);
            observer = new MountRecorder(type);
            registry.registerSystem(observer);
            store = registry.addStore(new Object(), EmptyResourceStorage.get());
        }

        private Ref<Object> add() {
            return Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
        }

        @Override
        public void close() {
            registry.shutdown();
        }
    }

    private static final class MutableData {
        private int distance;

        private MutableData(int distance) {
            this.distance = distance;
        }
    }

    private static final class DataChanges
        extends RefChangeSystem<Object, OutgoingLink<Object, Object>> {
        private final GenericRelationshipType<Object, Object, MutableData> type;
        private final List<Ref<Object>> addedSources = new ArrayList<>();
        private final List<Ref<Object>> setSources = new ArrayList<>();
        private final List<Ref<Object>> removedSources = new ArrayList<>();
        private Ref<Object> removeSource;
        private Ref<Object> removeTarget;
        private int targetCountAfterRejectedRemoval = -1;
        private int incomingCountAfterRejectedRemoval = -1;

        private DataChanges(GenericRelationshipType<Object, Object, MutableData> type) {
            this.type = type;
        }

        private void clear() {
            addedSources.clear();
            setSources.clear();
            removedSources.clear();
        }

        private int total() {
            return addedSources.size() + setSources.size() + removedSources.size();
        }

        private void removeDuringSet(Ref<Object> source, Ref<Object> target) {
            removeSource = source;
            removeTarget = target;
        }

        @Override
        public ComponentType<Object, OutgoingLink<Object, Object>> componentType() {
            return type.getSourceType();
        }

        @Override
        public Query<Object> getQuery() {
            return Query.any();
        }

        @Override
        public void onComponentAdded(
            @Nonnull Ref<Object> ref,
            @Nonnull OutgoingLink<Object, Object> component,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            addedSources.add(ref);
        }

        @Override
        public void onComponentSet(
            @Nonnull Ref<Object> ref,
            OutgoingLink<Object, Object> oldComponent,
            @Nonnull OutgoingLink<Object, Object> newComponent,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            setSources.add(ref);
            if (ref == removeSource) {
                var rejectedSource = removeSource;
                assertThrows(IllegalStateException.class,
                    () -> relationships.removeTarget(store, rejectedSource, type, removeTarget));
                targetCountAfterRejectedRemoval = relationships.getTargetCount(removeSource, type);
                incomingCountAfterRejectedRemoval = relationships.getIncomingCount(removeTarget, type);
                removeSource = null;
                removeTarget = null;
            }
        }

        @Override
        public void onComponentRemoved(
            @Nonnull Ref<Object> ref,
            @Nonnull OutgoingLink<Object, Object> component,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            removedSources.add(ref);
        }
    }

    private static final class CallbackStateOracle
        extends RefChangeSystem<Object, OutgoingLink<Object, Object>> {
        private final GenericRelationshipType<Object, Object, MutableData> type;
        private final ArrayDeque<IdentityHashMap<Ref<Object>, MutableData>> expectedStates = new ArrayDeque<>();
        private Ref<Object> source;
        private List<Ref<Object>> targets;
        private Ref<Object> rejectedTarget;
        private MutableData rejectedData;

        private CallbackStateOracle(GenericRelationshipType<Object, Object, MutableData> type) {
            this.type = type;
        }

        private void observe(Ref<Object> source, List<Ref<Object>> targets) {
            this.source = source;
            this.targets = targets;
        }

        private void expect(IdentityHashMap<Ref<Object>, MutableData> expected) {
            expectedStates.addLast(new IdentityHashMap<>(expected));
        }

        private void setDuringNextCallback(Ref<Object> target, MutableData data) {
            rejectedTarget = target;
            rejectedData = data;
        }

        private void assertComplete() {
            assertTrue(expectedStates.isEmpty());
            assertNull(rejectedTarget);
        }

        @Override
        public ComponentType<Object, OutgoingLink<Object, Object>> componentType() {
            return type.getSourceType();
        }

        @Override
        public Query<Object> getQuery() {
            return Query.any();
        }

        @Override
        public void onComponentAdded(
            @Nonnull Ref<Object> ref,
            @Nonnull OutgoingLink<Object, Object> component,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            assertState(ref, store);
        }

        @Override
        public void onComponentSet(
            @Nonnull Ref<Object> ref,
            OutgoingLink<Object, Object> oldComponent,
            @Nonnull OutgoingLink<Object, Object> newComponent,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            assertState(ref, store);
        }

        @Override
        public void onComponentRemoved(
            @Nonnull Ref<Object> ref,
            @Nonnull OutgoingLink<Object, Object> component,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            assertState(ref, store);
        }

        private void assertState(Ref<Object> ref, Store<Object> store) {
            assertSame(source, ref);
            assertFalse(expectedStates.isEmpty());
            var expected = expectedStates.removeFirst();
            assertEquals(expected.size(), relationships.getTargetCount(source, type));
            for (var target : targets) {
                var expectedData = expected.get(target);
                var incoming = new ArrayList<Ref<Object>>();
                relationships.forEachIncomingSource(target, type, incoming::add);
                if (expected.containsKey(target)) {
                    assertSame(expectedData, relationships.getData(source, type, target));
                    assertEquals(List.of(source), incoming);
                } else {
                    assertNull(relationships.getData(source, type, target));
                    assertTrue(incoming.isEmpty());
                }
            }
            if (rejectedTarget != null) {
                var target = rejectedTarget;
                var data = rejectedData;
                rejectedTarget = null;
                rejectedData = null;
                assertThrows(IllegalStateException.class, () -> relationships.putTarget(store, source, type, target, data));
                assertNull(relationships.getData(source, type, target));
                assertEquals(expected.size(), relationships.getTargetCount(source, type));
            }
        }
    }
}
