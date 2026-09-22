/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Objects;

/// What a registerRelationship call settled about a type, read by the tracker, the persistence
/// and the command paths. The target registry is null for a same-Store type.
record RelationshipDescriptor<TARGET, LINK_DATA>(
    @Nullable String id,
    @Nullable RelationshipTypeRegistry<TARGET> targetTypes,
    Class<LINK_DATA> linkDataClass,
    @Nullable ComponentType<?, ?> rawDataComponentType,
    @Nullable RelationshipDataObserver<?, ?> rawDataObserver,
    @Nullable Codec<LINK_DATA> codec,
    RelationshipRules rules
) {
    RelationshipDescriptor {
        Objects.requireNonNull(linkDataClass, "linkDataClass");
        Objects.requireNonNull(rules, "rules");
    }

    /// True when the type has an id, because only a named type is saved.
    boolean isPersistent() {
        return id != null;
    }

    /// Null when the link data lives in the outgoing storage instead of a component.
    @Nullable @SuppressWarnings("unchecked")
    <ECS_TYPE> ComponentType<ECS_TYPE, Component<ECS_TYPE>> getDataComponentType() {
        return (ComponentType<ECS_TYPE, Component<ECS_TYPE>>) rawDataComponentType;
    }

    @Nullable @SuppressWarnings("unchecked")
    <ECS_TYPE> RelationshipDataObserver<ECS_TYPE, Component<ECS_TYPE>> getDataObserver() {
        return (RelationshipDataObserver<ECS_TYPE, Component<ECS_TYPE>>) rawDataObserver;
    }

    @Nonnull
    RelationshipRules.Cardinality getCardinality() {
        return rules.getCardinality();
    }

    @Nonnull
    RelationshipRules.Survival getTransfer() {
        return rules.getTransfer();
    }

    @Nonnull
    RelationshipRules.Survival getTemporaryDeactivation() {
        return rules.getTemporaryDeactivation();
    }

    @Nonnull
    RelationshipRules.TargetDeletion getTargetDeletion() {
        return rules.getTargetDeletion();
    }

    @Nonnull
    RelationshipRules.SourceRetention getSourceRetention() {
        return rules.getSourceRetention();
    }
}
