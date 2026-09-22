/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.system.ISystem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Supplier;

/// Keeps the relationship system identity and its lazily initialized query together for registration.
final class RelationshipSystemLifecycle<ECS_TYPE, QUERY extends RelationshipQuery.Definition<ECS_TYPE, ?>> {
    private final ISystem<ECS_TYPE> system;
    private final Supplier<QUERY> queryFactory;
    private final RelationshipTypeRegistry.RegisteredRelationshipSystem<ECS_TYPE> registration;
    @Nullable
    private QUERY query;

    RelationshipSystemLifecycle(ISystem<ECS_TYPE> system, Supplier<QUERY> queryFactory) {
        this.system = system;
        this.registration = new RelationshipTypeRegistry.RegisteredRelationshipSystem<>(system);
        this.queryFactory = queryFactory;
    }

    @Nonnull
    QUERY query() {
        if (query == null) {
            query = Objects.requireNonNull(queryFactory.get(), "query");
        }
        return query;
    }

    void register(Runnable callback) {
        registry().onRelationshipSystemRegistered(registration);
        RelationshipTypeRegistry.invokeRelationshipSystemCallback(callback);
    }

    void unregister(Runnable callback) {
        registry().onRelationshipSystemUnregistered(system);
        // Preserve native removal before reporting callback failures to a type-unregistration caller.
        registration.invokeUnregistrationCallback(
            () -> RelationshipTypeRegistry.invokeRelationshipSystemCallback(callback)
        );
    }

    private RelationshipTypeRegistry<ECS_TYPE> registry() {
        return query().getRelationshipType().getRelationshipTypeRegistry();
    }
}
