/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.math.vector.Vector3iUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.codec.codecs.UUIDBinaryCodec;
import com.hypixel.hytale.component.IResourceStorage;
import org.bson.BsonArray;
import org.bson.BsonBinary;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonBinarySubType;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// One tombstone resource serves both Store kinds: deletions are recorded per installation
/// and per world, they round-trip through the codec Hytale saves them with, and the Store saves
/// them with its other resources without a Relwind saving system.
class TombstonesTest {
    private static final Codec<Tombstones<EntityStore, UUID>> ENTITY_RECORDS =
        Tombstones.getCodec(CoreServerTracker.IDENTITY_CODEC);
    private static final Codec<Tombstones<ChunkStore, Vector3i>> BLOCK_RECORDS =
        Tombstones.getCodec(Vector3iUtil.CODEC);

    @ParameterizedTest
    @MethodSource("installations")
    void eachInstallationRegistersOneRecordSetPerWorld(StoreKind kind, String resourceId) {
        var registry = newRegistry(kind);
        try {
            install(kind, registry);

            var resourceType = registry._internal_getData().getResourceType(resourceId);
            assertNotNull(resourceType, "the installation must register its deletion records");
            assertSame(Tombstones.class, resourceType.getTypeClass());
            var first = registry._internal_getData().createResource(resourceType);
            var second = registry._internal_getData().createResource(resourceType);
            assertInstanceOf(Tombstones.class, first);
            assertNotSame(first, second, "each world Store must get its own deletion records");
        } finally {
            registry.shutdown();
        }
    }

    @ParameterizedTest
    @MethodSource("installations")
    void closingAnInstallationReleasesItsRecordSet(StoreKind kind, String resourceId) {
        var registry = newRegistry(kind);
        try {
            var installation = install(kind, registry);
            assertNotNull(registry._internal_getData().getResourceType(resourceId),
                "the installation must register its deletion records");

            installation.close();

            assertNull(registry._internal_getData().getResourceType(resourceId),
                "closing the installation must release its deletion records");
        } finally {
            registry.shutdown();
        }
    }

