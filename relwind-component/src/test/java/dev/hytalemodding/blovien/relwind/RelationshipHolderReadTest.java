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
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.Component;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/// The links retained for a source that left the Store are read from its holder, naming the
/// targets that are available and leaving the ones that are away unknown.
class RelationshipHolderReadTest {
    private static final Relationships relationships = new Relationships();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parkedAndDecodedHoldersExposeAvailableAndUnknownTargetsWithoutAdmission(boolean decoded) {
        try (var fixture = new Fixture()) {
            var type = fixture.types.registerRelationship(
                "relwind:holder/void",
                RelationshipRules.multiple().retainOnTransfer().retainOnDeactivation());
            var source = fixture.entity();
            var available = fixture.entity();
            var unavailable = fixture.entity();
            relationships.addTarget(fixture.store, source, type, available);
            relationships.addTarget(fixture.store, source, type, unavailable);
            var holder = fixture.park(source, decoded);
            fixture.park(unavailable, false);
            var count = fixture.store.getEntityCount();
            var changes = fixture.changes;
            var incoming = relationships.getIncomingCount(available, type);
            var targets = new ArrayList<Ref<Object>>();

            fixture.tracker.readHolderLinks(type, holder, fixture.store, (target, data) -> {
                targets.add(target);
                assertNull(data);
            });

            assertEquals(2, targets.size());
            assertTrue(targets.contains(available));
            assertTrue(targets.contains(null));
            assertEquals(count, fixture.store.getEntityCount());
            assertEquals(changes, fixture.changes);
            assertNull(holder.getComponent(type.getSourceType()));
            assertEquals(incoming, relationships.getIncomingCount(available, type));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void holderLookupSurvivesTheRemovalOfAnotherTypeAndTheReturnOfItsSource(boolean close) {
        try (var fixture = new Fixture()) {
            var first = fixture.types.registerRelationship(
                RelationshipRules.multiple().retainOnTransfer().retainOnDeactivation());
            var second = fixture.types.registerRelationship(
                RelationshipRules.single().retainOnTransfer().retainOnDeactivation());
            var source = fixture.entity();
            var left = fixture.entity();
            var right = fixture.entity();
            var other = fixture.entity();
            relationships.addTarget(fixture.store, source, first, left);
            relationships.addTarget(fixture.store, source, first, right);
            relationships.addTarget(fixture.store, source, second, other);
            var holder = fixture.park(source, false);
            var targets = new ArrayList<Ref<Object>>();
            fixture.tracker.readHolderLinks(first, holder, fixture.store, (ref, data) -> targets.add(ref));
            assertEquals(Set.of(left, right), new HashSet<>(targets));

            fixture.types.unregisterRelationship(first);
            targets.clear();
            fixture.tracker.readHolderLinks(second, holder, fixture.store, (ref, data) -> targets.add(ref));
            assertEquals(List.of(other), targets, "another type still retains this holder");

            var returned = fixture.store.addEntity(holder, AddReason.LOAD);
            fixture.ids.put(returned, fixture.ids.get(source));
            fixture.tracker.onEntityLoaded(fixture.ids.get(source), returned);
            assertSame(other, relationships.getFirstTarget(returned, second));

            var parkedAgain = fixture.park(returned, false);
            targets.clear();
            fixture.tracker.readHolderLinks(second, parkedAgain, fixture.store, (ref, data) -> targets.add(ref));
            assertEquals(List.of(other), targets);

            releaseRetention(fixture, second, close);
        }
    }

    private static void releaseRetention(Fixture fixture, RelationshipType<Object, Void> type, boolean close) {
        if (close) {
            fixture.tracker.close();
        } else {
            fixture.types.unregisterRelationship(type);
        }
    }

    @Test
    void aPendingUnloadLeavesTheTargetUnknownAndReturnsTheRetainedDataOverTheMetadata() {
        try (var fixture = new Fixture()) {
            var type = register(fixture.types, HolderData.CODEC_V2);
            var source = fixture.entity();
            var target = fixture.entity();
            var data = new HolderData(7);
            relationships.addTarget(fixture.store, source, type, target, data);
            var holder = fixture.store.removeEntity(source, RemoveReason.UNLOAD);
            fixture.tracker.onEntityUnloaded(fixture.ids.get(source), source, UnloadReason.PENDING, holder);
            holder.putComponent(fixture.persistence.getComponentType(), decoded(
                raw(fixture.ids.get(target), 2, 99)));
            var values = new ArrayList<Object>();

            fixture.tracker.readHolderLinks(type, holder, fixture.store, (ref, payload) -> {
                assertNull(ref);
                values.add(payload);
            });

            assertEquals(1, values.size());
            assertSame(data, values.get(0));
        }
    }

    @Test
    void aResolvedDeactivationNamesTheTargetAndReturnsTheRetainedDataOverTheMetadata() {
        try (var fixture = new Fixture()) {
            var type = register(fixture.types, HolderData.CODEC_V2);
            var source = fixture.entity();
            var target = fixture.entity();
            var data = new HolderData(7);
            relationships.addTarget(fixture.store, source, type, target, data);
            var holder = fixture.store.removeEntity(source, RemoveReason.UNLOAD);
            fixture.tracker.onEntityUnloaded(fixture.ids.get(source), source, UnloadReason.PENDING, holder);
            holder.putComponent(fixture.persistence.getComponentType(), decoded(
                raw(fixture.ids.get(target), 2, 99)));
            fixture.tracker.onUnloadResolved(fixture.ids.get(source), source, UnloadReason.DEACTIVATION);
            var values = new ArrayList<Object>();

            fixture.tracker.readHolderLinks(type, holder, fixture.store, (ref, payload) -> {
                assertSame(target, ref);
                values.add(payload);
            });

            assertEquals(1, values.size());
            assertSame(data, values.get(0));
        }
    }

    @Test
    void aTargetLoadedIntoAnotherStoreStaysUnknownWhenTheHolderIsReadInItsOwnStore() {
        try (var fixture = new Fixture()) {
            var type = register(fixture.types, HolderData.CODEC_V2);
            var source = fixture.entity();
            var target = fixture.entity();
            var data = new HolderData(7);
            relationships.addTarget(fixture.store, source, type, target, data);
            var holder = fixture.park(source, false);
            var targetHolder = fixture.park(target, false);
            var otherStore = fixture.registry.addStore(new Object(), EmptyResourceStorage.get());
            var replacement = otherStore.addEntity(targetHolder, AddReason.LOAD);
            fixture.ids.put(replacement, fixture.ids.get(target));
            fixture.tracker.onEntityLoaded(fixture.ids.get(target), replacement);
            int[] calls = {0};

            fixture.tracker.readHolderLinks(type, holder, fixture.store, (ref, payload) -> {
                assertNull(ref);
                assertSame(data, payload);
                calls[0]++;
            });

            assertEquals(1, calls[0]);
            assertFalse(target.isValid());
            assertNull(holder.getComponent(type.getSourceType()));
        }
    }

    @Test
    void aTargetLoadedIntoAnotherStoreIsNamedByItsReplacementWhenTheHolderIsReadThere() {
        try (var fixture = new Fixture()) {
            var type = register(fixture.types, HolderData.CODEC_V2);
            var source = fixture.entity();
            var target = fixture.entity();
            var data = new HolderData(7);
            relationships.addTarget(fixture.store, source, type, target, data);
            var holder = fixture.park(source, false);
            var targetHolder = fixture.park(target, false);
            var otherStore = fixture.registry.addStore(new Object(), EmptyResourceStorage.get());
            var replacement = otherStore.addEntity(targetHolder, AddReason.LOAD);
            fixture.ids.put(replacement, fixture.ids.get(target));
            fixture.tracker.onEntityLoaded(fixture.ids.get(target), replacement);
            int[] calls = {0};

            fixture.tracker.readHolderLinks(type, holder, otherStore, (ref, payload) -> {
                assertSame(replacement, ref);
                assertSame(data, payload);
                calls[0]++;
            });

            assertEquals(1, calls[0]);
        }
    }

    @Test
    void deferredCleanupSuppressesMetadataWithoutExecutingItsObligation() {
        try (var fixture = new Fixture()) {
            var type = fixture.types.registerRelationship(
                "relwind:holder/cleanup",
                RelationshipRules.multiple().retainOnDeactivation());
            var source = fixture.entity();
            var target = fixture.entity();
            relationships.addTarget(fixture.store, source, type, target);
            var holder = fixture.park(source, false);
            var events = new ArrayList<String>();
            fixture.registry.registerSystem(new RelationshipChangeSystem<Object, Void>(type) {
                @Override
                protected void onRelationshipRemoved(
                    LinkedEntity<Object> from,
                    LinkedEntity<Object> to,
                    Void data,
                    Store<Object> store,
                    CommandBuffer<Object> buffer
                ) {
                    store.assertThread();
                    assertSame(fixture.store, store);
                    assertNull(from.reference());
                    assertNull(to.reference());
                    assertEquals(fixture.ids.get(source), from.identity());
                    assertEquals(fixture.ids.get(target), to.identity());
                    assertNull(holder.getComponent(fixture.persistence.getComponentType()));
                    assertFalse(fixture.tracker.contains(type, fixture.ids.get(source), fixture.ids.get(target)));
                    events.add("removed");
                }
            });

            CompletableFuture.runAsync(() -> fixture.tracker.onEntityUnloaded(fixture.ids.get(target), target,
                UnloadReason.TRANSFER)).join();

            assertEquals(1, fixture.queued.size());

            var metadata = holder.getComponent(fixture.persistence.getComponentType());
            assertEquals(1, metadata.getContent().getArray("Links").size());
            var changes = fixture.changes;
            fixture.tracker.readHolderLinks(type, holder, fixture.store, (ref, payload) -> fail("pending cleanup must stay inactive"));

            assertEquals(changes, fixture.changes);
            assertEquals(1, fixture.queued.size());
            assertSame(metadata, holder.getComponent(fixture.persistence.getComponentType()));
            assertTrue(events.isEmpty());

            fixture.queued.get(0).run();

            assertNull(holder.getComponent(fixture.persistence.getComponentType()));

            fixture.queued.get(0).run();

            assertEquals(List.of("removed"), events);
        }
    }

    @Test
    void decodedPayloadsShareTheRestoreCacheAndNeverEncodeDuringReads() {
        try (var fixture = new Fixture()) {
            var target = fixture.entity();
            var type = register(fixture.types, HolderData.CODEC_V2);
            var holder = fixture.registry.newHolder();
            holder.putComponent(fixture.persistence.getComponentType(), decoded(raw(fixture.ids.get(target), 1, 7)));

            var values = readRepeatedly(fixture, type, holder, target, 50);

            assertEquals(50, values.size());
            assertEquals(7, ((HolderData) values.get(0)).value);
            assertSame(values.get(0), values.get(49));

            holder.putComponent(fixture.persistence.getComponentType(), decoded(raw(fixture.ids.get(target), 2, 99)));
            fixture.persistence.readHolderLinks(type, holder, fixture.store,
                (ref, data) -> assertEquals(99, ((HolderData) data).value));
            var source = fixture.store.addEntity(holder, AddReason.LOAD);
            fixture.ids.put(source, UUID.randomUUID());
            fixture.tracker.onEntityLoaded(fixture.ids.get(source), source);
            fixture.persistence.restore(source);

            assertEquals(99, relationships.getData(source, type, target).value);
            assertEquals(0, fixture.changes);
        }
    }

    @Test
    void transientRetainedLinksExposeTheirCapturedData() {
        try (var fixture = new Fixture()) {
            var type = fixture.types.registerRelationship(
                BsonDocument.class,
                RelationshipRules.multiple().retainOnTransfer().retainOnDeactivation());
            var source = fixture.entity();
            var target = fixture.entity();
            var data = BsonDocument.parse("{value: 7}");
            relationships.addTarget(fixture.store, source, type, target, data);
            var holder = fixture.park(source, false);
            int[] calls = {0};

            fixture.tracker.readHolderLinks(type, holder, fixture.store, (ref, payload) -> {
                assertSame(target, ref);
                assertSame(data, payload);
                calls[0]++;
            });

            assertEquals(1, calls[0]);
            assertNull(holder.getComponent(fixture.persistence.getComponentType()));
        }
    }

    @Test
    void unknownRecordsStayInactiveAndAReplacementRegistrationGetsANewDecodeCache() {
        try (var fixture = new Fixture()) {
            var target = fixture.entity();
            var type = register(fixture.types, HolderData.CODEC_V2);
            var holder = fixture.registry.newHolder();
            var document = raw(fixture.ids.get(target), 3, 7);
            holder.putComponent(fixture.persistence.getComponentType(), decoded(document));

            for (int i = 0; i < 3; i++) {
                fixture.tracker.readHolderLinks(type, holder, fixture.store, (ref, data) -> fail("unsupported version"));
            }

            assertEquals(document, holder.getComponent(fixture.persistence.getComponentType()).getContent());

            fixture.types.unregisterRelationship(type);
            var replacement = register(fixture.types, HolderData.CODEC_V3);
            int[] calls = {0};
            fixture.tracker.readHolderLinks(replacement, holder, fixture.store, (ref, data) -> {
                assertSame(target, ref);
                assertEquals(7, ((HolderData) data).value);
                calls[0]++;
            });

            assertEquals(1, calls[0]);
        }
    }

    @Test
    void steadyHolderReadsDoNotAllocateInProportionToTheirLinkCount() {
        int iterations = 2_000;
        long small = allocatedHolderReads(8, iterations);
        long large = allocatedHolderReads(256, iterations);

        assertTrue(large <= small + 128L * iterations,
            "allocation must not grow per link after warmup: small=" + small + ", large=" + large);
    }

    private static long allocatedHolderReads(int linkCount, int iterations) {
        try (var fixture = new Fixture()) {
            var type = register(fixture.types, HolderData.CODEC_V2);
            var records = new BsonArray();
            for (int i = 0; i < linkCount; i++) {
                var target = fixture.entity();
                records.add(record(fixture.ids.get(target), 2, i));
            }
            var holder = fixture.registry.newHolder();
            holder.putComponent(fixture.persistence.getComponentType(),
                decoded(new BsonDocument("Links", records)));
            var callbacks = new int[1];
            BiConsumer<Ref<Object>, Object> sink = (ref, data) -> {
                if (ref != null && data instanceof HolderData) callbacks[0]++;
            };
            for (int i = 0; i < 10_000; i++) fixture.tracker.readHolderLinks(type, holder, fixture.store, sink);
            var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            assertTrue(bean.isThreadAllocatedMemorySupported());
            bean.setThreadAllocatedMemoryEnabled(true);
            var thread = Thread.currentThread().threadId();
            long before = bean.getThreadAllocatedBytes(thread);
            for (int i = 0; i < iterations; i++) fixture.tracker.readHolderLinks(type, holder, fixture.store, sink);
            long allocated = bean.getThreadAllocatedBytes(thread) - before;
            assertEquals(linkCount * (10_000 + iterations), callbacks[0]);
            assertEquals(0, fixture.changes);
            return allocated;
        }
    }

    @Test
    void aRetainedLinkOfAParkedSourceReadsItsDataComponentFromThatHolder() {
        try (var fixture = new Fixture()) {
            var saddleType = fixture.registry.registerComponent(Saddle.class, Saddle::new);
            var type = fixture.types.registerRelationship(
                saddleType,
                new SaddleObserver(),
                RelationshipRules.single().retainOnDeactivation());
            var source = fixture.entity();
            var target = fixture.entity();
            var saddle = new Saddle();
            relationships.addTarget(fixture.store, source, type, target, saddle);
            var holder = fixture.park(source, false);
            assertSame(saddle, holder.getComponent(saddleType));
            var read = new ArrayList<Object>();

            fixture.tracker.readHolderLinks(type, holder, fixture.store, (linkedEntity, data) -> {
                assertSame(target, linkedEntity);
                read.add(data);
            });

            assertEquals(List.of(saddle), read);
        }
    }

    /// Link data of a single target type, carried by a component on the source.
    private static final class Saddle implements Component<Object> {
        private int seat;

        @Override
        public Saddle clone() {
            var copy = new Saddle();
            copy.seat = seat;
            return copy;
        }
    }

    private static GenericRelationshipType<Object, Object, HolderData> register(
        RelationshipTypeRegistry<Object> types,
        Codec<HolderData> codec
    ) {
        return types.registerRelationship("relwind:holder/data", HolderData.class, codec,
            RelationshipRules.multiple().retainOnTransfer().retainOnDeactivation());
    }

    private static ArrayList<Object> readRepeatedly(
        Fixture fixture,
        GenericRelationshipType<Object, Object, ?> type,
        Holder<Object> holder,
        Ref<Object> expectedTarget,
        int times
    ) {
        var values = new ArrayList<Object>();
        for (int i = 0; i < times; i++) {
            fixture.tracker.readHolderLinks(type, holder, fixture.store, (ref, data) -> {
                assertSame(expectedTarget, ref);
                values.add(data);
            });
        }
        return values;
    }

    private static BsonDocument raw(UUID target, int version, int value) {
        return new BsonDocument("Links", new BsonArray(List.of(record(target, version, value))));
    }

    private static BsonDocument record(UUID target, int version, int value) {
        return new BsonDocument("Version", new BsonInt32(1))
            .append("Type", new BsonString("relwind:holder/data"))
            .append("Target", Codec.UUID_BINARY.encode(target, new ExtraInfo()))
            .append("CleanupDisposition", new BsonString("PreserveSource"))
            .append("TargetInstallation", new BsonString("ENTITIES"))
            .append("Payload", BsonDocument.parse("{Version: " + version + ", Value: " + value + "}"));
    }

    @SuppressWarnings("unchecked")
    private static RelationshipMetadata<Object> decoded(BsonDocument content) {
        return (RelationshipMetadata<Object>) RelationshipMetadata.CODEC.decode(
            content, new ExtraInfo());
    }

    static final class HolderData {
        static final BuilderCodec<HolderData> CODEC_V2 = BuilderCodec.builder(HolderData.class, HolderData::new)
            .versioned()
            .codecVersion(2)
            .append(new KeyedCodec<>("Value", Codec.INTEGER), (data, value) -> data.value = value, data -> data.value)
            .setVersionRange(1, 2)
            .add()
            .build();
        static final BuilderCodec<HolderData> CODEC_V3 = BuilderCodec.builder(HolderData.class, HolderData::new)
            .versioned()
            .codecVersion(3)
            .append(new KeyedCodec<>("Value", Codec.INTEGER), (data, value) -> data.value = value, data -> data.value)
            .setVersionRange(1, 3)
            .add()
            .build();

        int value;

        HolderData() {
        }

        HolderData(int value) {
            this.value = value;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final Store<Object> store = registry.addStore(new Object(), EmptyResourceStorage.get());
        private final Map<Ref<Object>, UUID> ids = new IdentityHashMap<>();
        private final ArrayList<Runnable> queued = new ArrayList<>();
        private int changes;
        private final RelationshipInstallation<UUID> installation = RelationshipInstallation.on(registry, ids::get, Codec.UUID_BINARY)
            .transitions((context, action) -> queued.add(action))
            .persistence((context, source) -> changes++, holder -> changes++, id -> false)
            .install();
        private final RelationshipTracker<Object, UUID> tracker = installation.tracker();
        private final RelationshipTypeRegistry<Object> types = installation.types();
        private final RelationshipPersistence<Object> persistence = installation.persistence();
        private Ref<Object> entity() {
            return entity(store);
        }

        private Ref<Object> entity(Store<Object> context) {
            var ref = context.addEntity(registry.newHolder(), AddReason.SPAWN);
            ids.put(ref, UUID.randomUUID());
            tracker.onEntityLoaded(ids.get(ref), ref);
            return ref;
        }

        private Holder<Object> park(Ref<Object> source, boolean decoded) {
            var context = source.getStore();
            var saved = decoded ? registry.serialize(context.copySerializableEntity(source)) : null;
            var holder = context.removeEntity(source, RemoveReason.UNLOAD);
            tracker.onEntityUnloaded(ids.get(source), source,
                UnloadReason.DEACTIVATION, holder);
            return decoded ? registry.deserialize(saved) : holder;
        }

        @Override
        public void close() {
            tracker.close();
            registry.shutdown();
        }
    }

    private static final class SaddleObserver extends RelationshipDataObserver<Object, Saddle> {
    }
}
