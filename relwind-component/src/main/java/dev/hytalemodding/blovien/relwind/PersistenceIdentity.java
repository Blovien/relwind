/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/// What one Store kind supplies to save its linked entities and find them again. Install it
/// through {@link RelationshipTypeRegistry#installTracker}. One installation serves every Store
/// of that kind.
public interface PersistenceIdentity<ECS_TYPE, ID> {
    /// Null for a linked entity with no identity, whose links are never saved.
    @Nullable
    ID getIdentity(Store<ECS_TYPE> store, Ref<ECS_TYPE> ref);

    /// Must read back whatever it writes.
    @Nonnull
    Codec<ID> getIdentityCodec();

    /// Keep it the same across restarts. Every saved record names it.
    @Nonnull
    String getInstallationName();

    /// True only for a confirmed deletion. A linked entity that is merely unloaded is not deleted.
    boolean isDeleted(Store<ECS_TYPE> store, ID id);

    /// True when the same identity in another Store names a different linked entity, as a chunk position
    /// does. An identity unique across the whole save leaves this false.
    default boolean isScopedToStore() {
        return false;
    }

    /// The Store of this kind in the peer's world, where a bridge link's target lives. Only that
    /// Store answers whether the target was deleted. Null when there is no such Store.
    @Nullable
    Store<ECS_TYPE> storeBeside(Store<?> peer);
}
