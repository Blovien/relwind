/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;


import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.system.ISystem;
import com.hypixel.hytale.component.system.StoreSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A stopped Store and a closed type registry reject commands and reads. A type registered later
/// still works, and a tracker can be installed again once the first one closes.
class RelationshipAccessorTrackerTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void typesRegisteredAfterAnEarlierCommandAreUsable() {
        try (var fixture = new StoreFixture()) {
            var early = registerType(fixture.registry(), "early-type");
            var source = fixture.addEntity(new StoreFixture.Position(1, 2), null);
            var target = fixture.addEntity(new StoreFixture.Position(3, 4), null);
            relationships.addTarget(fixture.store(), source, early, target);

            var late = registerType(fixture.registry(), "late-type");

            relationships.addTarget(fixture.store(), source, late, target);
            assertSame(target, relationships.getFirstTarget(source, late));
            relationships.removeTarget(fixture.store(), source, late, target);
            relationships.tryRemoveTarget(fixture.store(), source, late, target);
            assertEquals(0, relationships.getTargetCount(source, late));
            assertSame(target, relationships.getFirstTarget(source, early));
        }
    }

    @Test
    void aStoppedStoreRejectsCommandsAndReads() {
        var registry = new ComponentRegistry<Object>();
        try {
            var follows = registerType(registry, "stopped-type");
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var source = Objects.requireNonNull(store.addEntity(registry.newHolder(), AddReason.SPAWN));
            var target = Objects.requireNonNull(store.addEntity(registry.newHolder(), AddReason.SPAWN));

            store.shutdown();

            assertStopped(() -> relationships.getTargetCount(source, follows));
            assertStopped(() -> relationships.getIncomingCount(target, follows));
            assertStopped(() -> relationships.addTarget(store, source, follows, target));
        } finally {
            registry.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingTheTypeRegistryRejectsReadsOnItsTypes(boolean trackerInstalled) {
        var registry = new ComponentRegistry<Object>();
        try {
            var types = new RelationshipTypeRegistry<>(registry);
            installTrackerIfRequested(trackerInstalled, types);
            var follows = types.registerRelationship(
                "relwind:test/accessor/closed",
                RelationshipTraits.defaults().exclusive());
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var source = Objects.requireNonNull(store.addEntity(registry.newHolder(), AddReason.SPAWN));

            types.close();

            assertThrows(IllegalStateException.class, () -> relationships.getTargetCount(source, follows));
        } finally {
            registry.shutdown();
        }
    }

    private static void installTrackerIfRequested(boolean trackerInstalled, RelationshipTypeRegistry<Object> types) {
        if (trackerInstalled) {
            types.installTracker(
                TestPersistenceIdentity.of(ignored -> 1, Codec.INTEGER),
                TestStoreRuntime.inline());
        }
    }

    @Test
    void aReplacementTrackerInstallsOnceTheFirstOneCloses() {
        var registry = new ComponentRegistry<Object>();
        try {
            var firstTypes = new RelationshipTypeRegistry<>(registry);
            var firstTracker = firstTypes.installTracker(
                TestPersistenceIdentity.of(ignored -> 1, Codec.INTEGER),
                TestStoreRuntime.inline());
            registry.addStore(new Object(), EmptyResourceStorage.get());
            assertSame(firstTracker, firstTypes.getTracker());

            firstTracker.close();

            assertNull(firstTypes.getTracker());
            var secondTypes = new RelationshipTypeRegistry<>(registry);
            var replacement = secondTypes.installTracker(
                TestPersistenceIdentity.of(ignored -> 2, Codec.INTEGER),
                TestStoreRuntime.inline());
            try {
                assertNotSame(firstTracker, replacement);
                assertSame(replacement, secondTypes.getTracker());
            } finally {
                replacement.close();
            }
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void shutdownCallbackOffStoreThreadObservesResourcesThatRemainRegistered() throws InterruptedException {
        var registry = new ComponentRegistry<Object>();
        try {
            var storeReference = new AtomicReference<Store<Object>>();
            var failure = new AtomicReference<Throwable>();
            var resourceTypesReference = new AtomicReference<List<ResourceType<Object, ?>>>();
            var originalThread = new Thread(() -> {
                try {
                    var store = registry.addStore(new Object(), EmptyResourceStorage.get());
                    storeReference.set(store);
                    relationships.getTargetCount(Objects.requireNonNull(store.addEntity(registry.newHolder(), AddReason.SPAWN)), registerType(registry, "shutdown-callback"));
                    resourceTypesReference.set(registeredResourceTypes(registry));
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            originalThread.start();
            originalThread.join();
            rethrowStoreCreationFailure(failure);

            var store = storeReference.get();
            var resourceTypes = Objects.requireNonNull(resourceTypesReference.get());
            assertFalse(resourceTypes.isEmpty(), "The registry must contain resources");

            var observer = new StoreSystem<Object>() {
                private boolean callbackRan;

                @Override
                public Set<Dependency<Object>> getDependencies() {
                    // this observer is removed after the access system, because shutdown reverses the dependency order
                    return Set.of(new SystemDependency<>(Order.BEFORE, accessSystemClass()));
                }

                @Override
                public void onSystemAddedToStore(Store<Object> addedStore) {
                }

                @Override
                public void onSystemRemovedFromStore(Store<Object> removedStore) {
                    assertSame(store, removedStore);
                    assertNotSame(originalThread, Thread.currentThread());
                    assertFalse(removedStore.isShutdown(), "Observation must precede shutdown completion");
                    assertResourcesStillRegistered(registry, resourceTypes, removedStore);
                    callbackRan = true;
                }
            };
            registry.registerSystem(observer);

            store.shutdown();

            assertTrue(observer.callbackRan, "The removal callback must run");
        } finally {
            registry.shutdown();
        }
    }

    private static GenericRelationshipType<Object, Object, Void> registerType(ComponentRegistry<Object> registry, String name) {
        return new RelationshipTypeRegistry<>(registry).registerRelationship(
            "relwind:test/accessor/" + name,
            RelationshipTraits.defaults().exclusive());
    }

    private static void assertStopped(Executable call) {
        var failure = assertThrows(IllegalStateException.class, call);
        assertTrue(failure.getMessage().contains("stopped Store"), failure.getMessage());
    }

    private static void rethrowStoreCreationFailure(AtomicReference<Throwable> failure) {
        if (failure.get() != null) {
            throw new AssertionError("Store creation failed", failure.get());
        }
    }

    private static List<ResourceType<Object, ?>> registeredResourceTypes(ComponentRegistry<Object> registry) {
        var resourceTypes = new ArrayList<ResourceType<Object, ?>>();
        var data = registry.getData();
        for (int index = 0; index < data.getResourceSize(); index++) {
            var resourceType = data.getResourceType(index);
            if (resourceType != null) {
                resourceTypes.add(resourceType);
            }
        }
        return resourceTypes;
    }

    private static void assertResourcesStillRegistered(
        ComponentRegistry<Object> registry,
        List<ResourceType<Object, ?>> resourceTypes,
        Store<Object> store
    ) {
        for (var resourceType : resourceTypes) {
            resourceType.validate();
            assertNotNull(store.getResource(resourceType));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Class<? extends ISystem<Object>> accessSystemClass() {
        return (Class) RelationshipAccessSystem.class;
    }
}
