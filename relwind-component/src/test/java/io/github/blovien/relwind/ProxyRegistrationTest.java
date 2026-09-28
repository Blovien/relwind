/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package io.github.blovien.relwind;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.function.consumer.BooleanConsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Storage a plugin registers through its ComponentRegistryProxy is released when the plugin is
/// disabled.
class ProxyRegistrationTest {
    private final ComponentRegistry<Object> componentRegistry = new ComponentRegistry<>();
    private final List<BooleanConsumer> pluginShutdown = new ArrayList<>();
    private final ComponentRegistryProxy<Object> proxy =
        new ComponentRegistryProxy<>(pluginShutdown, componentRegistry);
    private final Store<Object> store =
        componentRegistry.addStore(new Object(), EmptyResourceStorage.get());

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSiblingProxyReinstallsTypedAccessAfterTheInstallingProxyShutsDown(boolean retainResourceSlot) {
        var siblingShutdown = new ArrayList<BooleanConsumer>();
        var siblingProxy = new ComponentRegistryProxy<>(siblingShutdown, componentRegistry);
        new RelationshipTypeRegistry<>(componentRegistry, proxy);
        var siblingTypes = new RelationshipTypeRegistry<>(componentRegistry, siblingProxy);
        var follows = siblingTypes.registerRelationship(RelationshipTraits.defaults());
        var source = store.addEntity(componentRegistry.newHolder(), AddReason.SPAWN);
        var target = store.addEntity(componentRegistry.newHolder(), AddReason.SPAWN);

        if (retainResourceSlot) {
            componentRegistry.registerResource(LaterResource.class, LaterResource::new);
        }

        runShutdownTasks(pluginShutdown);

        var relationships = new Relationships();
        relationships.addTarget(store, source, follows, target);
        var visited = new ArrayList<Ref<Object>>();
        relationships.forEachTarget(source, follows, visited::add);
        assertEquals(1, visited.size());
        assertSame(target, visited.getFirst());
    }

    private static final class LaterResource implements Resource<Object> {
        @Override
        public LaterResource clone() {
            return new LaterResource();
        }
    }

    /// Hytale runs the tasks of a disabled plugin in reverse order, as PluginBase.cleanup does.
    private void runPluginShutdown() {
        runShutdownTasks(pluginShutdown);
    }

    private static void runShutdownTasks(List<BooleanConsumer> shutdownTasks) {
        for (int index = shutdownTasks.size() - 1; index >= 0; index--) {
            shutdownTasks.get(index).accept(false);
        }
    }
}
