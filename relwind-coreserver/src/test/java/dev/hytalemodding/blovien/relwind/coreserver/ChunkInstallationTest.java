/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3iUtil;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.GenericRelationshipType;
import dev.hytalemodding.blovien.relwind.PersistenceIdentity;
import dev.hytalemodding.blovien.relwind.RelationshipPersistence;
import dev.hytalemodding.blovien.relwind.RelationshipRules;
import dev.hytalemodding.blovien.relwind.RelationshipTracker;
import dev.hytalemodding.blovien.relwind.RelationshipType;
import dev.hytalemodding.blovien.relwind.RelationshipTypeRegistry;
import dev.hytalemodding.blovien.relwind.Relationships;
import dev.hytalemodding.blovien.relwind.StoreRuntime;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The chunk installation saves links the way the entity one does: its registry accepts named
/// types, and a chunk-sourced link comes back with the block entity it started at. A real chunk
/// installation needs a server.
class ChunkInstallationTest {
    private static final Relationships relationships = new Relationships();

    private static final String ANCHORED_TO = "relwind:test/chunk-anchored";
    private static final String TENDED_BY = "relwind:test/chunk-tended-by";
    private static final short SOURCE_INDEX = (short) ChunkUtil.indexBlock(11, 4, 7);

    @ParameterizedTest
    @MethodSource("registrationKinds")
    void theChunkRegistryAcceptsEveryRegistrationKind(String name) {
        try (var fixture = new ChunkStoreFixture()) {
            var chunkTypes = fixture.installation().getRelationshipTypeRegistry();

            var type = register(chunkTypes, name);
            var holdingSource = fixture.addSection(2, 5, -3, AddReason.LOAD);
            var source = fixture.addBlock(holdingSource, SOURCE_INDEX, AddReason.LOAD);
            var holding = fixture.addSection(2, 6, -3, AddReason.LOAD);
            var target = fixture.addBlock(holding, (short) 11, AddReason.LOAD);

            relationships.addTarget(fixture.store(), source, type, target);

            assertSame(target, relationships.getFirstTarget(source, type));
        }
    }

    /// A block entity that links another one saves that link with itself.
    @Test
    void aNamedChunkLinkIsSavedWithItsBlockEntity() {
        try (var fixture = new ChunkStoreFixture()) {
            var persistence = fixture.persistence();
            var anchoredTo = chunkType(fixture);
            var holdingSource = fixture.addSection(2, 5, -3, AddReason.LOAD);
            var source = fixture.addBlock(holdingSource, SOURCE_INDEX, AddReason.LOAD);
            var holding = fixture.addSection(2, 6, -3, AddReason.LOAD);
            var target = fixture.addBlock(holding, (short) 11, AddReason.LOAD);
            // adding the block already marked the section
            var blocks = fixture.blockComponents(holdingSource);
            blocks.consumeNeedsSaving();

            relationships.addTarget(fixture.store(), source, anchoredTo, target);

            assertNotNull(fixture.store().getComponent(source, persistence.getComponentType()));
            assertTrue(blocks.needsSaving());
            var saved = fixture.unload(source);
            var records = links(saved);
            assertEquals(1, records.size(), saved.toString());
            var record = records.get(0).asDocument();
            assertEquals(ANCHORED_TO, record.getString("Type").getValue());
            assertEquals("CHUNK_POSITIONS", record.getString("TargetInstallation").getValue());
        }
    }

    /// The saved block entity loads again in a world that has no memory of the first one.
    @Test
    void aSavedNamedChunkLinkIsRestoredWhenItsTargetIsBack() {
        var saved = savedNamedLink();
        try (var fixture = new ChunkStoreFixture()) {
            var anchoredTo = chunkType(fixture);
            var holdingSource = fixture.addSection(2, 5, -3, AddReason.LOAD);

            var source = fixture.loadBlock(saved, holdingSource, SOURCE_INDEX);

            assertNull(relationships.getFirstTarget(source, anchoredTo));
            assertTrue(relationships.hasUnresolvedTargets(source, anchoredTo));

            var holding = fixture.addSection(2, 6, -3, AddReason.LOAD);
            var target = fixture.addBlock(holding, (short) 11, AddReason.LOAD);

            assertSame(target, relationships.getFirstTarget(source, anchoredTo));
        }
    }

