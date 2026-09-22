/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.query.Query;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.RefSystem;

import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A registry takes a tracker and then a persistence, once each and in that order, and close
/// releases them in reverse.
class RelationshipInstallationTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void installedTrackerIsReadBackFromTheRegistry() {
        try (var installation = new Installation()) {
            var tracker = installation.installTracker();

            assertSame(tracker, installation.types.getTracker());
        }
    }

    @Test
    void installedPersistenceIsReadBackFromTheRegistry() {
        try (var installation = new Installation()) {
            installation.installTracker();

            var persistence = installation.installPersistence();

            assertSame(persistence, installation.types.getPersistence());
        }
    }

    @Test
    void installingATrackerTwiceIsRejected() {
        try (var installation = new Installation()) {
            installation.installTracker();

            assertThrows(IllegalStateException.class, installation::installTracker);
        }
    }

    @Test
    void installingPersistenceTwiceIsRejected() {
        try (var installation = new Installation()) {
            installation.installTracker();
            installation.installPersistence();

            assertThrows(IllegalStateException.class, installation::installPersistence);
        }
    }

    @Test
    void installingATrackerAfterCloseIsRejected() {
        try (var installation = new Installation()) {
            installation.types.close();

            assertThrows(IllegalStateException.class, installation::installTracker);
        }
    }

    @Test
    void installingPersistenceBeforeATrackerIsRejected() {
        try (var installation = new Installation(); var other = new Installation()) {
            var foreign = other.installTracker();

            assertThrows(IllegalStateException.class, () -> installation.installPersistence(foreign));
        }
    }

    @Test
    void installingPersistenceWithAnotherInstallationsTrackerIsRejected() {
        try (var installation = new Installation(); var other = new Installation()) {
            installation.installTracker();
            var namedLinkedEntities = other.installNamedTracker();

            var rejection = assertThrows(IllegalStateException.class,
                () -> installation.installPersistence(namedLinkedEntities));

            assertNull(installation.types.getPersistence());
            assertTrue(rejection.getMessage().contains("tracker"), rejection.getMessage());
        }
    }

    @Test
    void persistenceRejectsIdentitiesFromAReplacementTracker() {
        try (var installation = new Installation()) {
            var tracker = installation.installTracker();
            var persistence = installation.installPersistence();
            var linkedEntity = installation.add(UUID.randomUUID());
            assertNotNull(persistence.getIdentity(linkedEntity));

            tracker.close();
            installation.installNamedTracker();

            assertThrows(IllegalStateException.class, () -> persistence.getIdentity(linkedEntity));
        }
    }

    @Test
    void closeReleasesTheTrackerPersistenceAndRecordComponent() {
        try (var installation = new Installation()) {
            var tracker = installation.installTracker();
            var persistence = installation.installPersistence();
            var id = UUID.randomUUID();
            var linkedEntity = installation.add(id);
            tracker.onEntityLoaded(id, linkedEntity);

            installation.types.close();

            assertNull(installation.types.getTracker());
            assertNull(installation.types.getPersistence());
            assertNull(tracker.getRef(id));
            assertThrows(IllegalStateException.class, persistence.getComponentType()::validate);
        }
    }

    @Test
    void closingOneRegistryLeavesTheInstallationItsSiblingRegistryStillUses() {
        try (var installation = new Installation()) {
            var sibling = new RelationshipTypeRegistry<>(installation.registry);
            var follows = sibling.registerRelationship(RelationshipRules.single());
            var source = installation.add(UUID.randomUUID());
            var target = installation.add(UUID.randomUUID());

            installation.types.close();

            relationships.addTarget(installation.store, source, follows, target);

            assertEquals(1, relationships.getTargetCount(source, follows));

            sibling.close();

            assertThrows(IllegalStateException.class, follows.getSourceType()::validate);
        }
    }

    @Test
    void aSiblingRegistryInstallsPersistenceOnTheTrackerTheInstallationAlreadyHas() {
        try (var installation = new Installation()) {
            var runtime = new CountingRuntime();
            var tracker = installation.installTracker(runtime);
            var sibling = new RelationshipTypeRegistry<>(installation.registry);
            sibling.registerRelationship(
                "relwind:test/follows",
                RelationshipRules.single().retainOnTransfer());

            sibling.installPersistence(installation.types.getTracker());

            assertSame(tracker, sibling.getTracker());
            assertEquals(1, runtime.transitionsCalls);
            sibling.close();
        }
    }

    private static final class CountingRuntime implements StoreRuntime<Object> {
        private final StoreRuntime<Object> delegate = TestStoreRuntime.inline();
        private int transitionsCalls;

        @Override
        public void execute(Store<Object> store, Runnable action) {
            delegate.execute(store, action);
        }

        @Override
        public void markNeedsSaving(ComponentAccessor<Object> accessor, Ref<Object> ref) {
            delegate.markNeedsSaving(accessor, ref);
        }

        @Override
        public void markNeedsSaving(Holder<Object> holder) {
            delegate.markNeedsSaving(holder);
        }

        @Override
        public boolean isDeletionSupported() {
            return delegate.isDeletionSupported();
        }

        @Nonnull
        @Override
        public RefSystem<Object> getTransitionSystem(RelationshipTracker<Object, ?> tracker) {
            transitionsCalls++;
            return delegate.getTransitionSystem(tracker);
        }
    }

    @Test
    void addingATargetMarksItsSourceForSavingThroughTheInstalledRuntime() {
        try (var installation = new Installation()) {
            var marks = new ArrayList<String>();
            var tracker = installation.installTracker(TestStoreRuntime.marking(
                (store, ref) -> marks.add("source"),
                holder -> marks.add("holder")));
            var sibling = new RelationshipTypeRegistry<>(installation.registry);
            var follows = sibling.registerRelationship(
                "relwind:test/follows",
                RelationshipRules.single().retainOnTransfer());
            sibling.installPersistence(tracker);
            var source = installation.add(UUID.randomUUID());
            var targetId = UUID.randomUUID();
            var target = installation.add(targetId);
            tracker.onEntityLoaded(installation.identities.get(source), source);
            tracker.onEntityLoaded(targetId, target);
            marks.clear();

            relationships.addTarget(installation.store, source, follows, target);

            assertEquals(List.of("source"), marks);
            sibling.close();
        }
    }

    @Test
    void unloadingASourceMarksItsParkedHolderForSaving() {
        try (var installation = new Installation()) {
            var marks = new ArrayList<String>();
            var tracker = installation.installTracker(TestStoreRuntime.marking(
                (store, ref) -> marks.add("source"),
                holder -> marks.add("holder")));
            var sibling = new RelationshipTypeRegistry<>(installation.registry);
            var follows = sibling.registerRelationship(
                "relwind:test/follows",
                RelationshipRules.single().retainOnTransfer());
            var persistence = sibling.installPersistence(tracker);
            var sourceId = UUID.randomUUID();
            var source = installation.add(sourceId);
            var targetId = UUID.randomUUID();
            var target = installation.add(targetId);
            tracker.onEntityLoaded(sourceId, source);
            tracker.onEntityLoaded(targetId, target);
            marks.clear();
            relationships.addTarget(installation.store, source, follows, target);

            var holder = installation.store.removeEntity(source, RemoveReason.UNLOAD);
            tracker.onEntityUnloaded(sourceId, source, UnloadReason.DEACTIVATION, holder);

            assertEquals(List.of("source", "holder"), marks);
            assertNotNull(persistence.getIdentity(source));
            sibling.close();
        }
    }

    private static final class Installation implements AutoCloseable {
        private final Map<Ref<Object>, UUID> identities = new IdentityHashMap<>();
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final Store<Object> store = registry.addStore(new Object(), EmptyResourceStorage.get());
        private final RelationshipTypeRegistry<Object> types = new RelationshipTypeRegistry<>(registry);

        private RelationshipTracker<Object, UUID> installTracker() {
            return installTracker(TestStoreRuntime.inline());
        }

        private RelationshipTracker<Object, UUID> installTracker(StoreRuntime<Object> runtime) {
            return types.installTracker(
                TestPersistenceIdentity.of(identities::get, Codec.UUID_BINARY), runtime);
        }

        /// A tracker for the same Store type whose linked entities are named by a String identity.
        private RelationshipTracker<Object, String> installNamedTracker() {
            return types.installTracker(
                TestPersistenceIdentity.of(ref -> String.valueOf(identities.get(ref)), Codec.STRING),
                TestStoreRuntime.inline());
        }

        private RelationshipPersistence<Object> installPersistence() {
            return installPersistence(types.getTracker());
        }

        private RelationshipPersistence<Object> installPersistence(@Nullable RelationshipTracker<Object, ?> tracker) {
            return types.installPersistence(tracker);
        }

        private Ref<Object> add(UUID id) {
            var ref = store.addEntity(registry.newHolder(), AddReason.LOAD);
            identities.put(ref, id);
            return ref;
        }

        @Override
        public void close() {
            registry.shutdown();
        }
    }
}
