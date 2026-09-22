/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.ArchetypeTickingSystem;
import dev.hytalemodding.blovien.relwind.compat.AllEntitiesQuerySystem;

import javax.annotation.Nonnull;

public abstract class RelationshipChunkTickingSystem<ECS_TYPE, LINK_DATA> extends ArchetypeTickingSystem<ECS_TYPE> implements AllEntitiesQuerySystem<ECS_TYPE> {
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
    public final void tick(
        float seconds,
        ArchetypeChunk<ECS_TYPE> sourceChunk,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    ) {
        var batch = resultsPool.borrow();
        try {
            for (int sourceIndex = 0; sourceIndex < sourceChunk.size(); sourceIndex++) {
                RelationshipEvaluator.evaluate(
                    store,
                    sourceChunk.getReferenceTo(sourceIndex),
                    lifecycle.query(),
                    batch
                );
                for (int resultIndex = 0; resultIndex < batch.size(); resultIndex++) {
                    var result = batch.getCallbackResult(resultIndex);
                    try {
                        tickRelationship(
                            seconds,
                            sourceIndex,
                            sourceChunk,
                            result,
                            store,
                            commandBuffer
                        );
                    } finally {
                        result.clear();
                    }
                }
            }
        } finally {
            resultsPool.release(batch);
        }
    }

    protected abstract void tickRelationship(
        float seconds,
        int sourceIndex,
        ArchetypeChunk<ECS_TYPE> sourceChunk,
        RelationshipResult<ECS_TYPE, LINK_DATA> result,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    );
}
