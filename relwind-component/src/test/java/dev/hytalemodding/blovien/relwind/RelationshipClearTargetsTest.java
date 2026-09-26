/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.BridgeStoreFixture;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.hytalemodding.blovien.relwind.RelationshipTestFixtures.LinkedInstallations;
import static dev.hytalemodding.blovien.relwind.RelationshipTestFixtures.blockTypes;
import static dev.hytalemodding.blovien.relwind.RelationshipTestFixtures.entityTypes;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RelationshipClearTargetsTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void clearTargetsLeavesAnUnlinkedSourceUnchanged() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = types.registerRelationship(RelationshipTraits.defaults());
            var source = fixture.addEntity(new Position(1, 2), null);
            var archetype = fixture.store().getArchetype(source);

            relationships.clearTargets(fixture.store(), source, type);

            assertSame(archetype, fixture.store().getArchetype(source));
        }
    }

    @Test
    void aBufferedClearLeavesLoadedLinksUntilTheBufferDrains() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = types.registerRelationship(RelationshipTraits.defaults());
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            relationships.addTarget(fixture.store(), source, type, target);

            relationships.clearTargets(fixture.commandBuffer(), source, type);
            var beforeDrain = relationships.getTargetCount(source, type);
            BridgeStoreFixture.consume(fixture.commandBuffer());

            assertAll(
                () -> assertEquals(1, beforeDrain),
                () -> assertEquals(0, relationships.getTargetCount(source, type)),
                () -> assertEquals(0, relationships.getIncomingCount(target, type)));
        }
    }

    @Test
    void aBufferedClearIncludesEarlierBufferedAdditions() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = types.registerRelationship(RelationshipTraits.defaults());
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            relationships.addTarget(fixture.commandBuffer(), source, type, target);
            relationships.clearTargets(fixture.commandBuffer(), source, type);

            BridgeStoreFixture.consume(fixture.commandBuffer());

            assertAll(
                () -> assertEquals(0, relationships.getTargetCount(source, type)),
                () -> assertEquals(0, relationships.getIncomingCount(target, type)));
        }
    }

    @Test
    void clearTargetsKeepsRequestedEmptySourceStorage() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var type = types.registerRelationship(RelationshipTraits.defaults().retainSourceStorage());
            var source = fixture.addEntity(new Position(1, 2), null);
            var target = fixture.addEntity(new Position(3, 4), null);
            relationships.addTarget(fixture.store(), source, type, target);
            var outgoing = fixture.store().getComponent(source, type.getSourceType());

            relationships.clearTargets(fixture.store(), source, type);

            assertAll(
                () -> assertSame(outgoing, fixture.store().getComponent(source, type.getSourceType())),
                () -> assertEquals(0, outgoing.size()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clearTargetsRemovesBridgeLinksFromBothStores(boolean buffered) {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("clear-world");
            var entities = entityTypes(fixture);
            var blocks = blockTypes(fixture);
            var type = entities.registerRelationship(blocks, RelationshipTraits.defaults());
            var source = fixture.addEntity(world);
            var first = fixture.addBlock(world);
            var second = fixture.addBlock(world);
            relationships.addTarget(world.entityStore(), source, type, first);
            relationships.addTarget(world.entityStore(), source, type, second);
            var commands = fixture.entityCommandBuffer(world);

            clear(buffered, world.entityStore(), commands, source, type);
            BridgeStoreFixture.consume(commands);

            assertAll(
                () -> assertEquals(0, relationships.getTargetCount(source, type)),
                () -> assertEquals(0, relationships.getIncomingCount(first, type)),
                () -> assertEquals(0, relationships.getIncomingCount(second, type)));
        }
    }

    @Test
    void clearTargetsRemovesAnAwayBridgeLinkFromBothTrackersAndPersistence() {
        try (var linked = new LinkedInstallations()) {
            var type = linked.entityTypes.registerRelationship("relwind:test/clear-away-bridge", linked.blockTypes,
                RelationshipTraits.defaults().retainOnDeactivation());
            var source = linked.entity("source");
            var target = linked.block(7);
            relationships.addTarget(linked.world.entityStore(), source, type, target);
            linked.unloadBlock(7, target);
            linked.runTransitions();

            relationships.clearTargets(linked.world.entityStore(), source, type);
            var loaded = linked.block(7);
            linked.runTransitions();

            assertAll(
                () -> assertEquals(false, relationships.hasUnresolvedTargets(source, type)),
                () -> assertEquals(0, relationships.getTargetCount(source, type)),
                () -> assertEquals(0, relationships.getIncomingCount(loaded, type)),
                () -> assertEquals(null, linked.world.entityStore().getComponent(source,
                    linked.persistence.getComponentType())));
        }
    }

    @Test
    void clearTargetsRejectsTheTargetStoreWhileItsLinksAreBeingRead() {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("clear-world");
            var entities = entityTypes(fixture);
            var blocks = blockTypes(fixture);
            var type = entities.registerRelationship(blocks, RelationshipTraits.defaults());
            var source = fixture.addEntity(world);
            var target = fixture.addBlock(world);
            relationships.addTarget(world.entityStore(), source, type, target);

            assertThrows(IllegalStateException.class, () -> relationships.forEachIncomingSource(target, type,
                ignored -> relationships.clearTargets(world.entityStore(), source, type)));
        }
    }

    private static <SOURCE, TARGET> void clear(
        boolean buffered,
        Store<SOURCE> store,
        CommandBuffer<SOURCE> commands,
        Ref<SOURCE> source,
        GenericRelationshipType<SOURCE, TARGET, ?> type
    ) {
        relationships.clearTargets(buffered ? commands : store, source, type);
    }
}
