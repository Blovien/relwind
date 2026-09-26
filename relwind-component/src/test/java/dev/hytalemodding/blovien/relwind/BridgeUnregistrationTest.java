/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.BridgeStoreFixture;
import com.hypixel.hytale.component.BridgeStoreFixture.Blocks;
import com.hypixel.hytale.component.BridgeStoreFixture.Entities;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;

import static dev.hytalemodding.blovien.relwind.RelationshipTestFixtures.worldOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Unregistering a bridge type releases the storage and the retained links of both registries,
/// and closing either registry does the same.
class BridgeUnregistrationTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void unregisteringReleasesTheStorageOfBothRegistriesAndTheLinksOfBothTrackers() {
        try (var channels = new Channels()) {
            int entityComponents = channels.fixture.entityRegistry().getData().getComponentSize();
            int blockComponents = channels.fixture.blockRegistry().getData().getComponentSize();
            var anchoredTo = channels.anchoredTo();
            var source = channels.entity("source");
            var block = channels.block(7);
            relationships.addTarget(channels.world.entityStore(), source, anchoredTo, block, "cargo");
            channels.unloadBlock(7, block);
            assertTrue(channels.entityTracker.hasUnresolvedOutgoing(anchoredTo, source));
            assertTrue(channels.blockTracker.hasUnresolvedIncoming(anchoredTo, block));

            channels.entityTypes.unregisterRelationship(anchoredTo);

            assertEquals(entityComponents, channels.fixture.entityRegistry().getData().getComponentSize());
            assertEquals(blockComponents, channels.fixture.blockRegistry().getData().getComponentSize());
            assertFalse(channels.entityTracker.hasUnresolvedOutgoing(anchoredTo, source));
            assertFalse(channels.blockTracker.hasUnresolvedIncoming(anchoredTo, block));
        }
    }

    @Test
    void unregisteringLeavesTheSavedRecordsNamingTheirTypeAndTargetInstallation() {
        try (var channels = new Channels()) {
            var anchoredTo = channels.anchoredTo();
            var source = channels.entity("source");
            var block = channels.block(7);
            relationships.addTarget(channels.world.entityStore(), source, anchoredTo, block, "cargo");

            channels.entityTypes.unregisterRelationship(anchoredTo);

            var saved = channels.saved(source);
            var records = saved.getArray("Links");
            assertEquals(1, records.size());
            var record = records.get(0).asDocument();
            assertEquals("relwind:test/anchoredTo", record.getString("Type").getValue());
            assertEquals("CHUNK_POSITIONS", record.getString("TargetInstallation").getValue());
        }
    }

    @Test
    void recordsReleasedByUnregistrationLoadBackWithTheirTargetAndPayload() {
        try (var channels = new Channels()) {
            var anchoredTo = channels.anchoredTo();
            var source = channels.entity("source");
            var block = channels.block(7);
            relationships.addTarget(channels.world.entityStore(), source, anchoredTo, block, "cargo");
            channels.entityTypes.unregisterRelationship(anchoredTo);
            var saved = channels.saved(source);

            var registeredAgain = channels.anchoredTo();
            var loaded = channels.load("loaded", saved);

            assertEquals(1, relationships.getTargetCount(loaded, registeredAgain));
            assertSame(block, relationships.getFirstTarget(loaded, registeredAgain));
            assertEquals("cargo", relationships.getData(loaded, registeredAgain, block));
        }
    }

    @Test
    void aCommandOnAnUnregisteredTypeFailsWhereverItsTargetsLive() {
        try (var channels = new Channels()) {
            var anchoredTo = channels.anchoredTo();
            var follows = channels.entityTypes.registerRelationship(RelationshipTraits.defaults());
            var source = channels.entity("source");
            var block = channels.block(7);
            var other = channels.entity("other");

            channels.entityTypes.unregisterRelationship(anchoredTo);
            channels.entityTypes.unregisterRelationship(follows);

            var sameStore = assertThrows(IllegalStateException.class,
                () -> relationships.addTarget(channels.world.entityStore(), source, follows, other));
            var bridge = assertThrows(IllegalStateException.class,
                () -> relationships.addTarget(channels.world.entityStore(), source, anchoredTo, block, "cargo"));
            assertEquals(IllegalStateException.class, sameStore.getClass());
            assertEquals(IllegalStateException.class, bridge.getClass());
            assertTrue(bridge.getMessage().contains("ComponentType is invalid"), bridge.getMessage());
        }
    }

    @Test
    void closingTheTargetRegistryReleasesTheTypeFromTheRegistryOfItsSources() {
        try (var channels = new Channels()) {
            int entityComponents = channels.fixture.entityRegistry().getData().getComponentSize();
            int blockComponents = channels.fixture.blockRegistry().getData().getComponentSize();
            var anchoredTo = channels.anchoredTo();
            var source = channels.entity("source");
            var block = channels.block(7);
            relationships.addTarget(channels.world.entityStore(), source, anchoredTo, block, "cargo");
            channels.unloadBlock(7, block);

            channels.blockTypes.close();

            assertEquals(entityComponents, channels.fixture.entityRegistry().getData().getComponentSize());
            assertEquals(blockComponents, channels.fixture.blockRegistry().getData().getComponentSize());
            assertFalse(channels.entityTracker.hasUnresolvedOutgoing(anchoredTo, source));
            var failure = assertThrows(IllegalStateException.class,
                () -> relationships.addTarget(channels.world.entityStore(), source, anchoredTo, block, "cargo"));

            assertTrue(failure.getMessage().contains("ComponentType is invalid"), failure.getMessage());
        }
    }

    /// One entity installation and one block installation of the same world, as the server has.
    private static final class Channels implements AutoCloseable {
        private final BridgeStoreFixture fixture = new BridgeStoreFixture();
        private final BridgeStoreFixture.World world = fixture.addWorld("overworld");
        private final IdentityHashMap<Ref<Entities>, String> entityIds = new IdentityHashMap<>();
        private final IdentityHashMap<Ref<Blocks>, Integer> blockIds = new IdentityHashMap<>();
        private final RelationshipTypeRegistry<Entities> entityTypes =
            new RelationshipTypeRegistry<>(fixture.entityRegistry());
        private final RelationshipTypeRegistry<Blocks> blockTypes =
            new RelationshipTypeRegistry<>(fixture.blockRegistry());
        private final RelationshipTracker<Entities, String> entityTracker = entityTypes.installTracker(
            new TestPersistenceIdentity<>((store, ref) -> entityIds.get(ref), Codec.STRING,
                "ENTITIES", (store, id) -> false, peer -> worldOf(peer).entityStore()),
            TestStoreRuntime.inline());
        private final RelationshipTracker<Blocks, Integer> blockTracker = blockTypes.installTracker(
            new TestPersistenceIdentity<>((store, ref) -> blockIds.get(ref), Codec.INTEGER,
                "CHUNK_POSITIONS", (store, id) -> false, peer -> worldOf(peer).blockStore()),
            TestStoreRuntime.inline());
        private final RelationshipPersistence<Entities> persistence =
            entityTypes.installPersistence(entityTracker);

        private GenericRelationshipType<Entities, Blocks, String> anchoredTo() {
            return entityTypes.registerRelationship(
                "relwind:test/anchoredTo",
                blockTypes,
                String.class,
                Codec.STRING,
                RelationshipTraits.defaults().exclusive().retainOnDeactivation());
        }

        private Ref<Entities> entity(String id) {
            var ref = fixture.addEntity(world);
            entityIds.put(ref, id);
            entityTracker.onEntityLoaded(id, ref);
            return ref;
        }

        private Ref<Blocks> block(Integer id) {
            var ref = fixture.addBlock(world);
            blockIds.put(ref, id);
            blockTracker.onEntityLoaded(id, ref);
            return ref;
        }

        private void unloadBlock(Integer id, Ref<Blocks> ref) {
            var holder = world.blockStore().removeEntity(ref, RemoveReason.UNLOAD);
            blockTracker.onEntityUnloaded(id, ref,
                UnloadReason.DEACTIVATION, holder);
        }

        private BsonDocument saved(Ref<Entities> source) {
            return world.entityStore().getComponent(source, persistence.getComponentType()).getContent();
        }

        private Ref<Entities> load(String id, BsonDocument saved) {
            var ref = entity(id);
            world.entityStore().addComponent(ref, persistence.getComponentType(),
                (RelationshipMetadata<Entities>) RelationshipMetadata.CODEC.decode(saved.clone(),
                    new ExtraInfo()));
            persistence.restore(ref);
            return ref;
        }

        @Override
        public void close() {
            fixture.close();
        }
    }
}
