/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.Dirty;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.chunk.section.EntitySection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.RelationshipTracker;
import dev.hytalemodding.blovien.relwind.StoreRuntime;

import javax.annotation.Nonnull;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;

/// The Store runtime of the entity Store. A deferred write runs on the entity's world, and a
/// changed player also gets the marker that keeps Relwind saving it.
final class EntityStoreRuntime implements StoreRuntime<EntityStore> {
    private final Tombstones.Installation<EntityStore, UUID> deletions;
    private final Map<UUID, WeakReference<EntitySection>> parkedSections =
        Collections.synchronizedMap(new WeakHashMap<>());

    EntityStoreRuntime(Tombstones.Installation<EntityStore, UUID> deletions) {
        this.deletions = Objects.requireNonNull(deletions, "deletions");
    }

    @Nonnull
    Map<UUID, WeakReference<EntitySection>> getParkedSections() {
        return parkedSections;
    }

    @Override
    public void execute(Store<EntityStore> store, Runnable action) {
        store.getExternalData().getWorld().execute(action);
    }

    @Override
    public void markNeedsSaving(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref) {
        var dirty = accessor.getComponent(ref, Dirty.getComponentType());
        if (dirty == null) {
            accessor.addComponent(ref, Dirty.getComponentType(), new Dirty(null, true));
        } else {
            dirty.markDirty();
        }
        if (accessor.getComponent(ref, Player.getComponentType()) == null) return;
        var playerPending = RelationshipPlayerSavingSystem.Pending.getComponentType();
        if (accessor.getComponent(ref, playerPending) == null) {
            accessor.putComponent(ref, playerPending, RelationshipPlayerSavingSystem.Pending.INSTANCE);
        }
    }

    @Override
    public void markNeedsSaving(Holder<EntityStore> holder) {
        var dirty = holder.getComponent(Dirty.getComponentType());
        if (dirty == null) {
            holder.putComponent(Dirty.getComponentType(), new Dirty(null, true));
        } else {
            dirty.markDirty();
        }
        var identity = holder.getComponent(UUIDComponent.getComponentType());
        var sectionReference = identity == null ? null : parkedSections.get(identity.getUuid());
        var section = sectionReference == null ? null : sectionReference.get();
        if (section != null) section.markNeedsSaving();
        if (holder.getComponent(Player.getComponentType()) != null) {
            holder.ensureComponent(RelationshipPlayerSavingSystem.Pending.getComponentType());
        }
    }

    /// Hytale can remove an entity from its world.
    @Override
    public boolean isDeletionSupported() {
        return true;
    }

    @Nonnull
    @Override
    public RefSystem<EntityStore> getTransitionSystem(RelationshipTracker<EntityStore, ?> installed) {
        @SuppressWarnings("unchecked")
        var relationships = (RelationshipTracker<EntityStore, UUID>) installed;
        var tracker = new CoreServerTracker(relationships, (store, id) -> deletions.getTombstonesIn(store).record(id));
        return new EntityIdentitySystem(tracker, new PlayerLifecycle(tracker));
    }
}
