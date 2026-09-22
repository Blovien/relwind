/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.EcsEvent;
import com.hypixel.hytale.component.system.WorldEventSystem;

import javax.annotation.Nullable;
import java.util.Objects;

/// Observes a relationship change after the outgoing, incoming and tracker bookkeeping is done.
/// Register a subclass through the ComponentRegistry. Hytale keys a system by its class, so
/// different generic arguments do not make a second system.
public abstract class RelationshipChangeSystem<ECS_TYPE, LINK_DATA>
    extends WorldEventSystem<ECS_TYPE, RelationshipChangeSystem.ChangeEvent<ECS_TYPE, LINK_DATA>> {
    private final RelationshipType<ECS_TYPE, LINK_DATA> relationshipType;
    private final RelationshipTypeRegistry.RegisteredRelationshipSystem<ECS_TYPE> registration =
        new RelationshipTypeRegistry.RegisteredRelationshipSystem<>(this);

    @SuppressWarnings("unchecked")
    protected RelationshipChangeSystem(RelationshipType<ECS_TYPE, LINK_DATA> relationshipType) {
        super((Class<ChangeEvent<ECS_TYPE, LINK_DATA>>) (Class<?>) ChangeEvent.class);
        this.relationshipType = Objects.requireNonNull(relationshipType, "relationshipType");
    }

    public final RelationshipType<ECS_TYPE, LINK_DATA> getRelationshipType() {
        return relationshipType;
    }

    @Override
    public final void onSystemRegistered() {
        relationshipType.getSourceType().validate();
        relationshipType.getRelationshipTypeRegistry().onRelationshipSystemRegistered(registration);
        RelationshipTypeRegistry.invokeRelationshipSystemCallback(this::onRelationshipSystemRegistered);
    }

    protected void onRelationshipSystemRegistered() {
    }

    @Override
    public final void onSystemUnregistered() {
        relationshipType.getRelationshipTypeRegistry().onRelationshipSystemUnregistered(this);
        registration.invokeUnregistrationCallback(
            () -> RelationshipTypeRegistry.invokeRelationshipSystemCallback(this::onRelationshipSystemUnregistered)
        );
    }

    protected void onRelationshipSystemUnregistered() {
    }

    @Override
    public final void handle(Store<ECS_TYPE> store, CommandBuffer<ECS_TYPE> commandBuffer, ChangeEvent<ECS_TYPE, LINK_DATA> changeEvent) {
        if (changeEvent.type != relationshipType) return;
        switch (changeEvent.kind) {
            case ADDED -> onRelationshipAdded(changeEvent.source, changeEvent.target, changeEvent.data, store, commandBuffer);
            case SET -> onRelationshipSet(changeEvent.source, changeEvent.target, changeEvent.oldData, changeEvent.data, store, commandBuffer);
            // a retarget always carries the target it replaced
            case RETARGETED -> onRelationshipRetargeted(changeEvent.source,
                Objects.requireNonNull(changeEvent.oldTarget, "oldTarget"), changeEvent.target,
                changeEvent.data, store, commandBuffer);
            case REMOVED -> onRelationshipRemoved(changeEvent.source, changeEvent.target, changeEvent.data, store, commandBuffer);
        }
    }

    static <ECS_TYPE> void unregisterUnusedEventType(ComponentRegistry<ECS_TYPE> registry) {
        var lock = registry.getDataUpdateLock().readLock();
        lock.lock();
        try {
            var data = registry._internal_getData();
            for (int index = 0; index < data.getSystemSize(); index++) {
                if (data.getSystem(index) instanceof RelationshipChangeSystem<?, ?>) return;
            }
        } finally {
            lock.unlock();
        }
        var eventType = registry.getWorldEventTypeForClass(ChangeEvent.class);
        if (eventType != null) registry.unregisterWorldEventType(eventType);
    }

    static <ECS_TYPE, LINK_DATA> ChangeEvent<ECS_TYPE, LINK_DATA> newRemoval(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
        @Nullable Ref<ECS_TYPE> source,
        @Nullable Object sourceId,
        @Nullable Ref<ECS_TYPE> target,
        @Nullable Object targetId,
        @Nullable Object data
    ) {
        return new ChangeEvent<>(type, Kind.REMOVED, new LinkedEntity<>(source, sourceId), new LinkedEntity<>(target, targetId),
            null, null, type.getDescriptor().linkDataClass().cast(data));
    }

    static <ECS_TYPE, LINK_DATA> void dispatch(Store<ECS_TYPE> store, ChangeEvent<ECS_TYPE, LINK_DATA> changeEvent) {
        if (store.isShutdown() || store.getRegistry().isShutdown()) return;
        store.invoke(new ChangeEvent<>(changeEvent.type, changeEvent.kind, changeEvent.source.getAvailableIn(store),
            changeEvent.target.getAvailableIn(store), changeEvent.oldTarget, changeEvent.oldData, changeEvent.data));
    }

    /// Do not keep the buffer. An ordinary command issued during dispatch is rejected.
    protected void onRelationshipAdded(
        LinkedEntity<ECS_TYPE> source,
        LinkedEntity<ECS_TYPE> target,
        @Nullable LINK_DATA data,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    ) {
    }

    /// A set supplies the previous and the current data, even when they are the same value.
    protected void onRelationshipSet(
        LinkedEntity<ECS_TYPE> source,
        LinkedEntity<ECS_TYPE> target,
        @Nullable LINK_DATA oldData,
        @Nullable LINK_DATA data,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    ) {
    }

    protected void onRelationshipRetargeted(
        LinkedEntity<ECS_TYPE> source,
        LinkedEntity<ECS_TYPE> oldTarget,
        LinkedEntity<ECS_TYPE> target,
        @Nullable LINK_DATA data,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    ) {
    }

    protected void onRelationshipRemoved(
        LinkedEntity<ECS_TYPE> source,
        LinkedEntity<ECS_TYPE> target,
        @Nullable LINK_DATA data,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    ) {
    }

    /// Either field may be null, and a reference can go invalid later. Neither gives access through
    /// another Store.
    public record LinkedEntity<ECS_TYPE>(@Nullable Ref<ECS_TYPE> reference, @Nullable Object identity) {
        private LinkedEntity<ECS_TYPE> getAvailableIn(Store<ECS_TYPE> store) {
            return reference == null || (reference.isValid() && reference.getStore() == store)
                ? this : new LinkedEntity<>(null, identity);
        }
    }

    enum Kind { ADDED, SET, RETARGETED, REMOVED }

    public static final class ChangeEvent<ECS_TYPE, LINK_DATA> extends EcsEvent {
        final GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type;
        final Kind kind;
        final LinkedEntity<ECS_TYPE> source;
        final LinkedEntity<ECS_TYPE> target;
        @Nullable final LinkedEntity<ECS_TYPE> oldTarget;
        @Nullable final LINK_DATA oldData;
        @Nullable final LINK_DATA data;

        ChangeEvent(
            GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
            Kind kind,
            LinkedEntity<ECS_TYPE> source,
            LinkedEntity<ECS_TYPE> target,
            @Nullable LinkedEntity<ECS_TYPE> oldTarget,
            @Nullable LINK_DATA oldData,
            @Nullable LINK_DATA data
        ) {
            this.type = type;
            this.kind = kind;
            this.source = source;
            this.target = target;
            this.oldTarget = oldTarget;
            this.oldData = oldData;
            this.data = data;
        }
    }
}
