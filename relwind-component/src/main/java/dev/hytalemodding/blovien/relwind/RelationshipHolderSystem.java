/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.HolderSystem;

import javax.annotation.Nonnull;

public abstract class RelationshipHolderSystem<ECS_TYPE, LINK_DATA> extends HolderSystem<ECS_TYPE> {
    private final RelationshipSystemLifecycle<ECS_TYPE, RelationshipQuery.Definition<ECS_TYPE, LINK_DATA>> lifecycle =
        new RelationshipSystemLifecycle<>(this, this::getQuery);
    private final RelationshipResults.Pool<ECS_TYPE, LINK_DATA> resultsPool = new RelationshipResults.Pool<>();

    @Nonnull @Override
    public abstract RelationshipQuery.Definition<ECS_TYPE, LINK_DATA> getQuery();

    @Override
    public final void onSystemRegistered() {
        lifecycle.register(this::onRelationshipSystemRegistered);
    }

    protected void onRelationshipSystemRegistered() {
    }

    @Override
    public final void onSystemUnregistered() {
        lifecycle.unregister(this::onRelationshipSystemUnregistered);
    }

    protected void onRelationshipSystemUnregistered() {
    }

    @Override
    public final void onEntityAdd(Holder<ECS_TYPE> holder, AddReason reason, Store<ECS_TYPE> store) {
        var results = evaluate(holder, store);
        try {
            if (!results.isEmpty()) {
                onEntityAdd(results, reason, store);
            }
        } finally {
            resultsPool.release(results);
        }
    }

    protected abstract void onEntityAdd(
        RelationshipResults<ECS_TYPE, LINK_DATA> results,
        AddReason reason,
        Store<ECS_TYPE> store
    );

    @Override
    public final void onEntityRemoved(Holder<ECS_TYPE> holder, RemoveReason reason, Store<ECS_TYPE> store) {
        var results = evaluate(holder, store);
        try {
            if (!results.isEmpty()) {
                onEntityRemoved(results, reason, store);
            }
        } finally {
            resultsPool.release(results);
        }
    }

    protected abstract void onEntityRemoved(
        RelationshipResults<ECS_TYPE, LINK_DATA> results,
        RemoveReason reason,
        Store<ECS_TYPE> store
    );

    @Nonnull
    private RelationshipResults<ECS_TYPE, LINK_DATA> evaluate(Holder<ECS_TYPE> holder, Store<ECS_TYPE> store) {
        var results = resultsPool.borrow();
        try {
            RelationshipEvaluator.evaluate(store, holder, lifecycle.query(), results);
            return results;
        } catch (RuntimeException | Error failure) {
            resultsPool.release(results);
            throw failure;
        }
    }

}
