/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import javax.annotation.Nullable;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/// Relationship reads, queries and commands. Commands take a Store for immediate changes or its
/// CommandBuffer for queued changes. Immediate commands are rejected while relationships are processing.
/// Server plugins use `Relwind.get().getRelationships()`. Calls through a cached instance, and commands
/// queued through it, throw IllegalStateException after its plugin shuts down.
public final class Relationships implements AutoCloseable {
    private volatile boolean closed;

    /// Creates a service for a standalone component installation. Server plugins obtain the
    /// plugin-managed instance from `Relwind.get().getRelationships()` instead.
    public Relationships() {
    }

    /// Rejects subsequent API calls and queued commands submitted through this instance.
    /// The caller must stop Store work before closing its underlying installations.
    @Override
    public void close() {
        closed = true;
    }

    void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Relationships service is closed");
        }
    }

    /// @throws IllegalStateException if the pair exists, or a single target source holds another target
    public <SOURCE, TARGET> void addTarget(
        ComponentAccessor<SOURCE> accessor,
        Ref<SOURCE> source,
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<TARGET> target
    ) {
        ensureOpen();
        RelationshipCommands.add(accessor, this, type, source, target, null,
            type.getRelationshipTypeRegistry().getTracker(), true);
    }

    /// @throws IllegalStateException if the type carries no link data, the pair exists, or a single
    /// target source holds another target
    public <SOURCE, TARGET, LINK_DATA> void addTarget(
        ComponentAccessor<SOURCE> accessor,
        Ref<SOURCE> source,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<TARGET> target,
        LINK_DATA data
    ) {
        ensureOpen();
        requireLinkData(type, "addTarget");
        RelationshipCommands.add(accessor, this, type, source, target, data,
            type.getRelationshipTypeRegistry().getTracker(), true);
    }

    /// Inserts a data-free link, or replaces the existing link's data with null.
    /// @throws IllegalStateException if a single target source already holds another target
    public <SOURCE, TARGET> void putTarget(
        ComponentAccessor<SOURCE> accessor,
        Ref<SOURCE> source,
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<TARGET> target
    ) {
        ensureOpen();
        RelationshipCommands.put(accessor, this, type, source, target, null,
            type.getRelationshipTypeRegistry().getTracker());
    }

    /// Inserts or replaces link data and announces the change. Per-link data must be immutable:
    /// pass a replacement value instead of editing a value returned by a read. Native data
    /// components retain their own copy contract. Reacquire data obtained before this call.
    /// @throws IllegalStateException if the type carries no link data, or a single target source
    /// already holds another target
    public <SOURCE, TARGET, LINK_DATA> void putTarget(
        ComponentAccessor<SOURCE> accessor,
        Ref<SOURCE> source,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<TARGET> target,
        LINK_DATA data
    ) {
        ensureOpen();
        requireLinkData(type, "putTarget");
        RelationshipCommands.put(accessor, this, type, source, target, data,
            type.getRelationshipTypeRegistry().getTracker());
    }

    /// Retargeting to the same target changes nothing.
    /// @throws IllegalStateException if the pair does not exist, or this source holds the destination
    public <SOURCE, TARGET> void retarget(
        ComponentAccessor<SOURCE> accessor,
        Ref<SOURCE> source,
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<TARGET> oldTarget,
        Ref<TARGET> newTarget
    ) {
        ensureOpen();
        RelationshipCommands.retarget(accessor, this, type, source, oldTarget, newTarget,
            type.getRelationshipTypeRegistry().getTracker());
    }

    /// @throws IllegalStateException if the pair does not exist. {@link #tryRemoveTarget} ignores a missing pair.
    public <SOURCE, TARGET> void removeTarget(
        ComponentAccessor<SOURCE> accessor,
        Ref<SOURCE> source,
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<TARGET> target
    ) {
        ensureOpen();
        RelationshipCommands.remove(accessor, this, type, source, target, true,
            type.getRelationshipTypeRegistry().getTracker(), true);
    }

    public <SOURCE, TARGET> void tryRemoveTarget(
        ComponentAccessor<SOURCE> accessor,
        Ref<SOURCE> source,
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<TARGET> target
    ) {
        ensureOpen();
        RelationshipCommands.remove(accessor, this, type, source, target, false,
            type.getRelationshipTypeRegistry().getTracker(), true);
    }

    private static void requireLinkData(GenericRelationshipType<?, ?, ?> type, String command) {
        if (type.getDescriptor().linkDataClass() == Void.class) {
            throw new IllegalStateException(
                "Relationship type '" + type.getDescriptor().id() + "' carries no link data, so " + command
                    + " takes no data");
        }
    }

    /// Iterates matching relationships on the Store thread, outside an existing processing callback.
    /// Each result expires when its callback returns. Changes use the supplied native command buffer,
    /// which the Store consumes after successful iteration, as in {@link Store#forEachChunk}.
    public <ECS_TYPE, LINK_DATA> void forEach(
        Store<ECS_TYPE> store,
        RelationshipQuery.Definition<ECS_TYPE, LINK_DATA> query,
        BiConsumer<RelationshipResult<ECS_TYPE, LINK_DATA>, CommandBuffer<ECS_TYPE>> consumer
    ) {
        ensureOpen();
        Objects.requireNonNull(store, "store").assertThread();
        store.assertWriteProcessing();
        Objects.requireNonNull(query, "query").validateRegistry(store.getRegistry());
        query.validate();
        Objects.requireNonNull(consumer, "consumer");
        var access = RelationshipAccessSystem.forStore(store);
        access.getProcessingTracker().assertNotProcessing();
        var results = access.<LINK_DATA>borrowResults();
        try {
            store.forEachChunk(query, (chunk, commands) -> {
                for (int index = 0; index < chunk.size(); index++) {
                    RelationshipEvaluator.evaluate(store, chunk.getReferenceTo(index), query, results);
                    for (int i = 0; i < results.size(); i++) {
                        var result = results.getCallbackResult(i);
                        try {
                            consumer.accept(result, commands);
                        } finally {
                            result.clear();
                        }
                    }
                }
            });
        } finally {
            access.releaseResults(results);
        }
    }

    /// Evaluates one source on its Store thread, then lends the results to the reader, including
    /// an empty batch. Results and their bindings expire when the reader returns. Nested fetches
    /// use separate batches. Entity references and link data are not copied.
    ///
    /// The reader may change entities subject to the Store's existing processing rules. A fetch
    /// does not create or consume a command buffer, or read commands waiting in one.
    public <ECS_TYPE, LINK_DATA, R> R fetch(
        Ref<ECS_TYPE> source,
        RelationshipQuery.Definition<ECS_TYPE, LINK_DATA> query,
        Function<RelationshipResults<ECS_TYPE, LINK_DATA>, R> reader
    ) {
        ensureOpen();
        Objects.requireNonNull(reader, "reader");
        var store = Objects.requireNonNull(source, "source").getStore();
        store.assertThread();
        Objects.requireNonNull(query, "query").getRelationshipType().validate(store);
        var access = RelationshipAccessSystem.forStore(store);
        var results = access.<LINK_DATA>borrowResults();
        try {
            RelationshipEvaluator.evaluate(store, source, query, results);
            return reader.apply(results);
        } finally {
            access.releaseResults(results);
        }
    }

    /// Borrows the reachable results under the same lifetime and command rules as {@link #fetch(Ref,
    /// RelationshipQuery.Definition, Function)}. The reader also receives empty batches so it can
    /// distinguish a complete search from a truncated traversal with no matches.
    public <ECS_TYPE, LINK_DATA, R> R fetch(
        Ref<ECS_TYPE> start,
        RelationshipQuery.ReachableEnumeration<ECS_TYPE, LINK_DATA> query,
        Function<RelationshipResults<ECS_TYPE, LINK_DATA>, R> reader
    ) {
        ensureOpen();
        Objects.requireNonNull(reader, "reader");
        var store = Objects.requireNonNull(start, "start").getStore();
        store.assertThread();
        Objects.requireNonNull(query, "query").getRelationshipType().validate(store);
        var access = RelationshipAccessSystem.forStore(store);
        var results = access.<LINK_DATA>borrowResults();
        try {
            RelationshipEvaluator.evaluate(store, start, query, results);
            return reader.apply(results);
        } finally {
            access.releaseResults(results);
        }
    }

    private static <SOURCE, TARGET> void validateSourceRead(GenericRelationshipType<SOURCE, TARGET, ?> type, Ref<SOURCE> source) {
        Store<SOURCE> sourceStore = Objects.requireNonNull(source, "source").getStore();
        if (sourceStore.isShutdown() || sourceStore.getRegistry().isShutdown()) {
            throw new IllegalStateException("Cannot access relationships for a stopped Store");
        }
        type.validate(sourceStore);
        source.validate(sourceStore);
        sourceStore.assertThread();
    }

    private static <SOURCE, TARGET> void validateTargetRead(GenericRelationshipType<SOURCE, TARGET, ?> type, Ref<TARGET> target) {
        Store<TARGET> targetStore = Objects.requireNonNull(target, "target").getStore();
        if (targetStore.isShutdown() || targetStore.getRegistry().isShutdown()) {
            throw new IllegalStateException("Cannot access relationships for a stopped Store");
        }
        if (targetStore.getRegistry() != type.getExpectedTargetRegistry()) {
            throw new IllegalArgumentException(
                "Relationship type '" + type.getDescriptor().id() + "' is for a different registry");
        }
        type.getIncomingType().validate();
        target.validate(targetStore);
        targetStore.assertThread();
    }

    @Nullable
    public <SOURCE, TARGET> Ref<TARGET> getFirstTarget(
        Ref<SOURCE> source,
        GenericRelationshipType<SOURCE, TARGET, ?> type
    ) {
        ensureOpen();
        validateSourceRead(type, source);
        var outgoing = source.getStore().getComponent(source, type.getSourceType());
        if (outgoing == null) {
            return null;
        }
        return outgoing.getTarget();
    }

    public <SOURCE, TARGET> int getTargetCount(Ref<SOURCE> source, GenericRelationshipType<SOURCE, TARGET, ?> type) {
        ensureOpen();
        validateSourceRead(type, source);
        var outgoing = source.getStore().getComponent(source, type.getSourceType());
        return outgoing == null ? 0 : outgoing.size();
    }

    public <SOURCE, TARGET> void forEachTarget(
        Ref<SOURCE> source,
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Consumer<? super Ref<TARGET>> consumer
    ) {
        ensureOpen();
        validateSourceRead(type, source);
        Objects.requireNonNull(consumer, "consumer");
        var outgoing = source.getStore().getComponent(source, type.getSourceType());
        if (outgoing == null) {
            return;
        }
        var command = RelationshipAccessSystem.forStoreCommand(source.getStore());
        command.beginTraversal();
        try {
            for (int index = 0; index < outgoing.size(); index++) {
                consumer.accept(outgoing.getTarget(index));
            }
        } finally {
            command.endTraversal();
        }
    }

    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <SOURCE, TARGET, LINK_DATA> LINK_DATA getData(
        Ref<SOURCE> source,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<TARGET> target
    ) {
        ensureOpen();
        validateSourceRead(type, source);
        Objects.requireNonNull(target, "target").validate(target.getStore());
        var outgoing = source.getStore().getComponent(source, type.getSourceType());
        if (outgoing == null) {
            return null;
        }
        ComponentType dataType = type.getDescriptor().getDataComponentType();
        if (dataType == null) {
            return outgoing.getData(target, type.getDescriptor().linkDataClass());
        }
        if (!outgoing.contains(target)) {
            return null;
        }
        return (LINK_DATA) source.getStore().getComponent(source, dataType);
    }

    public <SOURCE, TARGET> int getIncomingCount(Ref<TARGET> target, GenericRelationshipType<SOURCE, TARGET, ?> type) {
        ensureOpen();
        validateTargetRead(type, target);
        var incoming = target.getStore().getComponent(target, type.getIncomingType());
        return incoming == null ? 0 : incoming.size();
    }

    public <SOURCE, TARGET> void forEachIncomingSource(
        Ref<TARGET> target,
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Consumer<? super Ref<SOURCE>> consumer
    ) {
        ensureOpen();
        validateTargetRead(type, target);
        Objects.requireNonNull(consumer, "consumer");
        var incoming = target.getStore().getComponent(target, type.getIncomingType());
        if (incoming == null) {
            return;
        }
        var command = RelationshipAccessSystem.forStoreCommand(target.getStore());
        command.beginTraversal();
        try {
            incoming.forEach(consumer);
        } finally {
            command.endTraversal();
        }
    }

    public <SOURCE, TARGET> boolean hasUnresolvedTargets(
        Ref<SOURCE> source,
        GenericRelationshipType<SOURCE, TARGET, ?> type
    ) {
        ensureOpen();
        Objects.requireNonNull(source, "source");
        Store<SOURCE> sourceStore = source.getStore();
        if (sourceStore.isShutdown() || sourceStore.getRegistry().isShutdown()) {
            throw new IllegalStateException("Cannot access relationships for a stopped Store");
        }
        type.validate(sourceStore);
        var sourceTracker = type.getRelationshipTypeRegistry().getTracker();
        if (sourceTracker == null) {
            return false;
        }
        return sourceTracker.hasUnresolvedOutgoing(type, source);
    }

    /// A queued trim returns false, then releases empty storage when the buffer is consumed,
    /// skipping a linked entity that has gone invalid by then. Bridge incoming storage takes the source Store.
    public <SOURCE, TARGET> boolean trimIncomingLinks(
        ComponentAccessor<SOURCE> accessor,
        Ref<TARGET> target,
        GenericRelationshipType<SOURCE, TARGET, ?> type
    ) {
        ensureOpen();
        Objects.requireNonNull(accessor, "accessor");
        validateTargetRead(type, target);
        Store<SOURCE> accessorStore = RelationshipStorage.storeOfAccessor(accessor);
        Store<TARGET> targetStore = target.getStore();
        boolean same = accessorStore == targetStore;
        if (!same && accessor != accessorStore) {
            throw new IllegalArgumentException("Bridge incoming trim takes the source Store, not a CommandBuffer");
        }
        if (same && accessor != accessorStore) {
            CommandBuffer<?> queue = (CommandBuffer<?>) accessor;
            queue.run(ignored -> {
                ensureOpen();
                if (target.isValid()) {
                    trimIncomingNow(type, target);
                }
            });
            return false;
        }
        return trimIncomingNow(type, target);
    }

    private static <SOURCE, TARGET> boolean trimIncomingNow(GenericRelationshipType<SOURCE, TARGET, ?> type, Ref<TARGET> target) {
        Store<TARGET> targetStore = target.getStore();
        RelationshipAccessSystem.forStoreCommand(targetStore).assertNotProcessing();
        targetStore.assertWriteProcessing();
        validateTargetRead(type, target);
        var incoming = targetStore.getComponent(target, type.getIncomingType());
        if (incoming == null || incoming.size() != 0) {
            return false;
        }
        targetStore.removeComponent(target, type.getIncomingType());
        return true;
    }

    /// A queued trim returns false and releases empty storage when the buffer is consumed.
    public <SOURCE, TARGET> boolean trimSourceStorage(
        ComponentAccessor<SOURCE> accessor,
        Ref<SOURCE> source,
        GenericRelationshipType<SOURCE, TARGET, ?> type
    ) {
        ensureOpen();
        Objects.requireNonNull(source, "source");
        Store<SOURCE> accessorStore = RelationshipStorage.storeOfAccessor(accessor);
        RelationshipStorage.getSourceStore(source, accessor, accessorStore);
        if (accessor != accessorStore) {
            validateSourceRead(type, source);
            CommandBuffer<?> queue = (CommandBuffer<?>) accessor;
            queue.run(ignored -> {
                ensureOpen();
                if (source.isValid()) {
                    trimSourceNow(type, source);
                }
            });
            return false;
        }
        return trimSourceNow(type, source);
    }

    private static <SOURCE, TARGET> boolean trimSourceNow(GenericRelationshipType<SOURCE, TARGET, ?> type, Ref<SOURCE> source) {
        Store<SOURCE> sourceStore = source.getStore();
        RelationshipAccessSystem.forStoreCommand(sourceStore).assertNotProcessing();
        sourceStore.assertWriteProcessing();
        validateSourceRead(type, source);
        var outgoing = sourceStore.getComponent(source, type.getSourceType());
        if (outgoing == null || outgoing.size() != 0) {
            return false;
        }
        sourceStore.removeComponent(source, type.getSourceType());
        return true;
    }
}
