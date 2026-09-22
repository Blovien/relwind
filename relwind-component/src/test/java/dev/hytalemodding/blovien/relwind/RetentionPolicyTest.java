/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;



import static org.junit.jupiter.api.Assertions.assertSame;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// A source keeps or releases its attached link storage as its retention policy says, and the
/// storage it keeps holds nothing of the links it no longer has.
class RetentionPolicyTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void theDefaultRetentionReleasesTheSourceMarkerWithTheLastLink() {
        var registry = new ComponentRegistry<Object>();
        try {
            var types = new RelationshipTypeRegistry<>(registry);
            var released = registerDefault(types, "released");
            var changes = new ReleasedMarkerChanges(released);
            registry.registerSystem(changes);
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var source = addEntity(store);
            var target = addEntity(store);
            assertEquals(RelationshipRules.SourceRetention.RELEASE, released.getDescriptor().getSourceRetention());

            relationships.addTarget(store, source, released, target, new Payload(1));
            relationships.removeTarget(store, source, released, target);

            assertFalse(store.getArchetype(source).contains(released.getSourceType()));
            assertEquals(1, changes.added);
            assertEquals(1, changes.removed);
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void retentionKeepsTheEmptiedMarkerUntilAnExplicitTrimRemovesIt() {
        var registry = new ComponentRegistry<Object>();
        try {
            var types = new RelationshipTypeRegistry<>(registry);
            var retained = register(types, "retained", RelationshipRules.SourceRetention.RETAIN);
            var changes = new RetainedMarkerChanges(retained);
            registry.registerSystem(changes);
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var source = addEntity(store);
            var targets = addEntities(store, 32);

            addTargetPerEntity(store, source, retained, targets);
            assertFalse(relationships.trimSourceStorage(store, source, retained));
            removeEachTargetLeavingNoIncomingLinks(store, source, retained, targets);

            var outgoing = store.getComponent(source, retained.getSourceType());
            assertEquals(0, outgoing.size());
            assertNull(outgoing.getTarget());
            assertNull(outgoing.getData(Payload.class));
            assertTrue(store.getArchetype(source).contains(retained.getSourceType()));
            assertNull(relationships.getFirstTarget(source, retained));
            assertEquals(0, relationships.getTargetCount(source, retained));
            assertEquals(1, changes.added);
            assertEquals(0, changes.removed);

            relationships.addTarget(store, source, retained, targets.get(0), new Payload(99));
            relationships.removeTarget(store, source, retained, targets.get(0));
            assertEquals(1, changes.added);
            assertEquals(0, changes.removed);
            assertTrue(relationships.trimSourceStorage(store, source, retained));
            assertFalse(relationships.trimSourceStorage(store, source, retained));
            assertEquals(1, changes.removed);
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void anEmptyRetainedSourceProducesNoCallbacksAndCanBeReused() {
        var registry = new ComponentRegistry<Object>();
        try {
            var types = new RelationshipTypeRegistry<>(registry);
            var retained = register(types, "callbacks", RelationshipRules.SourceRetention.RETAIN);
            var system = new RecordingSystem(retained);
            registry.registerSystem(system);
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var source = addEntity(store);
            var firstTarget = addEntity(store);
            var secondTarget = addEntity(store);

            relationships.addTarget(store, source, retained, firstTarget, new Payload(1));
            store.tick(0.05f);
            relationships.removeTarget(store, source, retained, firstTarget);
            store.tick(0.05f);
            relationships.addTarget(store, source, retained, secondTarget, new Payload(2));
            store.tick(0.05f);

            assertEquals(List.of(firstTarget, secondTarget), system.targets);
            assertEquals(List.of(1, 2), system.values);
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void unloadingTheTargetDropsTheSourceMarkerWhenTheSourceReleases() {
        var registry = new ComponentRegistry<Object>();
        try {
            var types = new RelationshipTypeRegistry<>(registry);
            var type = register(types, "unload-release", RelationshipRules.SourceRetention.RELEASE);
            var changes = new MarkerChanges(type);
            registry.registerSystem(changes);
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var source = addEntity(store);
            var target = addEntity(store);
            relationships.addTarget(store, source, type, target, new Payload(7));

            store.removeEntity(target, RemoveReason.REMOVE);

            assertTrue(source.isValid());
            assertFalse(target.isValid());
            assertFalse(store.getArchetype(source).contains(type.getSourceType()));
            assertNull(store.getComponent(source, type.getSourceType()));
            assertNull(relationships.getFirstTarget(source, type));
            assertEquals(1, changes.added);
            assertEquals(1, changes.removed);
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void unloadingTheTargetEmptiesTheKeptSourceMarkerWhenTheSourceRetains() {
        var registry = new ComponentRegistry<Object>();
        try {
            var types = new RelationshipTypeRegistry<>(registry);
            var type = register(types, "unload-retain", RelationshipRules.SourceRetention.RETAIN);
            var changes = new MarkerChanges(type);
            registry.registerSystem(changes);
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var source = addEntity(store);
            var target = addEntity(store);
            relationships.addTarget(store, source, type, target, new Payload(7));

            store.removeEntity(target, RemoveReason.REMOVE);

            assertTrue(source.isValid());
            assertFalse(target.isValid());
            assertTrue(store.getArchetype(source).contains(type.getSourceType()));
            var outgoing = store.getComponent(source, type.getSourceType());
            assertEquals(0, outgoing.size());
            assertNull(outgoing.getTarget());
            assertNull(outgoing.getData(Payload.class));
            assertNull(relationships.getFirstTarget(source, type));
            assertEquals(1, changes.added);
            assertEquals(0, changes.removed);
        } finally {
            registry.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(RelationshipRules.SourceRetention.class)
    void attachedStorageReleasesTheDataOfRemovedLinksAcrossGrowthTrimAndUnload(
        RelationshipRules.SourceRetention retention
    ) {
        var registry = new ComponentRegistry<Object>();
        try {
            var types = new RelationshipTypeRegistry<>(registry);
            var type = register(types, "released-storage-" + retention, retention);
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var source = addEntity(store);
            var targets = addEntities(store, 64);
            var data = growThenRemoveAll(type, store, source, targets);

            assertEquals(retention == RelationshipRules.SourceRetention.RETAIN,
                relationships.trimSourceStorage(store, source, type),
                "retained emptied storage trims away, released storage is already gone");
            assertNull(relationships.getData(source, type, targets.getLast()), "a removed link keeps no data");
            assertReleased(data, "removed links");

            store.removeEntity(source, RemoveReason.REMOVE);

            assertReleased(data, "the links of an unloaded source");
        } finally {
            registry.shutdown();
        }
    }

    /// Only the weak references leave this frame, and nothing but the attached storage can still
    /// hold the data objects.
    private static List<WeakReference<Payload>> growThenRemoveAll(
        GenericRelationshipType<Object, Object, Payload> type,
        Store<Object> store,
        Ref<Object> source,
        List<Ref<Object>> targets
    ) {
        var data = new ArrayList<WeakReference<Payload>>();
        for (int i = 0; i < targets.size(); i++) {
            var payload = new Payload(i);
            relationships.addTarget(store, source, type, targets.get(i), payload);
            data.add(new WeakReference<>(payload));
        }
        for (int i = 0; i < targets.size(); i++) {
            relationships.removeTarget(store, source, type, targets.get(i));
        }
        return data;
    }

    /// The collector clears a reference only once nothing holds the object. The wait is bounded,
    /// and data that is still held fails the test.
    private static void assertReleased(List<WeakReference<Payload>> data, String what) {
        for (int attempt = 0; attempt < 100; attempt++) {
            System.gc();
            if (data.stream().allMatch(reference -> reference.get() == null)) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail(data.stream().filter(reference -> reference.get() != null).count()
            + " of " + data.size() + " data objects of " + what + " are still reachable");
    }

    @ParameterizedTest
    @MethodSource("linkCommands")
    void addAndSetRejectATrimBufferedByTheIncomingCallback(LinkCommand command) {
        assertBufferedTrimIsRejected(command);
    }

    private static Stream<Arguments> linkCommands() {
        return Stream.of(
            Arguments.argumentSet("add", (LinkCommand) relationships::addTarget),
            Arguments.argumentSet("set", (LinkCommand) relationships::putTarget)
        );
    }

    @FunctionalInterface
    private interface LinkCommand {
        void apply(
            Store<Object> store,
            Ref<Object> source,
            GenericRelationshipType<Object, Object, Object> type,
            Ref<Object> target,
            Object data
        );
    }

    private static void assertBufferedTrimIsRejected(LinkCommand command) {
        var registry = new ComponentRegistry<Object>();
        try {
            var types = new RelationshipTypeRegistry<>(registry);
            var type = types.registerRelationship(
                Object.class,
                RelationshipRules.single().retainSourceStorage());
            var listener = new TrimOnIncoming(type);
            registry.registerSystem(listener);
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var source = addEntity(store);
            var oldTarget = addEntity(store);
            var target = addEntity(store);
            relationships.addTarget(store, source, type, oldTarget, new Object());
            relationships.removeTarget(store, source, type, oldTarget);
            assertNotNull(store.getComponent(source, type.getSourceType()));
            assertNull(store.getComponent(target, type.getIncomingType()));
            listener.source = source;
            var payload = new Object();
            var rejected = assertThrows(IllegalStateException.class,
                () -> command.apply(store, source, type, target, payload));

            assertTrue(rejected.getMessage().contains("CommandBuffer"), rejected.getMessage());
            assertTrue(listener.buffered);
            // the failed command leaves the link half written, with only the incoming side present
            assertEquals(0, relationships.getTargetCount(source, type));
            assertEquals(1, relationships.getIncomingCount(target, type));
        } finally {
            registry.shutdown();
        }
    }

    private static GenericRelationshipType<Object, Object, Payload> register(
        RelationshipTypeRegistry<Object> types,
        String name,
        RelationshipRules.SourceRetention retention
    ) {
        var rules = RelationshipRules.multiple();
        if (retention == RelationshipRules.SourceRetention.RETAIN) rules = rules.retainSourceStorage();
        return types.registerRelationship("relwind:test/" + name, Payload.class, null, rules);
    }

    private static GenericRelationshipType<Object, Object, Payload> registerDefault(
        RelationshipTypeRegistry<Object> types,
        String name
    ) {
        return types.registerRelationship(
            "relwind:test/" + name,
            Payload.class,
            null,
            RelationshipRules.multiple());
    }

    private static Ref<Object> addEntity(Store<Object> store) {
        return Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
    }

    private static List<Ref<Object>> addEntities(Store<Object> store, int count) {
        var entities = new ArrayList<Ref<Object>>();
        for (int i = 0; i < count; i++) {
            entities.add(addEntity(store));
        }
        return entities;
    }

    private static void addTargetPerEntity(
        Store<Object> store,
        Ref<Object> source,
        GenericRelationshipType<Object, Object, Payload> type,
        List<Ref<Object>> targets
    ) {
        for (int i = 0; i < targets.size(); i++) {
            relationships.addTarget(store, source, type, targets.get(i), new Payload(i));
        }
    }

    private static void removeEachTargetLeavingNoIncomingLinks(
        Store<Object> store,
        Ref<Object> source,
        GenericRelationshipType<Object, Object, Payload> type,
        List<Ref<Object>> targets
    ) {
        for (var target : targets) {
            relationships.removeTarget(store, source, type, target);
            var incoming = store.getComponent(target, type.getIncomingType());
            assertEquals(0, incoming.size());
            assertNull(incoming.source);
            assertNull(incoming.sources);
            assertNull(incoming.positions);
        }
    }

    private record Payload(int value) {
    }

    private static final class RecordingSystem extends RelationshipTickingSystem<Object, Payload> {
        private final RelationshipQuery.Definition<Object, Payload> query;
        private final List<Ref<Object>> targets = new ArrayList<>();
        private final List<Integer> values = new ArrayList<>();

        private RecordingSystem(GenericRelationshipType<Object, Object, Payload> type) {
            this.query = RelationshipQuery.of(Query.any(), type);
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Payload> getQuery() {
            return query;
        }

        @Override
        protected void tickRelationship(
            float seconds,
            RelationshipResult<Object, Payload> result,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            targets.add(result.getTarget());
            values.add(result.getData().value());
        }
    }

    private static class MarkerChanges
        extends RefChangeSystem<Object, OutgoingLink<Object, Object>> {
        private final GenericRelationshipType<Object, Object, Payload> type;
        int added;
        int removed;

        private MarkerChanges(GenericRelationshipType<Object, Object, Payload> type) {
            this.type = type;
        }

        @Override
        public ComponentType<Object, OutgoingLink<Object, Object>> componentType() {
            return type.getSourceType();
        }

        @Override
        public Query<Object> getQuery() {
            return Query.any();
        }

        @Override
        public void onComponentAdded(
            @Nonnull Ref<Object> ref,
            @Nonnull OutgoingLink<Object, Object> component,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            added++;
        }

        @Override
        public void onComponentSet(
            @Nonnull Ref<Object> ref,
            OutgoingLink<Object, Object> oldComponent,
            @Nonnull OutgoingLink<Object, Object> newComponent,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
        }

        @Override
        public void onComponentRemoved(
            @Nonnull Ref<Object> ref,
            @Nonnull OutgoingLink<Object, Object> component,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            removed++;
        }
    }

    private static final class ReleasedMarkerChanges extends MarkerChanges {
        private ReleasedMarkerChanges(GenericRelationshipType<Object, Object, Payload> type) {
            super(type);
        }
    }

    private static final class RetainedMarkerChanges extends MarkerChanges {
        private RetainedMarkerChanges(GenericRelationshipType<Object, Object, Payload> type) {
            super(type);
        }
    }

    private static final class TrimOnIncoming extends RefChangeSystem<Object, IncomingLinks<Object, Object>> {
        private final GenericRelationshipType<Object, Object, Object> type;
        private Ref<Object> source;
        private boolean buffered;

        private TrimOnIncoming(GenericRelationshipType<Object, Object, Object> type) {
            this.type = type;
        }

        @Override
        public ComponentType<Object, IncomingLinks<Object, Object>> componentType() {
            return type.getIncomingType();
        }

        @Override
        public Query<Object> getQuery() {
            return Query.any();
        }

        @Override
        public void onComponentAdded(
            @Nonnull Ref<Object> ref,
            @Nonnull IncomingLinks<Object, Object> component,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            if (source == null) return;
            relationships.trimSourceStorage(commandBuffer, source, type);
            buffered = true;
        }

        @Override
        public void onComponentSet(
            @Nonnull Ref<Object> ref,
            IncomingLinks<Object, Object> oldComponent,
            @Nonnull IncomingLinks<Object, Object> newComponent,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
        }

        @Override
        public void onComponentRemoved(
            @Nonnull Ref<Object> ref,
            @Nonnull IncomingLinks<Object, Object> component,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
        }
    }
}
