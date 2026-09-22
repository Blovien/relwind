/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.RemoveReason;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/// After a linked entity unloads, the index stops resolving its identity, and resolves it again to
/// the entity that loads under it next.
class IdentityIndexTest {
    @Test
    void unloadInvalidatesTheOldReferenceAndLoadResolvesTheNewOne() {
        var registry = new ComponentRegistry<Object>();
        try {
            var firstStore = registry.addStore(new Object(), EmptyResourceStorage.get());
            var secondStore = registry.addStore(new Object(), EmptyResourceStorage.get());
            var oldRef = firstStore.addEntity(Archetype.empty(), AddReason.SPAWN);
            var id = UUID.randomUUID();
            var index = new IdentityIndex<Object, UUID>();

            assertEquals(IdentityIndex.PutResult.ACCEPTED, index.put(id, oldRef));
            index.remove(id, oldRef);
            firstStore.removeEntity(oldRef, RemoveReason.UNLOAD);
            assertNull(index.getRef(id));

            var newRef = secondStore.addEntity(Archetype.empty(), AddReason.LOAD);
            assertEquals(IdentityIndex.PutResult.ACCEPTED, index.put(id, newRef));
            assertSame(newRef, index.getRef(id));
            assertSame(secondStore, index.getRef(id).getStore());
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void duplicateLoadDoesNotReplaceTheSurvivingIdentity() {
        var registry = new ComponentRegistry<Object>();
        try {
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var surviving = store.addEntity(Archetype.empty(), AddReason.SPAWN);
            var duplicate = store.addEntity(Archetype.empty(), AddReason.SPAWN);
            var id = UUID.randomUUID();
            var index = new IdentityIndex<Object, UUID>();

            assertEquals(IdentityIndex.PutResult.ACCEPTED, index.put(id, surviving));
            assertEquals(IdentityIndex.PutResult.DUPLICATE, index.put(id, duplicate));
            index.remove(id, duplicate);

            assertSame(surviving, index.getRef(id));
        } finally {
            registry.shutdown();
        }
    }
}
