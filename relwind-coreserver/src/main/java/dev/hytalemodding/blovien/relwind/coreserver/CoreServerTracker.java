/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.IdentityIndex;
import dev.hytalemodding.blovien.relwind.RelationshipTracker;
import dev.hytalemodding.blovien.relwind.UnloadReason;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

/// Tracks the live UUIDs and the confirmed removals of one entity installation. An UNLOAD stays
/// pending until later evidence calls it a transfer or a temporary deactivation.
public final class CoreServerTracker {
    /// Hytale identifies an entity by the UUID in its UUIDComponent and writes it as binary.
    public static final Codec<UUID> IDENTITY_CODEC = Codec.UUID_BINARY;

    private record TrackedUnload(UUID id, UnloadReason reason) { }

    private final Map<Ref<EntityStore>, TrackedUnload> unloads = new IdentityHashMap<>();
    private final Map<UUID, Ref<EntityStore>> latestUnloads = new HashMap<>();
    private final RelationshipTracker<EntityStore, UUID> relationships;
    private final BiConsumer<Store<EntityStore>, UUID> deleted;
    private final Map<UUID, Ref<EntityStore>> unloadingRefs = new HashMap<>();

    CoreServerTracker(RelationshipTracker<EntityStore, UUID> relationships, BiConsumer<Store<EntityStore>, UUID> deleted) {
        this.relationships = Objects.requireNonNull(relationships, "relationships");
        this.deleted = Objects.requireNonNull(deleted, "deleted");
    }

    @Nullable
    public Ref<EntityStore> getRef(UUID id) {
        Objects.requireNonNull(id, "id");
        return relationships.getRef(id);
    }

    synchronized void close() {
        relationships.close();
        unloads.clear();
        latestUnloads.clear();
        unloadingRefs.clear();
    }

    @Nullable
    public synchronized UnloadReason getUnloadReason(UUID id) {
        var ref = latestUnloads.get(Objects.requireNonNull(id, "id"));
        var unload = unloads.get(ref);
        return unload == null ? null : unload.reason();
    }

    @Nonnull
    synchronized IdentityIndex.PutResult onEntityLoaded(CommandBuffer<EntityStore> commandBuffer, UUID id, Ref<EntityStore> ref) {
        var result = relationships.onEntityLoaded(commandBuffer, id, ref);
        if (result == IdentityIndex.PutResult.ACCEPTED) {
            var unloaded = latestUnloads.get(id);
            var unload = unloads.get(unloaded);
            if (unloaded != null && (unload == null || unload.reason() != UnloadReason.PENDING)) {
                forgetUnload(id, unloaded);
            }
        }
        return result;
    }

    synchronized void onEntityUnloading(CommandBuffer<EntityStore> commandBuffer, UUID id, Ref<EntityStore> ref) {
        boolean current = relationships.onEntityUnloading(commandBuffer, id, ref);
        if (current) {
            unloadingRefs.put(id, ref);
        }
    }

    synchronized void onEntityRemoved(UUID id, Holder<EntityStore> holder, RemoveReason reason) {
        var ref = unloadingRefs.remove(id);
        if (ref == null || ref.isValid()) {
            return;
        }
        var current = getRef(id);
        if (current != null && current != ref) {
            return;
        }
        if (reason != RemoveReason.UNLOAD) {
            deleted.accept(ref.getStore(), id);
            delete(id, ref, holder);
            return;
        }
        unloads.put(ref, new TrackedUnload(id, UnloadReason.PENDING));
        latestUnloads.put(id, ref);
        relationships.onEntityUnloaded(id, ref, UnloadReason.PENDING, holder);
    }

    private void delete(UUID id, Ref<EntityStore> ref, Holder<EntityStore> holder) {
        relationships.onEntityDeleted(id, ref, holder);
        for (var unloaded : new java.util.ArrayList<>(unloads.keySet())) {
            if (id.equals(unloads.get(unloaded).id())) {
                forgetUnload(id, unloaded);
            }
        }
        unloads.remove(ref);
    }

    /// Applies late evidence for one unloaded Ref and returns false for unrelated or repeated evidence.
    /// Call outside Store processing. A system enqueues this call with `CommandBuffer.run`.
    public synchronized boolean onUnloadResolved(UUID id, Ref<EntityStore> unloaded, UnloadReason reason) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(unloaded, "unloaded");
        Objects.requireNonNull(reason, "reason");
        if (reason == UnloadReason.PENDING) {
            throw new IllegalArgumentException("reason must not be PENDING");
        }
        var unload = unloads.get(unloaded);
        if (unload == null || !id.equals(unload.id()) || unload.reason() != UnloadReason.PENDING) {
            return false;
        }
        unloads.put(unloaded, new TrackedUnload(id, reason));
        boolean classified = relationships.onUnloadResolved(id, unloaded, reason);
        var current = getRef(id);
        if (current != null || latestUnloads.get(id) != unloaded) {
            forgetUnload(id, unloaded);
        }
        if (current != null && !current.getStore().isInThread()) {
            current.getStore().getExternalData().getWorld().execute(() -> relationships.reconcile(id));
        }
        return classified;
    }

    private void forgetUnload(UUID id, Ref<EntityStore> ref) {
        latestUnloads.remove(id, ref);
        unloads.remove(ref);
    }
}
