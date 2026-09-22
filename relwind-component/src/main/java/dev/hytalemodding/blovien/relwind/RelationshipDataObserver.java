/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import dev.hytalemodding.blovien.relwind.compat.AllEntitiesQuerySystem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Objects;

/// Turns a change of a single target type's data component into a relationship change, when the
/// change was not made by a relationship command. A plugin writes one subclass per data component
/// type and registers an instance with the relationship type.
public abstract class RelationshipDataObserver<ECS_TYPE, DATA extends Component<ECS_TYPE>> extends RefChangeSystem<ECS_TYPE, DATA> implements AllEntitiesQuerySystem<ECS_TYPE> {
    private static final ThreadLocal<int[]> COMMAND_WRITE_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    @Nullable
    private ComponentType<ECS_TYPE, DATA> dataType;
    @Nullable
    private GenericRelationshipType<ECS_TYPE, ECS_TYPE, DATA> relationshipType;

    @SuppressWarnings("unchecked")
    void observe(GenericRelationshipType<ECS_TYPE, ?, ?> type, ComponentType<ECS_TYPE, DATA> dataComponentType) {
        relationshipType = (GenericRelationshipType<ECS_TYPE, ECS_TYPE, DATA>) Objects.requireNonNull(type, "type");
        dataType = Objects.requireNonNull(dataComponentType, "dataComponentType");
    }

    /// A later registration may take this instance again.
    void release() {
        relationshipType = null;
        dataType = null;
    }

    /// Suppresses every observer on this thread while a command writes a data component itself.
    /// The depth holds the suppression when that write raises a callback that writes again.
    static void beginCommandWrite() {
        COMMAND_WRITE_DEPTH.get()[0]++;
    }

    static void endCommandWrite() {
        var depth = COMMAND_WRITE_DEPTH.get();
        if (--depth[0] == 0) COMMAND_WRITE_DEPTH.remove();
    }

    @Nonnull @Override
    public final Query<ECS_TYPE> getQuery() {
        return componentType();
    }

    @Nonnull @Override
    public final ComponentType<ECS_TYPE, DATA> componentType() {
        var type = dataType;
        if (type == null) {
            throw new IllegalStateException("Data observer " + getClass().getName()
                + " observes no component until it is registered with a relationship type");
        }
        return type;
    }

    @Override
    public final void onComponentAdded(
        Ref<ECS_TYPE> ref,
        DATA component,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    ) {
        announceReplacement(commandBuffer, store, ref, null, component);
    }

    @Override
    public final void onComponentSet(
        Ref<ECS_TYPE> ref,
        @Nullable DATA oldComponent,
        DATA newComponent,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    ) {
        announceReplacement(commandBuffer, store, ref, oldComponent, newComponent);
    }

    @Override
    public final void onComponentRemoved(
        Ref<ECS_TYPE> ref,
        DATA component,
        Store<ECS_TYPE> store,
        CommandBuffer<ECS_TYPE> commandBuffer
    ) {
        var type = getLinkedType(store, ref);
        if (type == null) {
            return;
        }
        throw new IllegalStateException("Link data of relationship type '"
            + type.getDescriptor().id() + "' must not be removed while the link exists");
    }

    private void announceReplacement(
        CommandBuffer<ECS_TYPE> commandBuffer,
        Store<ECS_TYPE> store,
        Ref<ECS_TYPE> source,
        @Nullable DATA oldData,
        DATA data
    ) {
        var type = getLinkedType(store, source);
        if (type == null) {
            return;
        }
        // Hytale is still changing the component here
        commandBuffer.run(ignored ->
            RelationshipLifecycle.onLinkDataReplaced(store, type, source, oldData, data));
    }

    /// Null when the change is a command's own write, when this observer has no type, or when the
    /// source has no link.
    @Nullable
    private GenericRelationshipType<ECS_TYPE, ECS_TYPE, DATA> getLinkedType(Store<ECS_TYPE> store, Ref<ECS_TYPE> source) {
        var type = relationshipType;
        if (COMMAND_WRITE_DEPTH.get()[0] != 0 || type == null) {
            return null;
        }
        var outgoing = store.getComponent(source, type.getSourceType());
        return outgoing != null && outgoing.size() != 0 ? type : null;
    }
}