    /// A chunk-sourced bridge type saves the UUID of its entity target.
    @Test
    void aChunkSourcedBridgeLinkSavesItsEntityTargetByUuid() {
        var targetId = UUID.randomUUID();
        try (var entities = new EntitySide(); var fixture = new ChunkStoreFixture()) {
            var tendedBy = chunkType(fixture, entities.types);
            var holdingSource = fixture.addSection(2, 5, -3, AddReason.LOAD);
            var source = fixture.addBlock(holdingSource, SOURCE_INDEX, AddReason.LOAD);

            relationships.addTarget(fixture.store(), source, tendedBy, entities.add(targetId));

            var record = links(fixture.unload(source)).getFirst().asDocument();
            assertEquals(TENDED_BY, record.getString("Type").getValue());
            assertEquals("ENTITIES", record.getString("TargetInstallation").getValue());
            assertEquals(targetId, record.getBinary("Target").asUuid());
        }
    }

    /// The reloaded block entity reads its target through the entity installation the type targets.
    @Test
    void aSavedChunkSourcedBridgeLinkRestoresItsEntityTargetByUuid() {
        var targetId = UUID.randomUUID();
        var saved = savedBridgeLink(targetId);
        try (var entities = new EntitySide(); var fixture = new ChunkStoreFixture()) {
            var tendedBy = chunkType(fixture, entities.types);
            var holdingSource = fixture.addSection(2, 5, -3, AddReason.LOAD);

            var source = fixture.loadBlock(saved, holdingSource, SOURCE_INDEX);

            assertNull(relationships.getFirstTarget(source, tendedBy));
            assertTrue(relationships.hasUnresolvedTargets(source, tendedBy));

            var target = entities.add(targetId);

            assertSame(target, relationships.getFirstTarget(source, tendedBy));
        }
    }

    /// A deletion cascade cannot remove a chunk position, and the chunk runtime is what says so.
    @Test
    void theChunkRuntimeDeletesNoLinkedEntityWhileTheEntityRuntimeDoes() {
        var entityRegistry = new ComponentRegistry<EntityStore>();
        var chunkRegistry = new ComponentRegistry<ChunkStore>();
        var deletions = new Tombstones.Installation<>(
            entityRegistry, Tombstones.ENTITY_RESOURCE_ID, CoreServerTracker.IDENTITY_CODEC);
        var blocks = new Tombstones.Installation<>(
            chunkRegistry, Tombstones.CHUNK_RESOURCE_ID, Vector3iUtil.CODEC);
        try {
            var entityRuntime = new EntityStoreRuntime(deletions);
            var chunkRuntime = new ChunkStoreRuntime(new ChunkPositions(null, null, null), blocks);

            assertTrue(entityRuntime.isDeletionSupported());
            assertFalse(chunkRuntime.isDeletionSupported());
        } finally {
            blocks.close();
            deletions.close();
            entityRegistry.shutdown();
            chunkRegistry.shutdown();
        }
    }

    @Test
    void aSecondEntitySourcedTypeWithTheSameNameIsRejected() {
        try (var entities = new EntitySide(); var fixture = new ChunkStoreFixture()) {
            var chunkTypes = fixture.installation().getRelationshipTypeRegistry();
            entities.types.registerRelationship(
                "relwind:test/anchored-to", chunkTypes, RelationshipRules.single());

            assertThrows(IllegalArgumentException.class,
                () -> entities.types.registerRelationship(
                    "relwind:test/anchored-to", chunkTypes, RelationshipRules.single()));
        }
    }

    static Stream<Arguments> registrationKinds() {
        return Stream.of(
            Arguments.of(ANCHORED_TO),
            Arguments.of(TENDED_BY),
            Arguments.of((String) null));
    }

    @Nonnull
    private static RelationshipType<ChunkStore, Void> register(
        RelationshipTypeRegistry<ChunkStore> types,
        @Nullable String name
    ) {
        return name == null ? types.registerRelationship(RelationshipRules.single())
            : types.registerRelationship(name, RelationshipRules.single());
    }

    /// One saved block entity source, linked to the block at index 11 of section (2, 6, -3).
    @Nonnull
    private static BsonDocument savedNamedLink() {
        try (var fixture = new ChunkStoreFixture()) {
            var anchoredTo = chunkType(fixture);
            var holdingSource = fixture.addSection(2, 5, -3, AddReason.LOAD);
            var source = fixture.addBlock(holdingSource, SOURCE_INDEX, AddReason.LOAD);
            var holding = fixture.addSection(2, 6, -3, AddReason.LOAD);
            relationships.addTarget(fixture.store(), source, anchoredTo,
                fixture.addBlock(holding, (short) 11, AddReason.LOAD));
            return fixture.unload(source);
        }
    }

    /// One saved block entity source, linked to the entity `targetId` of the entity side.
    @Nonnull
    private static BsonDocument savedBridgeLink(UUID targetId) {
        try (var entities = new EntitySide(); var fixture = new ChunkStoreFixture()) {
            var tendedBy = chunkType(fixture, entities.types);
            var holdingSource = fixture.addSection(2, 5, -3, AddReason.LOAD);
            var source = fixture.addBlock(holdingSource, SOURCE_INDEX, AddReason.LOAD);
            relationships.addTarget(fixture.store(), source, tendedBy, entities.add(targetId));
            return fixture.unload(source);
        }
    }

