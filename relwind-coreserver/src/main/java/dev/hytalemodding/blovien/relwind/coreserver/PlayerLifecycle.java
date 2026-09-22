/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.DrainPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.IdentityIndex;
import dev.hytalemodding.blovien.relwind.UnloadReason;

import javax.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/// Matches a removed player holder to its next load before calling the unload a transfer.
/// A disconnect event can run on the network thread.
final class PlayerLifecycle {
    private final CoreServerTracker tracker;
    private boolean closed;
    private final Map<PlayerRef, Unload> unloads = new WeakHashMap<>();
    // removeFromStore clears the Ref inside PlayerRef before prepare runs
    private final Set<PlayerRef> disconnected = Collections.newSetFromMap(new WeakHashMap<>());

    PlayerLifecycle(CoreServerTracker tracker) {
        this.tracker = tracker;
    }

    void registerEvents(EventRegistry events) {
        events.register(PlayerDisconnectEvent.class, this::disconnect);
        events.registerGlobal(RemovedPlayerFromWorldEvent.class, this::onRemoved);
        events.registerGlobal(DrainPlayerFromWorldEvent.class, event -> onJoining(event.getHolder(), event.getWorld()));
        events.registerGlobal(AddPlayerToWorldEvent.class, event -> onJoining(event.getHolder(), event.getWorld()));
    }

    void close() {
        synchronized (tracker) {
            if (closed) return;
            closed = true;
            unloads.clear();
            disconnected.clear();
        }
    }

    void onPlayerLoaded(CommandBuffer<EntityStore> commands, PlayerRef player, Ref<EntityStore> ref) {
        synchronized (tracker) {
            if (closed) return;
            var unload = unloads.get(player);
            if (disconnected.contains(player)
                || (unload != null && unload.destination != ref.getStore().getExternalData().getWorld())) {
                commands.removeEntity(ref, RemoveReason.UNLOAD);
                return;
            }
            if (tracker.onEntityLoaded(commands, player.getUuid(), ref) == IdentityIndex.PutResult.DUPLICATE) {
                commands.removeEntity(ref, RemoveReason.REMOVE);
                return;
            }
            if (unload != null) {
                commands.run(ignored -> {
                    synchronized (tracker) {
                        if (closed) return;
                        if (unloads.get(player) == unload
                            && tracker.onUnloadResolved(player.getUuid(), unload.ref, UnloadReason.TRANSFER)) {
                            unloads.remove(player);
                        }
                    }
                });
            }
        }
    }

    void onPlayerUnloading(PlayerRef player, Ref<EntityStore> ref) {
        synchronized (tracker) {
            if (closed) return;
            var linkedEntity = tracker.getRef(player.getUuid());
            if (linkedEntity != ref) {
                return;
            }
            var unload = unloads.get(player);
            if (unload == null || unload.ref != ref) {
                unloads.put(player, new Unload(ref));
            }
        }
    }

    void onConfirmed(Holder<EntityStore> holder) {
        synchronized (tracker) {
            if (closed) return;
            var player = holder.getComponent(PlayerRef.getComponentType());
            if (player == null) return;
            var unload = unloads.get(player);
            if (unload != null && disconnected.contains(player) && !unload.ref.isValid()) {
                tracker.onUnloadResolved(player.getUuid(), unload.ref, UnloadReason.DEACTIVATION);
            }
        }
    }

    private void onRemoved(RemovedPlayerFromWorldEvent event) {
        synchronized (tracker) {
            if (closed) return;
            var player = event.getHolder().getComponent(PlayerRef.getComponentType());
            var unload = unloads.get(player);
            if (unload != null && !unload.ref.isValid()
                && unload.ref.getStore().getExternalData().getWorld() == event.getWorld()
                && unload.holder == null) {
                unload.holder = new WeakReference<>(event.getHolder());
            }
            onConfirmed(event.getHolder());
        }
    }

    private void onJoining(Holder<EntityStore> holder, World world) {
        synchronized (tracker) {
            if (closed) return;
            var player = holder.getComponent(PlayerRef.getComponentType());
            var unload = unloads.get(player);
            if (unload != null && unload.holder != null && unload.holder.get() == holder
                && !disconnected.contains(player)) {
                // a drain listener may have set a different destination
                unload.destination = world;
            }
        }
    }

    private void disconnect(PlayerDisconnectEvent event) {
        var player = event.getPlayerRef();
        synchronized (tracker) {
            if (closed) return;
            disconnected.add(player);
            var ref = player.getReference();
            var unload = unloads.get(player);
            var linkedEntity = tracker.getRef(player.getUuid());
            // PlayerRef exposes a new Ref before identity callbacks accept it
            if (ref != null && (unload == null || (linkedEntity != null && linkedEntity == ref && unload.ref != ref))) {
                unload = new Unload(ref);
                unloads.put(player, unload);
            }
            if (unload != null) {
                tracker.onUnloadResolved(player.getUuid(), unload.ref, UnloadReason.DEACTIVATION);
            }
        }
    }

    private static final class Unload {
        private final Ref<EntityStore> ref;
        // a strong reference would pin the weak map key, because the holder carries the PlayerRef
        @Nullable
        private WeakReference<Holder<EntityStore>> holder;
        @Nullable
        private World destination;

        private Unload(Ref<EntityStore> ref) {
            this.ref = ref;
        }
    }
}
