/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.DrainPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.RelationshipTracker;
import dev.hytalemodding.blovien.relwind.RelationshipTypeRegistry;
import dev.hytalemodding.blovien.relwind.StoreRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import javax.annotation.Nonnull;

/// Player event registration and cleanup.
class PlayerLifecycleTest {
    @Test
    void thePlayerEventRegistrationsAreReleasedAndRegisteredAgainOnTheSameBus() {
        var registry = new ComponentRegistry<EntityStore>();
        var events = new EventBus(false);
        var types = new RelationshipTypeRegistry<EntityStore>(registry);
        var deletions = new Tombstones.Installation<>(
            registry, Tombstones.ENTITY_RESOURCE_ID, CoreServerTracker.IDENTITY_CODEC);
        var relationships = types.installTracker(new EntityPersistenceIdentity(deletions),
            new BareRuntime());
        try {
            var players = new PlayerLifecycle(new CoreServerTracker(relationships, (store, id) -> { }));
            var pluginEvents = new EventRegistry(new ArrayList<>(), () -> true, null, events);
            players.registerEvents(pluginEvents);
            assertTrue(events.dispatchFor(PlayerDisconnectEvent.class).hasListener());
            assertTrue(events.dispatchFor(RemovedPlayerFromWorldEvent.class, "test").hasListener());
            assertTrue(events.dispatchFor(DrainPlayerFromWorldEvent.class, "test").hasListener());
            assertTrue(events.dispatchFor(AddPlayerToWorldEvent.class, "test").hasListener());
            var unrelated = events.register(PlayerDisconnectEvent.class, ignored -> { });

            players.close();
            players.close();
            pluginEvents.shutdownAndCleanup(false);

            assertTrue(events.dispatchFor(PlayerDisconnectEvent.class).hasListener());
            unrelated.unregister();
            assertFalse(events.dispatchFor(PlayerDisconnectEvent.class).hasListener());
            assertFalse(events.dispatchFor(RemovedPlayerFromWorldEvent.class, "test").hasListener());
            assertFalse(events.dispatchFor(DrainPlayerFromWorldEvent.class, "test").hasListener());
            assertFalse(events.dispatchFor(AddPlayerToWorldEvent.class, "test").hasListener());

            var reRegistered = new PlayerLifecycle(new CoreServerTracker(relationships, (store, id) -> { }));
            var secondRegistration = new EventRegistry(new ArrayList<>(), () -> true, null, events);
            reRegistered.registerEvents(secondRegistration);

            assertTrue(events.dispatchFor(PlayerDisconnectEvent.class).hasListener());
            assertTrue(events.dispatchFor(RemovedPlayerFromWorldEvent.class, "test").hasListener());
            assertTrue(events.dispatchFor(DrainPlayerFromWorldEvent.class, "test").hasListener());
            assertTrue(events.dispatchFor(AddPlayerToWorldEvent.class, "test").hasListener());

            reRegistered.close();
            secondRegistration.shutdownAndCleanup(false);

            assertFalse(events.dispatchFor(PlayerDisconnectEvent.class).hasListener());
            assertFalse(events.dispatchFor(RemovedPlayerFromWorldEvent.class, "test").hasListener());
            assertFalse(events.dispatchFor(DrainPlayerFromWorldEvent.class, "test").hasListener());
            assertFalse(events.dispatchFor(AddPlayerToWorldEvent.class, "test").hasListener());
        } finally {
            relationships.close();
            deletions.close();
            events.shutdown();
            registry.shutdown();
        }
    }

    /// The production runtime needs the running server's entity module, and this test checks event
    /// cleanup only.
    private static final class BareRuntime implements StoreRuntime<EntityStore> {
        @Override
        public void execute(Store<EntityStore> store, Runnable action) {
            store.assertThread();
            store.assertWriteProcessing();
            action.run();
        }

        @Override
        public void markNeedsSaving(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref) { }

        @Override
        public void markNeedsSaving(Holder<EntityStore> holder) { }

        @Override
        public boolean isDeletionSupported() {
            return true;
        }

        @Nonnull
        @Override
        public RefSystem<EntityStore> getTransitionSystem(RelationshipTracker<EntityStore, ?> installed) {
            return new RefSystem<>() {
                @Override
                public Query<EntityStore> getQuery() {
                    return Query.not(Query.any());
                }

                @Override
                public void onEntityAdded(
                    Ref<EntityStore> ref,
                    AddReason reason,
                    Store<EntityStore> store,
                    CommandBuffer<EntityStore> buffer
                ) { }

                @Override
                public void onEntityRemove(
                    Ref<EntityStore> ref,
                    RemoveReason reason,
                    Store<EntityStore> store,
                    CommandBuffer<EntityStore> buffer
                ) { }
            };
        }
    }
}
