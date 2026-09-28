/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import java.util.concurrent.atomic.AtomicInteger;

/// The processing state of one Store, owned by its {@link RelationshipAccessResource}. A traversal,
/// a mutation or a deletion holds it, the way Hytale's Store holds its own processing counter, and
/// a command that arrives while it is held is rejected.
final class RelationshipProcessingTracker {
    // traversal, mutation and deletion each hold one contribution until their matching end
    private final AtomicInteger depth = new AtomicInteger();
    private boolean deletionActive;

    /// @throws IllegalStateException if a traversal, a mutation or a deletion is in progress
    // a request can come from any thread
    void assertNotProcessing() {
        if (depth.get() == 0) {
            return;
        }
        throw new IllegalStateException("Relationships are currently processing! Pass a CommandBuffer"
            + " as the accessor, or read with Relationships.fetch and change relationships once the"
            + " traversal, deletion or callback has finished.");
    }

    void beginTraversal() {
        depth.incrementAndGet();
    }

    void endTraversal() {
        depth.decrementAndGet();
    }

    // a Hytale component callback can run before both directions of the link are attached
    void beginMutation() {
        depth.incrementAndGet();
    }

    void endMutation() {
        depth.decrementAndGet();
    }

    synchronized void beginDeletion() {
        if (deletionActive) {
            throw new IllegalStateException("Relationship deletion is already active for this store");
        }
        depth.incrementAndGet();
        deletionActive = true;
    }

    synchronized void endDeletion() {
        deletionActive = false;
        depth.decrementAndGet();
    }
}
