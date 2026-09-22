/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;



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
import it.unimi.dsi.fastutil.HashCommon;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A type with many targets keeps both directions addressable as links are added, moved and
/// removed, and a traversal rejects commands until it finishes.
class MultipleTargetRelationshipsTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void completedTraversalDoesNotRetainShutdownStoreWhileRegistryLives() throws InterruptedException {
        var registry = new ComponentRegistry<Object>();
        try {
            var types = new RelationshipTypeRegistry<>(registry);
            var type = register(types, "store-tracker", RelationshipRules.TargetDeletion.PRESERVE_SOURCE);
            var unloaded = createUnloadedStore(registry, type);
            var liveStore = registry.addStore(new Object(), EmptyResourceStorage.get());

            try {
                for (int attempt = 0; attempt < 100 && !unloaded.refersTo(null); attempt++) {
                    System.gc();
                    Thread.sleep(10);
                }

                assertTrue(unloaded.refersTo(null));
                assertFalse(liveStore.isShutdown());
            } finally {
                Reference.reachabilityFence(types);
            }
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void multipleTargetOperationsPreserveDataAndBothDirections() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var first = fixture.addEntity();
            var second = fixture.addEntity();
            var third = fixture.addEntity();
            var fourth = fixture.addEntity();
            var firstData = new LinkData(1);
            var secondData = new LinkData(2);
            var replacement = new LinkData(20);

            relationships.addTarget(fixture.store, source, fixture.type, first, firstData);
            relationships.addTarget(fixture.store, source, fixture.type, second, secondData);
            relationships.addTarget(fixture.store, source, fixture.type, third, new LinkData(3));
            assertThrows(IllegalStateException.class,
                () -> relationships.addTarget(fixture.store, source, fixture.type, second, new LinkData(200)));
            assertSame(secondData, relationships.getData(source, fixture.type, second));

            relationships.putTarget(fixture.store, source, fixture.type, second, replacement);
            relationships.putTarget(fixture.store, source, fixture.type, fourth, new LinkData(4));
            assertSame(replacement, relationships.getData(source, fixture.type, second));
            assertEquals(4, relationships.getTargetCount(source, fixture.type));
            assertTargets(fixture.type, source, first, second, third, fourth);

            relationships.retarget(fixture.store, source, fixture.type, first, fixture.addEntity());
            assertThrows(
                IllegalStateException.class,
                () -> relationships.retarget(fixture.store, source, fixture.type, second, third)
            );
            relationships.removeTarget(fixture.store, source, fixture.type, second);
            relationships.tryRemoveTarget(fixture.store, source, fixture.type, second);
            assertEquals(0, relationships.getIncomingCount(second, fixture.type));
            assertEquals(3, relationships.getTargetCount(source, fixture.type));
        }
    }

    @Test
    void seededOperationsAgreeWithAnIndependentIdentityGraph() {
        try (var fixture = new Fixture()) {
            var refs = new ArrayList<Ref<Object>>();
            for (int i = 0; i < 48; i++) {
                refs.add(fixture.addEntity());
            }
            var expected = new IdentityHashMap<Ref<Object>, IdentityHashMap<Ref<Object>, LinkData>>();
            var random = new Random(4_611_703);

            for (int operation = 0; operation < 3_000; operation++) {
                var source = refs.get(random.nextInt(refs.size()));
                var target = refs.get(random.nextInt(refs.size()));
                var links = expected.computeIfAbsent(source, ignored -> new IdentityHashMap<>());
                switch (random.nextInt(4)) {
                    case 0 -> {
                        var data = new LinkData(operation);
                        if (links.putIfAbsent(target, data) == null) {
                            relationships.addTarget(fixture.store, source, fixture.type, target, data);
                        } else {
                            assertThrows(
                                IllegalStateException.class,
                                () -> relationships.addTarget(fixture.store, source, fixture.type, target, data)
                            );
                        }
                    }
                    case 1 -> {
                        var data = new LinkData(operation);
                        relationships.putTarget(fixture.store, source, fixture.type, target, data);
                        links.put(target, data);
                    }
                    case 2 -> {
                        if (links.remove(target) != null) {
                            relationships.removeTarget(fixture.store, source, fixture.type, target);
                        } else {
                            assertThrows(
                                IllegalStateException.class,
                                () -> relationships.removeTarget(fixture.store, source, fixture.type, target)
                            );
                            relationships.tryRemoveTarget(fixture.store, source, fixture.type, target);
                        }
                    }
                    case 3 -> {
                        var newTarget = refs.get(random.nextInt(refs.size()));
                        var data = links.get(target);
                        if (target == newTarget) {
                            relationships.retarget(fixture.store, source, fixture.type, target, newTarget);
                        } else if (data == null) {
                            assertThrows(
                                IllegalStateException.class,
                                () -> relationships.retarget(fixture.store, source, fixture.type, target, newTarget)
                            );
                        } else if (links.containsKey(newTarget)) {
                            assertThrows(
                                IllegalStateException.class,
                                () -> relationships.retarget(fixture.store, source, fixture.type, target, newTarget)
                            );
                        } else {
                            relationships.retarget(fixture.store, source, fixture.type, target, newTarget);
                            links.remove(target);
                            links.put(newTarget, data);
                        }
                    }
                    default -> throw new AssertionError();
                }
                if (links.isEmpty()) {
                    expected.remove(source);
                }
                assertGraph(fixture.type, refs, expected);
            }
        }
    }

    @Test
    void indexedForwardCollisionsAndMovedRowsRemainAddressable() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var collisions = targetsSharingOneForwardBucket(fixture, 17);
            var expected = new IdentityHashMap<Ref<Object>, LinkData>();
            for (int i = 0; i < 17; i++) {
                var data = new LinkData(i);
                relationships.addTarget(fixture.store, source, fixture.type, collisions.get(i), data);
                expected.put(collisions.get(i), data);
            }

            for (int i = 1; i < 17; i += 2) {
                relationships.removeTarget(fixture.store, source, fixture.type, collisions.get(i));
                expected.remove(collisions.get(i));
            }

            for (var entry : expected.entrySet()) {
                assertSame(entry.getValue(), relationships.getData(source, fixture.type, entry.getKey()));
            }
            var actual = new HashSet<Ref<Object>>();
            relationships.forEachTarget(source, fixture.type, actual::add);
            assertEquals(expected.keySet(), actual);
        }
    }

    @Test
    void deletingATargetRemovesOnlyItsOwnLinkWhenTheRulePreservesTheSource() {
        var registry = new ComponentRegistry<Object>();
        try {
            var types = new RelationshipTypeRegistry<>(registry);
            var preserves = register(types, "preserves", RelationshipRules.TargetDeletion.PRESERVE_SOURCE);
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var preservedSource = addEntity(store);
            var deletedTarget = addEntity(store);
            var retainedTarget = addEntity(store);
            relationships.addTarget(store, preservedSource, preserves, deletedTarget, new LinkData(1));
            relationships.addTarget(store, preservedSource, preserves, retainedTarget, new LinkData(2));

            store.removeEntity(deletedTarget, RemoveReason.REMOVE);

            assertTrue(preservedSource.isValid());
            assertEquals(1, relationships.getTargetCount(preservedSource, preserves));
            assertSame(retainedTarget, relationships.getFirstTarget(preservedSource, preserves));
            assertEquals(1, relationships.getIncomingCount(retainedTarget, preserves));
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void deletingATargetDeletesTheWholeSourceWhenTheRuleCascades() {
        var registry = new ComponentRegistry<Object>();
        try {
            var types = new RelationshipTypeRegistry<>(registry);
            var cascades = register(types, "cascades", RelationshipRules.TargetDeletion.CASCADE_SOURCE);
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var cascadingSource = addEntity(store);
            var cascadingTarget = addEntity(store);
            var otherTarget = addEntity(store);
            relationships.addTarget(store, cascadingSource, cascades, cascadingTarget, new LinkData(3));
            relationships.addTarget(store, cascadingSource, cascades, otherTarget, new LinkData(4));

            store.removeEntity(cascadingTarget, RemoveReason.REMOVE);

            assertFalse(cascadingSource.isValid());
            assertFalse(cascadingTarget.isValid());
            assertEquals(0, relationships.getIncomingCount(otherTarget, cascades));
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void putAndRemoveChurnKeepsEveryTargetAddressable() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var targets = new ArrayList<Ref<Object>>();
            for (int i = 0; i < 64; i++) {
                targets.add(fixture.addEntity());
            }
            var expected = new IdentityHashMap<Ref<Object>, LinkData>();
            for (int i = 0; i < 64; i++) {
                var data = new LinkData(i);
                relationships.putTarget(fixture.store, source, fixture.type, targets.get(i), data);
                expected.put(targets.get(i), data);
            }

            for (int i = 0; i < 64; i += 2) {
                relationships.removeTarget(fixture.store, source, fixture.type, targets.get(i));
                expected.remove(targets.get(i));
            }

            for (int i = 0; i < 64; i += 2) {
                var data = new LinkData(100 + i);
                relationships.putTarget(fixture.store, source, fixture.type, targets.get(i), data);
                expected.put(targets.get(i), data);
            }

            for (var entry : expected.entrySet()) {
                assertSame(entry.getValue(), relationships.getData(source, fixture.type, entry.getKey()));
            }
            var actual = new HashSet<Ref<Object>>();
            relationships.forEachTarget(source, fixture.type, actual::add);
            assertEquals(expected.keySet(), actual);
        }
    }

    @Test
    void forwardTraversalVisitsEveryTargetAndRejectsRemovalUntilItFinishes() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var targets = List.of(
                fixture.addEntity(),
                fixture.addEntity(),
                fixture.addEntity(),
                fixture.addEntity()
            );
            for (int i = 0; i < targets.size(); i++) {
                relationships.addTarget(fixture.store, source, fixture.type, targets.get(i), new LinkData(i));
            }
            var visited = new HashSet<Ref<Object>>();

            relationships.forEachTarget(source, fixture.type, target -> {
                visited.add(target);
                assertThrows(IllegalStateException.class,
                    () -> relationships.removeTarget(fixture.store, source, fixture.type, target));
                assertEquals(targets.size(), relationships.getTargetCount(source, fixture.type));
            });

            assertEquals(new HashSet<>(targets), visited);
            for (var target : visited) {
                relationships.removeTarget(fixture.store, source, fixture.type, target);
            }
            assertEquals(0, relationships.getTargetCount(source, fixture.type));
            for (var target : targets) {
                assertEquals(0, relationships.getIncomingCount(target, fixture.type));
            }
        }
    }

    @Test
    void reverseTraversalVisitsEverySourceAndRejectsRemovalUntilItFinishes() {
        try (var fixture = new Fixture()) {
            var target = fixture.addEntity();
            var sources = List.of(
                fixture.addEntity(),
                fixture.addEntity(),
                fixture.addEntity(),
                fixture.addEntity()
            );
            for (int i = 0; i < sources.size(); i++) {
                relationships.addTarget(fixture.store, sources.get(i), fixture.type, target, new LinkData(i));
            }
            var visited = new HashSet<Ref<Object>>();

            relationships.forEachIncomingSource(target, fixture.type, source -> {
                visited.add(source);
                assertThrows(IllegalStateException.class,
                    () -> relationships.removeTarget(fixture.store, source, fixture.type, target));
                assertEquals(sources.size(), relationships.getIncomingCount(target, fixture.type));
            });

            assertEquals(new HashSet<>(sources), visited);
            for (var source : visited) {
                relationships.removeTarget(fixture.store, source, fixture.type, target);
            }
            assertEquals(0, relationships.getIncomingCount(target, fixture.type));
            for (var source : sources) {
                assertEquals(0, relationships.getTargetCount(source, fixture.type));
            }
        }
    }

    @Test
    void nestedTraversalRejectsMutationUntilTheOuterTraversalCompletes() {
        try (var fixture = new Fixture()) {
            var outerSource = fixture.addEntity();
            var firstOuterTarget = fixture.addEntity();
            var secondOuterTarget = fixture.addEntity();
            var innerSource = fixture.addEntity();
            var innerTarget = fixture.addEntity();
            relationships.addTarget(fixture.store, outerSource, fixture.type, firstOuterTarget, new LinkData(1));
            relationships.addTarget(fixture.store, outerSource, fixture.type, secondOuterTarget, new LinkData(2));
            relationships.addTarget(fixture.store, innerSource, fixture.type, innerTarget, new LinkData(3));
            var visited = new HashSet<Ref<Object>>();

            relationships.forEachTarget(outerSource, fixture.type, outerTarget -> {
                visited.add(outerTarget);
                if (outerTarget == firstOuterTarget) {
                    relationships.forEachTarget(innerSource, fixture.type, ignored -> {
                        assertThrows(IllegalStateException.class, () -> relationships.removeTarget(fixture.store, outerSource, fixture.type, firstOuterTarget));
                        assertEquals(2, relationships.getTargetCount(outerSource, fixture.type));
                    });
                    assertThrows(IllegalStateException.class, () -> relationships.removeTarget(fixture.store, outerSource, fixture.type, firstOuterTarget));
                    assertEquals(2, relationships.getTargetCount(outerSource, fixture.type));
                }
            });

            assertEquals(new HashSet<>(List.of(firstOuterTarget, secondOuterTarget)), visited);
            relationships.removeTarget(fixture.store, outerSource, fixture.type, firstOuterTarget);
            assertEquals(1, relationships.getTargetCount(outerSource, fixture.type));
            assertSame(secondOuterTarget, relationships.getFirstTarget(outerSource, fixture.type));
            assertEquals(0, relationships.getIncomingCount(firstOuterTarget, fixture.type));
        }
    }

    @Test
    void callbackFailurePropagatesAndLeavesEveryLinkCommitted() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var removedTarget = fixture.addEntity();
            var retainedTarget = fixture.addEntity();
            relationships.addTarget(fixture.store, source, fixture.type, removedTarget, new LinkData(1));
            relationships.addTarget(fixture.store, source, fixture.type, retainedTarget, new LinkData(2));
            var expected = new IllegalStateException("callback failed");
            var removed = new ArrayList<Ref<Object>>(1);

            var actual = assertThrows(IllegalStateException.class, () -> relationships.forEachTarget(source, fixture.type, target -> {
                    removed.add(target);
                    assertThrows(IllegalStateException.class,
                        () -> relationships.removeTarget(fixture.store, source, fixture.type, target));
                    assertEquals(2, relationships.getTargetCount(source, fixture.type));
                    throw expected;
                }));

            assertSame(expected, actual);
            assertEquals(2, relationships.getTargetCount(source, fixture.type));
            assertEquals(1, relationships.getIncomingCount(removed.getFirst(), fixture.type));
            assertEquals(1, relationships.getIncomingCount(removedTarget, fixture.type));
            assertEquals(1, relationships.getIncomingCount(retainedTarget, fixture.type));
        }
    }

    @Test
    void rejectedMutationsDoNotCarryLaterWorkIntoAnotherTraversal() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var oldTarget = fixture.addEntity();
            var conflictingTarget = fixture.addEntity();
            var retainedTarget = fixture.addEntity();
            relationships.addTarget(fixture.store, source, fixture.type, oldTarget, new LinkData(1));
            relationships.addTarget(fixture.store, source, fixture.type, conflictingTarget, new LinkData(2));
            relationships.addTarget(fixture.store, source, fixture.type, retainedTarget, new LinkData(3));
            var attempted = new boolean[1];

            relationships.forEachTarget(source, fixture.type, ignored -> {
                    if (!attempted[0]) {
                        attempted[0] = true;
                        assertThrows(IllegalStateException.class, () -> relationships.retarget(fixture.store, source, fixture.type, oldTarget, conflictingTarget));
                        assertThrows(IllegalStateException.class,
                            () -> relationships.removeTarget(fixture.store, source, fixture.type, retainedTarget));
                    }
                });

            assertTrue(attempted[0]);

            relationships.forEachTarget(source, fixture.type, ignored -> {
            });
            assertEquals(3, relationships.getTargetCount(source, fixture.type));
            assertEquals(1, relationships.getIncomingCount(retainedTarget, fixture.type));
        }
    }

    @Test
    void nativeCallbackTraversalReadsCommittedLinksWhileTheOuterTraversalRejectsCommands() {
        var registry = new ComponentRegistry<Object>();
        try {
            var types = new RelationshipTypeRegistry<>(registry);
            var type = register(types, "drain-callback", RelationshipRules.TargetDeletion.PRESERVE_SOURCE);
            var listener = new TraversingAddListener(type);
            registry.registerSystem(listener);
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var outerSource = addEntity(store);
            var firstOuterTarget = addEntity(store);
            var secondOuterTarget = addEntity(store);
            var readSource = addEntity(store);
            var readTarget = addEntity(store);
            var removedSource = addEntity(store);
            var removedTarget = addEntity(store);
            var addedSource = addEntity(store);
            var addedTarget = addEntity(store);
            relationships.addTarget(store, outerSource, type, firstOuterTarget, new LinkData(1));
            relationships.addTarget(store, outerSource, type, secondOuterTarget, new LinkData(2));
            relationships.addTarget(store, readSource, type, readTarget, new LinkData(3));
            relationships.addTarget(store, removedSource, type, removedTarget, new LinkData(4));
            listener.addedSource = addedSource;
            listener.readSource = readSource;
            listener.pendingSource = removedSource;
            listener.pendingTarget = removedTarget;
            listener.armed = true;
            var attempted = new boolean[1];

            relationships.forEachTarget(outerSource, type, ignored -> {
                if (!attempted[0]) {
                    attempted[0] = true;
                    assertThrows(IllegalStateException.class,
                        () -> relationships.addTarget(store, addedSource, type, addedTarget, new LinkData(5)));
                    assertThrows(IllegalStateException.class,
                        () -> relationships.removeTarget(store, removedSource, type, removedTarget));
                }
            });

            assertTrue(attempted[0]);

            relationships.addTarget(store, addedSource, type, addedTarget, new LinkData(5));
            assertEquals(List.of(readTarget), listener.observedReadTargets);
            assertSame(removedTarget, listener.observedPendingTarget);
            assertEquals(1, listener.observedPendingIncomingCount);
            assertSame(addedTarget, relationships.getFirstTarget(addedSource, type));
            assertEquals(1, relationships.getIncomingCount(addedTarget, type));
            relationships.removeTarget(store, removedSource, type, removedTarget);
            assertNull(relationships.getFirstTarget(removedSource, type));
            assertEquals(0, relationships.getIncomingCount(removedTarget, type));
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void tickingVisitsEveryMultipleTarget() {
        var registry = new ComponentRegistry<Object>();
        try {
            var types = new RelationshipTypeRegistry<>(registry);
            var type = register(types, "ticks", RelationshipRules.TargetDeletion.PRESERVE_SOURCE);
            var system = new RecordingSystem(type);
            registry.registerSystem(system);
            var store = registry.addStore(new Object(), EmptyResourceStorage.get());
            var source = addEntity(store);
            var first = addEntity(store);
            var second = addEntity(store);
            var third = addEntity(store);
            relationships.addTarget(store, source, type, first, new LinkData(1));
            relationships.addTarget(store, source, type, second, new LinkData(2));
            relationships.addTarget(store, source, type, third, new LinkData(3));

            store.tick(0.05f);

            assertEquals(3, system.targets.size());
            assertEquals(new HashSet<>(List.of(first, second, third)), new HashSet<>(system.targets));
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @Tag("slow")
    void repeatedGrowthToTenThousandTargetsAndBackClearsMovedEntries() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var targets = new ArrayList<Ref<Object>>(10_000);
            for (int i = 0; i < 10_000; i++) {
                targets.add(fixture.addEntity());
            }

            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < targets.size(); i++) {
                    relationships.putTarget(fixture.store, source, fixture.type, targets.get(i), new LinkData(pass * 10_000 + i));
                }
                assertEquals(10_000, relationships.getTargetCount(source, fixture.type));
                var removalOrder = new ArrayList<>(targets.subList(1, targets.size()));
                Collections.shuffle(removalOrder, new Random(91_027 + pass));
                for (var target : removalOrder) {
                    relationships.removeTarget(fixture.store, source, fixture.type, target);
                }
                assertEquals(1, relationships.getTargetCount(source, fixture.type));
                assertSame(targets.get(0), relationships.getFirstTarget(source, fixture.type));
            }
        }
    }

    @Test
    @Tag("slow")
    void shrinkingALargeIncomingGroupLeavesOneSource() {
        try (var fixture = new Fixture()) {
            var sharedTarget = fixture.addEntity();
            var sources = new ArrayList<Ref<Object>>(10_000);
            for (int i = 0; i < 10_000; i++) {
                var incomingSource = fixture.addEntity();
                sources.add(incomingSource);
                relationships.addTarget(fixture.store, incomingSource, fixture.type, sharedTarget, new LinkData(i));
            }
            var removalOrder = new ArrayList<>(sources.subList(1, sources.size()));
            Collections.shuffle(removalOrder, new Random(19_443));

            for (var incomingSource : removalOrder) {
                relationships.removeTarget(fixture.store, incomingSource, fixture.type, sharedTarget);
            }

            assertEquals(1, relationships.getIncomingCount(sharedTarget, fixture.type));
            var remaining = new ArrayList<Ref<Object>>();
            relationships.forEachIncomingSource(sharedTarget, fixture.type, remaining::add);
            assertEquals(List.of(sources.get(0)), remaining);
        }
    }

    private static void assertGraph(
        GenericRelationshipType<Object, Object, LinkData> type,
        List<Ref<Object>> refs,
        IdentityHashMap<Ref<Object>, IdentityHashMap<Ref<Object>, LinkData>> expected
    ) {
        for (var source : refs) {
            var links = expected.get(source);
            var actualTargets = new HashSet<Ref<Object>>();
            relationships.forEachTarget(source, type, actualTargets::add);
            assertEquals(links == null ? 0 : links.size(), relationships.getTargetCount(source, type));
            assertEquals(links == null ? Collections.emptySet() : links.keySet(), actualTargets);
            if (links == null) {
                assertNull(relationships.getFirstTarget(source, type));
            } else {
                assertTrue(links.containsKey(relationships.getFirstTarget(source, type)));
                links.forEach((target, data) -> assertSame(data, relationships.getData(source, type, target)));
            }
        }
        for (var target : refs) {
            var expectedSources = new HashSet<Ref<Object>>();
            expected.forEach((source, links) -> {
                if (links.containsKey(target)) {
                    expectedSources.add(source);
                }
            });
            var actualSources = new HashSet<Ref<Object>>();
            relationships.forEachIncomingSource(target, type, actualSources::add);
            assertEquals(expectedSources, actualSources);
            assertEquals(expectedSources.size(), relationships.getIncomingCount(target, type));
        }
    }

    @SafeVarargs
    private static void assertTargets(
        GenericRelationshipType<Object, Object, LinkData> type,
        Ref<Object> source,
        Ref<Object>... expected
    ) {
        var actual = new HashSet<Ref<Object>>();
        relationships.forEachTarget(source, type, actual::add);
        assertEquals(new HashSet<>(List.of(expected)), actual);
    }

    private static GenericRelationshipType<Object, Object, LinkData> register(
        RelationshipTypeRegistry<Object> types,
        String name,
        RelationshipRules.TargetDeletion targetDeletion
    ) {
        var rules = RelationshipRules.multiple();
        if (targetDeletion == RelationshipRules.TargetDeletion.CASCADE_SOURCE) rules = rules.cascadeSource();
        return types.registerRelationship("relwind:test/" + name, LinkData.class, null, rules);
    }

    private static Ref<Object> addEntity(Store<Object> store) {
        return Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
    }

    private static List<Ref<Object>> targetsSharingOneForwardBucket(Fixture fixture, int count) {
        var buckets = new ArrayList<List<Ref<Object>>>(32);
        for (int i = 0; i < 32; i++) {
            buckets.add(new ArrayList<>());
        }
        for (int i = 0; i < 513; i++) {
            var candidate = fixture.addEntity();
            buckets.get(HashCommon.mix(System.identityHashCode(candidate)) & 31).add(candidate);
        }
        return buckets.stream().filter(bucket -> bucket.size() >= count).findFirst().orElseThrow();
    }

    private static WeakReference<Store<Object>> createUnloadedStore(
        ComponentRegistry<Object> registry,
        GenericRelationshipType<Object, Object, LinkData> type
    ) {
        var store = registry.addStore(new Object(), EmptyResourceStorage.get());
        var source = addEntity(store);
        var target = addEntity(store);
        relationships.addTarget(store, source, type, target, new LinkData(1));
        relationships.forEachTarget(source, type, ignored -> {
        });
        store.shutdown();
        return new WeakReference<>(store);
    }

    private static final class Fixture implements AutoCloseable {
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final RelationshipTypeRegistry<Object> types = new RelationshipTypeRegistry<>(registry);
        private final GenericRelationshipType<Object, Object, LinkData> type = register(
            types,
            "multiple",
            RelationshipRules.TargetDeletion.PRESERVE_SOURCE
        );
        private final Store<Object> store = registry.addStore(new Object(), EmptyResourceStorage.get());

        private Ref<Object> addEntity() {
            return MultipleTargetRelationshipsTest.addEntity(store);
        }

        @Override
        public void close() {
            registry.shutdown();
        }
    }

    private static final class RecordingSystem extends RelationshipTickingSystem<Object, LinkData> {
        private final RelationshipQuery.Definition<Object, LinkData> query;
        private final List<Ref<Object>> targets = new ArrayList<>();

        private RecordingSystem(GenericRelationshipType<Object, Object, LinkData> type) {
            this.query = RelationshipQuery.of(Query.any(), type);
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, LinkData> getQuery() {
            return query;
        }

        @Override
        protected void tickRelationship(
            float seconds,
            RelationshipResult<Object, LinkData> result,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            targets.add(result.getTarget());
        }
    }

    private static final class TraversingAddListener
        extends RefChangeSystem<Object, OutgoingLink<Object, Object>> {
        private final GenericRelationshipType<Object, Object, LinkData> type;
        private Ref<Object> addedSource;
        private Ref<Object> readSource;
        private Ref<Object> pendingSource;
        private Ref<Object> pendingTarget;
        private boolean armed;
        private List<Ref<Object>> observedReadTargets;
        private Ref<Object> observedPendingTarget;
        private int observedPendingIncomingCount = -1;

        private TraversingAddListener(GenericRelationshipType<Object, Object, LinkData> type) {
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
            if (!armed || ref != addedSource) {
                return;
            }
            armed = false;
            var visited = new ArrayList<Ref<Object>>();
            relationships.forEachTarget(readSource, type, visited::add);
            observedReadTargets = visited;
            observedPendingTarget = relationships.getFirstTarget(pendingSource, type);
            observedPendingIncomingCount = relationships.getIncomingCount(pendingTarget, type);
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
        }
    }

    private record LinkData(int value) {
    }
}