    @Nonnull
    private static BsonArray links(BsonDocument saved) {
        var components = saved.getDocument("Components");
        assertTrue(components.containsKey(RelationshipPersistence.COMPONENT_ID), saved.toString());
        return components.getDocument(RelationshipPersistence.COMPONENT_ID).getArray("Links");
    }

    /// The same-Store type both halves of the reload test register. It keeps its link while its
    /// section is away.
    @Nonnull
    private static RelationshipType<ChunkStore, Void> chunkType(ChunkStoreFixture fixture) {
        return fixture.installation().getRelationshipTypeRegistry()
            .registerRelationship(ANCHORED_TO, RelationshipRules.single().retainOnDeactivation());
    }

    @Nonnull
    private static GenericRelationshipType<ChunkStore, EntityStore, Void> chunkType(
        ChunkStoreFixture fixture,
        RelationshipTypeRegistry<EntityStore> entities
    ) {
        return fixture.installation().getRelationshipTypeRegistry()
            .registerRelationship(TENDED_BY, entities, RelationshipRules.single().retainOnDeactivation());
    }

    /// The entity side of the two-runtime seam: one entity installation on a bare registry, whose
    /// linked entities a test loads itself. The production identity and runtime both need the running
    /// server's entity module.
    private static final class EntitySide implements AutoCloseable {
        private final ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
        private final ComponentType<EntityStore, EntityIdentity> identityType =
            registry.registerComponent(EntityIdentity.class, () -> new EntityIdentity(null));
        private final Store<EntityStore> store = registry.addStore(null, EmptyResourceStorage.get());
        private final RelationshipTypeRegistry<EntityStore> types = new RelationshipTypeRegistry<>(registry);
        private final RelationshipTracker<EntityStore, UUID> tracker =
            types.installTracker(new EntityIdentities(), new EntityRuntime());

        @Nonnull
        private Ref<EntityStore> add(UUID id) {
            var holder = registry.newHolder();
            holder.putComponent(identityType, new EntityIdentity(id));
            var ref = store.addEntity(holder, AddReason.LOAD);
            tracker.onEntityLoaded(id, ref);
            return ref;
        }

        @Override
        public void close() {
            types.close();
            registry.shutdown();
        }

        private final class EntityIdentities implements PersistenceIdentity<EntityStore, UUID> {
            @Nullable
            @Override
            public UUID getIdentity(Store<EntityStore> entityStore, Ref<EntityStore> ref) {
                var identity = entityStore.getComponent(ref, identityType);
                return identity == null ? null : identity.id;
            }

            @Nonnull
            @Override
            public Codec<UUID> getIdentityCodec() {
                return Codec.UUID_BINARY;
            }

            @Nonnull
            @Override
            public String getInstallationName() {
                return "ENTITIES";
            }

            @Override
            public boolean isDeleted(Store<EntityStore> entityStore, UUID id) {
                return false;
            }

            @Nullable
            @Override
            public Store<EntityStore> storeBeside(Store<?> peer) {
                return store;
            }
        }
    }

    private record EntityIdentity(@Nullable UUID id) implements Component<EntityStore> {

        @Nonnull
            @Override
            public EntityIdentity clone() {
                return new EntityIdentity(id);
            }
        }

    private static final class EntityRuntime implements StoreRuntime<EntityStore> {
        @Override
        public void execute(Store<EntityStore> store, Runnable action) {
            store.assertThread();
            store.assertWriteProcessing();
            action.run();
        }

        @Override
        public void markNeedsSaving(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref) { }

        @Override
        public void markNeedsSaving(Holder<EntityStore> holder) { }

        @Override
        public boolean isDeletionSupported() {
            return true;
        }

        @Nonnull
        @Override
        public RefSystem<EntityStore> getTransitionSystem(RelationshipTracker<EntityStore, ?> installed) {
            return new RefSystem<>() {
                @Override
                public Query<EntityStore> getQuery() {
                    return Query.not(Query.any());
                }

                @Override
                public void onEntityAdded(
                    Ref<EntityStore> ref,
                    AddReason reason,
                    Store<EntityStore> store,
                    CommandBuffer<EntityStore> buffer
                ) { }

                @Override
                public void onEntityRemove(
                    Ref<EntityStore> ref,
                    RemoveReason reason,
                    Store<EntityStore> store,
                    CommandBuffer<EntityStore> buffer
                ) { }
            };
        }
    }
}
