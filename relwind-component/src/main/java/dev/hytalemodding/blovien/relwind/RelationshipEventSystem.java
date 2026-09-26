/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.EcsEvent;
import com.hypixel.hytale.component.system.EntityEventSystem;
import dev.hytalemodding.blovien.relwind.compat.AllEntitiesQuerySystem;

import javax.annotation.Nonnull;

import java.util.Objects;

public abstract class RelationshipEventSystem<ECS_TYPE, LINK_DATA, EVENT extends EcsEvent> extends EntityEventSystem<ECS_TYPE, EVENT> implements AllEntitiesQuerySystem<ECS_TYPE> {
    private final RelationshipSystemLifecycle<ECS_TYPE, RelationshipQuery.Definition<ECS_TYPE, LINK_DATA>> lifecycle =
        new RelationshipSystemLifecycle<>(this, this::getQuery);
    private final RelationshipResults.Pool<ECS_TYPE, LINK_DATA> resultsPool = new RelationshipResults.Pool<>();

    protected RelationshipEventSystem(Class<EVENT> eventType) {
        super(Objects.requireNonNull(eventType, "eventType"));
    }

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
    public final boolean test(ComponentRegistry<ECS_TYPE> componentRegistry, Archetype<ECS_TYPE> archetype) {
        return lifecycle.query().testLoaded(archetype);
    }

    @Override
    public final void handle(
        int index,
        ArchetypeChunk<ECS_TYPE> archetypeChunk,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer,
        EVENT event
    ) {
        var results = resultsPool.borrow();
        try {
            RelationshipEvaluator.evaluate(
                store,
                archetypeChunk.getReferenceTo(index),
                lifecycle.query(),
                results
            );
            if (!results.isEmpty()) {
                handleRelationship(results, store, commandBuffer, event);
            }
        } finally {
            resultsPool.release(results);
        }
    }

    protected abstract void handleRelationship(
        RelationshipResults<ECS_TYPE, LINK_DATA> results,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer,
        EVENT event
    );
}
