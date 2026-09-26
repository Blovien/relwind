/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.BridgeStoreFixture;
import com.hypixel.hytale.component.BridgeStoreFixture.Blocks;
import com.hypixel.hytale.component.BridgeStoreFixture.Entities;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;

import org.bson.BsonDocument;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// The installations, registries and assertions that several relationship suites share.
final class RelationshipTestFixtures {
    private static final Relationships relationships = new Relationships();

    private RelationshipTestFixtures() {
    }

    /// Runs a same Store command on each side during a target write, while both modules are held.
    static final class IncomingWrites extends RefChangeSystem<Blocks, IncomingLinks<Entities, Blocks>> {
        final ComponentType<Blocks, IncomingLinks<Entities, Blocks>> incomingType;
        final ComponentType<Entities, OutgoingLink<Entities, Blocks>> outgoingType;
        final Ref<Entities> commandSource;
        final Store<Entities> entityStore;
        final GenericRelationshipType<Entities, Entities, Void> entityType;
        final Ref<Entities> entitySource;
        final Ref<Entities> entityTarget;
        final Store<Blocks> blockStore;
        final GenericRelationshipType<Blocks, Blocks, Void> blockType;
        final Ref<Blocks> blockSource;
        final Ref<Blocks> blockTarget;
        final List<String> sourceModuleRejections = new ArrayList<>();
        final List<String> targetModuleRejections = new ArrayList<>();
        final List<Boolean> sourceStoreUnwritten = new ArrayList<>();

        IncomingWrites(
            ComponentType<Blocks, IncomingLinks<Entities, Blocks>> incomingType,
            ComponentType<Entities, OutgoingLink<Entities, Blocks>> outgoingType,
            Ref<Entities> commandSource,
            Store<Entities> entityStore,
            GenericRelationshipType<Entities, Entities, Void> entityType,
            Ref<Entities> entitySource,
            Ref<Entities> entityTarget,
            Store<Blocks> blockStore,
            GenericRelationshipType<Blocks, Blocks, Void> blockType,
            Ref<Blocks> blockSource,
            Ref<Blocks> blockTarget
        ) {
            this.incomingType = incomingType;
            this.outgoingType = outgoingType;
            this.commandSource = commandSource;
            this.entityStore = entityStore;
            this.entityType = entityType;
            this.entitySource = entitySource;
            this.entityTarget = entityTarget;
            this.blockStore = blockStore;
            this.blockType = blockType;
            this.blockSource = blockSource;
            this.blockTarget = blockTarget;
        }

        @Nonnull @Override
        public Query<Blocks> getQuery() {
            return incomingType;
        }

        @Nonnull @Override
        public ComponentType<Blocks, IncomingLinks<Entities, Blocks>> componentType() {
            return incomingType;
        }

        @Override
        public void onComponentAdded(
            Ref<Blocks> ref,
            IncomingLinks<Entities, Blocks> component,
            Store<Blocks> store,
            CommandBuffer<Blocks> commandBuffer
        ) {
            sourceStoreUnwritten.add(entityStore.getComponent(commandSource, outgoingType) == null);
            sourceModuleRejections.add(assertThrows(IllegalStateException.class,
                () -> relationships.addTarget(entityStore, entitySource, entityType, entityTarget))
                .getMessage());
            targetModuleRejections.add(assertThrows(IllegalStateException.class,
                () -> relationships.addTarget(blockStore, blockSource, blockType, blockTarget))
                .getMessage());
        }

        @Override
        public void onComponentSet(
            Ref<Blocks> ref,
            @Nullable IncomingLinks<Entities, Blocks> oldComponent,
            IncomingLinks<Entities, Blocks> newComponent,
            Store<Blocks> store,
            CommandBuffer<Blocks> commandBuffer
        ) {
        }

        @Override
        public void onComponentRemoved(
            Ref<Blocks> ref,
            IncomingLinks<Entities, Blocks> component,
            Store<Blocks> store,
            CommandBuffer<Blocks> commandBuffer
        ) {
        }
    }



    /// Two installations of one world, each with its own identities and transitions. A bridge link
    /// crosses both trackers.
    static final class LinkedInstallations implements AutoCloseable {
        final BridgeStoreFixture fixture = new BridgeStoreFixture();
        final BridgeStoreFixture.World world = fixture.addWorld("overworld");
        final IdentityHashMap<Ref<Entities>, String> entityIds = new IdentityHashMap<>();
        final IdentityHashMap<Ref<Blocks>, Integer> blockIds = new IdentityHashMap<>();
        final List<Runnable> entityTasks = new ArrayList<>();
        final List<Runnable> blockTasks = new ArrayList<>();
        final RelationshipTypeRegistry<Entities> entityTypes = new RelationshipTypeRegistry<>(
            fixture.entityRegistry());
        final RelationshipTypeRegistry<Blocks> blockTypes = new RelationshipTypeRegistry<>(
            fixture.blockRegistry());
        final RelationshipTracker<Entities, String> entityTracker = entityTypes.installTracker(
            entityIdentity(entityIds::get), TestStoreRuntime.executing((store, task) -> entityTasks.add(task)));
        final RelationshipTracker<Blocks, Integer> blockTracker = blockTypes.installTracker(
            new TestPersistenceIdentity<>((store, ref) -> blockIds.get(ref), Codec.INTEGER,
                "CHUNK_POSITIONS", this::isDeleted, peer -> worldOf(peer).blockStore()),
            TestStoreRuntime.executing((store, task) -> blockTasks.add(task)));
        final RelationshipPersistence<Entities> persistence = entityTypes.installPersistence(
            entityTracker);
        final Set<Integer> deletedBlocks = new HashSet<>();
        final List<Store<Blocks>> deletionEvidence = new ArrayList<>();
        /// the deletion evidence of the block installation, which a loading entity source reads
        /// for a saved target that names it
        final RelationshipPersistence<Blocks> blockPersistence = blockTypes.installPersistence(
            blockTracker);

