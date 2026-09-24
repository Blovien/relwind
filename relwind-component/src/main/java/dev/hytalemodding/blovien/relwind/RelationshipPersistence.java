/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/// Saves each source's outgoing links as [LinkRecord] values in a component on that source.
/// Install it through {@link RelationshipTypeRegistry#installPersistence}.
public final class RelationshipPersistence<ECS_TYPE> {
    public static final String COMPONENT_ID = "RelwindRelationships";

    // the metadata of an entity that has gone is not held here
    private final Map<RelationshipMetadata<ECS_TYPE>, Boolean> boundMetadata = Collections.synchronizedMap(new WeakHashMap<>());
    private final RelationshipTypeRegistry<ECS_TYPE> types;
    private final RelationshipTracker<ECS_TYPE, Object> installedTracker;
    private final ComponentType<ECS_TYPE, RelationshipMetadata<ECS_TYPE>> componentType;

    RelationshipPersistence(
        RelationshipTypeRegistry<ECS_TYPE> types,
        RelationshipTracker<ECS_TYPE, Object> installedTracker
    ) {
        this.types = types;
        this.installedTracker = installedTracker;
        @SuppressWarnings({"unchecked", "rawtypes"})
        var registered =
                (ComponentType<ECS_TYPE, RelationshipMetadata<ECS_TYPE>>)
                (ComponentType) types.getRegistrar()
                        .registerComponent(RelationshipMetadata.class, COMPONENT_ID, RelationshipMetadata.CODEC);
        componentType = registered;
    }

    void close() {
        var snapshots = new IdentityHashMap<RelationshipMetadata<ECS_TYPE>, RelationshipMetadata<ECS_TYPE>>();
        synchronized (boundMetadata) {
            for (var metadata : boundMetadata.keySet()) snapshots.put(metadata, metadata.freezeForUnregistration());
        }
        snapshots.forEach(RelationshipMetadata::replaceWith);
        types.getComponentRegistry().unregisterComponent(componentType);
        boundMetadata.clear();
    }

    @Nonnull
    public ComponentType<ECS_TYPE, RelationshipMetadata<ECS_TYPE>> getComponentType() {
        return componentType;
    }

    /// Restores through the command buffer, for an entity Hytale is admitting in a batch. A source
    /// whose cascade target is gone is removed instead.
    public void restore(CommandBuffer<ECS_TYPE> commands, Ref<ECS_TYPE> source) {
        source.validate(commands.getStore());
        if (requiresCascade(source)) {
            commands.tryRemoveEntity(source, RemoveReason.REMOVE);
            return;
        }
        commands.run(ignored -> {
            if (source.isValid()) {
                restore(source);
            }
        });
    }

