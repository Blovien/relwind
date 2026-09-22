/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package com.hypixel.hytale.component;

import javax.annotation.Nonnull;

import java.util.Objects;

/// Two registries with their own ECS type, and worlds that own one Store of each, the way
/// `EntityStore.REGISTRY` and `ChunkStore.REGISTRY` are shared by every world on the server.
/// Entities stand for the entity store and blocks for the chunk store.
public final class BridgeStoreFixture implements AutoCloseable {
    private final ComponentRegistry<Entities> entityRegistry = new ComponentRegistry<>();
    private final ComponentRegistry<Blocks> blockRegistry = new ComponentRegistry<>();
    private final ComponentType<Entities, Marker<Entities>> entityMarker;
    private final ComponentType<Blocks, Marker<Blocks>> blockMarker;
    private final ComponentType<Blocks, Solid> solidType;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public BridgeStoreFixture() {
        entityMarker = (ComponentType) entityRegistry.registerComponent(Marker.class, Marker::new);
        blockMarker = (ComponentType) blockRegistry.registerComponent(Marker.class, Marker::new);
        solidType = blockRegistry.registerComponent(Solid.class, Solid::new);
    }

    @Nonnull
    public ComponentType<Blocks, Solid> solidType() {
        return solidType;
    }

    @Nonnull
    public ComponentRegistry<Entities> entityRegistry() {
        return entityRegistry;
    }

    @Nonnull
    public ComponentRegistry<Blocks> blockRegistry() {
        return blockRegistry;
    }

    @Nonnull
    public World addWorld(String name) {
        var world = new World(name);
        world.entities.store = entityRegistry.addStore(world.entities, EmptyResourceStorage.get());
        world.blocks.store = blockRegistry.addStore(world.blocks, EmptyResourceStorage.get());
        return world;
    }

    @Nonnull
    public Ref<Entities> addEntity(World world) {
        return Objects.requireNonNull(
            world.entityStore().addEntity(Archetype.of(entityMarker), AddReason.SPAWN), "entity");
    }

    @Nonnull
    public Ref<Blocks> addBlock(World world) {
        return Objects.requireNonNull(
            world.blockStore().addEntity(Archetype.of(blockMarker), AddReason.SPAWN), "block");
    }

    @Nonnull
    public Ref<Blocks> addSolidBlock(World world) {
        return Objects.requireNonNull(
            world.blockStore().addEntity(Archetype.of(blockMarker, solidType), AddReason.SPAWN), "block");
    }

    /// Only the Store hands out a command buffer at runtime.
    @Nonnull
    public CommandBuffer<Entities> entityCommandBuffer(World world) {
        return new CommandBuffer<>(world.entityStore());
    }

    /// Only the Store consumes a command buffer at runtime.
    public static void consume(CommandBuffer<?> buffer) {
        buffer.consume();
    }

    @Override
    public void close() {
        blockRegistry.shutdown();
        entityRegistry.shutdown();
    }

    public static final class World {
        private final String name;
        private final Entities entities = new Entities(this);
        private final Blocks blocks = new Blocks(this);

        private World(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        @Nonnull
        public Store<Entities> entityStore() {
            return Objects.requireNonNull(entities.store, "entity store");
        }

        @Nonnull
        public Store<Blocks> blockStore() {
            return Objects.requireNonNull(blocks.store, "block store");
        }

        @Override
        public String toString() {
            return "World{" + name + '}';
        }
    }

    /// The external data of an entity Store, which knows the world that owns it.
    public static final class Entities {
        private final World world;
        private Store<Entities> store;

        private Entities(World world) {
            this.world = world;
        }

        @Nonnull
        public World world() {
            return world;
        }
    }

    public static final class Blocks {
        private final World world;
        private Store<Blocks> store;

        private Blocks(World world) {
            this.world = world;
        }

        @Nonnull
        public World world() {
            return world;
        }
    }

    public static final class Solid implements Component<Blocks> {
        public Solid() {
        }

        @Nonnull @Override
        public Solid clone() {
            return new Solid();
        }
    }

    /// A component every fixture entity carries so it has an archetype.
    public static final class Marker<ECS_TYPE> implements Component<ECS_TYPE> {
        public Marker() {
        }

        @Nonnull @Override
        public Marker<ECS_TYPE> clone() {
            return new Marker<>();
        }
    }
}
