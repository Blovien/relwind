/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package com.hypixel.hytale.component;

import com.hypixel.hytale.component.system.tick.TickingSystem;

import javax.annotation.Nonnull;
import java.util.Objects;

/// A real Hytale Store built for tests, with the registry, entity and tick helpers the suites use.
public final class StoreFixture implements AutoCloseable {
    private final Thread callingThread = Thread.currentThread();
    private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
    private final ComponentType<Object, Position> positionType = registry.registerComponent(Position.class, Position::new);
    private final ComponentType<Object, Player> playerType = registry.registerComponent(Player.class, Player::new);
    private final TickRecorder tickRecorder = new TickRecorder();
    private final Store<Object> store = registry.addStore(new Object(), EmptyResourceStorage.get());
    private final CommandBuffer<Object> commandBuffer = new CommandBuffer<>(store);

    public StoreFixture() {
        registry.registerSystem(tickRecorder);
    }

    public static void main(String[] args) {
        try (var fixture = new StoreFixture()) {
            var playerEntity = fixture.addEntity(new Position(4, 8), new Player("player"));
            var positionedEntity = fixture.addEntity(new Position(-3, 12), null);

            require(fixture.store().getEntityCount() == 2, "Store must contain both fixture entities");
            require(playerEntity.getStore() == fixture.store(), "Player entity must belong to the fixture Store");
            require(positionedEntity.getStore() == fixture.store(), "Positioned entity must belong to the fixture Store");
            requirePosition(fixture, playerEntity, 4, 8);
            requirePosition(fixture, positionedEntity, -3, 12);

            var storedPlayer = fixture.store().getComponent(playerEntity, fixture.playerType());
            require(storedPlayer != null, "Player entity must retain its player component");
            require("player".equals(storedPlayer.name), "Player component must retain its supplied name");

            fixture.tick(0.05f);

            require(fixture.lastTickThread() == fixture.callingThread(), "Store must tick on the calling thread");
            require(fixture.tickRecorder.lastStore == fixture.store(), "Tick system must receive the fixture Store");
            require(fixture.tickRecorder.lastSeconds == 0.05f, "Tick system must receive the supplied duration");
            require(fixture.commandBuffer().getStore() == fixture.store(), "Command buffer must belong to the fixture Store");
            System.out.println("StoreFixture checks passed");
        }
    }

    public ComponentRegistry<Object> registry() {
        return registry;
    }

    public Store<Object> store() {
        return store;
    }

    public Store<Object> addStore() {
        requireCallingThread();
        return registry.addStore(new Object(), EmptyResourceStorage.get());
    }

    public CommandBuffer<Object> commandBuffer() {
        return commandBuffer;
    }

    public ComponentType<Object, Position> positionType() {
        return positionType;
    }

    public ComponentType<Object, Player> playerType() {
        return playerType;
    }

    public Thread callingThread() {
        return callingThread;
    }

    public Thread lastTickThread() {
        return tickRecorder.lastThread;
    }

    public Ref<Object> addEntity(Position position, Player player) {
        requireCallingThread();
        Objects.requireNonNull(position, "position");
        var archetype = Archetype.of(positionType);
        if (player != null) {
            archetype = Archetype.add(archetype, playerType);
        }

        var ref = Objects.requireNonNull(store.addEntity(archetype, AddReason.SPAWN), "Store refused the fixture entity");
        var storedPosition = store.getComponent(ref, positionType);
        assert storedPosition != null;
        storedPosition.x = position.x;
        storedPosition.y = position.y;
        if (player != null) {
            Objects.requireNonNull(store.getComponent(ref, playerType)).name = player.name;
        }
        return ref;
    }

    public void tick(float seconds) {
        requireCallingThread();
        store.tick(seconds);
    }

    @Override
    public void close() {
        requireCallingThread();
        commandBuffer.validateEmpty();
        registry.shutdown();
    }

    private static void requirePosition(StoreFixture fixture, Ref<Object> ref, int x, int y) {
        var position = fixture.store().getComponent(ref, fixture.positionType());
        require(position != null, "Fixture entity must retain its position component");
        require(position.x == x && position.y == y, "Position component must retain its supplied values");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void requireCallingThread() {
        if (Thread.currentThread() != callingThread) {
            throw new IllegalStateException("StoreFixture must be used on its calling thread");
        }
    }

    private static final class TickRecorder extends TickingSystem<Object> {
        private Thread lastThread;
        private Store<Object> lastStore;
        private float lastSeconds;

        @Override
        public void tick(float seconds, int systemIndex, @Nonnull Store<Object> store) {
            lastThread = Thread.currentThread();
            lastStore = store;
            lastSeconds = seconds;
        }
    }

    public static final class Position implements Component<Object> {
        private int x;
        private int y;

        public Position() {
        }

        public Position(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        @Override
        public Position clone() {
            return new Position(x, y);
        }
    }

    public static final class Weapon implements Component<Object> {
        @Override
        public Weapon clone() {
            return new Weapon();
        }
    }

    public static final class Player implements Component<Object> {
        private String name;

        public Player() {
            this("");
        }

        public Player(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        public String name() {
            return name;
        }

        @Override
        public Player clone() {
            return new Player(name);
        }
    }
}
