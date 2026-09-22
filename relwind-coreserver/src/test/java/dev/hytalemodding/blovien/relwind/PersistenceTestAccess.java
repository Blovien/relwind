/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.Store;

public final class PersistenceTestAccess {
    private PersistenceTestAccess() {
    }

    public static <ECS_TYPE, ID> boolean isDeleted(
        StoreInstallation<ECS_TYPE, ID> installation, Store<ECS_TYPE> store, ID id
    ) {
        return installation.getTracker().getPersistenceIdentity().isDeleted(store, id);
    }
}
