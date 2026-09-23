/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import javax.annotation.Nullable;

import java.util.Objects;

/// Handles linked entity unload, deletion and restoration.
final class RelationshipLifecycle {
    private RelationshipLifecycle() {
    }

    /// Detaches the link from whichever linked entity is still loaded.
    static <ECS_TYPE> void detachLinkedEntity(
        Store<ECS_TYPE> store,
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type,
        Ref<ECS_TYPE> source,
        Ref<ECS_TYPE> target,
        Ref<ECS_TYPE> detached
    ) {
        store.assertThread();
        store.assertWriteProcessing();
        Objects.requireNonNull(type, "type").validate(store);
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(detached, "detached");
        if (source != detached && target != detached) {
            throw new IllegalArgumentException("Detached reference is not a linked entity of this relationship");
        }
        var command = RelationshipAccessSystem.forStoreCommand(store);
        // no record update and no announcement here, because the tracker already recorded the
        // unload and the deletion path announces it
        RelationshipCommands.finish(command, command, true,
            () -> detachRemainingLinkedEntity(store, type, source, target, detached),
            () -> { },
            () -> { });
    }

    private static <ECS_TYPE> void detachRemainingLinkedEntity(
        Store<ECS_TYPE> store,
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type,
        Ref<ECS_TYPE> source,
        Ref<ECS_TYPE> target,
        Ref<ECS_TYPE> detached
    ) {
        if (source != detached && source.isValid()) {
            var outgoing = store.getComponent(source, type.getSourceType());
            if (outgoing != null && outgoing.contains(target)) {
                RelationshipStorage.removeOutgoingTarget(store, type, source, target, outgoing);
            }
        }
        if (target != detached && target.isValid()) {
            var incoming = store.getComponent(target, type.getIncomingType());
            if (incoming != null) {
                incoming.remove(source);
            }
        }
    }

    /// Takes the source command module first and the target second, the order a bridge command uses.
    static <SOURCE, TARGET> void restoreLink(
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        @Nullable Object data
    ) {
        var sourceCommand = RelationshipAccessSystem.forStoreCommand(source.getStore());
        var targetCommand = RelationshipAccessSystem.forStoreCommand(target.getStore());
        sourceCommand.beginMutation();
        try {
            targetCommand.beginMutation();
            try {
                attachRestored(type, source, target, data);
            } finally {
                targetCommand.endMutation();
            }
        } finally {
            sourceCommand.endMutation();
        }
    }

    private static <SOURCE, TARGET> void attachRestored(
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        @Nullable Object data
    ) {
        var targetStore = target.getStore();
        RelationshipStorage.addIncoming(targetStore, type, source, target);
        var sourceStore = source.getStore();
        var outgoing = sourceStore.getComponent(source, type.getSourceType());
        if (outgoing == null) {
            sourceStore.addComponent(source, type.getSourceType(), new OutgoingLink<>(target, data));
        } else {
            outgoing.add(target, data);
            sourceStore.replaceComponent(source, type.getSourceType(), outgoing);
        }
    }

    /// Nothing here calls the target Store, and no native callback runs.
    /// By the time the removal command buffer drains, neither Store is processing.
    static <SOURCE, TARGET> void releaseDeletedSource(
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<SOURCE> deleted,
        Ref<TARGET> target
    ) {
        var incoming = target.getStore().getComponent(target, type.getIncomingType());
        if (incoming != null) {
            incoming.remove(deleted);
        }
    }

    /// The deleted target's incoming component goes with it.
    /// Returns whether that source still held the deleted target.
    static <SOURCE, TARGET> boolean releaseDeletedTarget(
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<SOURCE> source,
        Ref<TARGET> deleted
    ) {
        var sourceStore = source.getStore();
        var outgoing = sourceStore.getComponent(source, type.getSourceType());
        if (outgoing == null) {
            return false;
        }
        if (!outgoing.contains(deleted)) {
            return false;
        }
        var command = RelationshipAccessSystem.forStoreCommand(sourceStore);
        command.beginMutation();
        try {
            RelationshipStorage.removeOutgoingTarget(sourceStore, type, source, deleted, outgoing);
        } finally {
            command.endMutation();
        }
        return true;
    }
}
