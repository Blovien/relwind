/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nullable;

import java.util.Objects;

/// The outgoing and incoming component storage shared by command execution, lifecycle behavior and
/// the public reads, together with the Store and accessor plumbing those operations need.
final class RelationshipStorage {
    private RelationshipStorage() {
    }

    /// Reads the source Store off the accessor for either command path.
    @SuppressWarnings("unchecked")
    static <SOURCE> Store<SOURCE> storeOfAccessor(ComponentAccessor<SOURCE> accessor) {
        Objects.requireNonNull(accessor, "accessor");
        if (accessor instanceof Store<?> store) {
            return (Store<SOURCE>) store;
        }
        if (accessor instanceof CommandBuffer<?> buffer) {
            return (Store<SOURCE>) buffer.getStore();
        }
        throw new IllegalArgumentException(
            "Accessor must be a Store or a CommandBuffer, not " + accessor.getClass().getName());
    }

    @NonNullDecl
    static <SOURCE> Store<SOURCE> getSourceStore(
        Ref<SOURCE> source,
        ComponentAccessor<SOURCE> accessor,
        Store<SOURCE> accessorStore
    ) {
        Store<SOURCE> sourceStore = source.getStore();
        if (accessorStore != sourceStore) {
            if (accessor instanceof CommandBuffer<?>) {
                throw new IllegalArgumentException("Command buffer belongs to a different store");
            }
            if (accessor instanceof Store<?>) {
                throw new IllegalArgumentException("Store belongs to a different store");
            }
            throw new IllegalArgumentException(
                "Accessor must be a Store or a CommandBuffer, not " + accessor.getClass().getName());
        }
        return sourceStore;
    }

    static <SOURCE, TARGET> void addIncoming(
        Store<TARGET> targetStore,
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<SOURCE> source,
        Ref<TARGET> target
    ) {
        var incoming = targetStore.getComponent(target, type.getIncomingType());
        if (incoming != null) {
            incoming.add(source);
            return;
        }
        var created = new IncomingLinks<SOURCE, TARGET>();
        created.add(source);
        targetStore.addComponent(target, type.getIncomingType(), created);
    }

    static <SOURCE, TARGET> void removeIncoming(
        Store<TARGET> targetStore,
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<SOURCE> source,
        Ref<TARGET> target
    ) {
        var incoming = targetStore.getComponent(target, type.getIncomingType());
        if (incoming == null || !incoming.remove(source)) {
            throw new IllegalStateException("Relationship incoming state is inconsistent for type '" + type.getDescriptor().id() + "'");
        }
    }

    /// Attaches the incoming side, stores the link data in its component when the type uses one, and
    /// adds the target to the source's outgoing storage.
    @SuppressWarnings({"rawtypes"})
    static <SOURCE, TARGET, LINK_DATA> void attachLink(
        Store<SOURCE> sourceStore,
        Store<TARGET> targetStore,
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        @Nullable LINK_DATA data
    ) {
        addIncoming(targetStore, type, source, target);
        ComponentType dataType = type.getDescriptor().getDataComponentType();
        Object slotData = data;
        if (dataType != null) {
            storeLinkData(sourceStore, dataType, source, data);
            slotData = null;
        }
        var current = sourceStore.getComponent(source, type.getSourceType());
        if (current == null) {
            var created = new OutgoingLink<SOURCE, TARGET>(target, slotData);
            sourceStore.addComponent(source, type.getSourceType(), created);
        } else {
            current.add(target, slotData);
            sourceStore.replaceComponent(source, type.getSourceType(), current);
        }
    }

    /// Removes the target from the source's outgoing storage, clearing retained empty storage or
    /// removing the component when the type does not retain it.
    static <SOURCE, TARGET> void removeOutgoingTarget(
        Store<SOURCE> sourceStore,
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        OutgoingLink<SOURCE, TARGET> outgoing
    ) {
        if (outgoing.size() != 1) {
            outgoing.remove(target);
            sourceStore.replaceComponent(source, type.getSourceType(), outgoing);
        } else if (type.getDescriptor().getSourceRetention() == RelationshipRules.SourceRetention.RETAIN) {
            outgoing.clear();
            sourceStore.replaceComponent(source, type.getSourceType(), outgoing);
        } else {
            sourceStore.removeComponent(source, type.getSourceType());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <SOURCE> void storeLinkData(
        Store<SOURCE> sourceStore,
        ComponentType dataType,
        Ref<SOURCE> source,
        @Nullable Object data
    ) {
        var attached = sourceStore.getComponent(source, dataType);
        var component = (Component<SOURCE>) data;
        RelationshipDataObserver.beginCommandWrite();
        try {
            if (component == null) {
                if (attached != null) {
                    sourceStore.removeComponent(source, dataType);
                }
            } else if (attached == null) {
                sourceStore.addComponent(source, dataType, component);
            } else {
                sourceStore.replaceComponent(source, dataType, component);
            }
        } finally {
            RelationshipDataObserver.endCommandWrite();
        }
    }

    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <SOURCE, TARGET, LINK_DATA> LINK_DATA getLinkDataOf(
        GenericRelationshipType<SOURCE, TARGET, LINK_DATA> type,
        Store<SOURCE> sourceStore,
        Ref<SOURCE> source,
        Ref<TARGET> target,
        @Nullable OutgoingLink<SOURCE, TARGET> outgoing
    ) {
        var dataClass = type.getDescriptor().linkDataClass();
        ComponentType dataType = type.getDescriptor().getDataComponentType();
        if (dataType == null) {
            return outgoing == null
                ? null : outgoing.getData(target, dataClass);
        }
        if (outgoing == null || !outgoing.contains(target)) {
            return null;
        }
        return (LINK_DATA) sourceStore.getComponent(source, dataType);
    }
}
