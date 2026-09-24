/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Objects;

/// Executes relationship commands. It validates the call, orders the storage update, the tracker and
/// persistence record and the change announcement, and queues the call when given a command buffer.
final class RelationshipCommands {
    private RelationshipCommands() {
    }

    static <SOURCE, TARGET, LINK_DATA> void add(
        ComponentAccessor<SOURCE> accessor,
        @Nullable Relationships relationships,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        @Nullable LINK_DATA data,
        @Nullable RelationshipTracker<?, ?> sourceTracker,
        boolean notifyChanges
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Store<SOURCE> accessorStore = RelationshipStorage.storeOfAccessor(accessor);
        Store<SOURCE> sourceStore = RelationshipStorage.getSourceStore(source, accessor, accessorStore);
        Store<TARGET> targetStore = target.getStore();
        boolean same = sourceStore == targetStore;
        if (accessor != accessorStore) {
            validateSubmission(type, source, target, sourceStore, targetStore);
            type.validateData(data);
            queueCommand(accessor, relationships, type, source, target, sourceStore, targetStore,
                () -> addNow(sourceStore, targetStore, type, source, target, data, same, sourceTracker, notifyChanges));
            return;
        }
        addNow(sourceStore, targetStore, type, source, target, data, same, sourceTracker, notifyChanges);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <SOURCE, TARGET, LINK_DATA> void addNow(
        Store<SOURCE> sourceStore,
        Store<TARGET> targetStore,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        @Nullable LINK_DATA data,
        boolean same,
        @Nullable RelationshipTracker sourceTracker,
        boolean notifyChanges
    ) {
        type.validateData(data);
        validateNow(type, source, target, sourceStore, targetStore, sourceTracker, same);
        var sourceCommand = RelationshipAccessSystem.forStoreCommand(sourceStore);
        var targetCommand = RelationshipAccessSystem.forStoreCommand(targetStore);
        assertFree(sourceCommand, targetCommand, sourceStore, targetStore, same);
        if (sourceTracker != null
            && sourceTracker.hasUnresolvedLink(type, source, target)) {
            throw newExistingLinkException(type);
        }
        var outgoing = sourceStore.getComponent(source, type.getSourceType());
        if (outgoing != null) {
            if (outgoing.contains(target)) {
                throw newExistingLinkException(type);
            }
            if (outgoing.size() != 0
                && type.getDescriptor().isExclusive()) {
                throw newConflictingTargetException(type, source);
            }
        }
        attachNewLink(sourceStore, targetStore, type, source, target, data, same, sourceTracker,
            notifyChanges, sourceCommand, targetCommand);
    }

    private static <SOURCE, TARGET, LINK_DATA> void attachNewLink(
        Store<SOURCE> sourceStore,
        Store<TARGET> targetStore,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        @Nullable LINK_DATA data,
        boolean same,
        @Nullable RelationshipTracker<?, ?> sourceTracker,
        boolean notifyChanges,
        RelationshipProcessingTracker sourceCommand,
        RelationshipProcessingTracker targetCommand
    ) {
        Runnable storage = () ->
            RelationshipStorage.attachLink(sourceStore, targetStore, type, source, target, data);
        Runnable record = () ->
            recordLinked(targetStore, type, source, target, data, same, sourceTracker);
        Runnable announcement = () -> {
            if (same && notifyChanges) {
                onChanged(sourceStore, type, sourceTracker,
                    RelationshipChangeSystem.Kind.ADDED, source, target, null, null, data);
            }
        };
        finish(sourceCommand, targetCommand, same, storage, record, announcement);
    }

    static <SOURCE, TARGET, LINK_DATA> void put(
        ComponentAccessor<SOURCE> accessor,
        @Nullable Relationships relationships,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        @Nullable LINK_DATA data,
        @Nullable RelationshipTracker<?, ?> sourceTracker
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Store<SOURCE> accessorStore = RelationshipStorage.storeOfAccessor(accessor);
        Store<SOURCE> sourceStore = RelationshipStorage.getSourceStore(source, accessor, accessorStore);
        Store<TARGET> targetStore = target.getStore();
        boolean same = sourceStore == targetStore;
        if (accessor != accessorStore) {
            validateSubmission(type, source, target, sourceStore, targetStore);
            type.validateData(data);
            queueCommand(accessor, relationships, type, source, target, sourceStore, targetStore,
                () -> putNow(sourceStore, targetStore, type, source, target, data, same, sourceTracker));
            return;
        }
        putNow(sourceStore, targetStore, type, source, target, data, same, sourceTracker);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <SOURCE, TARGET, LINK_DATA> void putNow(
        Store<SOURCE> sourceStore,
        Store<TARGET> targetStore,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        @Nullable LINK_DATA data,
        boolean same,
        @Nullable RelationshipTracker sourceTracker
    ) {
        type.validateData(data);
        validateNow(type, source, target, sourceStore, targetStore, sourceTracker, same);
        var sourceCommand = RelationshipAccessSystem.forStoreCommand(sourceStore);
        var targetCommand = RelationshipAccessSystem.forStoreCommand(targetStore);
        assertFree(sourceCommand, targetCommand, sourceStore, targetStore, same);
        if (same && sourceTracker != null
            && sourceTracker.hasUnresolvedLink(type, source, target)) {
            Object oldData = sourceTracker.getUnresolvedLinkData(type, source, target);
            Runnable storage = () ->
                sourceTracker.updateUnresolvedLink(type, source, target, data);
            Runnable record = () -> {
                var persistence = (RelationshipPersistence) type.getRelationshipTypeRegistry().getPersistence();
                Object targetId = sourceTracker.getIdentity(target);
                syncPersistence(type, source, targetId, true, data, sourceTracker, persistence);
            };
            Runnable announcement = () ->
                onChanged(sourceStore, type, sourceTracker,
                    RelationshipChangeSystem.Kind.SET, source, target, null, (LINK_DATA) oldData, data);
            finish(sourceCommand, targetCommand, true, storage, record, announcement);
            return;
        }
        var outgoing = sourceStore.getComponent(source, type.getSourceType());
        if (outgoing != null && outgoing.contains(target)) {
            LINK_DATA oldData = RelationshipStorage.getLinkDataOf(type, target, outgoing);
            Runnable storage = () -> {
                outgoing.setData(target, data);
                sourceStore.replaceComponent(source, type.getSourceType(), outgoing);
            };
            Runnable record = () ->
                recordLinked(targetStore, type, source, target, data, same, sourceTracker);
            Runnable announcement = () -> {
                if (same) {
                    onChanged(sourceStore, type, sourceTracker,
                        RelationshipChangeSystem.Kind.SET, source, target, null, oldData, data);
                }
            };
            finish(sourceCommand, targetCommand, same, storage, record, announcement);
            return;
        }
        if (outgoing != null && outgoing.size() != 0
            && type.getDescriptor().isExclusive()) {
            throw newConflictingTargetException(type, source);
        }
        attachNewLink(sourceStore, targetStore, type, source, target, data, same, sourceTracker,
            true, sourceCommand, targetCommand);
    }

    static <SOURCE, TARGET, LINK_DATA> void remove(
        ComponentAccessor<SOURCE> accessor,
        @Nullable Relationships relationships,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        boolean strict,
        @Nullable RelationshipTracker<?, ?> sourceTracker,
        boolean notifyChanges
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Store<SOURCE> accessorStore = RelationshipStorage.storeOfAccessor(accessor);
        Store<SOURCE> sourceStore = RelationshipStorage.getSourceStore(source, accessor, accessorStore);
        Store<TARGET> targetStore = target.getStore();
        boolean same = sourceStore == targetStore;
        if (accessor != accessorStore) {
            validateSubmission(type, source, target, sourceStore, targetStore);
            queueCommand(accessor, relationships, type, source, target, sourceStore, targetStore,
                () -> removeNow(sourceStore, targetStore, type, source, target, strict, same, sourceTracker, notifyChanges));
            return;
        }
        removeNow(sourceStore, targetStore, type, source, target, strict, same, sourceTracker, notifyChanges);
    }

    @SuppressWarnings({"rawtypes"})
    private static <SOURCE, TARGET, LINK_DATA> void removeNow(
        Store<SOURCE> sourceStore,
        Store<TARGET> targetStore,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        boolean strict,
        boolean same,
        @Nullable RelationshipTracker sourceTracker,
        boolean notifyChanges
    ) {
        validateNow(type, source, target, sourceStore, targetStore, sourceTracker, same);
        var sourceCommand = RelationshipAccessSystem.forStoreCommand(sourceStore);
        var targetCommand = RelationshipAccessSystem.forStoreCommand(targetStore);
        assertFree(sourceCommand, targetCommand, sourceStore, targetStore, same);
        var outgoing = sourceStore.getComponent(source, type.getSourceType());
        if (outgoing == null || !outgoing.contains(target)) {
            if (!strict) {
                return;
            }
            throw newMissingLinkException(type);
        }
        LINK_DATA data = RelationshipStorage.getLinkDataOf(type, target, outgoing);
        Runnable storage = () -> {
            RelationshipStorage.removeIncoming(targetStore, type, source, target);
            RelationshipStorage.removeOutgoingTarget(sourceStore, type, source, target, outgoing);
        };
        Runnable record = () -> {
            unlinkTracker(sourceTracker, type, source, target);
            var persistence = (RelationshipPersistence) type.getRelationshipTypeRegistry().getPersistence();
            var targetTracker = targetTracker(sourceTracker, targetStore, same);
            Object targetId = targetIdentity(targetTracker, targetStore, target, same);
            syncPersistence(type, source, targetId, false, null, sourceTracker, persistence);
        };
        Runnable announcement = () -> {
            if (same && notifyChanges) {
                onChanged(sourceStore, type, sourceTracker,
                    RelationshipChangeSystem.Kind.REMOVED, source, target, null, null, data);
            }
        };
        finish(sourceCommand, targetCommand, same, storage, record, announcement);
    }

    static <SOURCE, TARGET, LINK_DATA> void retarget(
        ComponentAccessor<SOURCE> accessor,
        @Nullable Relationships relationships,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        Ref<TARGET> oldTarget,
        Ref<TARGET> newTarget,
        @Nullable RelationshipTracker<?, ?> sourceTracker
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(oldTarget, "oldTarget");
        Objects.requireNonNull(newTarget, "newTarget");
        Store<SOURCE> accessorStore = RelationshipStorage.storeOfAccessor(accessor);
        Store<SOURCE> sourceStore = RelationshipStorage.getSourceStore(source, accessor, accessorStore);
        Store<TARGET> targetStore = oldTarget.getStore();
        Store<TARGET> newStore = newTarget.getStore();
        if (newStore != targetStore) {
            throw new IllegalArgumentException("Relationship target belongs to a different store");
        }
        boolean same = sourceStore == targetStore;
        if (accessor != accessorStore) {
            validateSubmission(type, source, oldTarget, sourceStore, targetStore);
            newTarget.validate(targetStore);
            CommandBuffer<?> queue = (CommandBuffer<?>) accessor;
            queue.run(ignored -> {
                if (relationships != null) relationships.ensureOpen();
                if (!source.isValid() || !oldTarget.isValid() || !newTarget.isValid()) {
                    return;
                }
                validateSubmission(type, source, oldTarget, sourceStore, targetStore);
                retargetNow(sourceStore, targetStore, type, source, oldTarget, newTarget, same, sourceTracker);
            });
            return;
        }
        retargetNow(sourceStore, targetStore, type, source, oldTarget, newTarget, same, sourceTracker);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <SOURCE, TARGET, LINK_DATA> void retargetNow(
        Store<SOURCE> sourceStore,
        Store<TARGET> targetStore,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        Ref<TARGET> oldTarget,
        Ref<TARGET> newTarget,
        boolean same,
        @Nullable RelationshipTracker sourceTracker
    ) {
        Objects.requireNonNull(newTarget, "newTarget").validate(targetStore);
        if (newTarget.getStore() != oldTarget.getStore()) {
            throw new IllegalArgumentException("Relationship target belongs to a different store");
        }
        validateNow(type, source, oldTarget, sourceStore, targetStore, sourceTracker, same);
        validateNow(type, source, newTarget, sourceStore, targetStore, sourceTracker, same);
        var sourceCommand = RelationshipAccessSystem.forStoreCommand(sourceStore);
        var targetCommand = RelationshipAccessSystem.forStoreCommand(targetStore);
        assertFree(sourceCommand, targetCommand, sourceStore, targetStore, same);
        if (oldTarget == newTarget) {
            return;
        }
        var outgoing = sourceStore.getComponent(source, type.getSourceType());
        if (outgoing == null || !outgoing.contains(oldTarget)) {
            throw newMissingLinkException(type);
        }
        if (outgoing.contains(newTarget)) {
            throw new IllegalStateException("Destination is already linked for relationship type '"
                + type.getDescriptor().id() + "'");
        }
        if (same && sourceTracker != null
            && sourceTracker.hasUnresolvedLink(type, source, newTarget)) {
            throw new IllegalStateException("Destination is already linked for relationship type '"
                + type.getDescriptor().id() + "'");
        }
        LINK_DATA data = RelationshipStorage.getLinkDataOf(type, oldTarget, outgoing);
        Runnable storage = () -> {
            RelationshipStorage.addIncoming(targetStore, type, source, newTarget);
            RelationshipStorage.removeIncoming(targetStore, type, source, oldTarget);
            outgoing.retarget(oldTarget, newTarget);
            sourceStore.replaceComponent(source, type.getSourceType(), outgoing);
        };
        Runnable record = () -> {
            unlinkTracker(sourceTracker, type, source, oldTarget);
            attachLinkTracker(sourceTracker, type, source, newTarget);
            var persistence = (RelationshipPersistence) type.getRelationshipTypeRegistry().getPersistence();
            var targetTracker = targetTracker(sourceTracker, targetStore, same);
            Object oldId = targetIdentity(targetTracker, targetStore, oldTarget, same);
            Object newId = targetIdentity(targetTracker, targetStore, newTarget, same);
            syncPersistence(type, source, oldId, false, null, sourceTracker, persistence);
            syncPersistence(type, source, newId, true, data, sourceTracker, persistence);
        };
        Runnable announcement = () -> {
            if (same) {
                onChanged(sourceStore, type, sourceTracker,
                    RelationshipChangeSystem.Kind.RETARGETED, source, newTarget, oldTarget, null, data);
            }
        };
        finish(sourceCommand, targetCommand, same, storage, record, announcement);
    }

    private static <SOURCE, TARGET> void queueCommand(
        ComponentAccessor<SOURCE> accessor,
        @Nullable Relationships relationships,
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        Store<SOURCE> sourceStore,
        Store<TARGET> targetStore,
        Runnable command
    ) {
        CommandBuffer<?> queue = (CommandBuffer<?>) accessor;
        queue.run(ignored -> {
            if (relationships != null) relationships.ensureOpen();
            validateSubmission(type, source, target, sourceStore, targetStore);
            if (source.isValid() && target.isValid()) {
                command.run();
            }
        });
    }

    private static <SOURCE, TARGET, LINK_DATA> void validateSubmission(
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        Store<SOURCE> sourceStore,
        Store<TARGET> targetStore
    ) {
        if (sourceStore.isShutdown() || sourceStore.getRegistry().isShutdown()
            || targetStore.isShutdown() || targetStore.getRegistry().isShutdown()) {
            throw new IllegalStateException("Cannot access relationships for a stopped Store");
        }
        type.validate(sourceStore);
        if (targetStore.getRegistry() != type.getExpectedTargetRegistry()) {
            throw new IllegalArgumentException(
                "Relationship type '" + type.getDescriptor().id() + "' is for a different registry");
        }
        if (sourceStore != targetStore
            && type.getExpectedTargetRegistry() == sourceStore.getRegistry()) {
            throw new IllegalArgumentException("Linked entity belongs to a different store");
        }
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (source.getStore() != sourceStore) {
            throw new IllegalArgumentException("Linked entity belongs to a different store");
        }
    }

    private static <SOURCE, TARGET> void assertFree(
        RelationshipProcessingTracker sourceCommand,
        RelationshipProcessingTracker targetCommand,
        Store<SOURCE> sourceStore,
        Store<TARGET> targetStore,
        boolean same
    ) {
        sourceCommand.assertNotProcessing();
        sourceStore.assertWriteProcessing();
        if (!same) {
            targetCommand.assertNotProcessing();
            targetStore.assertWriteProcessing();
        }
    }

    /// Runs the storage update and the record under the command mutation brackets, then announces.
    static void finish(
        RelationshipProcessingTracker sourceCommand,
        RelationshipProcessingTracker targetCommand,
        boolean same,
        Runnable storage,
        Runnable record,
        Runnable announcement
    ) {
        sourceCommand.beginMutation();
        try {
            if (!same) targetCommand.beginMutation();
            try {
                storage.run();
                record.run();
            } finally {
                if (!same) targetCommand.endMutation();
            }
        } finally {
            sourceCommand.endMutation();
        }
        announcement.run();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <SOURCE, TARGET> void assertSameWorld(Store<SOURCE> sourceStore, Store<TARGET> targetStore) {
        var targetTracker = RelationshipAccessSystem.install(
            (ComponentRegistry) targetStore.getRegistry()).getCurrentTracker();
        if (targetTracker == null) {
            throw new IllegalStateException("Bridge relationships require a target persistence identity");
        }
        Store<?> beside;
        try {
            beside = targetTracker.getPersistenceIdentity().storeBeside(sourceStore);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("The target installation cannot place the source Store", failure);
        }
        if (beside != targetStore) {
            throw new IllegalArgumentException("The linked entities of a bridge relationship must live in one world, and these Stores do not");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <SOURCE, TARGET> void validateNow(
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        Store<SOURCE> sourceStore,
        Store<TARGET> targetStore,
        @Nullable RelationshipTracker sourceTracker,
        boolean same
    ) {
        validateSubmission(type, source, target, sourceStore, targetStore);
        source.validate(sourceStore);
        target.validate(targetStore);
        sourceStore.assertThread();
        targetStore.assertThread();
        if (sourceTracker != null) {
            sourceTracker.validateLink(type, source, target);
        } else if (!same && retains(type.getDescriptor())) {
            throw new IllegalStateException("Relationship type '" + type.getDescriptor().id()
                + "' requires an installed tracker and stable identities on both sides");
        }
        if (same && sourceTracker != null) {
            var persistence = (RelationshipPersistence) type.getRelationshipTypeRegistry().getPersistence();
            if (persistence != null) {
                persistence.validateMutation(type, source, target);
            }
        }
        if (!same) {
            assertSameWorld(sourceStore, targetStore);
        }
    }

    private static boolean retains(RelationshipDescriptor<?, ?> descriptor) {
        return descriptor.isPersistent()
            || descriptor.getTransfer() == RelationshipTraits.Survival.RETAIN
            || descriptor.getTemporaryDeactivation() == RelationshipTraits.Survival.RETAIN;
    }

    @Nonnull
    private static IllegalStateException newMissingLinkException(GenericRelationshipType<?, ?, ?> type) {
        return new IllegalStateException(
            "Link does not exist for relationship type '" + type.getDescriptor().id() + "'"
        );
    }

    @Nonnull
    private static IllegalStateException newExistingLinkException(GenericRelationshipType<?, ?, ?> type) {
        return new IllegalStateException(
            "Link already exists for relationship type '" + type.getDescriptor().id() + "'"
        );
    }

    @Nonnull
    private static IllegalStateException newConflictingTargetException(GenericRelationshipType<?, ?, ?> type, Ref<?> source) {
        return new IllegalStateException(
            "Source " + source + " already has a target for relationship type '" + type.getDescriptor().id() + "'"
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void attachLinkTracker(
        @Nullable RelationshipTracker sourceTracker,
        GenericRelationshipType type,
        Ref source,
        Ref target
    ) {
        if (sourceTracker == null) {
            return;
        }
        sourceTracker.onLinked(type, source, target);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void unlinkTracker(
        @Nullable RelationshipTracker sourceTracker,
        GenericRelationshipType type,
        Ref source,
        Ref target
    ) {
        if (sourceTracker == null) {
            return;
        }
        sourceTracker.onUnlinked(type, source, target);
    }

    private static <SOURCE, TARGET, LINK_DATA> void recordLinked(
        Store<TARGET> targetStore,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        @Nullable LINK_DATA data,
        boolean same,
        @Nullable RelationshipTracker<?, ?> sourceTracker
    ) {
        attachLinkTracker(sourceTracker, type, source, target);
        var persistence = type.getRelationshipTypeRegistry().getPersistence();
        var targetTracker = targetTracker(sourceTracker, targetStore, same);
        Object targetId = targetIdentity(targetTracker, targetStore, target, same);
        syncPersistence(type, source, targetId, true, data, sourceTracker, persistence);
    }

    @Nullable
    private static RelationshipTracker<?, ?> targetTracker(
        @Nullable RelationshipTracker<?, ?> sourceTracker,
        Store<?> targetStore,
        boolean same
    ) {
        if (sourceTracker == null || same) {
            return sourceTracker;
        }
        return RelationshipAccessSystem.install(targetStore.getRegistry()).getCurrentTracker();
    }

    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object targetIdentity(
        @Nullable RelationshipTracker targetTracker,
        Store<?> targetStore,
        Ref<?> target,
        boolean same
    ) {
        if (targetTracker == null) {
            return null;
        }
        return same ? targetTracker.getIdentity(target)
            : targetTracker.getPersistenceIdentity().getIdentity(targetStore, target);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void syncPersistence(
        GenericRelationshipType type,
        Ref source,
        @Nullable Object targetId,
        boolean present,
        @Nullable Object data,
        @Nullable RelationshipTracker sourceTracker,
        @Nullable RelationshipPersistence sourcePersistence
    ) {
        if (sourceTracker == null || sourcePersistence == null || !source.isValid()) {
            return;
        }
        if (!type.getDescriptor().isPersistent()) {
            return;
        }
        sourcePersistence.synchronize(type, source, targetId, present, data);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <SOURCE, TARGET, LINK_DATA> void onChanged(
        Store<SOURCE> sourceStore,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        @Nullable RelationshipTracker sourceTracker,
        RelationshipChangeSystem.Kind kind,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        @Nullable Ref<TARGET> oldTarget,
        @Nullable LINK_DATA oldData,
        @Nullable LINK_DATA data
    ) {
        if (sourceStore.getRegistry().getWorldEventTypeForClass(RelationshipChangeSystem.ChangeEvent.class) == null) {
            return;
        }
        RelationshipTracker<SOURCE, Object> tracker =
            (RelationshipTracker<SOURCE, Object>) sourceTracker;
        Ref<SOURCE> targetAsSource = (Ref<SOURCE>) target;
        Ref<SOURCE> oldAsSource = oldTarget == null ? null : (Ref<SOURCE>) oldTarget;
        var sourceLinkedEntity = new RelationshipChangeSystem.LinkedEntity<>(source.isValid() ? source : null,
            tracker == null ? null : tracker.getIdentity(source));
        var targetLinkedEntity = new RelationshipChangeSystem.LinkedEntity<>(targetAsSource.isValid() ? targetAsSource : null,
            tracker == null ? null : tracker.getIdentity(targetAsSource));
        RelationshipChangeSystem.LinkedEntity<SOURCE> oldLinkedEntity = oldAsSource == null ? null
            : new RelationshipChangeSystem.LinkedEntity<>(oldAsSource.isValid() ? oldAsSource : null,
                tracker == null ? null : tracker.getIdentity(oldAsSource));
        sourceStore.invoke(new RelationshipChangeSystem.ChangeEvent<>((GenericRelationshipType<SOURCE, SOURCE, LINK_DATA>) type,
            kind, sourceLinkedEntity, targetLinkedEntity, oldLinkedEntity, oldData, data));
    }
}
