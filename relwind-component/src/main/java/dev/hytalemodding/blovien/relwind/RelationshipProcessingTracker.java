/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

/// The processing state of one Store, owned by its {@link RelationshipAccessResource}. A traversal,
/// a mutation or a deletion holds it, the way Hytale's Store holds its own processing counter, and
/// a command that arrives while it is held is rejected.
final class RelationshipProcessingTracker {
    private int traversalDepth;
    private int mutationDepth;
    private boolean deletionActive;

    /// @throws IllegalStateException if a traversal, a mutation or a deletion is in progress
    // a request can come from any thread
    synchronized void assertNotProcessing() {
        if (traversalDepth == 0 && mutationDepth == 0 && !deletionActive) {
            return;
        }
        throw new IllegalStateException("Relationships are currently processing! Pass a CommandBuffer"
            + " as the accessor, or read with Relationships.fetch and change relationships once the"
            + " traversal, deletion or callback has finished.");
    }

    synchronized void beginTraversal() {
        traversalDepth++;
    }

    synchronized void endTraversal() {
        traversalDepth--;
    }

    // a Hytale component callback can run before both directions of the link are attached
    synchronized void beginMutation() {
        mutationDepth++;
    }

    synchronized void endMutation() {
        mutationDepth--;
    }

    synchronized void beginDeletion() {
        if (deletionActive) {
            throw new IllegalStateException("Relationship deletion is already active for this store");
        }
        deletionActive = true;
    }

    synchronized void endDeletion() {
        deletionActive = false;
    }
}