    // Hytale compacts its array of surviving Refs after the batch
    private boolean requiresCascade(Ref<ECS_TYPE> source) {
        var store = source.getStore();
        var pending = new ArrayDeque<Ref<ECS_TYPE>>();
        var visited = new HashSet<Ref<ECS_TYPE>>();
        var tracker = getTracker();
        pending.add(source);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            var metadata = store.getComponent(current, componentType);
            if (metadata == null) {
                continue;
            }
            for (var record : metadata.getRecords()) {
                if (!LinkRecord.CASCADE_SOURCE.equals(record.getCleanupDisposition())) {
                    continue;
                }
                if (!types.getPersistenceIdentity().getInstallationName().equals(record.getTargetInstallation())) {
                    if (isTargetDeleted(store, record)) {
                        return true;
                    }
                    continue;
                }
                var target = record.getTargetIdentity(getTracker().getIdentityCodec());
                if (target == null) {
                    continue;
                }
                if (installedTracker.getPersistenceIdentity().isDeleted(store, target)
                    || tracker.hasPendingDeletion(target, store)) {
                    return true;
                }
                var linkedEntity = tracker.getRef(target, store);
                if (linkedEntity != null && linkedEntity.getStore() == store) {
                    pending.addLast(linkedEntity);
                }
            }
        }
        return false;
    }

    public void restore(Ref<ECS_TYPE> source) {
        Objects.requireNonNull(source, "source").validate();
        var store = source.getStore();
        store.assertThread();
        store.assertWriteProcessing();
        var metadata = store.getComponent(source, componentType);
        if (metadata == null) {
            return;
        }
        metadata = reconcileDeleted(source, metadata);
        if (metadata == null) {
            return;
        }
        metadata.checkAccess(store::assertThread);
        boundMetadata.put(metadata, Boolean.TRUE);
        var tracker = getTracker();
        var sourceId = requireIdentity(source);
        // readLinks decodes each target through the installation its record names
        for (var type : types.getRegisteredTypes()) {
            for (var link : metadata.readLinks(type)) {
                tracker.restorePersistent(type, sourceId, link.target(), source, link.data());
            }
        }
    }

    @Nonnull @SuppressWarnings("unchecked")
    private static Codec<Object> getIdentityCodecOf(RelationshipTracker<?, ?> tracker) {
        return (Codec<Object>) tracker.getIdentityCodec();
    }

    @Nullable
    private RelationshipMetadata<ECS_TYPE> reconcileDeleted(Ref<ECS_TYPE> source, RelationshipMetadata<ECS_TYPE> metadata) {
        var records = metadata.getRecords();
        var store = source.getStore();
        RelationshipMetadata<ECS_TYPE> replacement = null;
        for (int i = records.size() - 1; i >= 0; i--) {
            var record = records.get(i);
            var disposition = record.getCleanupDisposition();
            boolean cascade = LinkRecord.CASCADE_SOURCE.equals(disposition);
            if (!cascade && !LinkRecord.PRESERVE_SOURCE.equals(disposition)) {
                continue;
            }
            Object target = null;
            if (!types.getPersistenceIdentity().getInstallationName().equals(record.getTargetInstallation())) {
                if (!isTargetDeleted(store, record)) {
                    continue;
                }
            } else {
                target = record.getTargetIdentity(getTracker().getIdentityCodec());
                if (target == null || !installedTracker.getPersistenceIdentity().isDeleted(store, target)) {
                    continue;
                }
            }
            if (cascade) {
                store.removeEntity(source, RemoveReason.REMOVE);
                return null;
            }
            if (replacement == null) {
                replacement = metadata.newMutableCopy();
            }
            var removed = replacement.getRecords().get(i);
            replacement.removeRecord(removed);
            // only a record of this installation names an identity this tracker can match
            if (target != null) {
                getTracker().removePersistent(source, removed.getTypeId(), target);
            }
        }
        if (replacement == null) {
            return metadata;
        }
        replacement = replaceMetadata(store, source, replacement);
        installedTracker.getStoreRuntime().markNeedsSaving(store, source);
        return replacement;
    }

    /// Only reached for a record naming another installation.
    private boolean isTargetDeleted(Store<ECS_TYPE> store, LinkRecord record) {
        for (var type : types.getRegisteredTypes()) {
            if (type.getTargetRelationshipTypeRegistry().getTracker() != null
                && record.isNamed(type.getDescriptor().id(), LinkRecord.getCleanupDisposition(type.getDescriptor()))
                && type.getTargetRelationshipTypeRegistry().getPersistenceIdentity().getInstallationName().equals(record.getTargetInstallation())) {
                return isDeletedInNamedInstallation(type, store, record);
            }
        }
        return false;
    }

    /// A record that installation cannot decode names no identity to test.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean isDeletedInNamedInstallation(
        GenericRelationshipType<?, ?, ?> type,
        Store<ECS_TYPE> store,
        LinkRecord record
    ) {
        var targetTypes = ((GenericRelationshipType) type).getTargetRelationshipTypeRegistry();
        var targetTracker = targetTypes.getTracker();
        var targetCodec = targetTracker == null ? null : getIdentityCodecOf(targetTracker);
        var targetPersistence = targetTypes.getPersistence();
        if (targetCodec == null || targetPersistence == null) {
            return false;
        }
        var target = record.getTargetIdentity(targetCodec);
        if (target == null) {
            return false;
        }
        Store targetStore;
        try {
            targetStore = targetTypes.getPersistenceIdentity().storeBeside(store);
        } catch (RuntimeException unplaceable) {
            // no peer Store in this world
            return false;
        }
        return targetStore != null && targetPersistence.isDeleted(targetStore, target);
    }

    /// Called by another installation, for a target whose identity this installation's codec read.
    boolean isDeleted(Store<ECS_TYPE> store, Object identity) {
        return installedTracker.getPersistenceIdentity().isDeleted(store, identity);
    }

    void readHolderLinks(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type,
        Holder<ECS_TYPE> holder,
        Store<ECS_TYPE> context,
        BiConsumer<Ref<ECS_TYPE>, Object> link
    ) {
        if (type.getRelationshipTypeRegistry() != types) throw new IllegalArgumentException("Relationship type belongs to another registry");
        type.validate(context);
        type.getSourceType().validate();
        context.assertThread();
        Objects.requireNonNull(link, "link");
        var metadata = Objects.requireNonNull(holder, "holder").getComponent(componentType);
        if (metadata == null) return;
        var tracker = getTracker();
        var source = tracker.getHolderSource(holder);
        var records = metadata.readLinks(type);
        boundMetadata.put(metadata, Boolean.TRUE);
        for (RelationshipMetadata.DecodedLink record : records) {
            if (tracker.hasHolderLink(type, holder, source, record.target(), context)) continue;
            var linkedEntity = tracker.getRef(record.target(), context);
            link.accept(linkedEntity != null && linkedEntity.getStore() == context ? linkedEntity : null, record.data());
        }
    }

    void onTypeUnregistering(GenericRelationshipType<ECS_TYPE, ?, ?> type) {
        var frozen = new IdentityHashMap<RelationshipMetadata<ECS_TYPE>, RelationshipMetadata<ECS_TYPE>>();
        java.util.List<RelationshipMetadata<ECS_TYPE>> metadata;
        synchronized (boundMetadata) {
            metadata = java.util.List.copyOf(boundMetadata.keySet());
        }
        for (var entry : metadata) {
            if (entry.hasBindings(type)) frozen.put(entry, entry.freeze(type));
        }
        frozen.forEach(RelationshipMetadata::replaceWith);
    }

    void validateMutation(GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type, Ref<ECS_TYPE> source, Ref<ECS_TYPE> target) {
        if (!type.getDescriptor().isPersistent()) {
            return;
        }
        validateMutation(type, source, requireIdentity(target));
    }

    /// Takes the identity because the target is already removed.
    void validateMutation(GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type, Ref<ECS_TYPE> source, @Nullable Object target) {
        if (!type.getDescriptor().isPersistent()) {
            return;
        }
        if (type.getDescriptor().linkDataClass() != Void.class && type.getCodec() == null) {
            throw new IllegalStateException("Persistent relationship payloads require a registered codec");
        }
        requireIdentity(source);
        requireIdentity(target);
    }

    void validateMutationWithIdentities(GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type,
        @Nullable Object source, @Nullable Object target) {
        if (!type.getDescriptor().isPersistent()) {
            return;
        }
        if (type.getDescriptor().linkDataClass() != Void.class && type.getCodec() == null) {
            throw new IllegalStateException("Persistent relationship payloads require a registered codec");
        }
        requireIdentity(source);
        requireIdentity(target);
    }

    <LINK_DATA> void synchronize(
        GenericRelationshipType<ECS_TYPE, ?, LINK_DATA> type,
        Ref<ECS_TYPE> source,
        Ref<ECS_TYPE> target,
        boolean present,
        @Nullable LINK_DATA data
    ) {
        if (!type.getDescriptor().isPersistent() || !source.isValid()) {
            return;
        }
        synchronize(type, source, requireIdentity(target), present, data);
    }

    <LINK_DATA> void synchronize(
        GenericRelationshipType<ECS_TYPE, ?, LINK_DATA> type,
        Ref<ECS_TYPE> source,
        @Nullable Object target,
        boolean present,
        @Nullable LINK_DATA data
    ) {
        if (!type.getDescriptor().isPersistent() || !source.isValid()) {
            return;
        }
        var store = source.getStore();
        var metadata = store.getComponent(source, componentType);
        var targetId = requireIdentity(target);
        if (metadata == null) {
            if (!present) {
                return;
            }
            metadata = new RelationshipMetadata<>();
            add(metadata, type, targetId);
            bind(metadata, type, source, targetId, data);
            store.addComponent(source, componentType, metadata);
            installedTracker.getStoreRuntime().markNeedsSaving(store, source);
            return;
        }
        var replacement = metadata.newMutableCopy();
        boolean changed = present
            ? add(replacement, type, targetId)
            : removeActive(replacement, type, targetId);
        if (present && type.getCodec() != null) {
            bind(replacement, type, source, targetId, data);
            changed = true;
        }
        if (!changed) {
            return;
        }
        replaceMetadata(store, source, replacement);
        installedTracker.getStoreRuntime().markNeedsSaving(store, source);
    }

    private <LINK_DATA> void bind(
        RelationshipMetadata<ECS_TYPE> metadata,
        GenericRelationshipType<ECS_TYPE, ?, LINK_DATA> type,
        Ref<ECS_TYPE> source,
        Object target,
        @Nullable LINK_DATA data
    ) {
        if (type.getCodec() != null) metadata.checkAccess(source.getStore()::assertThread);
        metadata.bind(type, target, data);
        boundMetadata.put(metadata, Boolean.TRUE);
    }

    /// The link data saved on a holder, for a source that is parked outside the Store.
    @Nullable
    public <LINK_DATA> LINK_DATA getLinkData(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
        Holder<ECS_TYPE> source,
        Object target
    ) {
        if (type.getRelationshipTypeRegistry() != types) throw new IllegalArgumentException("Relationship type belongs to another registry");
        type.getSourceType().validate();
        var metadata = source.getComponent(componentType);
        if (metadata == null) return null;
        boundMetadata.put(metadata, Boolean.TRUE);
        var link = getDecodedLink(metadata, type, target);
        if (link == null) return null;
        return type.getDescriptor().linkDataClass().cast(link.data());
    }

    void removeFromHolder(GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type, Holder<ECS_TYPE> holder, Object targetId) {
        var metadata = holder.getComponent(componentType);
        if (metadata == null) {
            return;
        }
        var replacement = metadata.newMutableCopy();
        if (!removeMatchingRecords(replacement, type.getDescriptor().id(), targetId, LinkRecord.getCleanupDisposition(type))) {
            return;
        }
        replaceMetadata(holder, replacement);
        installedTracker.getStoreRuntime().markNeedsSaving(holder);
    }

    void remove(GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type, Ref<ECS_TYPE> source, Object targetId) {
        if (!source.isValid()) {
            return;
        }
        var store = source.getStore();
        var metadata = store.getComponent(source, componentType);
        if (metadata == null) {
            return;
        }
        var replacement = metadata.newMutableCopy();
        if (!removeMatchingRecords(replacement, type.getDescriptor().id(), targetId, LinkRecord.getCleanupDisposition(type))) {
            return;
        }
        replaceMetadata(store, source, replacement);
        installedTracker.getStoreRuntime().markNeedsSaving(store, source);
    }

    @Nullable
    private RelationshipMetadata<ECS_TYPE> replaceMetadata(
        Store<ECS_TYPE> store,
        Ref<ECS_TYPE> source,
        RelationshipMetadata<ECS_TYPE> replacement
    ) {
        if (replacement.isEmpty()) {
            store.removeComponent(source, componentType);
            return null;
        }
        boundMetadata.put(replacement, Boolean.TRUE);
        store.replaceComponent(source, componentType, replacement);
        return replacement;
    }

    private void replaceMetadata(Holder<ECS_TYPE> holder, RelationshipMetadata<ECS_TYPE> replacement) {
        if (replacement.isEmpty()) {
            holder.removeComponent(componentType);
        } else {
            boundMetadata.put(replacement, Boolean.TRUE);
            holder.putComponent(componentType, replacement);
        }
    }

    private boolean add(RelationshipMetadata<ECS_TYPE> metadata, GenericRelationshipType<ECS_TYPE, ?, ?> type, Object target) {
        if (getDecodedLink(metadata, type, target) != null) {
            return false;
        }
        var targetTracker = getTargetTracker(type);
        // every record names its target installation, including a link inside one Store
        metadata.addRecord(new LinkRecord(
            type.getDescriptor().id(), getIdentityCodecOf(targetTracker), target, LinkRecord.getCleanupDisposition(type),
            targetTracker.getPersistenceIdentity().getInstallationName()));
        return true;
    }

    @Nonnull
    private RelationshipTracker<?, ?> getTargetTracker(GenericRelationshipType<ECS_TYPE, ?, ?> type) {
        var targetTracker = type.getTargetRelationshipTypeRegistry().getTracker();
        if (targetTracker == null) {
            throw new IllegalStateException("Saved type relationship type '" + type.getDescriptor().id()
                + "' requires an identity codec on the installation of its targets");
        }
        return targetTracker;
    }

    private boolean removeActive(
        RelationshipMetadata<ECS_TYPE> metadata,
        GenericRelationshipType<ECS_TYPE, ?, ?> type,
        Object target
    ) {
        var link = getDecodedLink(metadata, type, target);
        return link != null && metadata.removeRecord(link.record());
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean removeMatchingRecords(
        RelationshipMetadata<?> metadata,
        @Nullable String typeId,
        Object target,
        String disposition
    ) {
        var records = java.util.List.copyOf(metadata.getRecords());
        boolean removed = false;
        for (var record : records) {
            if (record.isNamed(typeId, disposition)
                && types.getPersistenceIdentity().getInstallationName().equals(record.getTargetInstallation())
                && target.equals(record.getTargetIdentity(getTracker().getIdentityCodec()))) {
                metadata.removeRecord(record);
                removed = true;
            }
        }
        return removed;
    }

    @Nullable
    private static <ECS_TYPE> RelationshipMetadata.DecodedLink getDecodedLink(
        RelationshipMetadata<ECS_TYPE> metadata,
        GenericRelationshipType<ECS_TYPE, ?, ?> type,
        Object target
    ) {
        for (var link : metadata.readLinks(type)) {
            if (target.equals(link.target())) return link;
        }
        return null;
    }

    @Nonnull
    private Object requireIdentity(Ref<ECS_TYPE> ref) {
        return requireIdentity(getIdentity(ref));
    }

    private Object requireIdentity(@Nullable Object id) {
        if (id == null) {
            throw new IllegalStateException("Persistent relationships require stable identities");
        }
        return id;
    }

    /// Read it while the linked entity is attached. A caller that needs it after removal must keep it.
    @Nullable
    Object getIdentity(Ref<ECS_TYPE> ref) {
        return getTracker().getIdentity(ref);
    }

    @Nonnull
    private RelationshipTracker<ECS_TYPE, Object> getTracker() {
        var tracker = types.getTracker();
        if (tracker == null) {
            throw new IllegalStateException("Persistent relationships require an installed relationship tracker");
        }
        if (tracker != installedTracker) {
            throw new IllegalStateException("Persistent relationships require the tracker they were installed with");
        }
        return installedTracker;
    }
}
