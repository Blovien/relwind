/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.RefChangeSystem;
import dev.hytalemodding.blovien.relwind.compat.AllEntitiesQuerySystem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public abstract class RelationshipRefChangeSystem<
    ECS_TYPE,
    LINK_DATA,
    CHANGED_COMPONENT extends Component<ECS_TYPE>
> extends RefChangeSystem<ECS_TYPE, CHANGED_COMPONENT> implements AllEntitiesQuerySystem<ECS_TYPE> {
    private final RelationshipSystemLifecycle<ECS_TYPE, RelationshipQuery.ComponentChange<ECS_TYPE, LINK_DATA, CHANGED_COMPONENT>> lifecycle =
        new RelationshipSystemLifecycle<>(this, this::getQuery);
    private final RelationshipResults.Pool<ECS_TYPE, LINK_DATA> resultsPool = new RelationshipResults.Pool<>();

    @Nonnull @Override
    public abstract RelationshipQuery.ComponentChange<ECS_TYPE, LINK_DATA, CHANGED_COMPONENT> getQuery();

    @Nonnull
    @Override
    public final ComponentType<ECS_TYPE, CHANGED_COMPONENT> componentType() {
        return lifecycle.query().getComponentType();
    }

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
    public final void onComponentAdded(
        Ref<ECS_TYPE> ref,
        CHANGED_COMPONENT component,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    ) {
        var results = evaluate(ref, store, component);
        try {
            if (!results.isEmpty()) {
                onComponentAdded(results, component, store, commandBuffer);
            }
        } finally {
            resultsPool.release(results);
        }
    }

    protected abstract void onComponentAdded(
        RelationshipResults<ECS_TYPE, LINK_DATA> results,
        CHANGED_COMPONENT component,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    );

    @Override
    public final void onComponentSet(
        Ref<ECS_TYPE> ref,
        @Nullable CHANGED_COMPONENT oldComponent,
        CHANGED_COMPONENT newComponent,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    ) {
        var results = evaluate(ref, store, newComponent);
        try {
            if (!results.isEmpty()) {
                onComponentSet(results, oldComponent, newComponent, store, commandBuffer);
            }
        } finally {
            resultsPool.release(results);
        }
    }

    protected abstract void onComponentSet(
        RelationshipResults<ECS_TYPE, LINK_DATA> results,
        @Nullable CHANGED_COMPONENT oldComponent,
        CHANGED_COMPONENT newComponent,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    );

    @Override
    public final void onComponentRemoved(
        Ref<ECS_TYPE> ref,
        CHANGED_COMPONENT component,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    ) {
        var results = evaluate(ref, store, component);
        try {
            if (!results.isEmpty()) {
                onComponentRemoved(results, component, store, commandBuffer);
            }
        } finally {
            resultsPool.release(results);
        }
    }

    protected abstract void onComponentRemoved(
        RelationshipResults<ECS_TYPE, LINK_DATA> results,
        CHANGED_COMPONENT component,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    );

    @Nonnull
    private RelationshipResults<ECS_TYPE, LINK_DATA> evaluate(
        Ref<ECS_TYPE> source,
        Store<ECS_TYPE> store,
        CHANGED_COMPONENT suppliedComponent
    ) {
        var results = resultsPool.borrow();
        try {
            RelationshipEvaluator.evaluate(
                store,
                source,
                lifecycle.query(),
                componentType(),
                suppliedComponent,
                results
            );
            return results;
        } catch (RuntimeException | Error failure) {
            resultsPool.release(results);
            throw failure;
        }
    }

}
