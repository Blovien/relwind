/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.IComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.function.consumer.BooleanConsumer;
import com.hypixel.hytale.math.vector.Vector3iUtil;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import dev.hytalemodding.blovien.relwind.RelationshipPersistence;
import dev.hytalemodding.blovien.relwind.RelationshipTracker;
import dev.hytalemodding.blovien.relwind.StoreRuntime;
import dev.hytalemodding.blovien.relwind.StoreInstallation;
import org.bson.BsonDocument;
import org.joml.Vector3i;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// One chunk Store registry carrying the section and block components Relwind reads. A real
/// chunk installation needs a server. This fixture registers those types itself, and its Store
/// carries no external data.
// TODO: Replace ChunkColumn when Hytale finishes its chunk-section storage migration.
// Shared-source ChunkColumn remains the serialization bridge used by the native chunk systems.
final class ChunkStoreFixture implements AutoCloseable {
    private final ComponentRegistry<ChunkStore> registry = new ComponentRegistry<>();
    private final List<BooleanConsumer> pluginShutdown = new ArrayList<>();
    private final ComponentType<ChunkStore, ChunkSection> sectionType;
    private final ComponentType<ChunkStore, BlockComponentSection> blockComponentsType;
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockStateType;
    private final ComponentType<ChunkStore, ChunkColumn> columnType;
    private final ChunkPositions positions;
    private final Tombstones.Installation<ChunkStore, Vector3i> deletions;
    private final StoreRuntime<ChunkStore> runtime;
    private final StoreInstallation<ChunkStore, Vector3i> installation;
    private final RelationshipPersistence<ChunkStore> persistence;
    private final Store<ChunkStore> store;
    private final Store<ChunkStore> secondStore;

    ChunkStoreFixture() {
        this(false);
    }

    /// Registers through a plugin's ComponentRegistryProxy when `throughProxy`. The section and
    /// block types stay on the registry itself, because LegacyModule and BlockModule own them on
    /// a running server.
    ChunkStoreFixture(boolean throughProxy) {
        IComponentRegistry<ChunkStore> registrar = throughProxy ? new ComponentRegistryProxy<>(pluginShutdown, registry) : registry;
        sectionType = registry.registerComponent(ChunkSection.class, () -> new ChunkSection(null, 0, 0, 0, false));
        blockComponentsType = registry.registerComponent(BlockComponentSection.class, BlockComponentSection::new);
        // BlockModule registers BlockStateInfo with a throwing supplier too, because a block state
        // always loads with its section and index
        blockStateType = registry.registerComponent(BlockModule.BlockStateInfo.class, () -> {
            throw new UnsupportedOperationException();
        });
        columnType = registry.registerComponent(ChunkColumn.class, ChunkColumn::new);
        positions = new ChunkPositions(sectionType, blockComponentsType, blockStateType);
        deletions = new Tombstones.Installation<>(
            registry, registrar, Tombstones.CHUNK_RESOURCE_ID, Vector3iUtil.CODEC);
        runtime = new FixtureRuntime();
        installation = new StoreInstallation<>(registry, registrar,
            new ChunkPersistenceIdentity(positions, deletions), runtime);
        persistence = installation.installPersistence();
        var sources = Query.and(positions.blockState(), persistence.getComponentType());
        registrar.registerSystem(new RelationshipPersistenceSystem<>(persistence, sources, ChunkRefSystem.class));
        registrar.registerSystem(new RelationshipPersistenceSystem.RestoreSystem<>(persistence, sources, runtime));
        store = registry.addStore(null, EmptyResourceStorage.get());
        // a second Store of the same kind on the same registry, the way a second world has one
        secondStore = registry.addStore(null, EmptyResourceStorage.get());
    }

    @Nonnull
    RelationshipPersistence<ChunkStore> persistence() {
        return persistence;
    }

    @Nonnull
    ComponentRegistry<ChunkStore> registry() {
        return registry;
    }

    /// Stops runtime tracking, leaving registrations to the plugin shutdown tasks.
    void runIntegrationClose() {
        installation.getTracker().close();
    }

    /// Runs the shutdown tasks from the last one back, as PluginBase.cleanup does.
    void runPluginShutdown() {
        for (int index = pluginShutdown.size() - 1; index >= 0; index--) {
            pluginShutdown.get(index).accept(false);
        }
    }

    void shutDownRegistry() {
        registry.shutdown();
    }

    /// Unloads a section and returns what Hytale saves of it: the holder components that have a
    /// codec, which is the form a column writes its sections in.
    @Nonnull
    BsonDocument unload(Ref<ChunkStore> ref) {
        return unload(store, ref);
    }

    @Nonnull
    BsonDocument unload(Store<ChunkStore> in, Ref<ChunkStore> ref) {
        return registry.serialize(in.removeEntity(ref, RemoveReason.UNLOAD));
    }

    /// Loads a saved section back at its position. The section codec saves no coordinates. The
    /// real loader rebuilds them from the column the section belongs to.
    @Nonnull
    Ref<ChunkStore> loadSection(BsonDocument saved, int x, int y, int z) {
        return loadSection(store, saved, x, y, z);
    }

    @Nonnull
    Ref<ChunkStore> loadSection(Store<ChunkStore> in, BsonDocument saved, int x, int y, int z) {
        var holder = registry.deserialize(saved);
        holder.putComponent(sectionType, new ChunkSection(null, x, y, z, false));
        holder.putComponent(blockComponentsType, new BlockComponentSection());
        return Objects.requireNonNull(in.addEntity(holder, AddReason.LOAD), "section entity");
    }

