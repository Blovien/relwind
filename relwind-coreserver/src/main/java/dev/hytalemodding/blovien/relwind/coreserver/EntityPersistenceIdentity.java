/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.WorldProvider;
import dev.hytalemodding.blovien.relwind.PersistenceIdentity;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;

/// UUID identities and deletion evidence for the entity Store of each world.
public final class EntityPersistenceIdentity implements PersistenceIdentity<EntityStore, UUID> {
    private final Tombstones.Installation<EntityStore, UUID> deletions;

    EntityPersistenceIdentity(Tombstones.Installation<EntityStore, UUID> deletions) {
        this.deletions = deletions;
    }

    @Override
    public UUID getIdentity(Store<EntityStore> store, Ref<EntityStore> ref) {
        var identity = store.getComponent(ref, UUIDComponent.getComponentType());
        return identity == null ? null : identity.getUuid();
    }

    @Nonnull
    @Override
    public Codec<UUID> getIdentityCodec() {
        return Codec.UUID_BINARY;
    }

    @Nonnull
    @Override
    public String getInstallationName() {
        return "ENTITIES";
    }

    @Override
    public boolean isDeleted(Store<EntityStore> store, UUID id) {
        return deletions.getTombstonesIn(store).contains(id);
    }

    @Override
    public Store<EntityStore> storeBeside(Store<?> peer) {
        if (!(peer.getExternalData() instanceof WorldProvider provider)) return null;
        var store = provider.getWorld().getEntityStore().getStore();
        return store == null || store.isShutdown() ? null : store;
    }
}
