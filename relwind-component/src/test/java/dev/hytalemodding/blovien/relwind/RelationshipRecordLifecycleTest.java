/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.RefChangeSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.hytalemodding.blovien.relwind.RelationshipTestFixtures.LinkedInstallations;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RelationshipRecordLifecycleTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void loadedLinksUseComponentsWithoutTrackerRecords() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(1);
            var target = fixture.add(2);

            relationships.addTarget(fixture.store, source, fixture.type, target, "loaded");

            assertAll(
                () -> assertEquals(false, fixture.tracker.hasRecordedLinks()),
                () -> assertEquals(true, fixture.tracker.contains(fixture.type, 1, 2)),
                () -> assertEquals(true, fixture.tracker.isResolved(fixture.type, 1, 2)),
                () -> assertEquals("loaded", relationships.getData(source, fixture.type, target)));
        }
    }

    @Test
    void removingALoadedLinkLeavesNoTrackerRecord() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(1);
            var target = fixture.add(2);
            relationships.addTarget(fixture.store, source, fixture.type, target, "loaded");

            relationships.removeTarget(fixture.store, source, fixture.type, target);

            assertAll(
                () -> assertEquals(false, fixture.tracker.hasRecordedLinks()),
                () -> assertEquals(false, fixture.tracker.contains(fixture.type, 1, 2)),
                () -> assertEquals(false, fixture.tracker.isResolved(fixture.type, 1, 2)));
        }
    }

    @Test
    void loadedBridgeLinksUseComponentsWithoutRecordsInEitherTracker() {
        try (var linked = new LinkedInstallations()) {
            var type = linked.entityTypes.registerRelationship(linked.blockTypes, String.class,
                RelationshipTraits.defaults().retainOnDeactivation());
            var source = linked.entity("source");
            var target = linked.block(1);

            relationships.addTarget(linked.world.entityStore(), source, type, target, "bridge");

            assertAll(
                () -> assertEquals(false, linked.entityTracker.hasRecordedLinks()),
                () -> assertEquals(false, linked.blockTracker.hasRecordedLinks()),
                () -> assertEquals("bridge", relationships.getData(source, type, target)));
        }
    }

    @Test
    void removingALoadedBridgeLinkLeavesNeitherTrackerWithARecord() {
        try (var linked = new LinkedInstallations()) {
            var type = linked.entityTypes.registerRelationship(linked.blockTypes, String.class,
                RelationshipTraits.defaults().retainOnDeactivation());
            var source = linked.entity("source");
            var target = linked.block(1);
            relationships.addTarget(linked.world.entityStore(), source, type, target, "bridge");

            relationships.removeTarget(linked.world.entityStore(), source, type, target);

            assertAll(
                () -> assertEquals(false, linked.entityTracker.hasRecordedLinks()),
                () -> assertEquals(false, linked.blockTracker.hasRecordedLinks()),
                () -> assertEquals(false, relationships.hasTarget(source, type, target)));
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void unloadingEitherEndRecordsEachLinkAndRestoresItsData(boolean sourceLeaves, boolean withHolder) {
        try (var fixture = new Fixture()) {
            var leaving = fixture.add(1);
            var first = fixture.add(2);
            var second = fixture.add(3);
            fixture.link(leaving, first, sourceLeaves, "first");
            fixture.link(leaving, second, sourceLeaves, "second");

            var holder = fixture.unload(leaving, withHolder, UnloadReason.DEACTIVATION);

            assertEquals(true, fixture.tracker.hasRecordedLinks());
            fixture.assertRetained(1, 2, sourceLeaves);
            fixture.assertRetained(1, 3, sourceLeaves);
            var returned = fixture.load(holder);
            fixture.assertData(returned, first, sourceLeaves, "first");
            fixture.assertData(returned, second, sourceLeaves, "second");
            assertEquals(false, fixture.tracker.hasRecordedLinks());
        }
    }

    @Test
    void aPendingTransferKeepsItsRecordUntilClassification() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(1);
            var target = fixture.add(2);
            relationships.addTarget(fixture.store, source, fixture.type, target, "pending");
            var holder = fixture.unload(source, true, UnloadReason.PENDING);

            var returned = fixture.load(holder);

            assertEquals(true, fixture.tracker.hasRecordedLinks());
            assertEquals(false, fixture.tracker.isResolved(fixture.type, 1, 2));
            assertEquals(0, relationships.getTargetCount(returned, fixture.type));
            assertEquals(true, fixture.tracker.onUnloadResolved(1, source, UnloadReason.TRANSFER));
            assertEquals("pending", relationships.getData(returned, fixture.type, target));
            assertEquals(false, fixture.tracker.hasRecordedLinks());
        }
    }

    @Test
    void unloadingABridgeSourceRecordsAndRestoresItsData() {
        try (var linked = new LinkedInstallations()) {
            var type = linked.entityTypes.registerRelationship(linked.blockTypes, String.class,
                RelationshipTraits.defaults().retainOnDeactivation());
            var source = linked.entity("source");
            var target = linked.block(1);
            relationships.addTarget(linked.world.entityStore(), source, type, target, "bridge");

            linked.unloadEntity("source", source);
            linked.runTransitions();

            assertEquals(true, linked.entityTracker.hasRecordedLinks());
            assertEquals(true, linked.blockTracker.hasRecordedLinks());
            var returned = linked.entity("source");
            linked.runTransitions();
            assertEquals("bridge", relationships.getData(returned, type, target));
            assertEquals(false, linked.entityTracker.hasRecordedLinks());
            assertEquals(false, linked.blockTracker.hasRecordedLinks());
        }
    }

    @Test
    void unloadingABridgeTargetRecordsAndRestoresItsData() {
        try (var linked = new LinkedInstallations()) {
            var type = linked.entityTypes.registerRelationship(linked.blockTypes, String.class,
                RelationshipTraits.defaults().retainOnDeactivation());
            var source = linked.entity("source");
            var target = linked.block(1);
            relationships.addTarget(linked.world.entityStore(), source, type, target, "bridge");

            linked.unloadBlock(1, target);
            linked.runTransitions();

            assertEquals(true, linked.entityTracker.hasRecordedLinks());
            assertEquals(true, linked.blockTracker.hasRecordedLinks());
            var returned = linked.block(1);
            linked.runTransitions();
            assertEquals("bridge", relationships.getData(source, type, returned));
            assertEquals(false, linked.entityTracker.hasRecordedLinks());
            assertEquals(false, linked.blockTracker.hasRecordedLinks());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeBatchUnloadCapturesLinksBeforeEitherReferenceBecomesInvalid(boolean symmetric) {
        try (var fixture = new Fixture()) {
            fixture.registry.registerSystem(new BeforeUnload(fixture));
            var type = fixture.register(symmetric);
            var source = fixture.add(1);
            var target = fixture.add(2);
            relationships.addTarget(fixture.store, source, type, target, "batch");

            var holders = removeBatch(fixture.store, source, target);
            fixture.tracker.onEntityUnloaded(1, source, UnloadReason.DEACTIVATION, holders[0]);
            fixture.tracker.onEntityUnloaded(2, target, UnloadReason.DEACTIVATION, holders[1]);

            assertEquals(true, fixture.tracker.hasRecordedLinks());
            var returnedSource = fixture.load(holders[0]);
            var returnedTarget = fixture.load(holders[1]);
            assertEquals("batch", relationships.getData(returnedSource, type, returnedTarget));
            assertEquals(symmetric, relationships.hasTarget(returnedTarget, type, returnedSource));
            assertEquals(false, fixture.tracker.hasRecordedLinks());
        }
    }

    @Test
    void unloadingASelfLinkRestoresOneLinkAndReleasesItsRecord() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(1);
            relationships.addTarget(fixture.store, source, fixture.type, source, "self");

            var holder = fixture.unload(source, true, UnloadReason.DEACTIVATION);
            var returned = fixture.load(holder);

            assertEquals(1, relationships.getTargetCount(returned, fixture.type));
            assertEquals(1, relationships.getIncomingCount(returned, fixture.type));
            assertEquals("self", relationships.getData(returned, fixture.type, returned));
            assertEquals(false, fixture.tracker.hasRecordedLinks());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeBridgeDeletionReleasesTheCapturedRecord(boolean sourceLeaves) {
        try (var linked = new LinkedInstallations()) {
            linked.fixture.entityRegistry().registerSystem(new BeforeLinkedUnload<>(linked.entityTracker, linked.entityIds::get));
            linked.fixture.blockRegistry().registerSystem(new BeforeLinkedUnload<>(linked.blockTracker, linked.blockIds::get));
            var type = linked.entityTypes.registerRelationship(linked.blockTypes, String.class,
                RelationshipTraits.defaults().retainOnDeactivation());
            var source = linked.entity("source");
            var target = linked.block(1);
            relationships.addTarget(linked.world.entityStore(), source, type, target, "deleted");

            deleteBridgeEnd(linked, source, target, sourceLeaves);
            linked.runTransitions();

            assertAll(
                () -> assertEquals(false, linked.entityTracker.hasRecordedLinks()),
                () -> assertEquals(false, linked.blockTracker.hasRecordedLinks()));
        }
    }

    private static void deleteBridgeEnd(LinkedInstallations linked,
        Ref<com.hypixel.hytale.component.BridgeStoreFixture.Entities> source,
        Ref<com.hypixel.hytale.component.BridgeStoreFixture.Blocks> target, boolean sourceLeaves) {
        if (sourceLeaves) {
            linked.world.entityStore().removeEntity(source, RemoveReason.REMOVE);
            linked.entityTracker.onEntityDeleted("source", source);
        } else linked.deleteBlock(1, target);
    }

    @ParameterizedTest
    @CsvSource({"false,DEACTIVATION", "true,DEACTIVATION", "false,PENDING", "true,PENDING"})
    void unloadingAnEndDuringRestorationKeepsItsRecordAndData(boolean sourceLeaves, UnloadReason reason) {
        try (var fixture = new Fixture()) {
            fixture.registry.registerSystem(new BeforeUnload(fixture));
            var source = fixture.add(1);
            var target = fixture.add(2);
            relationships.addTarget(fixture.store, source, fixture.type, target, "restoring");
            var sourceHolder = fixture.unload(source, true, UnloadReason.DEACTIVATION);
            var callback = new UnloadDuringRestore(fixture, target, sourceLeaves, reason);
            fixture.registry.registerSystem(callback);

            fixture.load(sourceHolder);

            assertEquals(true, fixture.tracker.contains(fixture.type, 1, 2));
            assertEquals(false, fixture.tracker.isResolved(fixture.type, 1, 2));
            fixture.registry.unregisterSystem(UnloadDuringRestore.class);
            fixture.load(callback.removed);
            callback.resolve();
            assertEquals("restoring", relationships.getData(fixture.tracker.getRef(1), fixture.type, fixture.tracker.getRef(2)));
            assertEquals(false, fixture.tracker.hasRecordedLinks());
        }
    }

    @SafeVarargs
    private static <ECS_TYPE> Holder<ECS_TYPE>[] removeBatch(Store<ECS_TYPE> store, Ref<ECS_TYPE>... refs) {
        return store.removeEntities(refs, RemoveReason.UNLOAD);
    }

    private static final class Fixture implements AutoCloseable {
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final ComponentType<Object, Identity> identityType = registry.registerComponent(Identity.class, Identity::new);
        private final Store<Object> store = registry.addStore(new Object(), EmptyResourceStorage.get());
        private final RelationshipTypeRegistry<Object> types = new RelationshipTypeRegistry<>(registry);
        private final RelationshipTracker<Object, Integer> tracker = types.installTracker(
            TestPersistenceIdentity.of(ref -> ref.getStore().getComponent(ref, identityType).value, Codec.INTEGER),
            TestStoreRuntime.inline());
        private final RelationshipType<Object, String> type = register(false);

        private RelationshipType<Object, String> register(boolean symmetric) {
            var traits = RelationshipTraits.defaults().retainOnDeactivation().retainOnTransfer();
            if (symmetric) traits = traits.symmetric();
            return types.registerRelationship(String.class, traits);
        }

        private Ref<Object> add(int id) {
            var holder = registry.newHolder();
            holder.addComponent(identityType, new Identity(id));
            return load(holder);
        }

        private Ref<Object> load(Holder<Object> holder) {
            int id = holder.getComponent(identityType).value;
            var ref = store.addEntity(holder, AddReason.LOAD);
            tracker.onEntityLoaded(id, ref);
            return ref;
        }

        private Holder<Object> unload(Ref<Object> ref, boolean withHolder, UnloadReason reason) {
            int id = store.getComponent(ref, identityType).value;
            if (withHolder) {
                var holder = store.removeEntity(ref, RemoveReason.UNLOAD);
                tracker.onEntityUnloaded(id, ref, reason, holder);
                return holder;
            }
            tracker.onEntityUnloaded(id, ref, reason);
            return store.removeEntity(ref, RemoveReason.UNLOAD);
        }

        private void link(Ref<Object> leaving, Ref<Object> peer, boolean sourceLeaves, String data) {
            if (sourceLeaves) relationships.addTarget(store, leaving, type, peer, data);
            else relationships.addTarget(store, peer, type, leaving, data);
        }

        private void assertRetained(int leaving, int peer, boolean sourceLeaves) {
            int source = sourceLeaves ? leaving : peer;
            int target = sourceLeaves ? peer : leaving;
            assertEquals(true, tracker.contains(type, source, target));
            assertEquals(false, tracker.isResolved(type, source, target));
        }

        private void assertData(Ref<Object> leaving, Ref<Object> peer, boolean sourceLeaves, String data) {
            var source = sourceLeaves ? leaving : peer;
            var target = sourceLeaves ? peer : leaving;
            assertEquals(data, relationships.getData(source, type, target));
            assertEquals(sourceLeaves ? 1 : 2, relationships.getIncomingCount(target, type));
        }

        @Override
        public void close() {
            tracker.close();
            registry.shutdown();
        }
    }

    private static final class Identity implements Component<Object> {
        private final int value;
        private Identity() { this(0); }
        private Identity(int value) { this.value = value; }
        @Override
        public Component<Object> clone() { return new Identity(value); }
    }

    private static final class UnloadDuringRestore extends RefChangeSystem<Object, OutgoingLink<Object, Object>> {
        private final Fixture fixture;
        private final Ref<Object> target;
        private final boolean sourceLeaves;
        private final UnloadReason reason;
        private Ref<Object> leaving;
        private Holder<Object> removed;
        private UnloadDuringRestore(Fixture fixture, Ref<Object> target, boolean sourceLeaves, UnloadReason reason) {
            this.fixture = fixture;
            this.target = target;
            this.sourceLeaves = sourceLeaves;
            this.reason = reason;
        }
        private void resolve() {
            if (reason == UnloadReason.PENDING) {
                assertEquals(true, fixture.tracker.onUnloadResolved(sourceLeaves ? 1 : 2, leaving, UnloadReason.TRANSFER));
            }
        }
        @Override
        public ComponentType<Object, OutgoingLink<Object, Object>> componentType() { return fixture.type.getSourceType(); }
        @Override
        public Query<Object> getQuery() { return Query.any(); }
        @Override
        public void onComponentAdded(Ref<Object> ref, OutgoingLink<Object, Object> component,
            Store<Object> store, CommandBuffer<Object> commands) {
            leaving = sourceLeaves ? ref : target;
            int id = sourceLeaves ? 1 : 2;
            commands.run(ignored -> {
                removed = store.removeEntity(leaving, RemoveReason.UNLOAD);
                fixture.tracker.onEntityUnloaded(id, leaving, reason, removed);
            });
        }
        @Override
        public void onComponentSet(Ref<Object> ref, OutgoingLink<Object, Object> old,
            OutgoingLink<Object, Object> component, Store<Object> store, CommandBuffer<Object> commands) { }
        @Override
        public void onComponentRemoved(Ref<Object> ref, OutgoingLink<Object, Object> component,
            Store<Object> store, CommandBuffer<Object> commands) { }
    }

    private static final class BeforeLinkedUnload<ECS_TYPE, ID> extends RefSystem<ECS_TYPE> {
        private final RelationshipTracker<ECS_TYPE, ID> tracker;
        private final java.util.function.Function<Ref<ECS_TYPE>, ID> identity;
        private BeforeLinkedUnload(RelationshipTracker<ECS_TYPE, ID> tracker,
            java.util.function.Function<Ref<ECS_TYPE>, ID> identity) {
            this.tracker = tracker;
            this.identity = identity;
        }
        @Override
        public Query<ECS_TYPE> getQuery() { return Query.any(); }
        @Override
        public void onEntityAdded(Ref<ECS_TYPE> ref, AddReason reason, Store<ECS_TYPE> store,
            CommandBuffer<ECS_TYPE> commands) { }
        @Override
        public void onEntityRemove(Ref<ECS_TYPE> ref, RemoveReason reason, Store<ECS_TYPE> store,
            CommandBuffer<ECS_TYPE> commands) {
            tracker.onEntityUnloading(commands, identity.apply(ref), ref);
        }
    }

    private static final class BeforeUnload extends RefSystem<Object> {
        private final Fixture fixture;
        private BeforeUnload(Fixture fixture) { this.fixture = fixture; }
        @Override
        public Query<Object> getQuery() { return Query.any(); }
        @Override
        public void onEntityAdded(Ref<Object> ref, AddReason reason, Store<Object> store, CommandBuffer<Object> commands) { }
        @Override
        public void onEntityRemove(Ref<Object> ref, RemoveReason reason, Store<Object> store, CommandBuffer<Object> commands) {
            int id = store.getComponent(ref, fixture.identityType).value;
            fixture.tracker.onEntityUnloading(commands, id, ref);
        }
    }
}
