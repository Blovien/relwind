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

import java.util.ArrayList;
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
                () -> addNow(sourceStore, targetStore, type, source, target, data, same, sourceTracker, notifyChanges,
                    relationships != null && type.getDescriptor().isSymmetric()));
            return;
        }
        addNow(sourceStore, targetStore, type, source, target, data, same, sourceTracker, notifyChanges,
            relationships != null && type.getDescriptor().isSymmetric());
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
        boolean notifyChanges,
        boolean maintainTwins
    ) {
        type.validateData(data);
        validateNow(type, source, target, sourceStore, targetStore);
        Object sourceId = sourceTracker == null ? null : sourceTracker.getIdentity(source);
        Object targetId = source == target ? sourceId
            : targetIdentity(targetTracker(sourceTracker, targetStore, same), targetStore, target, same);
        validateIdentities(type, source, target, sourceTracker, same, sourceId, targetId, false);
        var sourceCommand = RelationshipAccessSystem.forStoreCommand(sourceStore);
        var targetCommand = RelationshipAccessSystem.forStoreCommand(targetStore);
        assertFree(sourceCommand, targetCommand, sourceStore, targetStore, same);
        if (sourceTracker != null
            && sourceTracker.hasUnresolvedLink(type, source, target, sourceId, targetId)) {
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
        if (maintainTwins) {
            symmetricCommand(sourceStore, type, sourceTracker)
                .putPair(source, sourceId, (Ref<SOURCE>) target, targetId, data);
            return;
        }
        attachNewLink(sourceStore, targetStore, type, source, sourceId, target, targetId, data, same, sourceTracker,
            notifyChanges, sourceCommand, targetCommand);
    }

    private static <SOURCE, TARGET, LINK_DATA> void attachNewLink(
        Store<SOURCE> sourceStore,
        Store<TARGET> targetStore,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        @Nullable Object sourceId,
        Ref<TARGET> target,
        @Nullable Object targetId,
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
            recordLinked(type, source, targetId, data, sourceTracker);
        Runnable announcement = () -> {
            if (same && notifyChanges) {
                onChanged(sourceStore, type,
                    RelationshipChangeSystem.Kind.ADDED, source, sourceId, target, targetId, null, null, null, data);
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
        validateNow(type, source, target, sourceStore, targetStore);
        Object sourceId = sourceTracker == null ? null : sourceTracker.getIdentity(source);
        Object targetId = source == target ? sourceId
            : targetIdentity(targetTracker(sourceTracker, targetStore, same), targetStore, target, same);
        validateIdentities(type, source, target, sourceTracker, same, sourceId, targetId, true);
        var sourceCommand = RelationshipAccessSystem.forStoreCommand(sourceStore);
        var targetCommand = RelationshipAccessSystem.forStoreCommand(targetStore);
        assertFree(sourceCommand, targetCommand, sourceStore, targetStore, same);
        if (type.getDescriptor().isSymmetric()) {
            symmetricCommand(sourceStore, type, sourceTracker)
                .putPair(source, sourceId, (Ref<SOURCE>) target, targetId, data);
            return;
        }
        if (same && sourceTracker != null
            && sourceTracker.hasUnresolvedLink(type, source, target, sourceId, targetId)) {
            Object oldData = sourceTracker.getUnresolvedLinkData(type, source, target, sourceId, targetId);
            Runnable storage = () ->
                sourceTracker.updateUnresolvedLink(type, source, target, sourceId, targetId, data);
            Runnable record = () -> {
                var persistence = (RelationshipPersistence) type.getRelationshipTypeRegistry().getPersistence();
                syncPersistence(type, source, targetId, true, data, sourceTracker, persistence);
            };
            Runnable announcement = () ->
                onChanged(sourceStore, type,
                    RelationshipChangeSystem.Kind.SET, source, sourceId, target, targetId, null, null, (LINK_DATA) oldData, data);
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
                recordLinked(type, source, targetId, data, sourceTracker);
            Runnable announcement = () -> {
                if (same) {
                    onChanged(sourceStore, type,
                        RelationshipChangeSystem.Kind.SET, source, sourceId, target, targetId, null, null, oldData, data);
                }
            };
            finish(sourceCommand, targetCommand, same, storage, record, announcement);
            return;
        }
        if (outgoing != null && outgoing.size() != 0 && type.getDescriptor().isExclusive()) {
            Ref<TARGET> oldTarget = outgoing.getTarget(0);
            LINK_DATA oldData = RelationshipStorage.getLinkDataOf(type, oldTarget, outgoing);
            Object oldTargetId = oldTarget == source ? sourceId
                : targetIdentity(targetTracker(sourceTracker, targetStore, same), targetStore, oldTarget, same);
            moveTarget(sourceStore, targetStore, type, source, sourceId, oldTarget, oldTargetId, target, targetId,
                outgoing, oldData, data, same, sourceTracker, sourceCommand, targetCommand);
            return;
        }
        if (type.getDescriptor().isExclusive() && sourceTracker != null
            && sourceTracker.hasUnresolvedOutgoing(type, source, sourceId)) {
            var previousTargets = new ArrayList<RelationshipTracker.DroppedTarget<LINK_DATA>>();
            Runnable storage = () ->
                RelationshipStorage.attachLink(sourceStore, targetStore, type, source, target, data);
            Runnable record = () -> {
                previousTargets.addAll(sourceTracker.dropUnresolvedTargets(type, source, sourceId));
                var persistence = type.getRelationshipTypeRegistry().getPersistence();
                for (var previous : previousTargets) {
                    syncPersistence(type, source, previous.identity(), false, null, sourceTracker, persistence);
                }
                recordLinked(type, source, targetId, data, sourceTracker);
            };
            Runnable announcement = () -> {
                if (same && sourceStore.getRegistry()
                    .getWorldEventTypeForClass(RelationshipChangeSystem.ChangeEvent.class) != null) {
                    var previous = previousTargets.getFirst();
                    RelationshipChangeSystem.dispatch(sourceStore, new RelationshipChangeSystem.ChangeEvent<>(
                        (GenericRelationshipType<SOURCE, SOURCE, LINK_DATA>) type,
                        RelationshipChangeSystem.Kind.RETARGETED,
                        new RelationshipChangeSystem.LinkedEntity<>(source, sourceId),
                        new RelationshipChangeSystem.LinkedEntity<>((Ref<SOURCE>) target, targetId),
                        new RelationshipChangeSystem.LinkedEntity<>(null, previous.identity()), previous.data(), data));
                }
            };
            finish(sourceCommand, targetCommand, same, storage, record, announcement);
            return;
        }
        attachNewLink(sourceStore, targetStore, type, source, sourceId, target, targetId, data, same, sourceTracker,
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
                () -> removeNow(sourceStore, targetStore, type, source, target, strict, same, sourceTracker, notifyChanges,
                    relationships != null && type.getDescriptor().isSymmetric()));
            return;
        }
        removeNow(sourceStore, targetStore, type, source, target, strict, same, sourceTracker, notifyChanges,
            relationships != null && type.getDescriptor().isSymmetric());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <SOURCE, TARGET, LINK_DATA> void removeNow(
        Store<SOURCE> sourceStore,
        Store<TARGET> targetStore,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        boolean strict,
        boolean same,
        @Nullable RelationshipTracker sourceTracker,
        boolean notifyChanges,
        boolean maintainTwins
    ) {
        validateNow(type, source, target, sourceStore, targetStore);
        Object sourceId = sourceTracker == null ? null : sourceTracker.getIdentity(source);
        Object targetId = source == target ? sourceId
            : targetIdentity(targetTracker(sourceTracker, targetStore, same), targetStore, target, same);
        validateIdentities(type, source, target, sourceTracker, same, sourceId, targetId, false);
        var sourceCommand = RelationshipAccessSystem.forStoreCommand(sourceStore);
        var targetCommand = RelationshipAccessSystem.forStoreCommand(targetStore);
        assertFree(sourceCommand, targetCommand, sourceStore, targetStore, same);
        if (maintainTwins) {
            symmetricCommand(sourceStore, type, sourceTracker)
                .removePair(source, sourceId, (Ref<SOURCE>) target, targetId, strict);
            return;
        }
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
            var persistence = (RelationshipPersistence) type.getRelationshipTypeRegistry().getPersistence();
            syncPersistence(type, source, targetId, false, null, sourceTracker, persistence);
        };
        Runnable announcement = () -> {
            if (same && notifyChanges) {
                onChanged(sourceStore, type,
                    RelationshipChangeSystem.Kind.REMOVED, source, sourceId, target, targetId, null, null, null, data);
            }
        };
        finish(sourceCommand, targetCommand, same, storage, record, announcement);
    }

    static <SOURCE, TARGET, LINK_DATA> void clearTargets(
        ComponentAccessor<SOURCE> accessor,
        @Nullable Relationships relationships,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        @Nullable RelationshipTracker<?, ?> sourceTracker
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Store<SOURCE> accessorStore = RelationshipStorage.storeOfAccessor(accessor);
        Store<SOURCE> sourceStore = RelationshipStorage.getSourceStore(source, accessor, accessorStore);
        validateClearSubmission(type, source, sourceStore);
        if (accessor != accessorStore) {
            CommandBuffer<?> queue = (CommandBuffer<?>) accessor;
            queue.run(ignored -> {
                if (relationships != null) relationships.ensureOpen();
                validateClearSubmission(type, source, sourceStore);
                if (source.isValid()) {
                    clearTargetsNow(sourceStore, type, source, sourceTracker);
                }
            });
            return;
        }
        clearTargetsNow(sourceStore, type, source, sourceTracker);
    }

    private static <SOURCE> void validateClearSubmission(
        GenericRelationshipType<SOURCE, ?, ?> type,
        Ref<SOURCE> source,
        Store<SOURCE> sourceStore
    ) {
        if (sourceStore.isShutdown() || sourceStore.getRegistry().isShutdown()
            || type.getExpectedTargetRegistry().isShutdown()) {
            throw new IllegalStateException("Cannot access relationships for a stopped Store");
        }
        type.validate(sourceStore);
        if (source.getStore() != sourceStore) {
            throw new IllegalArgumentException("Linked entity belongs to a different store");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <SOURCE, TARGET, LINK_DATA> void clearTargetsNow(
        Store<SOURCE> sourceStore,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        @Nullable RelationshipTracker<?, ?> sourceTracker
    ) {
        source.validate(sourceStore);
        sourceStore.assertThread();
        var outgoing = sourceStore.getComponent(source, type.getSourceType());
        Store<TARGET> targetStore = outgoing == null || outgoing.size() == 0
            ? (Store<TARGET>) sourceStore : outgoing.getTarget(0).getStore();
        boolean same = sourceStore == targetStore;
        boolean announce = type.getExpectedTargetRegistry() == sourceStore.getRegistry();
        var sourceCommand = RelationshipAccessSystem.forStoreCommand(sourceStore);
        var targetCommand = RelationshipAccessSystem.forStoreCommand(targetStore);
        assertFree(sourceCommand, targetCommand, sourceStore, targetStore, same);
        var tracker = (RelationshipTracker<SOURCE, ?>) sourceTracker;
        Object sourceId = tracker == null ? null : tracker.getIdentity(source);
        if (type.getDescriptor().isSymmetric()) {
            symmetricCommand(sourceStore, type, sourceTracker).clear(source, sourceId);
            return;
        }
        var targets = new ArrayList<ClearedTarget<TARGET, LINK_DATA>>();
        var announcements = new ArrayList<RelationshipChangeSystem.ChangeEvent<SOURCE, LINK_DATA>>();
        if (outgoing != null) {
            for (int index = 0; index < outgoing.size(); index++) {
                var target = outgoing.getTarget(index);
                validateNow(type, source, target, sourceStore, targetStore);
                var data = RelationshipStorage.getLinkDataOf(type, target, outgoing);
                Object targetId = source == target ? sourceId
                    : targetIdentity(targetTracker(sourceTracker, targetStore, same), targetStore, target, same);
                validateIdentities(type, source, target, sourceTracker, same, sourceId, targetId, false);
                targets.add(new ClearedTarget<>(target, targetId, data));
                if (announce) {
                    announcements.add(RelationshipChangeSystem.newRemoval((GenericRelationshipType) type,
                        source, sourceId, (Ref) target, targetId, data));
                }
            }
        }
        Runnable storage = () -> {
            for (var target : targets) {
                RelationshipStorage.removeIncoming(targetStore, type, source, target.reference());
                RelationshipStorage.removeOutgoingTarget(sourceStore, type, source, target.reference(), outgoing);
            }
        };
        Runnable record = () -> {
            var persistence = type.getRelationshipTypeRegistry().getPersistence();
            for (var target : targets) {
                syncPersistence(type, source, target.identity(), false, null, sourceTracker, persistence);
            }
            if (tracker != null) {
                for (var target : tracker.dropUnresolvedTargets(type, source, sourceId)) {
                    syncPersistence(type, source, target.identity(), false, null, sourceTracker, persistence);
                    if (announce) {
                        announcements.add(RelationshipChangeSystem.newRemoval((GenericRelationshipType) type,
                            source, sourceId, null, target.identity(), target.data()));
                    }
                }
            }
        };
        Runnable announcement = () -> {
            if (sourceStore.getRegistry().getWorldEventTypeForClass(RelationshipChangeSystem.ChangeEvent.class) != null) {
                for (var change : announcements) {
                    RelationshipChangeSystem.dispatch(sourceStore, change);
                }
            }
        };
        finish(sourceCommand, targetCommand, same, storage, record, announcement);
    }

    private record ClearedTarget<TARGET, LINK_DATA>(
        Ref<TARGET> reference,
        @Nullable Object identity,
        @Nullable LINK_DATA data
    ) { }

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
        validateNow(type, source, oldTarget, sourceStore, targetStore);
        validateNow(type, source, newTarget, sourceStore, targetStore);
        Object sourceId = sourceTracker == null ? null : sourceTracker.getIdentity(source);
        var targetTracker = targetTracker(sourceTracker, targetStore, same);
        Object oldTargetId = source == oldTarget ? sourceId
            : targetIdentity(targetTracker, targetStore, oldTarget, same);
        Object newTargetId = source == newTarget ? sourceId : oldTarget == newTarget ? oldTargetId
            : targetIdentity(targetTracker, targetStore, newTarget, same);
        validateIdentities(type, source, oldTarget, sourceTracker, same, sourceId, oldTargetId, false);
        validateIdentities(type, source, newTarget, sourceTracker, same, sourceId, newTargetId, false);
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
            && sourceTracker.hasUnresolvedLink(type, source, newTarget, sourceId, newTargetId)) {
            throw new IllegalStateException("Destination is already linked for relationship type '"
                + type.getDescriptor().id() + "'");
        }
        LINK_DATA data = RelationshipStorage.getLinkDataOf(type, oldTarget, outgoing);
        if (type.getDescriptor().isSymmetric()) {
            symmetricCommand(sourceStore, type, sourceTracker).retargetPair(source, sourceId,
                (Ref<SOURCE>) oldTarget, oldTargetId, (Ref<SOURCE>) newTarget, newTargetId,
                (OutgoingLink<SOURCE, SOURCE>) outgoing, data);
            return;
        }
        moveTarget(sourceStore, targetStore, type, source, sourceId, oldTarget, oldTargetId, newTarget,
            newTargetId, outgoing, null, data, same, sourceTracker, sourceCommand, targetCommand);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <SOURCE, TARGET, LINK_DATA> void moveTarget(
        Store<SOURCE> sourceStore,
        Store<TARGET> targetStore,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        @Nullable Object sourceId,
        Ref<TARGET> oldTarget,
        @Nullable Object oldTargetId,
        Ref<TARGET> newTarget,
        @Nullable Object newTargetId,
        OutgoingLink<SOURCE, TARGET> outgoing,
        @Nullable LINK_DATA oldData,
        @Nullable LINK_DATA data,
        boolean same,
        @Nullable RelationshipTracker sourceTracker,
        RelationshipProcessingTracker sourceCommand,
        RelationshipProcessingTracker targetCommand
    ) {
        Runnable storage = () -> {
            RelationshipStorage.addIncoming(targetStore, type, source, newTarget);
            RelationshipStorage.removeIncoming(targetStore, type, source, oldTarget);
            outgoing.retarget(oldTarget, newTarget);
            outgoing.setData(newTarget, data);
            sourceStore.replaceComponent(source, type.getSourceType(), outgoing);
        };
        Runnable record = () -> {
            var persistence = (RelationshipPersistence) type.getRelationshipTypeRegistry().getPersistence();
            syncPersistence(type, source, oldTargetId, false, null, sourceTracker, persistence);
            syncPersistence(type, source, newTargetId, true, data, sourceTracker, persistence);
        };
        Runnable announcement = () -> {
            if (same) {
                onChanged(sourceStore, type,
                    RelationshipChangeSystem.Kind.RETARGETED, source, sourceId, newTarget, newTargetId,
                    oldTarget, oldTargetId, oldData, data);
            }
        };
        finish(sourceCommand, targetCommand, same, storage, record, announcement);
    }

    @SuppressWarnings("unchecked")
    private static <SOURCE, TARGET, LINK_DATA> SymmetricCommand<SOURCE, LINK_DATA> symmetricCommand(
        Store<SOURCE> store,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        @Nullable RelationshipTracker<?, ?> tracker
    ) {
        return new SymmetricCommand<>(store, (GenericRelationshipType<SOURCE, SOURCE, LINK_DATA>) type,
            (RelationshipTracker<SOURCE, ?>) tracker);
    }

    private static final class SymmetricCommand<ECS_TYPE, LINK_DATA> {
        private final Store<ECS_TYPE> store;
        private final GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type;
        @Nullable private final RelationshipTracker<ECS_TYPE, ?> tracker;
        private final ArrayList<Runnable> storage = new ArrayList<>();
        private final ArrayList<Runnable> records = new ArrayList<>();
        private final ArrayList<RelationshipChangeSystem.ChangeEvent<ECS_TYPE, LINK_DATA>> announcements = new ArrayList<>();

        private SymmetricCommand(Store<ECS_TYPE> store,
            GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
            @Nullable RelationshipTracker<ECS_TYPE, ?> tracker) {
            this.store = store;
            this.type = type;
            this.tracker = tracker;
        }

        private void putPair(Ref<ECS_TYPE> source, @Nullable Object sourceId,
            Ref<ECS_TYPE> target, @Nullable Object targetId, @Nullable LINK_DATA data) {
            put(source, sourceId, target, targetId, data);
            if (source != target) put(target, targetId, source, sourceId, data);
            finish();
        }

        private void removePair(Ref<ECS_TYPE> source, @Nullable Object sourceId,
            Ref<ECS_TYPE> target, @Nullable Object targetId, boolean strict) {
            var outgoing = store.getComponent(source, type.getSourceType());
            if (strict && (outgoing == null || !outgoing.contains(target))) {
                throw newMissingLinkException(type);
            }
            remove(source, sourceId, target, targetId);
            if (source != target) remove(target, targetId, source, sourceId);
            finish();
        }

        private void retargetPair(Ref<ECS_TYPE> source, @Nullable Object sourceId,
            Ref<ECS_TYPE> oldTarget, @Nullable Object oldTargetId,
            Ref<ECS_TYPE> newTarget, @Nullable Object newTargetId,
            OutgoingLink<ECS_TYPE, ECS_TYPE> outgoing, @Nullable LINK_DATA data) {
            storage.add(() -> {
                RelationshipStorage.addIncoming(store, type, source, newTarget);
                RelationshipStorage.removeIncoming(store, type, source, oldTarget);
                outgoing.retarget(oldTarget, newTarget);
                store.replaceComponent(source, type.getSourceType(), outgoing);
            });
            records.add(() -> {
                synchronize(source, oldTargetId, false, null);
                synchronize(source, newTargetId, true, data);
            });
            addChange(RelationshipChangeSystem.Kind.RETARGETED, source, sourceId, newTarget, newTargetId,
                oldTarget, oldTargetId, null, data);
            if (source != oldTarget) remove(oldTarget, oldTargetId, source, sourceId);
            if (source != newTarget) put(newTarget, newTargetId, source, sourceId, data);
            finish();
        }

        private void clear(Ref<ECS_TYPE> source, @Nullable Object sourceId) {
            var outgoing = store.getComponent(source, type.getSourceType());
            if (outgoing != null) {
                for (int index = 0; index < outgoing.size(); index++) {
                    var target = outgoing.getTarget(index);
                    validateNow(type, source, target, store, store);
                    Object targetId = source == target ? sourceId : tracker == null ? null : tracker.getIdentity(target);
                    validateIdentities(type, source, target, tracker, true, sourceId, targetId, false);
                    remove(source, sourceId, target, targetId);
                    if (source != target) remove(target, targetId, source, sourceId);
                }
            }
            if (tracker != null) {
                records.add(() -> {
                    for (var target : tracker.dropUnresolvedTargets(type, source, sourceId)) {
                        synchronize(source, target.identity(), false, null);
                        var removed = RelationshipChangeSystem.newRemoval(type, source, sourceId,
                            null, target.identity(), target.data());
                        announcements.add(removed);
                        var twin = tracker.dropUnresolvedTwin(type, source, sourceId, target.identity());
                        if (twin != null) {
                            var removedTwin = RelationshipChangeSystem.newRemoval(type, null, twin.identity(),
                                source, sourceId, twin.data());
                            announcements.add(removedTwin);
                        }
                    }
                });
            }
            finish();
        }

        private void put(Ref<ECS_TYPE> source, @Nullable Object sourceId,
            Ref<ECS_TYPE> target, @Nullable Object targetId, @Nullable LINK_DATA data) {
            var outgoing = store.getComponent(source, type.getSourceType());
            boolean loaded = outgoing != null && outgoing.contains(target);
            boolean unresolved = tracker != null && tracker.hasUnresolvedLink(type, source, target, sourceId, targetId);
            LINK_DATA oldData = unresolved ? tracker.getUnresolvedLinkData(type, source, target, sourceId, targetId)
                : RelationshipStorage.getLinkDataOf(type, target, outgoing);
            storage.add(() -> {
                if (unresolved) {
                    tracker.updateUnresolvedLink(type, source, target, sourceId, targetId, data);
                } else if (loaded) {
                    outgoing.setData(target, data);
                    store.replaceComponent(source, type.getSourceType(), outgoing);
                } else {
                    RelationshipStorage.attachLink(store, store, type, source, target, data);
                }
            });
            records.add(() -> synchronize(source, targetId, true, data));
            var kind = loaded || unresolved ? RelationshipChangeSystem.Kind.SET : RelationshipChangeSystem.Kind.ADDED;
            addChange(kind, source, sourceId, target, targetId, null, null, oldData, data);
        }

        private void remove(Ref<ECS_TYPE> source, @Nullable Object sourceId,
            Ref<ECS_TYPE> target, @Nullable Object targetId) {
            var outgoing = store.getComponent(source, type.getSourceType());
            boolean loaded = outgoing != null && outgoing.contains(target);
            boolean unresolved = tracker != null && tracker.hasUnresolvedLink(type, source, target, sourceId, targetId);
            if (!loaded && !unresolved) return;
            LINK_DATA data = unresolved ? tracker.getUnresolvedLinkData(type, source, target, sourceId, targetId)
                : RelationshipStorage.getLinkDataOf(type, target, outgoing);
            if (loaded) {
                storage.add(() -> {
                    RelationshipStorage.removeIncoming(store, type, source, target);
                    RelationshipStorage.removeOutgoingTarget(store, type, source, target, outgoing);
                });
            }
            records.add(() -> {
                if (unresolved) tracker.dropUnresolvedLink(type, source, target, sourceId, targetId);
                synchronize(source, targetId, false, null);
            });
            addChange(RelationshipChangeSystem.Kind.REMOVED, source, sourceId, target, targetId, null, null, null, data);
        }

        private void addChange(RelationshipChangeSystem.Kind kind,
            Ref<ECS_TYPE> source, @Nullable Object sourceId, Ref<ECS_TYPE> target, @Nullable Object targetId,
            @Nullable Ref<ECS_TYPE> oldTarget, @Nullable Object oldTargetId,
            @Nullable LINK_DATA oldData, @Nullable LINK_DATA data) {
            if (store.getRegistry().getWorldEventTypeForClass(RelationshipChangeSystem.ChangeEvent.class) == null) return;
            announcements.add(new RelationshipChangeSystem.ChangeEvent<>(type, kind,
                new RelationshipChangeSystem.LinkedEntity<>(source, sourceId),
                new RelationshipChangeSystem.LinkedEntity<>(target, targetId),
                oldTarget == null ? null : new RelationshipChangeSystem.LinkedEntity<>(oldTarget, oldTargetId), oldData, data));
        }

        private void synchronize(Ref<ECS_TYPE> source, @Nullable Object targetId,
            boolean present, @Nullable LINK_DATA data) {
            syncPersistence(type, source, targetId, present, data, tracker,
                type.getRelationshipTypeRegistry().getPersistence());
        }

        private void dispatch(RelationshipChangeSystem.ChangeEvent<ECS_TYPE, LINK_DATA> change) {
            if (store.getRegistry().getWorldEventTypeForClass(RelationshipChangeSystem.ChangeEvent.class) != null) {
                RelationshipChangeSystem.dispatch(store, change);
            }
        }

        private void finish() {
            var command = RelationshipAccessSystem.forStoreCommand(store);
            RelationshipCommands.finish(command, command, true,
                () -> storage.forEach(Runnable::run),
                () -> records.forEach(Runnable::run),
                () -> announcements.forEach(this::dispatch));
        }
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

    private static <SOURCE, TARGET> void validateNow(
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        Store<SOURCE> sourceStore,
        Store<TARGET> targetStore
    ) {
        validateSubmission(type, source, target, sourceStore, targetStore);
        source.validate(sourceStore);
        target.validate(targetStore);
        sourceStore.assertThread();
        targetStore.assertThread();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <SOURCE, TARGET> void validateIdentities(
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        @Nullable RelationshipTracker sourceTracker,
        boolean same,
        @Nullable Object sourceId,
        @Nullable Object targetId,
        boolean replacesExclusiveTarget
    ) {
        if (sourceTracker != null) {
            sourceTracker.validateLink(type, source, target, sourceId, targetId, replacesExclusiveTarget);
        } else if (!same && retains(type.getDescriptor())) {
            throw new IllegalStateException("Relationship type '" + type.getDescriptor().id()
                + "' requires an installed tracker and stable identities on both sides");
        }
        if (same && sourceTracker != null) {
            var persistence = (RelationshipPersistence) type.getRelationshipTypeRegistry().getPersistence();
            if (persistence != null) {
                persistence.validateMutationWithIdentities(type, sourceId, targetId);
            }
        }
        if (!same) {
            assertSameWorld(source.getStore(), target.getStore());
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

    private static <SOURCE, TARGET, LINK_DATA> void recordLinked(
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        @Nullable Object targetId,
        @Nullable LINK_DATA data,
        @Nullable RelationshipTracker<?, ?> sourceTracker
    ) {
        syncPersistence(type, source, targetId, true, data, sourceTracker,
            type.getRelationshipTypeRegistry().getPersistence());
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
    private static <SOURCE, TARGET, LINK_DATA> void onChanged(
        Store<SOURCE> sourceStore,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        RelationshipChangeSystem.Kind kind,
        Ref<SOURCE> source,
        @Nullable Object sourceId,
        Ref<TARGET> target,
        @Nullable Object targetId,
        @Nullable Ref<TARGET> oldTarget,
        @Nullable Object oldTargetId,
        @Nullable LINK_DATA oldData,
        @Nullable LINK_DATA data
    ) {
        if (sourceStore.getRegistry().getWorldEventTypeForClass(RelationshipChangeSystem.ChangeEvent.class) == null) {
            return;
        }
        Ref<SOURCE> targetAsSource = (Ref<SOURCE>) target;
        Ref<SOURCE> oldAsSource = oldTarget == null ? null : (Ref<SOURCE>) oldTarget;
        var sourceLinkedEntity = new RelationshipChangeSystem.LinkedEntity<>(source.isValid() ? source : null, sourceId);
        var targetLinkedEntity = new RelationshipChangeSystem.LinkedEntity<>(targetAsSource.isValid() ? targetAsSource : null,
            targetId);
        RelationshipChangeSystem.LinkedEntity<SOURCE> oldLinkedEntity = oldAsSource == null ? null
            : new RelationshipChangeSystem.LinkedEntity<>(oldAsSource.isValid() ? oldAsSource : null, oldTargetId);
        sourceStore.invoke(new RelationshipChangeSystem.ChangeEvent<>((GenericRelationshipType<SOURCE, SOURCE, LINK_DATA>) type,
            kind, sourceLinkedEntity, targetLinkedEntity, oldLinkedEntity, oldData, data));
    }
}
