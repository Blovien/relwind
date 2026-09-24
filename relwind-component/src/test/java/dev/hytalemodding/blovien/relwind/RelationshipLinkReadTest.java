/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.BridgeStoreFixture;
import com.hypixel.hytale.component.BridgeStoreFixture.Blocks;
import com.hypixel.hytale.component.BridgeStoreFixture.Entities;
import com.hypixel.hytale.component.Ref;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import static dev.hytalemodding.blovien.relwind.RelationshipTestFixtures.blockTypes;
import static dev.hytalemodding.blovien.relwind.RelationshipTestFixtures.entityTypes;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RelationshipLinkReadTest {
    @ParameterizedTest
    @CsvSource({"ABSENT,false", "PRESENT,true", "REMOVED,false"})
    void hasTargetReportsWhetherTheLoadedLinkExists(LinkState state, boolean expected) {
        try (var fixture = new Fixture()) {
            fixture.prepareLink(state);

            var present = fixture.relationships.hasTarget(fixture.source, fixture.follows, fixture.target);

            assertEquals(expected, present);
        }
    }

    @Test
    void hasTargetFindsABridgeLink() {
        try (var fixture = new Fixture()) {
            fixture.linkBridge();

            var present = fixture.relationships.hasTarget(fixture.source, fixture.bridge, fixture.block);

            assertEquals(true, present);
        }
    }

    @Test
    void forEachLinkVisitsLoadedLinksAcrossSameStoreAndBridgeTypes() {
        try (var fixture = new Fixture()) {
            var sibling = new RelationshipTypeRegistry<>(fixture.stores.entityRegistry());
            var carries = sibling.registerRelationship(String.class, RelationshipTraits.defaults());
            var firstData = new Object();
            var secondData = "second";
            var bridgeData = new Object();
            fixture.relationships.addTarget(fixture.world.entityStore(), fixture.source,
                fixture.follows, fixture.target, firstData);
            fixture.relationships.addTarget(fixture.world.entityStore(), fixture.source,
                carries, fixture.target, secondData);
            fixture.relationships.addTarget(fixture.world.entityStore(), fixture.source,
                fixture.bridge, fixture.block, bridgeData);
            var anotherTarget = fixture.stores.addEntity(fixture.world);
            fixture.relationships.addTarget(fixture.world.entityStore(), fixture.source,
                fixture.follows, anotherTarget, null);
            var visited = new ArrayList<Link>();

            fixture.relationships.forEachLink(fixture.source,
                (type, target, data) -> visited.add(new Link(type, target, data)));

            assertAll(
                () -> assertEquals(4, visited.size()),
                () -> assertEquals(Set.of(new Link(fixture.follows, fixture.target, firstData),
                    new Link(carries, fixture.target, secondData),
                    new Link(fixture.bridge, fixture.block, bridgeData),
                    new Link(fixture.follows, anotherTarget, null)), Set.copyOf(visited)));
        }
    }

    @Test
    void forEachLinkVisitsNothingForASourceWithoutLinks() {
        try (var fixture = new Fixture()) {
            var visited = new ArrayList<Link>();

            fixture.relationships.forEachLink(fixture.source,
                (type, target, data) -> visited.add(new Link(type, target, data)));

            assertEquals(List.of(), visited);
        }
    }

    @Test
    void forEachIncomingLinkIncludesTypesRegisteredInAnotherRegistry() {
        try (var fixture = new Fixture()) {
            var sibling = new RelationshipTypeRegistry<>(fixture.stores.blockRegistry());
            var signals = sibling.registerRelationship(String.class, RelationshipTraits.defaults());
            var blockSource = fixture.stores.addBlock(fixture.world);
            var firstData = new Object();
            var secondData = "second";
            var bridgeData = new Object();
            fixture.relationships.addTarget(fixture.world.blockStore(), blockSource,
                fixture.powers, fixture.block, firstData);
            fixture.relationships.addTarget(fixture.world.blockStore(), blockSource,
                signals, fixture.block, secondData);
            fixture.relationships.addTarget(fixture.world.entityStore(), fixture.source,
                fixture.bridge, fixture.block, bridgeData);
            var anotherSource = fixture.stores.addBlock(fixture.world);
            fixture.relationships.addTarget(fixture.world.blockStore(), anotherSource,
                fixture.powers, fixture.block, null);
            var visited = new ArrayList<Link>();

            fixture.relationships.forEachIncomingLink(fixture.block,
                (type, source, data) -> visited.add(new Link(type, source, data)));

            assertAll(
                () -> assertEquals(4, visited.size()),
                () -> assertEquals(Set.of(new Link(fixture.powers, blockSource, firstData),
                    new Link(signals, blockSource, secondData),
                    new Link(fixture.bridge, fixture.source, bridgeData),
                    new Link(fixture.powers, anotherSource, null)), Set.copyOf(visited)));
        }
    }

    @Test
    void forEachIncomingLinkVisitsNothingForATargetWithoutLinks() {
        try (var fixture = new Fixture()) {
            var visited = new ArrayList<Link>();

            fixture.relationships.forEachIncomingLink(fixture.block,
                (type, source, data) -> visited.add(new Link(type, source, data)));

            assertEquals(List.of(), visited);
        }
    }

    @ParameterizedTest
    @CsvSource({"OUTGOING,ENTITIES", "OUTGOING,BLOCKS", "INCOMING,ENTITIES", "INCOMING,BLOCKS"})
    void bridgeConsumersRejectCommandsOnEitherStore(Direction direction, Side side) {
        try (var fixture = new Fixture()) {
            fixture.linkBridge();

            assertThrows(IllegalStateException.class,
                () -> fixture.visit(direction, () -> fixture.addLocalLink(side)));
        }
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    void aFailingConsumerReleasesBothStores(Direction direction) {
        try (var fixture = new Fixture()) {
            fixture.linkBridge();
            var failure = new IllegalArgumentException("consumer failed");

            var thrown = assertThrows(IllegalArgumentException.class,
                () -> fixture.visit(direction, () -> { throw failure; }));
            fixture.addLocalLink(Side.ENTITIES);
            fixture.addLocalLink(Side.BLOCKS);

            assertAll(
                () -> assertSame(failure, thrown),
                () -> assertEquals(1, fixture.relationships.getTargetCount(fixture.source, fixture.follows)),
                () -> assertEquals(1, fixture.relationships.getTargetCount(fixture.block, fixture.powers)));
        }
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void newReadsRejectAClosedService(Read read) {
        try (var fixture = new Fixture()) {
            fixture.relationships.close();

            assertThrows(IllegalStateException.class, () -> fixture.read(read));
        }
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void newReadsRejectAStoppedStore(Read read) {
        var fixture = new Fixture();
        fixture.close();

        assertThrows(IllegalStateException.class, () -> fixture.read(read));
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void newReadsRejectCallsOffTheStoreThread(Read read) throws Exception {
        try (var fixture = new Fixture(); var executor = Executors.newSingleThreadExecutor()) {
            var task = executor.submit(() -> fixture.read(read));

            var failure = assertThrows(ExecutionException.class, task::get);

            assertSame(IllegalStateException.class, failure.getCause().getClass());
        }
    }

    private enum LinkState { ABSENT, PRESENT, REMOVED }
    private enum Direction { OUTGOING, INCOMING }
    private enum Side { ENTITIES, BLOCKS }
    private enum Read { HAS_TARGET, OUTGOING, INCOMING }
    private record Link(GenericRelationshipType<?, ?, ?> type, Ref<?> linked, Object data) { }

    private static final class Fixture implements AutoCloseable {
        private final Relationships relationships = new Relationships();
        private final BridgeStoreFixture stores = new BridgeStoreFixture();
        private final BridgeStoreFixture.World world = stores.addWorld("read-world");
        private final RelationshipTypeRegistry<Entities> entities = entityTypes(stores);
        private final RelationshipTypeRegistry<Blocks> blocks = blockTypes(stores);
        private final RelationshipType<Entities, Object> follows =
            entities.registerRelationship(Object.class, RelationshipTraits.defaults());
        private final RelationshipType<Blocks, Object> powers =
            blocks.registerRelationship(Object.class, RelationshipTraits.defaults());
        private final GenericRelationshipType<Entities, Blocks, Object> bridge =
            entities.registerRelationship(blocks, Object.class, RelationshipTraits.defaults());
        private final Ref<Entities> source = stores.addEntity(world);
        private final Ref<Entities> target = stores.addEntity(world);
        private final Ref<Blocks> block = stores.addBlock(world);
        private final Ref<Blocks> otherBlock = stores.addBlock(world);

        private void prepareLink(LinkState state) {
            if (state == LinkState.ABSENT) return;
            relationships.addTarget(world.entityStore(), source, follows, target, new Object());
            if (state == LinkState.REMOVED) relationships.removeTarget(world.entityStore(), source, follows, target);
        }

        private void linkBridge() {
            relationships.addTarget(world.entityStore(), source, bridge, block, new Object());
        }

        private void visit(Direction direction, Runnable callback) {
            switch (direction) {
                case OUTGOING -> relationships.forEachLink(source, (type, target, data) -> callback.run());
                case INCOMING -> relationships.forEachIncomingLink(block, (type, from, data) -> callback.run());
            }
        }

        private void addLocalLink(Side side) {
            switch (side) {
                case ENTITIES -> relationships.addTarget(world.entityStore(), source, follows, target, new Object());
                case BLOCKS -> relationships.addTarget(world.blockStore(), block, powers, otherBlock, new Object());
            }
        }

        private void read(Read read) {
            switch (read) {
                case HAS_TARGET -> relationships.hasTarget(source, follows, target);
                case OUTGOING -> relationships.forEachLink(source, (type, target, data) -> { });
                case INCOMING -> relationships.forEachIncomingLink(target, (type, source, data) -> { });
            }
        }

        @Override
        public void close() {
            stores.close();
        }
    }
}
