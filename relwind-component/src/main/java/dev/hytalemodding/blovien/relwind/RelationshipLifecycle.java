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

/// Handles linked entity unload, deletion and restoration, and a link data component change.
final class RelationshipLifecycle {
    private RelationshipLifecycle() {
    }

    /// The link may come back. The source keeps its data component.
    static <ECS_TYPE> void detachAfterUnload(
        Store<ECS_TYPE> store,
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type,
        Ref<ECS_TYPE> source,
        Ref<ECS_TYPE> target,
        Ref<ECS_TYPE> unloaded
    ) {
        detachLinkedEntity(store, type, source, target, unloaded, false);
    }

    /// The link is over. A source that survives loses its data component.
    static <ECS_TYPE> void detachAfterDeletion(
        Store<ECS_TYPE> store,
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type,
        Ref<ECS_TYPE> source,
        Ref<ECS_TYPE> target,
        Ref<ECS_TYPE> deleted
    ) {
        detachLinkedEntity(store, type, source, target, deleted, true);
    }

    private static <ECS_TYPE> void detachLinkedEntity(
        Store<ECS_TYPE> store,
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type,
        Ref<ECS_TYPE> source,
        Ref<ECS_TYPE> target,
        Ref<ECS_TYPE> unloaded,
        boolean linkEnds
    ) {
        store.assertThread();
        store.assertWriteProcessing();
        Objects.requireNonNull(type, "type").validate(store);
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(unloaded, "unloaded");
        if (source != unloaded && target != unloaded) {
            throw new IllegalArgumentException("Unloaded reference is not a linked entity of this relationship");
        }
        var command = RelationshipAccessSystem.forStoreCommand(store);
        // no record update and no announcement here, because the tracker already recorded the
        // unload and the deletion path announces it
        RelationshipCommands.finish(command, command, true,
            () -> detachRemainingLinkedEntity(store, type, source, target, unloaded, linkEnds),
            () -> { },
            () -> { });
    }

    private static <ECS_TYPE> void detachRemainingLinkedEntity(
        Store<ECS_TYPE> store,
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type,
        Ref<ECS_TYPE> source,
        Ref<ECS_TYPE> target,
        Ref<ECS_TYPE> unloaded,
        boolean linkEnds
    ) {
        if (source != unloaded && source.isValid()) {
            var outgoing = store.getComponent(source, type.getSourceType());
            if (outgoing != null && outgoing.contains(target)) {
                // a link that can still come back keeps its data component for resolution to reuse
                var dataType = linkEnds ? type.getDescriptor().getDataComponentType() : null;
                if (dataType != null) RelationshipStorage.storeLinkData(store, dataType, source, null);
                RelationshipStorage.removeOutgoingTarget(store, type, source, target, outgoing);
            }
        }
        if (target != unloaded && target.isValid()) {
            var incoming = store.getComponent(target, type.getIncomingType());
            if (incoming != null) {
                incoming.remove(source);
            }
        }
    }

    /// The component already holds the new value.
    static <ECS_TYPE, LINK_DATA> void onLinkDataReplaced(
        Store<ECS_TYPE> store,
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
        Ref<ECS_TYPE> source,
        @Nullable LINK_DATA oldData,
        @Nullable LINK_DATA data
    ) {
        store.assertThread();
        if (!source.isValid()) {
            return;
        }
        var outgoing = store.getComponent(source, type.getSourceType());
        var target = outgoing == null ? null : outgoing.getTarget();
        if (target == null) {
            return;
        }
        var tracker = type.getRelationshipTypeRegistry().getTracker();
        var command = RelationshipAccessSystem.forStoreCommand(store);
        RelationshipCommands.finish(command, command, true,
            () -> { },
            () -> {
                updateTracker(store, type, tracker, source, target);
                updatePersistence(store, type, tracker, source, target);
            },
            () -> RelationshipCommands.onChanged(store, type, tracker, RelationshipChangeSystem.Kind.SET,
                source, target, null, oldData, data));
    }

    private static <ECS_TYPE, LINK_DATA> void updateTracker(
        Store<ECS_TYPE> store,
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
        @Nullable RelationshipTracker<ECS_TYPE, ?> tracker,
        Ref<ECS_TYPE> source,
        Ref<ECS_TYPE> target
    ) {
        if (tracker == null) {
            return;
        }
        var outgoing = source.isValid() ? store.getComponent(source, type.getSourceType()) : null;
        if (outgoing != null && outgoing.contains(target)) {
            tracker.onLinked(type, source, target);
        } else {
            tracker.onUnlinked(type, source, target);
        }
    }

    private static <ECS_TYPE, LINK_DATA> void updatePersistence(
        Store<ECS_TYPE> store,
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
        @Nullable RelationshipTracker<ECS_TYPE, ?> tracker,
        Ref<ECS_TYPE> source,
        Ref<ECS_TYPE> target
    ) {
        var persistence = type.getRelationshipTypeRegistry().getPersistence();
        if (tracker == null || persistence == null || !source.isValid()) {
            return;
        }
        var outgoing = store.getComponent(source, type.getSourceType());
        boolean present = outgoing != null && outgoing.contains(target);
        persistence.synchronize(type, source, target, present,
            present ? RelationshipStorage.getLinkDataOf(type, store, source, target, outgoing) : null);
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
