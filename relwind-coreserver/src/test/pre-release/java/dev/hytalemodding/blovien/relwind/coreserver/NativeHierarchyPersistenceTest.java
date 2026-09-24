/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import dev.hytalemodding.blovien.relwind.RelationshipTraits;
import dev.hytalemodding.blovien.relwind.Relationships;
import org.bson.BsonDocument;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Native parenting must not bypass the production block transition and persistence systems.
class NativeHierarchyPersistenceTest {
    private static final short INDEX = (short) ChunkUtil.indexBlock(11, 4, 7);
    private static final Vector3i BLOCK = new Vector3i(75, 164, -89);
    private static final String TYPE = "RelwindTest:ChildLink";
    private final Relationships relationships = new Relationships();

    @Test
    void aReAddedChildBlockIsTrackedAtItsWorldPosition() {
        try (var fixture = new ChunkStoreFixture()) {
            var section = fixture.addSection(2, 5, -3, AddReason.LOAD);
            var child = addChild(fixture, section, INDEX);
            assertSame(child, fixture.relationships().getRef(BLOCK, fixture.store()));

            var holder = fixture.store().removeEntity(child, RemoveReason.UNLOAD);
            assertNull(fixture.relationships().getRef(BLOCK, fixture.store()));
            assertFalse(fixture.deletions().contains(BLOCK));

            var restored = fixture.store().addEntity(holder, AddReason.LOAD, section);
            assertSame(restored, fixture.relationships().getRef(BLOCK, fixture.store()));

            fixture.store().removeEntity(restored, RemoveReason.REMOVE);

            assertNull(fixture.relationships().getRef(BLOCK, fixture.store()));
            assertTrue(fixture.deletions().contains(BLOCK));
        }
    }

    @Test
    void savedChildLinkRestoresInAFreshStore() {
        var saved = savedChildLink();
        try (var fixture = new ChunkStoreFixture()) {
            var type = fixture.installation().getRelationshipTypeRegistry().registerRelationship(
                TYPE, RelationshipTraits.defaults().exclusive().retainOnDeactivation());
            var section = fixture.addSection(2, 5, -3, AddReason.LOAD);
            var holder = fixture.registry().deserialize(saved);
            holder.putComponent(fixture.positions().blockState(), new BlockModule.BlockStateInfo(INDEX, section));
            var source = Objects.requireNonNull(fixture.store().addEntity(holder, AddReason.LOAD, section));
            fixture.blockComponents(section).addBlockReference(INDEX, source);
            assertTrue(relationships.hasUnresolvedTargets(source, type));

            var target = addChild(fixture, section, (short) 11);

            assertSame(target, relationships.getFirstTarget(source, type));
            assertFalse(relationships.hasUnresolvedTargets(source, type));
        }
    }

    /// One saved child source, linked to the child block at index 11 of section (2, 5, -3).
    private BsonDocument savedChildLink() {
        try (var fixture = new ChunkStoreFixture()) {
            var type = fixture.installation().getRelationshipTypeRegistry().registerRelationship(
                TYPE, RelationshipTraits.defaults().exclusive().retainOnDeactivation());
            var section = fixture.addSection(2, 5, -3, AddReason.LOAD);
            var source = addChild(fixture, section, INDEX);
            relationships.addTarget(fixture.store(), source, type, addChild(fixture, section, (short) 11));
            return fixture.unload(source);
        }
    }

    private static Ref<ChunkStore> addChild(ChunkStoreFixture fixture, Ref<ChunkStore> section, short index) {
        var ref = Objects.requireNonNull(fixture.store().addEntity(
            fixture.parkedBlock(section, index), AddReason.LOAD, section));
        fixture.blockComponents(section).addBlockReference(index, ref);
        return ref;
    }
}
