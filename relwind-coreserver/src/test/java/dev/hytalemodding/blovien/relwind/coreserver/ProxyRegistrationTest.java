/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import dev.hytalemodding.blovien.relwind.RelationshipPersistence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// The plugin's registry proxies release registrations during shutdown.
class ProxyRegistrationTest {
    /// Every entity registration the composition root makes goes through the proxy, and the plugin's
    /// shutdown runs before PluginBase drains the proxies.
    @Test
    void closeLeavesTheEntityRegistrationsToTheShutdownTasks() {
        var fixture = new EntityStoreFixture(true);
        try {
            var registry = fixture.registry();
            int installed = registry._internal_getData().getComponentSize();
            assertNotNull(registry._internal_getData().getResourceType(Tombstones.ENTITY_RESOURCE_ID));

            fixture.runIntegrationClose();

            assertEquals(installed, registry._internal_getData().getComponentSize(),
                "close must leave the player pending and the metadata component to the tasks");
            assertNotNull(registry._internal_getData().getResourceType(Tombstones.ENTITY_RESOURCE_ID),
                "close must leave the tombstones registered for the tasks to release");

            fixture.runPluginShutdown();

            assertNull(registry._internal_getData().getComponentType(RelationshipPersistence.COMPONENT_ID),
                "the tasks must release the metadata component");
            assertThrows(IllegalStateException.class, fixture.metadata()::validate,
                "the tasks must release the metadata component");
            assertEquals(installed - 2, registry._internal_getData().getComponentSize(),
                "the tasks must release the player pending and the metadata component");
            assertEquals(0, registry._internal_getData().getSystemSize(),
                "the tasks must release every system the proxy registered");
            assertNull(registry._internal_getData().getResourceType(Tombstones.ENTITY_RESOURCE_ID));
        } finally {
            fixture.shutDownRegistry();
        }
    }

    /// The chunk side registers through the proxy of its own Store kind.
    @Test
    void theChunkRegistrationsAreReleasedByTheShutdownTasks() {
        var fixture = new ChunkStoreFixture(true);
        try {
            var registry = fixture.registry();
            assertNotNull(registry._internal_getData().getResourceType(Tombstones.CHUNK_RESOURCE_ID));

            fixture.runIntegrationClose();

            assertNotNull(registry._internal_getData().getResourceType(Tombstones.CHUNK_RESOURCE_ID),
                "close must leave the block tombstones registered for the tasks to release");

            fixture.runPluginShutdown();

            assertNull(registry._internal_getData().getResourceType(Tombstones.CHUNK_RESOURCE_ID));
            assertEquals(0, registry._internal_getData().getSystemSize(),
                "the shutdown tasks must release every system the chunk proxy registered");
        } finally {
            fixture.shutDownRegistry();
        }
    }
}
