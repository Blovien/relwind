/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import dev.hytalemodding.blovien.relwind.RelationshipRules;
import dev.hytalemodding.blovien.relwind.Relationships;
import org.bson.BsonDocument;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Two chunk Stores of one registry hold a block entity at the same world position, and each Store
/// keeps its own linked entity, its own links and its own tombstones.
class ChunkStoreScopingTest {
    private static final Relationships relationships = new Relationships();

    private static final String ANCHORED_TO = "relwind:test/anchored-to";
    private static final short INDEX = (short) ChunkUtil.indexBlock(11, 4, 7);
    private static final short TARGET_INDEX = (short) ChunkUtil.indexBlock(3, 1, 6);
    /// The world block position of INDEX inside section (2, 5, -3).
    private static final Vector3i BLOCK = new Vector3i(75, 164, -89);

    /// A world block position names one block entity per Store.
    @Test
    void aBlockEntityInEachStoreRegistersAtTheSameWorldPosition() {
        try (var fixture = new ChunkStoreFixture()) {
            var first = fixture.store();
            var second = fixture.secondStore();

            var inFirst = addBlockAt(fixture, first, 2, 5, -3, INDEX);
            var inSecond = addBlockAt(fixture, second, 2, 5, -3, INDEX);

            assertSame(inFirst, fixture.relationships().getRef(BLOCK, first));
            assertSame(inSecond, fixture.relationships().getRef(BLOCK, second));
        }
    }

    /// Unloading the target of one Store parks that Store's link and must not reach the link of the
    /// other Store, whose target sits at the same world position.
    @Test
    void aLinkInEachStoreKeepsItsOwnStoresTarget() {
        try (var fixture = new ChunkStoreFixture()) {
            var anchoredTo = fixture.installation().getRelationshipTypeRegistry()
                .registerRelationship(RelationshipRules.single().retainOnDeactivation());
            var first = fixture.store();
            var second = fixture.secondStore();
            var firstSource = addBlockAt(fixture, first, 2, 5, -3, INDEX);
            var firstTarget = addBlockAt(fixture, first, 2, 6, -3, TARGET_INDEX);
            relationships.addTarget(first, firstSource, anchoredTo, firstTarget);
            var secondSource = addBlockAt(fixture, second, 2, 5, -3, INDEX);
            var secondTarget = addBlockAt(fixture, second, 2, 6, -3, TARGET_INDEX);
            relationships.addTarget(second, secondSource, anchoredTo, secondTarget);

            first.removeEntity(firstTarget, RemoveReason.UNLOAD);

            assertSame(secondTarget, relationships.getFirstTarget(secondSource, anchoredTo));
            assertFalse(relationships.hasUnresolvedTargets(secondSource, anchoredTo));
        }
    }

    /// Breaking a block entity records its deletion in its own Store and leaves the block entity of
    /// the other Store, at the same world position, registered and linked.
    @Test
    void breakingABlockInOneStoreLeavesTheOtherStoreAlone() {
        try (var fixture = new ChunkStoreFixture()) {
            var anchoredTo = fixture.installation().getRelationshipTypeRegistry()
                .registerRelationship(RelationshipRules.single().retainOnDeactivation());
            var first = fixture.store();
            var second = fixture.secondStore();
            var broken = addBlockAt(fixture, first, 2, 5, -3, INDEX);
            var secondSource = addBlockAt(fixture, second, 2, 5, -3, INDEX);
            var secondTarget = addBlockAt(fixture, second, 2, 6, -3, TARGET_INDEX);
            relationships.addTarget(second, secondSource, anchoredTo, secondTarget);

            first.removeEntity(broken, RemoveReason.REMOVE);

            assertTrue(fixture.deletionsIn(first).contains(BLOCK));
            assertFalse(fixture.deletionsIn(second).contains(BLOCK));
            assertSame(secondSource, fixture.relationships().getRef(BLOCK, second));
            assertSame(secondTarget, relationships.getFirstTarget(secondSource, anchoredTo));
        }
    }

    /// The saved record names a world block position, and the loaded source must find the target of
    /// its own Store even when another Store registered a block entity at that position first.
    @Test
    void aSavedLinkRestoresAgainstTheTargetOfItsOwnStore() {
        var saved = savedSourceRecords();
        try (var fixture = new ChunkStoreFixture()) {
            var anchoredTo = fixture.installation().getRelationshipTypeRegistry()
                .registerRelationship(ANCHORED_TO, RelationshipRules.single());
            var first = fixture.store();
            var second = fixture.secondStore();
            addBlockAt(fixture, second, 2, 6, -3, TARGET_INDEX);
            var firstTarget = addBlockAt(fixture, first, 2, 6, -3, TARGET_INDEX);
            var section = fixture.addSection(first, 2, 5, -3, AddReason.LOAD);

            var restored = fixture.loadBlock(first, saved, section, INDEX);

            assertSame(firstTarget, relationships.getFirstTarget(restored, anchoredTo));
        }
    }

    /// A block entity of another Store at the saved position is not this source's target.
    @Test
    void aSavedLinkDoesNotRestoreAgainstTheTargetOfAnotherStore() {
        var saved = savedSourceRecords();
        try (var fixture = new ChunkStoreFixture()) {
            var anchoredTo = fixture.installation().getRelationshipTypeRegistry()
                .registerRelationship(ANCHORED_TO, RelationshipRules.single());
            var first = fixture.store();
            var second = fixture.secondStore();
            addBlockAt(fixture, first, 2, 6, -3, TARGET_INDEX);
            var section = fixture.addSection(second, 2, 5, -3, AddReason.LOAD);

            var restored = fixture.loadBlock(second, saved, section, INDEX);

            assertNull(relationships.getFirstTarget(restored, anchoredTo));
            assertTrue(relationships.hasUnresolvedTargets(restored, anchoredTo));
        }
    }

    @Nonnull
    private static Ref<ChunkStore> addBlockAt(ChunkStoreFixture fixture, Store<ChunkStore> in, int x, int y, int z, short index) {
        return fixture.addBlock(in, fixture.addSection(in, x, y, z, AddReason.LOAD), index, AddReason.LOAD);
    }

    /// One saved block entity source, linked to the block at TARGET_INDEX of section (2, 6, -3).
    @Nonnull
    private static BsonDocument savedSourceRecords() {
        try (var fixture = new ChunkStoreFixture()) {
            var anchoredTo = fixture.installation().getRelationshipTypeRegistry()
                .registerRelationship(ANCHORED_TO, RelationshipRules.single());
            var store = fixture.store();
            var source = addBlockAt(fixture, store, 2, 5, -3, INDEX);
            relationships.addTarget(store, source, anchoredTo, addBlockAt(fixture, store, 2, 6, -3, TARGET_INDEX));
            return fixture.unload(store, source);
        }
    }
}
