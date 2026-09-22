/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/// A Store runtime for tests. Deferred writes run inline on the calling thread after the thread
/// and processing asserts the registry used to supply by default, unless the test supplies its own
/// executor. Saving marks go to the observers a test supplies, and the transition system the
/// registry registers matches nothing.
public final class TestStoreRuntime<ECS_TYPE> implements StoreRuntime<ECS_TYPE> {
    @Nullable
    private final BiConsumer<Store<ECS_TYPE>, Runnable> execute;
    @Nullable
    private final BiConsumer<Store<ECS_TYPE>, Ref<ECS_TYPE>> sourceChanged;
    @Nullable
    private final Consumer<Holder<ECS_TYPE>> holderChanged;
    private final boolean deletesEntities;

    private TestStoreRuntime(
        @Nullable BiConsumer<Store<ECS_TYPE>, Runnable> execute,
        @Nullable BiConsumer<Store<ECS_TYPE>, Ref<ECS_TYPE>> sourceChanged,
        @Nullable Consumer<Holder<ECS_TYPE>> holderChanged,
        boolean deletesEntities
    ) {
        this.execute = execute;
        this.sourceChanged = sourceChanged;
        this.holderChanged = holderChanged;
        this.deletesEntities = deletesEntities;
    }

    /// A runtime with any combination of a custom executor and saving-mark observers.
    @Nonnull
    public static <ECS_TYPE> TestStoreRuntime<ECS_TYPE> of(
        @Nullable BiConsumer<Store<ECS_TYPE>, Runnable> execute,
        @Nullable BiConsumer<Store<ECS_TYPE>, Ref<ECS_TYPE>> sourceChanged,
        @Nullable Consumer<Holder<ECS_TYPE>> holderChanged
    ) {
        return new TestStoreRuntime<>(execute, sourceChanged, holderChanged, true);
    }

    /// A copy of this runtime whose Store kind never deletes a linked entity, as a chunk store does
    /// not, so a relationship type declaring `cascadeSource()` on it is rejected.
    @Nonnull
    public TestStoreRuntime<ECS_TYPE> withoutDeletion() {
        return new TestStoreRuntime<>(execute, sourceChanged, holderChanged, false);
    }

    /// Runs deferred writes inline after asserting the Store thread and its write processing.
    @Nonnull
    public static <ECS_TYPE> TestStoreRuntime<ECS_TYPE> inline() {
        return of(null, null, null);
    }

    /// Runs deferred writes through the supplied executor instead of inline.
    @Nonnull
    public static <ECS_TYPE> TestStoreRuntime<ECS_TYPE> executing(BiConsumer<Store<ECS_TYPE>, Runnable> execute) {
        return of(Objects.requireNonNull(execute, "execute"), null, null);
    }

    /// Routes saving marks to the supplied observers instead of dropping them.
    @Nonnull
    public static <ECS_TYPE> TestStoreRuntime<ECS_TYPE> marking(
        BiConsumer<Store<ECS_TYPE>, Ref<ECS_TYPE>> sourceChanged,
        Consumer<Holder<ECS_TYPE>> holderChanged
    ) {
        return of(null,
            Objects.requireNonNull(sourceChanged, "sourceChanged"),
            Objects.requireNonNull(holderChanged, "holderChanged"));
    }

    @Override
    public void execute(Store<ECS_TYPE> store, Runnable action) {
        if (execute != null) {
            execute.accept(store, action);
            return;
        }
        store.assertThread();
        store.assertWriteProcessing();
        action.run();
    }

    @Override
    public void markNeedsSaving(ComponentAccessor<ECS_TYPE> accessor, Ref<ECS_TYPE> ref) {
        if (sourceChanged == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        var store = (Store<ECS_TYPE>) accessor;
        sourceChanged.accept(store, ref);
    }

    @Override
    public void markNeedsSaving(Holder<ECS_TYPE> holder) {
        if (holderChanged != null) holderChanged.accept(holder);
    }

    @Override
    public boolean isDeletionSupported() {
        return deletesEntities;
    }

    @Nonnull
    @Override
    public RefSystem<ECS_TYPE> getTransitionSystem(RelationshipTracker<ECS_TYPE, ?> tracker) {
        return new RefSystem<>() {
            @Override
            public Query<ECS_TYPE> getQuery() {
                // matches nothing, so the registered system observes no linked entity
                return Query.not(Query.any());
            }

            @Override
            public void onEntityAdded(
                @Nonnull Ref<ECS_TYPE> ref,
                @Nonnull AddReason reason,
                @Nonnull Store<ECS_TYPE> store,
                @Nonnull CommandBuffer<ECS_TYPE> buffer
            ) { }

            @Override
            public void onEntityRemove(
                @Nonnull Ref<ECS_TYPE> ref,
                @Nonnull RemoveReason reason,
                @Nonnull Store<ECS_TYPE> store,
                @Nonnull CommandBuffer<ECS_TYPE> buffer
            ) { }
        };
    }
}
