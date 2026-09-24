/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Objects;

/// What a registerRelationship call settled about a type, read by the tracker, the persistence
/// and the command paths. The target registry is null for a same-Store type.
record RelationshipDescriptor<TARGET, LINK_DATA>(
    @Nullable String id,
    @Nullable RelationshipTypeRegistry<TARGET> targetTypes,
    Class<LINK_DATA> linkDataClass,
    @Nullable Codec<LINK_DATA> codec,
    RelationshipTraits traits
) {
    RelationshipDescriptor {
        Objects.requireNonNull(linkDataClass, "linkDataClass");
        Objects.requireNonNull(traits, "traits");
    }

    /// True when the type has an id, because only a named type is saved.
    boolean isPersistent() {
        return id != null;
    }

    boolean isExclusive() {
        return traits.isExclusive();
    }

    boolean isSymmetric() {
        return traits.isSymmetric();
    }

    @Nonnull
    RelationshipTraits.Survival getTransfer() {
        return traits.getTransfer();
    }

    @Nonnull
    RelationshipTraits.Survival getTemporaryDeactivation() {
        return traits.getTemporaryDeactivation();
    }

    @Nonnull
    RelationshipTraits.OnDeleteTarget getOnDeleteTarget() {
        return traits.getOnDeleteTarget();
    }

    @Nonnull
    RelationshipTraits.SourceRetention getSourceRetention() {
        return traits.getSourceRetention();
    }
}
