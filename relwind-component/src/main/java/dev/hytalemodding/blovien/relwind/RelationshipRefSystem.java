/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.RefSystem;
import dev.hytalemodding.blovien.relwind.compat.AllEntitiesQuerySystem;

import javax.annotation.Nonnull;

public abstract class RelationshipRefSystem<ECS_TYPE, LINK_DATA> extends RefSystem<ECS_TYPE> implements AllEntitiesQuerySystem<ECS_TYPE> {
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
    public final void onEntityAdded(
        Ref<ECS_TYPE> ref,
        AddReason reason,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    ) {
        var results = evaluate(ref, store);
        try {
            if (!results.isEmpty()) {
                onEntityAdded(results, reason, store, commandBuffer);
            }
        } finally {
            resultsPool.release(results);
        }
    }

    protected abstract void onEntityAdded(
        RelationshipResults<ECS_TYPE, LINK_DATA> results,
        AddReason reason,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    );

    @Override
    public final void onEntityRemove(
        Ref<ECS_TYPE> ref,
        RemoveReason reason,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    ) {
        var results = evaluate(ref, store);
        try {
            if (!results.isEmpty()) {
                onEntityRemove(results, reason, store, commandBuffer);
            }
        } finally {
            resultsPool.release(results);
        }
    }

    protected abstract void onEntityRemove(
        RelationshipResults<ECS_TYPE, LINK_DATA> results,
        RemoveReason reason,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    );

    @Nonnull
    private RelationshipResults<ECS_TYPE, LINK_DATA> evaluate(Ref<ECS_TYPE> source, Store<ECS_TYPE> store) {
        var results = resultsPool.borrow();
        try {
            RelationshipEvaluator.evaluate(store, source, lifecycle.query(), results);
            return results;
        } catch (RuntimeException | Error failure) {
            resultsPool.release(results);
            throw failure;
        }
    }

}
