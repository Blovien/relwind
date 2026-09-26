/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Objects;

/// The handle a registration returns. SOURCE and TARGET are the ECS types of its linked entities, and
/// LINK_DATA is the value each link carries. Get one from a {@link RelationshipTypeRegistry}
/// registerRelationship overload. Read and change links through {@link Relationships}.
public class GenericRelationshipType<SOURCE, TARGET, LINK_DATA> {
    @Nullable
    private final Codec<LINK_DATA> codec;
    private final RelationshipTypeRegistry<SOURCE> types;
    private final ComponentRegistry<SOURCE> registry;
    private final RelationshipTypeRegistry<TARGET> targetTypes;
    private final RelationshipDescriptor<TARGET, LINK_DATA> descriptor;
    private final ComponentType<SOURCE, OutgoingLink<SOURCE, TARGET>> sourceType;
    private final ComponentType<TARGET, IncomingLinks<SOURCE, TARGET>> incomingType;

    @SuppressWarnings("unchecked")
    GenericRelationshipType(
        RelationshipTypeRegistry<SOURCE> types,
        ComponentRegistry<SOURCE> registry,
        RelationshipDescriptor<TARGET, LINK_DATA> descriptor,
        ComponentType<SOURCE, OutgoingLink<SOURCE, TARGET>> sourceType,
        ComponentType<TARGET, IncomingLinks<SOURCE, TARGET>> incomingType,
        @Nullable Codec<LINK_DATA> codec
    ) {
        this.types = Objects.requireNonNull(types, "types");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.targetTypes = descriptor.targetTypes() == null
            ? (RelationshipTypeRegistry<TARGET>) types : descriptor.targetTypes();
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        this.incomingType = Objects.requireNonNull(incomingType, "incomingType");
        this.codec = codec;
    }

    @Nonnull
    RelationshipTypeRegistry<SOURCE> getRelationshipTypeRegistry() {
        return types;
    }

    ComponentRegistry<?> getExpectedTargetRegistry() {
        return targetTypes.getComponentRegistry();
    }

    @Nonnull
    RelationshipTypeRegistry<TARGET> getTargetRelationshipTypeRegistry() {
        return targetTypes;
    }

    @Nonnull
    RelationshipDescriptor<TARGET, LINK_DATA> getDescriptor() {
        return descriptor;
    }

    @Nonnull
    ComponentType<SOURCE, OutgoingLink<SOURCE, TARGET>> getSourceType() {
        return sourceType;
    }

    @Nonnull
    ComponentType<TARGET, IncomingLinks<SOURCE, TARGET>> getIncomingType() {
        return incomingType;
    }

    @Nullable
    Codec<LINK_DATA> getCodec() {
        return codec;
    }

    void validate(Store<SOURCE> store) {
        sourceType.validate();
        incomingType.validate();
        if (store.getRegistry() != registry) {
            throw new IllegalArgumentException(
                "Relationship type '" + descriptor.id() + "' is for a different registry"
            );
        }
    }

    void validateData(@Nullable LINK_DATA data) {
        if (data != null && !descriptor.linkDataClass().isInstance(data)) {
            throw new IllegalArgumentException(
                "Link data for relationship type '" + descriptor.id() + "' must be " + descriptor.linkDataClass().getName()
            );
        }
    }
}
