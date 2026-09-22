/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.Resource;

final class RelationshipAccessResource<ECS_TYPE> implements Resource<ECS_TYPE> {
    private final RelationshipProcessingTracker processingTracker = new RelationshipProcessingTracker();
    private final RelationshipResults.Pool<ECS_TYPE, Object> results = new RelationshipResults.Pool<>();

    RelationshipProcessingTracker getProcessingTracker() {
        return processingTracker;
    }

    @SuppressWarnings("unchecked")
    <LINK_DATA> RelationshipResults<ECS_TYPE, LINK_DATA> borrowResults() {
        return (RelationshipResults<ECS_TYPE, LINK_DATA>) results.borrow();
    }

    @SuppressWarnings("unchecked")
    void releaseResults(RelationshipResults<ECS_TYPE, ?> borrowed) {
        results.release((RelationshipResults<ECS_TYPE, Object>) borrowed);
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Override
    public RelationshipAccessResource<ECS_TYPE> clone() {
        return new RelationshipAccessResource<>();
    }

}