        boolean isDeleted(Store<Blocks> store, Integer id) {
            deletionEvidence.add(store);
            return deletedBlocks.contains(id);
        }


        @Nonnull
        Ref<Entities> entity(String id) {
            var ref = fixture.addEntity(world);
            entityIds.put(ref, id);
            entityTracker.onEntityLoaded(id, ref);
            return ref;
        }

        @Nonnull
        Ref<Blocks> block(Integer id) {
            var ref = fixture.addBlock(world);
            blockIds.put(ref, id);
            blockTracker.onEntityLoaded(id, ref);
            return ref;
        }

        /// A source loaded later reads this deletion as evidence that its target is gone.
        void deleteBlock(Integer id, Ref<Blocks> ref) {
            deletedBlocks.add(id);
            world.blockStore().removeEntity(ref, RemoveReason.REMOVE);
            blockTracker.onEntityDeleted(id, ref);
            runTransitions();
        }

        /// The saved shape of a source's records, which native saving writes and a later load reads.
        @Nonnull
        BsonDocument savedRecords(Ref<Entities> source) {
            return world.entityStore().getComponent(source, persistence.getComponentType()).getContent();
        }

        /// Loads an entity carrying saved records, as a loading linked entity restores them.
        @Nonnull
        Ref<Entities> load(String id, BsonDocument saved) {
            var ref = entity(id);
            world.entityStore().addComponent(ref, persistence.getComponentType(), decoded(saved));
            persistence.restore(ref);
            runTransitions();
            return ref;
        }

        @Nonnull
        static RelationshipMetadata<Entities> decoded(BsonDocument saved) {
            @SuppressWarnings("unchecked")
            var metadata = (RelationshipMetadata<Entities>) RelationshipMetadata.CODEC
                .decode(saved.clone(), new ExtraInfo());
            return metadata;
        }

        void unloadEntity(String id, Ref<Entities> ref) {
            var holder = world.entityStore().removeEntity(ref, RemoveReason.UNLOAD);
            entityTracker.onEntityUnloaded(id, ref,
                UnloadReason.DEACTIVATION, holder);
        }

        void unloadBlock(Integer id, Ref<Blocks> ref) {
            var holder = world.blockStore().removeEntity(ref, RemoveReason.UNLOAD);
            blockTracker.onEntityUnloaded(id, ref,
                UnloadReason.DEACTIVATION, holder);
        }

        /// Runs the deferred writes of both sides until neither side has any left.
        void runTransitions() {
            while (!entityTasks.isEmpty() || !blockTasks.isEmpty()) {
                if (!entityTasks.isEmpty()) {
                    entityTasks.remove(0).run();
                } else {
                    blockTasks.remove(0).run();
                }
            }
        }

        @Override
        public void close() {
            entityTracker.close();
            blockTracker.close();
            fixture.close();
        }
    }

    static <LINK_DATA> void assertLink(
        GenericRelationshipType<Entities, Blocks, LINK_DATA> type,
        Ref<Entities> source,
        Ref<Blocks> target,
        @Nullable LINK_DATA data
    ) {
        assertSame(target, relationships.getFirstTarget(source, type));
        assertEquals(1, relationships.getTargetCount(source, type));
        assertEquals(1, relationships.getIncomingCount(target, type));
        assertSame(data, relationships.getData(source, type, target));
    }

    static RelationshipTypeRegistry<Entities> entityTypes(BridgeStoreFixture fixture) {
        var types = new RelationshipTypeRegistry<>(fixture.entityRegistry());
        types.installTracker(entityIdentity(ref -> null), TestStoreRuntime.inline());
        return types;
    }

    /// A block sourced type cannot declare `onDeleteTarget(DELETE)`.
    static RelationshipTypeRegistry<Blocks> blockTypes(BridgeStoreFixture fixture) {
        var types = new RelationshipTypeRegistry<>(fixture.blockRegistry());
        types.installTracker(new TestPersistenceIdentity<>((store, ref) -> null, Codec.INTEGER,
            "CHUNK_POSITIONS", (store, id) -> false, peer -> worldOf(peer).blockStore()),
            TestStoreRuntime.<Blocks>inline().withoutDeletion());
        return types;
    }

    static TestPersistenceIdentity<Entities, String> entityIdentity(java.util.function.Function<Ref<Entities>, String> ids) {
        return new TestPersistenceIdentity<>((store, ref) -> ids.apply(ref), Codec.STRING,
            "ENTITIES", (store, id) -> false, peer -> worldOf(peer).entityStore());
    }

    static BridgeStoreFixture.World worldOf(Store<?> peer) {
        return switch (peer.getExternalData()) {
            case Entities entities -> entities.world();
            case Blocks blocks -> blocks.world();
            default -> throw new IllegalArgumentException("Unknown peer Store");
        };
    }

    record Anchor(String face) {
        Anchor {
            Objects.requireNonNull(face, "face");
        }
    }}
