/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import dev.hytalemodding.blovien.relwind.compat.AllEntitiesQuerySystem;

import javax.annotation.Nonnull;

public abstract class RelationshipTickingSystem<ECS_TYPE, LINK_DATA> extends EntityTickingSystem<ECS_TYPE> implements AllEntitiesQuerySystem<ECS_TYPE> {
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

    /// The inherited test keeps Hytale's implicit NonTicking exclusion.
    @Override
    public final boolean test(ComponentRegistry<ECS_TYPE> componentRegistry, Archetype<ECS_TYPE> archetype) {
        return lifecycle.query().testLoaded(archetype) && super.test(componentRegistry, archetype);
    }

    @Override
    public final void tick(
        float seconds,
        int index,
        ArchetypeChunk<ECS_TYPE> archetypeChunk,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    ) {
        var source = archetypeChunk.getReferenceTo(index);
        var batch = resultsPool.borrow();
        try {
            RelationshipEvaluator.evaluate(store, source, lifecycle.query(), batch);
            for (int i = 0; i < batch.size(); i++) {
                var result = batch.getCallbackResult(i);
                try {
                    tickRelationship(seconds, result, store, commandBuffer);
                } finally {
                    result.clear();
                }
            }
        } finally {
            resultsPool.release(batch);
        }
    }

    protected abstract void tickRelationship(
        float seconds,
        RelationshipResult<ECS_TYPE, LINK_DATA> result,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    );

}
