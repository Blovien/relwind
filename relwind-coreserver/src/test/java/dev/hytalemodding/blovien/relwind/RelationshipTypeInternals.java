/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.ComponentType;

import javax.annotation.Nonnull;

public final class RelationshipTypeInternals {
    private RelationshipTypeInternals() {
    }

    @Nonnull
    public static <ECS_TYPE, LINK_DATA> ComponentType<ECS_TYPE, OutgoingLink<ECS_TYPE, ECS_TYPE>> sourceType(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type
    ) {
        return type.getSourceType();
    }
}
