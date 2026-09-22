/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.IComponentRegistry;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.ISystem;
import com.hypixel.hytale.component.system.System;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/// The installation of one ComponentRegistry: its tracker, its registered relationship systems and its per
/// Store access resources. Every RelationshipTypeRegistry over that ComponentRegistry shares one,
/// and the last holder to close removes it.
final class RelationshipAccessSystem<ECS_TYPE> extends System<ECS_TYPE> {
    private final Map<Class<?>, RelationshipTypeRegistry.RegisteredRelationshipSystem<ECS_TYPE>> relationshipSystems =
        new ConcurrentHashMap<>();
    private final List<RelationshipTypeRegistry<ECS_TYPE>> holders = new CopyOnWriteArrayList<>();
    private final ResourceType<ECS_TYPE, RelationshipAccessResource<ECS_TYPE>> resourceType;
    @Nullable
    private RelationshipTracker<ECS_TYPE, ?> tracker;

    private RelationshipAccessSystem() {
        resourceType = registerResource(RelationshipAccessResource.class, RelationshipAccessResource::new);
    }

    @Nonnull
    Map<Class<?>, RelationshipTypeRegistry.RegisteredRelationshipSystem<ECS_TYPE>> getRelationshipSystems() {
        return relationshipSystems;
    }

    void hold(RelationshipTypeRegistry<ECS_TYPE> types) {
        holders.add(types);
    }

    /// Installs the access resource on that Store if it is not there yet.
    @Nonnull
    static <ECS_TYPE> RelationshipProcessingTracker forStoreCommand(Store<ECS_TYPE> store) {
        return forStore(store).getProcessingTracker();
    }

    @Nonnull
    static <ECS_TYPE> RelationshipAccessResource<ECS_TYPE> forStore(Store<ECS_TYPE> store) {
        return store.getResource(install(store.getRegistry()).resourceType);
    }

    @Nonnull
    static <ECS_TYPE> RelationshipAccessSystem<ECS_TYPE> install(ComponentRegistry<ECS_TYPE> registry) {
        // a caller holding only a Store has no plugin proxy to use, and a type registry over this
        // ComponentRegistry has already registered the installation through its own registrar
        return install(registry, registry);
    }

    /// Takes the plugin's registry proxy, for a caller that has one.
    @Nonnull
    static <ECS_TYPE> RelationshipAccessSystem<ECS_TYPE> install(
        ComponentRegistry<ECS_TYPE> registry,
        IComponentRegistry<ECS_TYPE> registrar
    ) {
        var system = getInstalled(registry);
        return system == null ? registerOrFind(registry, registrar, new RelationshipAccessSystem<>()) : system;
    }

    void closeTracker(ComponentRegistry<ECS_TYPE> registry, RelationshipTracker<ECS_TYPE, ?> tracker) {
        if (registry.isShutdown() || getCurrentTracker() != tracker) {
            return;
        }
        clearTracker(tracker);
        unregisterWhenUnheld(registry);
    }

    /// The last holder removes the installation and its resource registration.
    void release(ComponentRegistry<ECS_TYPE> registry, RelationshipTypeRegistry<ECS_TYPE> types) {
        holders.remove(types);
        if (registry.isShutdown() || getInstalled(registry) != this) {
            return;
        }
        unregisterWhenUnheld(registry);
    }

    private void unregisterWhenUnheld(ComponentRegistry<ECS_TYPE> registry) {
        if (!holders.isEmpty()) {
            return;
        }
        unregister(registry);
    }

    private static <ECS_TYPE> void unregister(ComponentRegistry<ECS_TYPE> registry) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<? extends ISystem<ECS_TYPE>> systemClass = (Class) RelationshipAccessSystem.class;
        registry.unregisterSystem(systemClass);
    }

    private static <ECS_TYPE> RelationshipAccessSystem<ECS_TYPE> registerOrFind(
        ComponentRegistry<ECS_TYPE> registry,
        IComponentRegistry<ECS_TYPE> registrar,
        RelationshipAccessSystem<ECS_TYPE> candidate
    ) {
        try {
            registrar.registerSystem(candidate);
            return candidate;
        } catch (IllegalArgumentException failure) {
            var system = getInstalled(registry);
            if (system == null || system == candidate) {
                throw failure;
            }
            return system;
        }
    }

    @Nullable
    private static <ECS_TYPE> RelationshipAccessSystem<ECS_TYPE> getInstalled(ComponentRegistry<ECS_TYPE> registry) {
        var lock = registry.getDataUpdateLock().readLock();
        lock.lock();
        try {
            var data = registry._internal_getData();
            for (int index = 0; index < data.getSystemSize(); index++) {
                var system = data.getSystem(index);
                if (system instanceof RelationshipAccessSystem<?>) {
                    return (RelationshipAccessSystem<ECS_TYPE>) system;
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /// A type registered before this tracker was never checked against its runtime.
    void validateBridgeSources(StoreRuntime<ECS_TYPE> runtime) {
        for (var types : holders) {
            for (var type : types.getRegisteredTypes()) {
                if (type.getTargetRelationshipTypeRegistry() != types) {
                    RelationshipTypeRegistry.validateCascadingSource(type.getDescriptor(), runtime);
                }
            }
        }
    }

    synchronized void installTracker(RelationshipTracker<ECS_TYPE, ?> tracker) {
        if (this.tracker != null) {
            throw new IllegalStateException("A relationship tracker is already installed for this registry");
        }
        this.tracker = tracker;
    }

    @Nullable
    synchronized RelationshipTracker<ECS_TYPE, ?> getCurrentTracker() {
        return tracker;
    }

    private synchronized void clearTracker(RelationshipTracker<ECS_TYPE, ?> tracker) {
        if (this.tracker != tracker) {
            return;
        }
        this.tracker = null;
    }

    @Override
    public synchronized void onSystemUnregistered() {
        tracker = null;
    }
}