    /// Loads a saved block entity back into `sectionRef` at `index`. The block holder saves no
    /// position. The real loader rebuilds it from the section that holds it.
    @Nonnull
    Ref<ChunkStore> loadBlock(BsonDocument saved, Ref<ChunkStore> sectionRef, short index) {
        return loadBlock(store, saved, sectionRef, index);
    }

    @Nonnull
    Ref<ChunkStore> loadBlock(Store<ChunkStore> in, BsonDocument saved, Ref<ChunkStore> sectionRef, short index) {
        var holder = registry.deserialize(saved);
        holder.putComponent(blockStateType, new BlockModule.BlockStateInfo(index, sectionRef));
        var ref = Objects.requireNonNull(in.addEntity(holder, AddReason.LOAD), "block entity");
        blockComponents(in, sectionRef).addBlockReference(index, ref);
        return ref;
    }

    @Nonnull
    StoreInstallation<ChunkStore, Vector3i> installation() {
        return installation;
    }

    @Nonnull
    Store<ChunkStore> store() {
        return store;
    }

    @Nonnull
    Store<ChunkStore> secondStore() {
        return secondStore;
    }

    @Nonnull
    ChunkPositions positions() {
        return positions;
    }

    @Nonnull
    RelationshipTracker<ChunkStore, Vector3i> relationships() {
        return installation.getTracker();
    }

    @Nonnull
    Tombstones<ChunkStore, Vector3i> deletions() {
        return deletions.getTombstonesIn(store);
    }

    @Nonnull
    Tombstones<ChunkStore, Vector3i> deletionsIn(Store<ChunkStore> in) {
        return deletions.getTombstonesIn(in);
    }

    @Nonnull
    ChunkSection section(Ref<ChunkStore> ref) {
        return Objects.requireNonNull(store.getComponent(ref, sectionType), "section");
    }

    @Nonnull
    BlockComponentSection blockComponents(Ref<ChunkStore> ref) {
        return blockComponents(store, ref);
    }

    @Nonnull
    BlockComponentSection blockComponents(Store<ChunkStore> in, Ref<ChunkStore> ref) {
        return Objects.requireNonNull(in.getComponent(ref, blockComponentsType), "block components");
    }

    @Nonnull
    Ref<ChunkStore> addSection(int x, int y, int z, AddReason reason) {
        return addSection(store, x, y, z, reason);
    }

    @Nonnull
    Ref<ChunkStore> addSection(Store<ChunkStore> in, int x, int y, int z, AddReason reason) {
        var holder = registry.newHolder();
        holder.putComponent(sectionType, new ChunkSection(null, x, y, z, false));
        holder.putComponent(blockComponentsType, new BlockComponentSection());
        return Objects.requireNonNull(in.addEntity(holder, reason), "section entity");
    }

    /// Adds a block entity to a section and occupies its index, as the native block paths do.
    @Nonnull
    Ref<ChunkStore> addBlock(Ref<ChunkStore> sectionRef, short index, AddReason reason) {
        return addBlock(store, sectionRef, index, reason);
    }

    @Nonnull
    Ref<ChunkStore> addBlock(Store<ChunkStore> in, Ref<ChunkStore> sectionRef, short index, AddReason reason) {
        var holder = registry.newHolder();
        holder.putComponent(blockStateType, new BlockModule.BlockStateInfo(index, sectionRef));
        var ref = Objects.requireNonNull(in.addEntity(holder, reason), "block entity");
        blockComponents(in, sectionRef).addBlockReference(index, ref);
        return ref;
    }

    /// A chunk column, which carries neither a section position nor a block position.
    @Nonnull
    Ref<ChunkStore> addColumn() {
        var holder = registry.newHolder();
        holder.putComponent(columnType, new ChunkColumn());
        return Objects.requireNonNull(store.addEntity(holder, AddReason.LOAD), "column entity");
    }

    @Nonnull
    Holder<ChunkStore> parkedBlock(Ref<ChunkStore> sectionRef, short index) {
        var holder = registry.newHolder();
        holder.putComponent(blockStateType, new BlockModule.BlockStateInfo(index, sectionRef));
        return holder;
    }

    @Override
    public void close() {
        installation.close();
        deletions.close();
        registry.shutdown();
    }

    /// The production runtime reaches a Store's world and Hytale's saving marks. FixtureRuntime
    /// runs a deferred write inline, under the access the production runtime waits for.
    private final class FixtureRuntime implements StoreRuntime<ChunkStore> {
        @Override
        public void execute(Store<ChunkStore> chunkStore, Runnable action) {
            chunkStore.assertThread();
            chunkStore.assertWriteProcessing();
            action.run();
        }

        @Override
        public void markNeedsSaving(ComponentAccessor<ChunkStore> accessor, Ref<ChunkStore> ref) {
            positions.markNeedsSaving(accessor, ref);
        }

        @Override
        public void markNeedsSaving(Holder<ChunkStore> parked) {
            positions.markNeedsSaving(parked);
        }

        @Override
        public boolean isDeletionSupported() {
            return false;
        }

        @Nonnull
        @Override
        public RefSystem<ChunkStore> getTransitionSystem(RelationshipTracker<ChunkStore, ?> installed) {
            @SuppressWarnings("unchecked")
            var tracker = (RelationshipTracker<ChunkStore, Vector3i>) installed;
            return new ChunkRefSystem(positions, tracker, deletions);
        }
    }
}
