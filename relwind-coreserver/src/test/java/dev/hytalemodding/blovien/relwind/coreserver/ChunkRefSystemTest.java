/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.util.ChunkUtil;
import dev.hytalemodding.blovien.relwind.RelationshipRules;
import dev.hytalemodding.blovien.relwind.Relationships;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.bson.BsonDocument;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The transitions of the chunk installation: a block entity is the linked entity, an unload leaves its
/// world position waiting, and every other removal deletes it.
class ChunkRefSystemTest {
    private static final Relationships relationships = new Relationships();

    private static final short INDEX = (short) ChunkUtil.indexBlock(11, 4, 7);
    /// The world block position of INDEX inside section (2, 5, -3).
    private static final Vector3i BLOCK = new Vector3i(75, 164, -89);
    private static final Vector3i SECTION_CORNER = new Vector3i(64, 160, -96);

    @ParameterizedTest
    @EnumSource(value = AddReason.class, names = {"LOAD", "SPAWN"})
    void aBlockEntityLoadsAtItsWorldPosition(AddReason reason) {
        try (var fixture = new ChunkStoreFixture()) {
            var section = fixture.addSection(2, 5, -3, AddReason.LOAD);
            var ref = fixture.addBlock(section, INDEX, reason);

            assertSame(ref, fixture.relationships().getRef(BLOCK, fixture.store()));
        }
    }

    /// Parking a block entity into its section holder is an unload, not a break.
    @Test
    void aParkedBlockEntityLeavesWithoutBeingDeleted() {
        try (var fixture = new ChunkStoreFixture()) {
            var section = fixture.addSection(2, 5, -3, AddReason.LOAD);
            var ref = fixture.addBlock(section, INDEX, AddReason.LOAD);

            fixture.store().removeEntity(ref, RemoveReason.UNLOAD);

            assertNull(fixture.relationships().getRef(BLOCK, fixture.store()));
            assertFalse(fixture.deletions().contains(BLOCK));
        }
    }

    @ParameterizedTest
    @EnumSource(value = RemoveReason.class, names = {"REMOVE", "BUILDER_TOOLS_UNDO"})
    void aBlockEntityRemovedForGoodIsDeletedAtItsWorldPosition(RemoveReason reason) {
        try (var fixture = new ChunkStoreFixture()) {
            var section = fixture.addSection(2, 5, -3, AddReason.LOAD);
            var ref = fixture.addBlock(section, INDEX, AddReason.LOAD);

            fixture.store().removeEntity(ref, reason);

            assertNull(fixture.relationships().getRef(BLOCK, fixture.store()));
            assertTrue(fixture.deletions().contains(BLOCK));
        }
    }

    /// Hytale is still processing the Store when it reports a removal.
    @Test
    void aBlockEntityThatHoldsALiveLinkLeavesWithoutWritingWhileTheStoreProcesses() {
        try (var fixture = new ChunkStoreFixture()) {
            var anchoredTo = fixture.installation().getRelationshipTypeRegistry()
                .registerRelationship(RelationshipRules.single().retainOnDeactivation());
            var section = fixture.addSection(2, 5, -3, AddReason.LOAD);
            var source = fixture.addBlock(section, INDEX, AddReason.LOAD);
            var holding = fixture.addSection(2, 6, -3, AddReason.LOAD);
            var target = fixture.addBlock(holding, (short) 11, AddReason.LOAD);
            relationships.addTarget(fixture.store(), source, anchoredTo, target);

            assertDoesNotThrow(() -> fixture.store().removeEntity(source, RemoveReason.UNLOAD));

            assertNull(fixture.relationships().getRef(BLOCK, fixture.store()));
            assertEquals(0, relationships.getIncomingCount(target, anchoredTo));
        }
    }

    /// A section is dense storage and never a linked entity, whatever reason it leaves with.
    @Test
    void aSectionNeitherLoadsNorIsDeleted() {
        try (var fixture = new ChunkStoreFixture()) {
            var unloaded = fixture.addSection(2, 5, -3, AddReason.LOAD);
            var removed = fixture.addSection(-1, 0, 4, AddReason.SPAWN);

            assertNull(fixture.relationships().getRef(SECTION_CORNER, fixture.store()));

            fixture.store().removeEntity(unloaded, RemoveReason.UNLOAD);
            fixture.store().removeEntity(removed, RemoveReason.REMOVE);

            assertFalse(fixture.deletions().contains(SECTION_CORNER));
            assertFalse(fixture.deletions().contains(new Vector3i(-32, 0, 128)));
        }
    }

    /// A section has no identity.
    @Test
    void aSectionThatWasASavedSourceIsNotRestoredAsOne() {
        var saved = savedSourceRecords();
        try (var fixture = new ChunkStoreFixture()) {
            var anchoredTo = fixture.installation().getRelationshipTypeRegistry()
                .registerRelationship("relwind:test/anchored-to", RelationshipRules.single());

            var section = fixture.loadSection(saved, 2, 5, -3);

            assertNull(relationships.getFirstTarget(section, anchoredTo));
            assertFalse(relationships.hasUnresolvedTargets(section, anchoredTo));
        }
    }

    /// The saved link records of a chunk source, which the caller loads back as a section.
    private static BsonDocument savedSourceRecords() {
        try (var fixture = new ChunkStoreFixture()) {
            var anchoredTo = fixture.installation().getRelationshipTypeRegistry()
                .registerRelationship("relwind:test/anchored-to", RelationshipRules.single());
            var section = fixture.addSection(2, 5, -3, AddReason.LOAD);
            var source = fixture.addBlock(section, INDEX, AddReason.LOAD);
            var holding = fixture.addSection(2, 6, -3, AddReason.LOAD);
            relationships.addTarget(fixture.store(), source, anchoredTo, fixture.addBlock(holding, (short) 11, AddReason.LOAD));
            return fixture.unload(source);
        }
    }
}