    /// Hytale's periodic save and its Store shutdown both call `saveAllResources`, which hands
    /// each world's own records to that world's resource storage.
    @Test
    void theStoreSavesTheRecordsOfEveryWorldWithItsOtherResources() {
        var registry = new ComponentRegistry<EntityStore>();
        var storage = new RecordingStorage();
        try {
            var installation = entityInstallation(registry);
            var first = registry.addStore(null, storage);
            var second = registry.addStore(null, storage);

            first.saveAllResources().join();
            second.saveAllResources().join();

            assertEquals(List.of(installation.getTombstonesIn(first), installation.getTombstonesIn(second)),
                storage.saved, "each world must hand its own records to its own storage");
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void aDeletionIsRecordedInTheWorldWhereItHappened() {
        var registry = new ComponentRegistry<EntityStore>();
        var storage = new RecordingStorage();
        try {
            var installation = entityInstallation(registry);
            var first = registry.addStore(null, storage);
            var second = registry.addStore(null, storage);
            var deleted = UUID.randomUUID();

            installation.getTombstonesIn(first).record(deleted);

            assertTrue(installation.getTombstonesIn(first).contains(deleted));
            assertFalse(installation.getTombstonesIn(second).contains(deleted),
                "a deletion is recorded in the world where it happened");
        } finally {
            registry.shutdown();
        }
    }

    @ParameterizedTest
    @MethodSource("installations")
    void aSecondInstallationTakesOverAfterTheFirstOneCloses(StoreKind kind, String resourceId) {
        var registry = newRegistry(kind);
        try {
            install(kind, registry).close();
            var reinstalled = install(kind, registry);

            assertNotNull(registry._internal_getData().getResourceType(resourceId));

            reinstalled.close();
            reinstalled.close();

            assertNull(registry._internal_getData().getResourceType(resourceId));
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void entityRecordsKeepUuidKeysAndSaveThemAsBinary() {
        var id = UUID.randomUUID();
        var records = new Tombstones<EntityStore, UUID>();
        records.record(id);

        var saved = ENTITY_RECORDS.encode(records, new ExtraInfo()).asDocument();

        assertEquals(new BsonArray(List.of(
            new BsonBinary(BsonBinarySubType.UUID_STANDARD, binary(id))
        )), saved.getArray("Identities"), saved.toString());
        assertTrue(ENTITY_RECORDS.decode(saved, new ExtraInfo()).contains(id));
    }

    @Test
    void recordsAreKeyedByTheIdentityTypeOfTheirInstallation() {
        var codec = Tombstones.<EntityStore, String>getCodec(Codec.STRING);
        var records = new Tombstones<EntityStore, String>();
        records.record("relwind:test/entity");

        var saved = codec.encode(records, new ExtraInfo()).asDocument();
        var restored = codec.decode(saved, new ExtraInfo());

        assertEquals(new BsonArray(List.of(new BsonString("relwind:test/entity"))), saved.getArray("Identities"));
        assertTrue(restored.contains("relwind:test/entity"));
        assertFalse(restored.contains("relwind:test/other"));
    }

    @Test
    void encodedSnapshotDoesNotIncludeLaterDeletionAndCannotChangeLiveRecords() {
        var first = UUID.randomUUID();
        var later = UUID.randomUUID();
        var records = new Tombstones<EntityStore, UUID>();
        records.record(first);
        records.record(first);
        var snapshot = ENTITY_RECORDS.encode(records, new ExtraInfo()).asDocument();
        records.record(later);

        assertEquals(1, snapshot.getArray("Identities").size());
        var restored = ENTITY_RECORDS.decode(snapshot, new ExtraInfo());
        snapshot.getArray("Identities").clear();
        assertTrue(restored.contains(first));
        assertFalse(restored.contains(later));
        assertTrue(records.contains(first));
        assertTrue(records.contains(later));
    }

    @ParameterizedTest
    @MethodSource("recordSnapshots")
    void aResourceCloneKeepsAnIndependentDeletionSnapshot(StoreKind kind, Object first, Object later) {
        var records = newRecords(kind);
        records.record(first);
        Resource<Object> resource = records;

        @SuppressWarnings("unchecked")
        var snapshot = (Tombstones<Object, Object>) resource.clone();
        records.record(later);

        assertTrue(snapshot.contains(first));
        assertFalse(snapshot.contains(later));
    }

    @Test
    void repeatedSavedDeletionIsTheSameEvidence() {
        var id = UUID.randomUUID();
        var value = new BsonBinary(BsonBinarySubType.UUID_STANDARD, binary(id));
        var document = new BsonDocument("Identities", new BsonArray(List.of(value, value)));

        var restored = ENTITY_RECORDS.decode(document, new ExtraInfo());

        assertTrue(restored.contains(id));
        assertEquals(new BsonArray(List.of(value)),
            ENTITY_RECORDS.encode(restored, new ExtraInfo()).asDocument().getArray("Identities"));
    }

    /// A saved chunk identity has the keys a native saved block reference has.
    @Test
    void aDeletedBlockIsSavedByItsWorldPositionAndReadBack() {
        var deleted = new Vector3i(-122, 105, 547);
        var records = new Tombstones<ChunkStore, Vector3i>();
        records.record(deleted);

        var saved = BLOCK_RECORDS.encode(records, new ExtraInfo()).asDocument();
        var restored = BLOCK_RECORDS.decode(saved, new ExtraInfo());

        assertEquals(1, saved.getArray("Identities").size(), "one deleted block must save one entry");
        var entry = saved.getArray("Identities").get(0).asDocument();
        assertEquals(new BsonInt32(-122), entry.get("X"), saved.toString());
        assertEquals(new BsonInt32(105), entry.get("Y"), saved.toString());
        assertEquals(new BsonInt32(547), entry.get("Z"), saved.toString());
        assertTrue(restored.contains(deleted), saved.toString());
        assertFalse(restored.contains(new Vector3i(-122, 105, 548)));
    }

    @Nonnull
    private static Tombstones.Installation<EntityStore, UUID> entityInstallation(ComponentRegistry<EntityStore> registry) {
        return new Tombstones.Installation<>(
            registry, Tombstones.ENTITY_RESOURCE_ID, CoreServerTracker.IDENTITY_CODEC);
    }

    @Nonnull
    private static Tombstones.Installation<ChunkStore, Vector3i> chunkInstallation(ComponentRegistry<ChunkStore> registry) {
        return new Tombstones.Installation<>(
            registry, Tombstones.CHUNK_RESOURCE_ID, Vector3iUtil.CODEC);
    }

    static Stream<Arguments> installations() {
        return Stream.of(
            Arguments.of(StoreKind.ENTITY, Tombstones.ENTITY_RESOURCE_ID),
            Arguments.of(StoreKind.CHUNK, Tombstones.CHUNK_RESOURCE_ID));
    }

    static Stream<Arguments> recordSnapshots() {
        return Stream.of(
            Arguments.of(StoreKind.ENTITY, new UUID(0, 1), new UUID(0, 2)),
            Arguments.of(StoreKind.CHUNK, new Vector3i(0, 0, 1), new Vector3i(0, 0, 2)));
    }

    private enum StoreKind {
        ENTITY,
        CHUNK
    }

    @SuppressWarnings("unchecked")
    private static ComponentRegistry<Object> newRegistry(StoreKind kind) {
        return kind == StoreKind.ENTITY
            ? (ComponentRegistry<Object>) (ComponentRegistry<?>) new ComponentRegistry<EntityStore>()
            : (ComponentRegistry<Object>) (ComponentRegistry<?>) new ComponentRegistry<ChunkStore>();
    }

    @SuppressWarnings("unchecked")
    private static Tombstones.Installation<?, ?> install(StoreKind kind, ComponentRegistry<Object> registry) {
        return kind == StoreKind.ENTITY
            ? entityInstallation((ComponentRegistry<EntityStore>) (ComponentRegistry<?>) registry)
            : chunkInstallation((ComponentRegistry<ChunkStore>) (ComponentRegistry<?>) registry);
    }

    @SuppressWarnings("unchecked")
    private static Tombstones<Object, Object> newRecords(StoreKind kind) {
        return kind == StoreKind.ENTITY
            ? (Tombstones<Object, Object>) (Tombstones<?, ?>) new Tombstones<EntityStore, UUID>()
            : (Tombstones<Object, Object>) (Tombstones<?, ?>) new Tombstones<ChunkStore, Vector3i>();
    }

    private static byte[] binary(UUID id) {
        var bytes = new byte[16];
        UUIDBinaryCodec.writeLongToArrayBigEndian(bytes, 0, id.getMostSignificantBits());
        UUIDBinaryCodec.writeLongToArrayBigEndian(bytes, 8, id.getLeastSignificantBits());
        return bytes;
    }

    /// Keeps the resources a Store hands it, the way a world's disk storage receives them.
    private static final class RecordingStorage implements IResourceStorage {
        private final List<Resource<?>> saved = new ArrayList<>();
        private final Set<Store<?>> stores = Collections.newSetFromMap(new IdentityHashMap<>());

        @Nonnull
        @Override
        public <T extends Resource<ECS_TYPE>, ECS_TYPE> CompletableFuture<T> load(
            @Nonnull Store<ECS_TYPE> store,
            @Nonnull ComponentRegistry.Data<ECS_TYPE> data,
            @Nonnull ResourceType<ECS_TYPE, T> resourceType
        ) {
            return CompletableFuture.completedFuture(data.createResource(resourceType));
        }

        @Nonnull
        @Override
        public <T extends Resource<ECS_TYPE>, ECS_TYPE> CompletableFuture<Void> save(
            @Nonnull Store<ECS_TYPE> store,
            @Nonnull ComponentRegistry.Data<ECS_TYPE> data,
            @Nonnull ResourceType<ECS_TYPE, T> resourceType,
            T resource
        ) {
            saved.add(resource);
            stores.add(store);
            return CompletableFuture.completedFuture(null);
        }

        @Nonnull
        @Override
        public <T extends Resource<ECS_TYPE>, ECS_TYPE> CompletableFuture<Void> remove(
            @Nonnull Store<ECS_TYPE> store,
            @Nonnull ComponentRegistry.Data<ECS_TYPE> data,
            @Nonnull ResourceType<ECS_TYPE, T> resourceType
        ) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
