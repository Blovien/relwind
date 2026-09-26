/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.function.consumer.BooleanConsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Storage a plugin registers through its ComponentRegistryProxy is released when the plugin is
/// disabled.
class ProxyRegistrationTest {
    private final ComponentRegistry<Object> componentRegistry = new ComponentRegistry<>();
    private final List<BooleanConsumer> pluginShutdown = new ArrayList<>();
    private final ComponentRegistryProxy<Object> proxy =
        new ComponentRegistryProxy<>(pluginShutdown, componentRegistry);

    ProxyRegistrationTest() {
        componentRegistry.addStore(new Object(), EmptyResourceStorage.get());
    }

    @AfterEach
    void shutDownRegistry() {
        componentRegistry.shutdown();
    }

    @Test
    void theStorageOfAProxyRegisteredTypeIsReleasedByTheShutdownTasks() {
        int bare = componentRegistry.getData().getComponentSize();
        var types = new RelationshipTypeRegistry<>(componentRegistry, proxy);

        types.registerRelationship("relwind:test/follows", RelationshipTraits.defaults());

        assertEquals(bare + 2, componentRegistry.getData().getComponentSize(),
            "a registered type adds its outgoing and incoming storage");

        runPluginShutdown();

        assertEquals(bare, componentRegistry.getData().getComponentSize(),
            "the shutdown tasks leave the bare storage behind");
    }

    /// The shutdown task the proxy recorded must not unregister that storage a second time.
    @Test
    void unregisteringAtRuntimeLeavesTheShutdownTaskNothingToUnregister() {
        int bare = componentRegistry.getData().getComponentSize();
        var types = new RelationshipTypeRegistry<>(componentRegistry, proxy);
        int installed = componentRegistry.getData().getComponentSize();
        var follows = types.registerRelationship("relwind:test/follows", RelationshipTraits.defaults());

        types.unregisterRelationship(follows);

        assertEquals(installed, componentRegistry.getData().getComponentSize(),
            "unregistering must take both sides of the storage back");

        assertDoesNotThrow(this::runPluginShutdown);

        assertEquals(bare, componentRegistry.getData().getComponentSize(),
            "the shutdown tasks must leave the bare storage behind");
    }

    /// Hytale runs the tasks of a disabled plugin in reverse order, as PluginBase.cleanup does.
    private void runPluginShutdown() {
        for (int index = pluginShutdown.size() - 1; index >= 0; index--) {
            pluginShutdown.get(index).accept(false);
        }
    }
}
