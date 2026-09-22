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

import javax.annotation.Nullable;

/// A relationship type whose sources and targets are both in the registry it was registered with.
public final class RelationshipType<ECS_TYPE, LINK_DATA>
    extends GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> {

    RelationshipType(
        RelationshipTypeRegistry<ECS_TYPE> types,
        ComponentRegistry<ECS_TYPE> registry,
        RelationshipDescriptor<ECS_TYPE, LINK_DATA> descriptor,
        ComponentType<ECS_TYPE, OutgoingLink<ECS_TYPE, ECS_TYPE>> sourceType,
        ComponentType<ECS_TYPE, IncomingLinks<ECS_TYPE, ECS_TYPE>> incomingType,
        @Nullable Codec<LINK_DATA> codec
    ) {
        super(types, registry, descriptor, sourceType, incomingType, codec);
    }
}
