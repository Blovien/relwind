/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.IComponentRegistry;
import com.hypixel.hytale.component.system.RefSystem;

import javax.annotation.Nonnull;

import java.util.Objects;

/// The Relwind services installed on one Store kind: its relationship types, its tracker and,
/// optionally, its persistence.
public final class StoreInstallation<ECS_TYPE, ID> {
    private final RelationshipTypeRegistry<ECS_TYPE> types;
    private final RelationshipTracker<ECS_TYPE, ID> tracker;

    public StoreInstallation(
        ComponentRegistry<ECS_TYPE> componentRegistry,
        PersistenceIdentity<ECS_TYPE, ID> identity,
        StoreRuntime<ECS_TYPE> runtime
    ) {
        this(componentRegistry, componentRegistry, identity, runtime, true);
    }

    /// A plugin passes its ComponentRegistryProxy as `registrar`.
    public StoreInstallation(
        ComponentRegistry<ECS_TYPE> componentRegistry,
        IComponentRegistry<ECS_TYPE> registrar,
        PersistenceIdentity<ECS_TYPE, ID> identity,
        StoreRuntime<ECS_TYPE> runtime
    ) {
        this(componentRegistry, registrar, identity, runtime, true);
    }

    /// For a Store kind that will never save links. Its registry then rejects every named type.
    @Nonnull
    public static <ECS_TYPE, ID> StoreInstallation<ECS_TYPE, ID> withoutPersistence(
        ComponentRegistry<ECS_TYPE> componentRegistry,
        PersistenceIdentity<ECS_TYPE, ID> identity,
        StoreRuntime<ECS_TYPE> runtime
    ) {
        return new StoreInstallation<>(componentRegistry, componentRegistry, identity, runtime, false);
    }

    private StoreInstallation(
        ComponentRegistry<ECS_TYPE> componentRegistry,
        IComponentRegistry<ECS_TYPE> registrar,
        PersistenceIdentity<ECS_TYPE, ID> identity,
        StoreRuntime<ECS_TYPE> runtime,
        boolean persistable
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(runtime, "runtime");
        types = new RelationshipTypeRegistry<>(componentRegistry, registrar);
        if (!persistable) types.declareWithoutPersistence();
        try {
            tracker = types.installTracker(identity, runtime);
        } catch (RuntimeException | Error failure) {
            // nothing else can release the registry when the constructor throws
            try {
                types.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /// Call once, after construction and before the first saved link.
    @Nonnull
    public RelationshipPersistence<ECS_TYPE> installPersistence() {
        return types.installPersistence(tracker);
    }

    @Nonnull
    public RelationshipTypeRegistry<ECS_TYPE> getRelationshipTypeRegistry() {
        return types;
    }

    @Nonnull
    public RelationshipTracker<ECS_TYPE, ID> getTracker() {
        return tracker;
    }

    /// Cast it to the type your StoreRuntime built.
    /// @throws IllegalStateException if this installation is closed
    @Nonnull
    public RefSystem<ECS_TYPE> getTransitionSystem() {
        var system = types.getTransitionSystem();
        if (system == null) {
            throw new IllegalStateException("Relationship installation is closed");
        }
        return system;
    }

    /// A second close does nothing. Closing the ComponentRegistry directly leaves this handle stale.
    public void close() {
        types.close();
    }
}
