/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A block entity is the linked entity of the chunk store, at its world block position, and marking one
/// for saving saves the section that holds it.
class ChunkPositionsTest {
    /// A section is dense storage for its blocks, and a chunk column is not a block entity.
    @Test
    void aSectionAndAChunkColumnHaveNoIdentity() {
        try (var fixture = new ChunkStoreFixture()) {
            var section = fixture.addSection(-4, 3, 17, AddReason.LOAD);
            var column = fixture.addColumn();

            assertNull(fixture.positions().getPositionOf(fixture.store(), section));
            assertNull(fixture.positions().getPositionOf(fixture.store(), column));
        }
    }

    @Test
    void deletingAChunkColumnRecordsNoBlockDeletion() {
        try (var fixture = new ChunkStoreFixture()) {
            var column = fixture.addColumn();

            fixture.store().removeEntity(column, RemoveReason.REMOVE);

            assertFalse(fixture.deletions().contains(new Vector3i(0, 0, 0)));
        }
    }

    /// Section (-4, 3, 17) starts at block (-128, 96, 544), and the block sits at (6, 9, 3) in it.
    @Test
    void aBlockEntityIsTheLinkedEntityAtItsWorldBlockPosition() {
        try (var fixture = new ChunkStoreFixture()) {
            var section = fixture.addSection(-4, 3, 17, AddReason.LOAD);
            var block = fixture.addBlock(section, (short) ChunkUtil.indexBlock(6, 9, 3), AddReason.LOAD);

            assertEquals(new Vector3i(-122, 105, 547),
                fixture.positions().getPositionOf(fixture.store(), block));
        }
    }

    @Test
    void markingABlockLinkedEntityForSavingSavesItsSectionAndItsBlockEntry() {
        try (var fixture = new ChunkStoreFixture()) {
            var section = fixture.addSection(0, 1, 2, AddReason.LOAD);
            var block = fixture.addBlock(section, (short) 7, AddReason.LOAD);
            var blocks = fixture.blockComponents(section);
            blocks.consumeNeedsSaving();
            blocks.takeDirtyBlocks();

            fixture.positions().markNeedsSaving(fixture.store(), block);

            assertTrue(blocks.needsSaving(), "the section holding the block must be saved");
            var dirty = blocks.takeDirtyBlocks();
            assertNotNull(dirty, "the block must have a dirty entry");
            assertTrue(dirty.contains((short) 7), dirty.toString());
        }
    }

    @Test
    void markingASectionLinkedEntityForSavingSavesThatSection() {
        try (var fixture = new ChunkStoreFixture()) {
            var ref = fixture.addSection(0, 1, 2, AddReason.LOAD);
            var section = fixture.section(ref);
            section.consumeNeedsSaving();

            fixture.positions().markNeedsSaving(fixture.store(), ref);

            assertTrue(section.needsSaving());
        }
    }

    /// A parked block entity is a holder inside its section, and its records are saved with it.
    @Test
    void markingAParkedBlockForSavingSavesItsSectionAndItsBlockEntry() {
        try (var fixture = new ChunkStoreFixture()) {
            var section = fixture.addSection(0, 1, 2, AddReason.LOAD);
            var block = fixture.addBlock(section, (short) 7, AddReason.LOAD);
            var parked = fixture.parkedBlock(section, (short) 7);
            var blocks = fixture.blockComponents(section);
            blocks.consumeNeedsSaving();
            blocks.takeDirtyBlocks();

            fixture.positions().markNeedsSaving(parked);

            assertTrue(blocks.needsSaving(), "the section holding the parked block must be saved");
            var dirty = blocks.takeDirtyBlocks();
            assertNotNull(dirty, "the parked block must have a dirty entry");
            assertTrue(dirty.contains((short) 7), dirty.toString());
            assertTrue(block.isValid());
        }
    }
}
