/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import java.util.ArrayList;

import com.hypixel.hytale.component.system.QuerySystem;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Construction builds the type registry and installs the tracker, `installPersistence` is a
/// separate step, and close releases both in reverse.
class StoreInstallationTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void anInstallationDeclaredWithoutPersistenceRejectsInstallingPersistence() {
        try (var fixture = new Fixture()) {
            var installation = StoreInstallation.withoutPersistence(
                fixture.registry, fixture.identity(), TestStoreRuntime.inline());

            assertThrows(IllegalStateException.class, installation::installPersistence);

            assertNull(installation.getRelationshipTypeRegistry().getPersistence());
            assertNotNull(installation.getTracker());
            installation.close();
        }
    }

    /// The documented order registers the types before persistence is installed.
    @Test
    void theConstructorKeepsAcceptingANamedTypeBeforeInstallPersistence() {
        try (var fixture = new Fixture()) {
            var installation = new StoreInstallation<>(
                fixture.registry, fixture.identity(), TestStoreRuntime.inline());

            var type = installation.getRelationshipTypeRegistry().registerRelationship(
                "test:registered-first",
                RelationshipTraits.defaults().exclusive());

            assertNotNull(type);

            assertNotNull(installation.installPersistence());
            installation.close();
        }
    }

    @Test
    void constructionInstallsTheTrackerThroughTheRegistry() {
        try (var fixture = new Fixture()) {
            var installation = new StoreInstallation<>(
                fixture.registry, fixture.identity(), TestStoreRuntime.inline());

            assertNotNull(installation.getRelationshipTypeRegistry());
            assertNotNull(installation.getTracker());
            assertSame(installation.getTracker(), installation.getRelationshipTypeRegistry().getTracker());
            assertNull(installation.getRelationshipTypeRegistry().getPersistence());
        }
    }

    @Test
    void installPersistenceIsASeparateStepReadBackThroughTheRegistry() {
        try (var fixture = new Fixture()) {
            var installation = new StoreInstallation<>(
                fixture.registry, fixture.identity(), TestStoreRuntime.inline());

            var persistence = installation.installPersistence();

            assertNotNull(persistence);
            assertSame(persistence, installation.getRelationshipTypeRegistry().getPersistence());
        }
    }

    @Test
    void transitionsIsTheRegisteredSystemTheRuntimeBuiltForTheInstalledTracker() {
        try (var fixture = new Fixture()) {
            var runtime = new ObservingRuntime();
            var installation = new StoreInstallation<>(fixture.registry, fixture.identity(), runtime);

            var transitions = installation.getTransitionSystem();

            assertSame(runtime.built, installation.getTransitionSystem());
            assertTrue(fixture.registry.hasSystem(transitions));
            installation.close();
        }
    }

    @Test
    void transitionsThrowsAfterClose() {
        try (var fixture = new Fixture()) {
            var installation = new StoreInstallation<>(
                fixture.registry, fixture.identity(), TestStoreRuntime.inline());
            installation.close();

            var closed = assertThrows(IllegalStateException.class, installation::getTransitionSystem);

            assertTrue(closed.getMessage().contains("closed"), closed.getMessage());
        }
    }

    @Test
    void savingMarksAreIssuedOnlyThroughInstalledPersistence() {
        try (var without = new Fixture(); var with = new Fixture()) {
            assertTrue(marksOfAFirstLink(without, false).isEmpty(),
                "an installation without persistence issues no saving mark");
            assertFalse(marksOfAFirstLink(with, true).isEmpty(),
                "the same link marks its source once persistence is installed");
        }
    }

    /// Returns the sources the runtime was asked to save.
    private static List<Ref<Object>> marksOfAFirstLink(Fixture fixture, boolean withPersistence) {
        var marks = new ArrayList<Ref<Object>>();
        var installation = new StoreInstallation<>(fixture.registry, fixture.identity(),
            TestStoreRuntime.<Object>marking((store, ref) -> marks.add(ref), holder -> { }));
        if (withPersistence) installation.installPersistence();
        var type = installation.getRelationshipTypeRegistry().registerRelationship("test:saving-marks", RelationshipTraits.defaults().exclusive());
        var id = UUID.randomUUID();
        var source = fixture.add(id);
        installation.getTracker().onEntityLoaded(id, source);

        relationships.addTarget(fixture.store, source, type, fixture.add(UUID.randomUUID()));

        installation.close();
        return marks;
    }

    @Test
    void persistenceStaysOptionalWhenCloseRunsWithoutIt() {
        try (var fixture = new Fixture()) {
            var installation = new StoreInstallation<>(
                fixture.registry, fixture.identity(), TestStoreRuntime.inline());

            installation.close();

            assertNull(installation.getRelationshipTypeRegistry().getTracker());
            assertNull(installation.getRelationshipTypeRegistry().getPersistence());
        }
    }

    @Test
    void closeReleasesInstalledServicesInReverseAndPreservesSavedRecords() {
        try (var fixture = new Fixture()) {
            var installation = new StoreInstallation<>(
                fixture.registry, fixture.identity(), TestStoreRuntime.inline());
            var persistence = installation.installPersistence();
            var type = installation.getRelationshipTypeRegistry().registerRelationship(
                "test:installation-close",
                RelationshipTraits.defaults().exclusive());
            var id = UUID.randomUUID();
            var source = fixture.add(id);
            var target = fixture.add(UUID.randomUUID());
            installation.getTracker().onEntityLoaded(id, source);
            relationships.addTarget(fixture.store, source, type, target);
            var metadata = fixture.store.getComponent(source, persistence.getComponentType());
            var savedLinks = metadata.getContent().getArray("Links");
            assertEquals(1, savedLinks.size(), "the link is recorded before the close");

            installation.close();

            assertNull(installation.getRelationshipTypeRegistry().getTracker());
            assertNull(installation.getRelationshipTypeRegistry().getPersistence());
            assertNull(installation.getTracker().getRef(id));
            assertThrows(IllegalStateException.class, type.getSourceType()::validate);
            assertEquals(savedLinks, metadata.getContent().getArray("Links"),
                "the saved records survive the close");
        }
    }

    @Test
    void closeUnregistersTheTransitionsAfterPersistenceAndBeforeTheTypes() {
        try (var fixture = new Fixture()) {
            var runtime = new ObservingRuntime();
            var installation = new StoreInstallation<>(
                fixture.registry, fixture.identity(), runtime);
            var persistence = installation.installPersistence();
            var type = installation.getRelationshipTypeRegistry().registerRelationship(
                "test:close-order",
                RelationshipTraits.defaults().exclusive());
            runtime.observe(persistence.getComponentType(), type.getSourceType());

            installation.close();

            assertTrue(runtime.metadataTypeInvalidAtTransitionUnregister,
                "the persistence metadata component must be gone when the transitions unregister");
            assertTrue(runtime.outgoingTypeValidAtTransitionUnregister,
                "the relationship types must still be registered when the transitions unregister");
        }
    }

    @Test
    void rejectedConstructionLeavesNoRegisteredSystemsAfterClose() {
        try (var fixture = new Fixture()) {
            var installation = new StoreInstallation<>(
                fixture.registry, fixture.identity(), TestStoreRuntime.inline());

            assertThrows(IllegalStateException.class,
                () -> new StoreInstallation<>(fixture.registry, fixture.identity(), TestStoreRuntime.inline()));

            installation.close();
            assertEquals(0, registeredSystemCount(fixture.registry),
                "a rejected construction must not keep the shared systems registered after close");

            var retry = new StoreInstallation<>(
                fixture.registry, fixture.identity(), TestStoreRuntime.inline());
            retry.close();
            assertEquals(0, registeredSystemCount(fixture.registry),
                "the registrations one close released must stay released across a fresh install and close");
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Map<Ref<Object>, UUID> identities = new IdentityHashMap<>();
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final Store<Object> store = registry.addStore(new Object(), EmptyResourceStorage.get());

        TestPersistenceIdentity<Object, UUID> identity() {
            return TestPersistenceIdentity.of(identities::get, Codec.UUID_BINARY);
        }

        Ref<Object> add(UUID id) {
            var ref = store.addEntity(registry.newHolder(), AddReason.LOAD);
            identities.put(ref, id);
            return ref;
        }

        @Override
        public void close() {
            registry.shutdown();
        }
    }

    /// A registration leak would be hidden by the Fixture's final `registry.shutdown`.
    private static int registeredSystemCount(ComponentRegistry<Object> registry) {
        var lock = registry.getDataUpdateLock().readLock();
        lock.lock();
        try {
            return registry._internal_getData().getSystemSize();
        } finally {
            lock.unlock();
        }
    }

    /// The unregister callback runs while the registry holds its native lock.
    private static final class ObservingRuntime implements StoreRuntime<Object> {
        private RefSystem<Object> built;
        private RelationshipTracker<Object, ?> installedTracker;
        private ComponentType<Object, ?> metadataType;
        private ComponentType<Object, ?> outgoingType;
        private boolean metadataTypeInvalidAtTransitionUnregister;
        private boolean outgoingTypeValidAtTransitionUnregister;

        void observe(ComponentType<Object, ?> metadataType, ComponentType<Object, ?> outgoingType) {
            this.metadataType = metadataType;
            this.outgoingType = outgoingType;
        }

        @Override
        public void execute(Store<Object> store, Runnable action) {
            store.assertThread();
            store.assertWriteProcessing();
            action.run();
        }

        @Override
        public void markNeedsSaving(ComponentAccessor<Object> accessor, Ref<Object> ref) {
        }

        @Override
        public void markNeedsSaving(Holder<Object> holder) {
        }

        @Override
        public boolean isDeletionSupported() {
            return true;
        }

        @Nonnull
        @Override
        public RefSystem<Object> getTransitionSystem(RelationshipTracker<Object, ?> tracker) {
            installedTracker = tracker;
            built = new RefSystem<>() {
                @Override
                public Query<Object> getQuery() {
                    return Query.not(Query.any());
                }

                @Override
                public void onEntityAdded(
                    @Nonnull Ref<Object> ref,
                    @Nonnull AddReason reason,
                    @Nonnull Store<Object> store,
                    @Nonnull CommandBuffer<Object> buffer
                ) { }

                @Override
                public void onEntityRemove(
                    @Nonnull Ref<Object> ref,
                    @Nonnull RemoveReason reason,
                    @Nonnull Store<Object> store,
                    @Nonnull CommandBuffer<Object> buffer
                ) { }

                @Override
                public void onSystemUnregistered() {
                    // only the close-order test captures types to read here
                    if (metadataType == null) return;
                    metadataTypeInvalidAtTransitionUnregister = !isValid(metadataType);
                    outgoingTypeValidAtTransitionUnregister = isValid(outgoingType);
                }
            };
            return built;
        }

        private static boolean isValid(ComponentType<Object, ?> type) {
            try {
                type.validate();
                return true;
            } catch (IllegalStateException invalid) {
                return false;
            }
        }
    }
}
