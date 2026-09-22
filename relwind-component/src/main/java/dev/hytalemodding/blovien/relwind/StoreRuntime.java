/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.RefSystem;

import javax.annotation.Nonnull;

/// What one Store kind supplies for Relwind to write to it safely and hear about its loads
/// and removals.
public interface StoreRuntime<ECS_TYPE> {
    /// Runs the action on the Store's thread, outside processing. Run it inline only when already there.
    void execute(Store<ECS_TYPE> store, Runnable action);

    /// The accessor must be the Store the source belongs to.
    void markNeedsSaving(ComponentAccessor<ECS_TYPE> accessor, Ref<ECS_TYPE> ref);

    void markNeedsSaving(Holder<ECS_TYPE> holder);

    /// A type declaring `cascadeSource()` needs its sources in a Store kind that answers true.
    boolean isDeletionSupported();

    @Nonnull
    RefSystem<ECS_TYPE> getTransitionSystem(RelationshipTracker<ECS_TYPE, ?> tracker);
}
