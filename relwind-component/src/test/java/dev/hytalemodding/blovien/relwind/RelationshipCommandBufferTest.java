/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;


import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A command takes its Store from the accessor it is given: the Store runs it now, and a
/// CommandBuffer queues it and skips linked entities no longer valid when the buffer is consumed.
class RelationshipCommandBufferTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void aCommandBufferQueuesTheCommandUntilTheTickConsumesIt() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            relationships.addTarget(fixture.store, source, fixture.type, target, new LinkData("initial"));
            fixture.action.action = (store, commandBuffer) -> {
                relationships.tryRemoveTarget(commandBuffer, source, fixture.type, target);
                // reads exclude buffered changes
                assertSame(target, relationships.getFirstTarget(source, fixture.type));
            };

            fixture.store.tick(0.05f);

            assertNull(relationships.getFirstTarget(source, fixture.type));
        }
    }

    @Test
    void aQueuedCommandRejectsAnAccessorOrLinkedEntityFromAnotherStore() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            var otherStore = fixture.registry.addStore(new Object(), EmptyResourceStorage.get());
            var other = Objects.requireNonNull(otherStore.addEntity(Archetype.empty(), AddReason.SPAWN));
            relationships.addTarget(fixture.store, source, fixture.type, target, new LinkData("initial"));
            fixture.action.action = (store, commandBuffer) -> {
                relationships.tryRemoveTarget(commandBuffer, source, fixture.type, target);
                assertThrows(IllegalArgumentException.class,
                    () -> relationships.tryRemoveTarget(commandBuffer, other, fixture.type, other));
                assertThrows(IllegalArgumentException.class,
                    () -> relationships.tryRemoveTarget(otherStore, source, fixture.type, target));
                assertThrows(IllegalArgumentException.class,
                    () -> relationships.tryRemoveTarget(unknownAccessor(), source, fixture.type, target));
            };

            fixture.store.tick(0.05f);

            assertNull(relationships.getFirstTarget(source, fixture.type));
        }
    }

    /// A command rejects an accessor that is neither a Store nor a CommandBuffer before calling it.
    @SuppressWarnings("unchecked")
    private static ComponentAccessor<Object> unknownAccessor() {
        return (ComponentAccessor<Object>) Proxy.newProxyInstance(
            ComponentAccessor.class.getClassLoader(),
            new Class<?>[] {ComponentAccessor.class},
            (proxy, method, arguments) -> {
                throw new UnsupportedOperationException(method.getName());
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ordinary", "buffer", "traversal"})
    void logicalObserversRejectOrdinaryCommandsAndRunBufferedFollowUpsAfterDispatch(String dispatch) {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            var destination = fixture.addEntity();
            var otherSource = fixture.addEntity();
            var otherTarget = fixture.addEntity();
            var otherType = new RelationshipTypeRegistry<>(fixture.registry).registerRelationship(
                LinkData.class,
                RelationshipTraits.defaults().exclusive());
            var initial = new LinkData("initial");
            var replacement = new LinkData("replacement");
            var trace = new ArrayList<String>();
            fixture.registry.registerSystem(new RelationshipChangeSystem<Object, LinkData>(otherType) {
                @Override
                protected void onRelationshipAdded(
                    LinkedEntity<Object> from,
                    LinkedEntity<Object> to,
                    LinkData data,
                    Store<Object> store,
                    CommandBuffer<Object> buffer
                ) {
                    assertEquals(List.of("first", "second", "put", "buffer retarget", "second retarget", "remove"), trace);
                    assertSame(otherSource, from.reference());
                    assertSame(otherTarget, to.reference());
                    trace.add("other observer");
                }
            });
            fixture.registry.registerSystem(new SecondLogicalObserver(fixture.type, (from, buffer) -> {
                if (from != source) return;
                trace.add("second");
                assertSame(initial, relationships.getData(source, fixture.type, target));
                assertEquals(1, relationships.getIncomingCount(target, fixture.type));
                assertEquals(0, relationships.getTargetCount(otherSource, otherType));
            }));
            fixture.registry.registerSystem(new FirstLogicalObserver(fixture.type, (from, buffer) -> {
                if (from != source) return;
                trace.add("first");
                assertThrows(IllegalStateException.class,
                    () -> relationships.putTarget(fixture.store, source, fixture.type, target, replacement));
                relationships.putTarget(buffer, source, fixture.type, target, replacement);
                buffer.run(store -> {
                    assertSame(replacement, relationships.getData(source, fixture.type, target));
                    trace.add("put");
                });
                relationships.retarget(buffer, source, fixture.type, target, destination);
                buffer.run(store -> {
                    assertSame(destination, relationships.getFirstTarget(source, fixture.type));
                    assertEquals(0, relationships.getIncomingCount(target, fixture.type));
                    trace.add("buffer retarget");
                });
                relationships.retarget(buffer, source, fixture.type, destination, target);
                buffer.run(store -> {
                    assertSame(target, relationships.getFirstTarget(source, fixture.type));
                    trace.add("second retarget");
                });
                relationships.removeTarget(buffer, source, fixture.type, target);
                buffer.run(store -> {
                    assertNull(relationships.getFirstTarget(source, fixture.type));
                    trace.add("remove");
                });
                relationships.addTarget(buffer, otherSource, otherType, otherTarget, initial);
                buffer.run(store -> {
                    assertSame(otherTarget, relationships.getFirstTarget(otherSource, otherType));
                    assertEquals(1, relationships.getIncomingCount(otherTarget, otherType));
                    trace.add("other add");
                });
                relationships.putTarget(buffer, otherSource, otherType, otherTarget, replacement);
                buffer.run(store -> {
                    assertSame(replacement, relationships.getData(otherSource, otherType, otherTarget));
                    trace.add("other put");
                });
            }));

            dispatchFirstAdd(dispatch, fixture, source, target, initial);

            assertEquals(List.of("first", "second", "put", "buffer retarget", "second retarget",
                "remove", "other observer", "other add", "other put"), trace, dispatch);
        }
    }

    private static void dispatchFirstAdd(
        String dispatch,
        Fixture fixture,
        Ref<Object> source,
        Ref<Object> target,
        LinkData initial
    ) {
        switch (dispatch) {
            case "ordinary" -> relationships.addTarget(fixture.store, source, fixture.type, target, initial);
            case "buffer" -> {
                fixture.action.action = (store, buffer) -> relationships.addTarget(buffer, source, fixture.type, target, initial);
                fixture.store.tick(0.05f);
            }
            case "traversal" -> {
                var seed = fixture.addEntity();
                relationships.addTarget(fixture.store, seed, fixture.type, fixture.addEntity());
                relationships.forEachTarget(seed, fixture.type, ignored -> assertThrows(
                    IllegalStateException.class,
                    () -> relationships.addTarget(fixture.store, source, fixture.type, target, initial)));
                relationships.addTarget(fixture.store, source, fixture.type, target, initial);
            }
            default -> throw new AssertionError(dispatch);
        }
    }

    @Test
    void logicalCallbackFailureStopsObserversAndCommandsAfterTheCommittedChange() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            var data = new LinkData("initial");
            var trace = new ArrayList<String>();
            var failure = new CallbackFailure();
            var first = new FirstLogicalObserver(fixture.type, (from, buffer) -> {
                trace.add("first");
                assertSame(target, relationships.getFirstTarget(source, fixture.type));
                assertEquals(1, relationships.getIncomingCount(target, fixture.type));
                relationships.removeTarget(buffer, source, fixture.type, target);
                buffer.run(store -> trace.add("buffer"));
                data.value = "edited before failure";
                throw failure;
            });
            fixture.registry.registerSystem(new SecondLogicalObserver(fixture.type, (from, buffer) -> trace.add("second")));
            fixture.registry.registerSystem(first);
            fixture.action.action = (store, buffer) -> {
                relationships.addTarget(buffer, source, fixture.type, target, data);
                buffer.run(ignored -> trace.add("later command"));
            };

            assertSame(failure, assertThrows(CallbackFailure.class, () -> fixture.store.tick(0.05f)));

            assertEquals(List.of("first"), trace);
            assertSame(target, relationships.getFirstTarget(source, fixture.type));
            assertEquals(1, relationships.getIncomingCount(target, fixture.type));
            assertSame(data, relationships.getData(source, fixture.type, target));
            assertEquals("edited before failure", data.value);
            relationships.removeTarget(fixture.store, source, fixture.type, target);
            assertEquals(List.of("first"), trace);
        }
    }

    private static class SecondLogicalObserver extends RelationshipChangeSystem<Object, LinkData> {
        private final BiConsumer<Ref<Object>, CommandBuffer<Object>> action;

        private SecondLogicalObserver(
            RelationshipType<Object, LinkData> type,
            BiConsumer<Ref<Object>, CommandBuffer<Object>> action
        ) {
            super(type);
            this.action = action;
        }

        @Override
        protected void onRelationshipAdded(
            LinkedEntity<Object> source,
            LinkedEntity<Object> target,
            LinkData data,
            Store<Object> store,
            CommandBuffer<Object> buffer
        ) {
            action.accept(source.reference(), buffer);
        }
    }

    private static final class FirstLogicalObserver extends SecondLogicalObserver {
        private FirstLogicalObserver(
            RelationshipType<Object, LinkData> type,
            BiConsumer<Ref<Object>, CommandBuffer<Object>> action
        ) {
            super(type, action);
        }

        @Override
        public Set<Dependency<Object>> getDependencies() {
            return Set.of(new SystemDependency<>(Order.BEFORE, SecondLogicalObserver.class));
        }
    }

    @Test
    void tickReadsCommittedStateUntilBufferedMutationsRunInOrder() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var oldTarget = fixture.addEntity();
            var newTarget = fixture.addEntity();
            var oldData = new LinkData("old");
            var newData = new LinkData("new");
            relationships.addTarget(fixture.store, source, fixture.type, oldTarget, oldData);
            fixture.action.action = (store, commandBuffer) -> {
                assertSame(oldTarget, relationships.getFirstTarget(source, fixture.type));
                relationships.removeTarget(commandBuffer, source, fixture.type, oldTarget);
                relationships.addTarget(commandBuffer, source, fixture.type, newTarget, newData);
                assertSame(oldTarget, relationships.getFirstTarget(source, fixture.type));
                assertEquals(1, relationships.getIncomingCount(oldTarget, fixture.type));
                assertEquals(0, relationships.getIncomingCount(newTarget, fixture.type));
            };

            fixture.store.tick(0.05f);

            assertSame(newTarget, relationships.getFirstTarget(source, fixture.type));
            assertSame(newData, relationships.getData(source, fixture.type, newTarget));
            assertEquals(0, relationships.getIncomingCount(oldTarget, fixture.type));
            assertEquals(1, relationships.getIncomingCount(newTarget, fixture.type));
        }
    }

    @Test
    void rejectedQueuedOperationChangesNeitherDirection() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var currentTarget = fixture.addEntity();
            var rejectedTarget = fixture.addEntity();
            var data = new LinkData("current");
            relationships.addTarget(fixture.store, source, fixture.type, currentTarget, data);
            fixture.action.action = (store, commandBuffer) -> relationships.addTarget(commandBuffer, source, fixture.type, rejectedTarget, new LinkData("rejected"));

            assertThrows(IllegalStateException.class, () -> fixture.store.tick(0.05f));

            assertSame(currentTarget, relationships.getFirstTarget(source, fixture.type));
            assertSame(data, relationships.getData(source, fixture.type, currentTarget));
            assertEquals(1, relationships.getIncomingCount(currentTarget, fixture.type));
            assertEquals(0, relationships.getIncomingCount(rejectedTarget, fixture.type));
        }
    }

    @Test
    void queuedPutRetargetAndTrimApplyOnlyWhenTheirTurnIsConsumed() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var oldTarget = fixture.addEntity();
            var newTarget = fixture.addEntity();
            var oldData = new LinkData("old");
            var newData = new LinkData("new");
            relationships.addTarget(fixture.store, source, fixture.type, oldTarget, oldData);
            fixture.action.action = (store, commandBuffer) -> {
                relationships.putTarget(commandBuffer, source, fixture.type, oldTarget, newData);
                relationships.retarget(commandBuffer, source, fixture.type, oldTarget, newTarget);
                // a queued trim reports that the storage is not released yet
                assertFalse(relationships.trimIncomingLinks(commandBuffer, oldTarget, fixture.type));
                assertFalse(relationships.trimSourceStorage(commandBuffer, source, fixture.type));
                assertSame(oldTarget, relationships.getFirstTarget(source, fixture.type));
                assertSame(oldData, relationships.getData(source, fixture.type, oldTarget));
                assertTrue(store.getArchetype(oldTarget).contains(fixture.type.getIncomingType()));
            };

            fixture.store.tick(0.05f);

            assertSame(newTarget, relationships.getFirstTarget(source, fixture.type));
            assertSame(newData, relationships.getData(source, fixture.type, newTarget));
            assertFalse(fixture.store.getArchetype(oldTarget).contains(fixture.type.getIncomingType()));
            assertEquals(1, relationships.getIncomingCount(newTarget, fixture.type));
        }
    }

    @Test
    void aQueuedTrimChecksItsLinkedEntityAtSubmissionAndSkipsAnInvalidatedOne() {
        try (var fixture = new Fixture(); var foreign = new Fixture()) {
            var unloadingTarget = fixture.addEntity();
            var unloadingSource = fixture.addEntity();
            var liveSource = fixture.addEntity();
            var liveTarget = fixture.addEntity();
            var otherStore = fixture.registry.addStore(new Object(), EmptyResourceStorage.get());
            var other = Objects.requireNonNull(otherStore.addEntity(Archetype.empty(), AddReason.SPAWN));

            fixture.action.action = (store, commandBuffer) -> {
                assertThrows(IllegalArgumentException.class,
                    () -> relationships.trimIncomingLinks(commandBuffer, other, fixture.type));
                assertThrows(IllegalArgumentException.class,
                    () -> relationships.trimSourceStorage(commandBuffer, other, fixture.type));
                assertThrows(IllegalArgumentException.class,
                    () -> relationships.trimIncomingLinks(commandBuffer, unloadingTarget, foreign.type));

                commandBuffer.removeEntity(unloadingTarget, RemoveReason.REMOVE);
                commandBuffer.removeEntity(unloadingSource, RemoveReason.REMOVE);
                relationships.trimIncomingLinks(commandBuffer, unloadingTarget, fixture.type);
                relationships.trimSourceStorage(commandBuffer, unloadingSource, fixture.type);
                relationships.addTarget(commandBuffer, liveSource, fixture.type, liveTarget, new LinkData("later"));
            };
            fixture.store.tick(0.05f);

            assertFalse(unloadingTarget.isValid());
            assertFalse(unloadingSource.isValid());
            assertSame(liveTarget, relationships.getFirstTarget(liveSource, fixture.type));
        }
    }

    @Test
    void callbackFailureKeepsCompletedChangesAndDoesNotRollBackRawPayloadEdits() {
        try (var fixture = new Fixture()) {
            var listener = new FailingAddListener(fixture.type);
            fixture.registry.registerSystem(listener);
            var retainedSource = fixture.addEntity();
            var retainedTarget = fixture.addEntity();
            var addedSource = fixture.addEntity();
            var addedTarget = fixture.addEntity();
            var retainedData = new LinkData("before");
            relationships.addTarget(fixture.store, retainedSource, fixture.type, retainedTarget, retainedData);
            listener.expectedSource = addedSource;
            listener.expectedTarget = addedTarget;
            listener.armed = true;
            fixture.action.action = (store, commandBuffer) -> {
                retainedData.value = "edited";
                relationships.addTarget(commandBuffer, addedSource, fixture.type, addedTarget, new LinkData("added"));
                relationships.removeTarget(commandBuffer, retainedSource, fixture.type, retainedTarget);
            };

            assertThrows(CallbackFailure.class, () -> fixture.store.tick(0.05f));

            assertSame(addedTarget, listener.observedTarget);
            assertEquals(1, listener.observedIncomingCount);
            assertEquals("edited", retainedData.value);
            assertSame(retainedTarget, relationships.getFirstTarget(retainedSource, fixture.type));
            assertEquals(1, relationships.getIncomingCount(retainedTarget, fixture.type));
            assertSame(addedTarget, relationships.getFirstTarget(addedSource, fixture.type));
            assertEquals(1, relationships.getIncomingCount(addedTarget, fixture.type));
        }
    }

    @Test
    void componentCallbackCannotCommandThroughItsBufferDuringARelationshipChange() {
        try (var fixture = new Fixture()) {
            var firstSource = fixture.addEntity();
            var firstTarget = fixture.addEntity();
            var secondSource = fixture.addEntity();
            var secondTarget = fixture.addEntity();
            var listener = new ReentrantAddListener(
                fixture.type,
                firstSource,
                firstTarget,
                secondSource,
                secondTarget
            );
            fixture.registry.registerSystem(listener);
            listener.armed = true;
            fixture.action.action = (store, commandBuffer) -> relationships.addTarget(commandBuffer, firstSource, fixture.type, firstTarget, new LinkData("first"));

            var rejected = assertThrows(IllegalStateException.class, () -> fixture.store.tick(0.05f));

            assertTrue(rejected.getMessage().contains("CommandBuffer"), rejected.getMessage());
            assertSame(firstTarget, listener.observedFirstTarget);
            assertEquals(1, listener.observedFirstIncomingCount);
            assertNull(listener.observedSecondTarget);
            assertEquals(0, listener.observedSecondIncomingCount);
            assertNull(relationships.getFirstTarget(secondSource, fixture.type));
            assertEquals(0, relationships.getIncomingCount(secondTarget, fixture.type));
        }
    }

    @Test
    void confirmedDeletionCanBeQueuedThroughTheNativeCommandBuffer() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            relationships.addTarget(fixture.store, source, fixture.type, target, new LinkData("linked"));
            fixture.action.action = (store, commandBuffer) -> {
                commandBuffer.removeEntity(target, RemoveReason.REMOVE);
                assertTrue(target.isValid());
                assertSame(target, relationships.getFirstTarget(source, fixture.type));
            };

            fixture.store.tick(0.05f);

            assertFalse(target.isValid());
            assertTrue(source.isValid());
            assertNull(relationships.getFirstTarget(source, fixture.type));
        }
    }

    @Test
    void ordinaryMutationFromAnotherThreadIsRejectedByTheThreadRule() {
        try (var fixture = new Fixture(); var worker = Executors.newSingleThreadExecutor()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            var otherTarget = fixture.addEntity();
            var data = new LinkData("current");
            relationships.addTarget(fixture.store, source, fixture.type, target, data);

            var check = worker.submit(() -> {
                var add = assertThrows(IllegalStateException.class,
                    () -> relationships.addTarget(fixture.store, source, fixture.type, target, data));
                assertTrue(add.getMessage().contains("Assert not in thread"), add.getMessage());
                var put = assertThrows(IllegalStateException.class,
                    () -> relationships.putTarget(fixture.store, source, fixture.type, target, data));
                assertTrue(put.getMessage().contains("Assert not in thread"), put.getMessage());
                var retarget = assertThrows(IllegalStateException.class,
                    () -> relationships.retarget(fixture.store, source, fixture.type, target, otherTarget));
                assertTrue(retarget.getMessage().contains("Assert not in thread"), retarget.getMessage());
                var remove = assertThrows(IllegalStateException.class,
                    () -> relationships.removeTarget(fixture.store, source, fixture.type, target));
                assertTrue(remove.getMessage().contains("Assert not in thread"), remove.getMessage());
            });
            assertDoesNotThrow(() -> check.get(5, TimeUnit.SECONDS));

            assertSame(target, relationships.getFirstTarget(source, fixture.type));
            assertSame(data, relationships.getData(source, fixture.type, target));
            assertEquals(1, relationships.getIncomingCount(target, fixture.type));
            assertEquals(0, relationships.getIncomingCount(otherTarget, fixture.type));
        }
    }

    @Test
    void invalidatedTypeIsNotSilentlySkippedWithAnInvalidatedLinkedEntity() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();

            var rejected = assertThrows(IllegalStateException.class, () -> fixture.store.forEachChunk((chunk, commandBuffer) -> {
                commandBuffer.removeEntity(target, RemoveReason.REMOVE);
                commandBuffer.run(ignored -> fixture.types.unregisterRelationship(fixture.type));
                relationships.addTarget(commandBuffer, source, fixture.type, target);
                return true;
            }));

            assertTrue(rejected.getMessage().contains("ComponentType is invalid"), rejected.getMessage());
            assertFalse(fixture.store.getArchetype(source).contains(fixture.type.getSourceType()));
            assertFalse(target.isValid());
        }
    }

    @Test
    void workerLocalNativeBufferAcceptsMutationsWithoutStoreThreadAccess() {
        try (var fixture = new Fixture(); var worker = Executors.newSingleThreadExecutor()) {
            var source = fixture.addEntity();
            var first = fixture.addEntity();
            var second = fixture.addEntity();
            var replacement = new LinkData("replacement");
            fixture.action.action = (store, commandBuffer) -> {
                var buffer = commandBuffer.fork();
                var check = worker.submit(() -> {
                    assertFalse(store.isInThread());
                    // native parallel callbacks set the buffer thread after the Store forks it
                    assert buffer.setThread();
                    relationships.addTarget(buffer, source, fixture.type, first, new LinkData("initial"));
                    relationships.putTarget(buffer, source, fixture.type, first, replacement);
                    relationships.retarget(buffer, source, fixture.type, first, second);
                    relationships.removeTarget(buffer, source, fixture.type, second);
                    relationships.addTarget(buffer, source, fixture.type, first, replacement);
                });
                assertDoesNotThrow(() -> check.get(5, TimeUnit.SECONDS));
                buffer.mergeParallel(commandBuffer);
                assertNull(relationships.getFirstTarget(source, fixture.type));
                assertEquals(0, relationships.getIncomingCount(first, fixture.type));
                assertEquals(0, relationships.getIncomingCount(second, fixture.type));
            };

            fixture.store.tick(0.05f);

            assertSame(first, relationships.getFirstTarget(source, fixture.type));
            assertSame(replacement, relationships.getData(source, fixture.type, first));
            assertEquals(1, relationships.getIncomingCount(first, fixture.type));
            assertEquals(0, relationships.getIncomingCount(second, fixture.type));
        }
    }

    @Test
    void queuedMutationCanUseEntitiesAddedEarlierInTheSameNativeBuffer() {
        try (var fixture = new Fixture()) {
            var created = new ArrayList<Ref<Object>>();
            fixture.action.action = (store, commandBuffer) -> {
                var source = commandBuffer.addEntity(fixture.registry.newHolder(), AddReason.SPAWN);
                var target = commandBuffer.addEntity(fixture.registry.newHolder(), AddReason.SPAWN);
                assertFalse(source.isValid());
                assertFalse(target.isValid());
                relationships.addTarget(commandBuffer, source, fixture.type, target);
                created.add(source);
                created.add(target);
            };

            fixture.store.tick(0.05f);

            assertSame(created.get(1), relationships.getFirstTarget(created.get(0), fixture.type));
            assertEquals(1, relationships.getIncomingCount(created.get(1), fixture.type));
        }
    }

    @Test
    void anOrdinaryCommandInATraversalThrowsAndNamesTheBufferOverloadsAndFetch() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var first = fixture.addEntity();
            var initial = new LinkData("initial");
            var replacement = new LinkData("replacement");
            relationships.addTarget(fixture.store, source, fixture.type, first, initial);

            relationships.forEachTarget(source, fixture.type, ignored -> {
                var rejected = assertThrows(IllegalStateException.class,
                    () -> relationships.addTarget(fixture.store, source, fixture.type, first, replacement));
                assertTrue(rejected.getMessage().contains("CommandBuffer"), rejected.getMessage());
                assertTrue(rejected.getMessage().contains("Relationships.fetch"), rejected.getMessage());
                assertSame(first, relationships.getFirstTarget(source, fixture.type));
                assertSame(initial, relationships.getData(source, fixture.type, first));
                assertEquals(1, relationships.getIncomingCount(first, fixture.type));
            });

            assertSame(first, relationships.getFirstTarget(source, fixture.type));
            assertSame(initial, relationships.getData(source, fixture.type, first));
            assertEquals(1, relationships.getIncomingCount(first, fixture.type));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"add", "put", "remove", "retarget"})
    void aBufferedMutationWithAnInvalidatedSourceLeavesItsTargetUnlinked(String operation) {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            var destination = fixture.addEntity();
            var laterSource = fixture.addEntity();
            var laterTarget = fixture.addEntity();
            relationships.addTarget(fixture.store, source, fixture.type, target, new LinkData("initial"));
            fixture.action.action = (store, buffer) -> {
                buffer.removeEntity(source, RemoveReason.REMOVE);
                applyBufferedMutation(operation, buffer, fixture, source, target, destination);
                relationships.addTarget(buffer, laterSource, fixture.type, laterTarget);
            };

            assertDoesNotThrow(() -> fixture.store.tick(0.05f), operation);

            assertFalse(source.isValid());
            assertEquals(0, relationships.getIncomingCount(target, fixture.type));
            assertSame(laterTarget, relationships.getFirstTarget(laterSource, fixture.type));
            assertEquals(1, relationships.getIncomingCount(laterTarget, fixture.type));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"add", "put", "remove", "retarget"})
    void aBufferedMutationWithAnInvalidatedTargetLeavesItsSourceUnlinked(String operation) {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            var destination = fixture.addEntity();
            var laterSource = fixture.addEntity();
            var laterTarget = fixture.addEntity();
            relationships.addTarget(fixture.store, source, fixture.type, target, new LinkData("initial"));
            fixture.action.action = (store, buffer) -> {
                buffer.removeEntity(target, RemoveReason.REMOVE);
                applyBufferedMutation(operation, buffer, fixture, source, target, destination);
                relationships.addTarget(buffer, laterSource, fixture.type, laterTarget);
            };

            assertDoesNotThrow(() -> fixture.store.tick(0.05f), operation);

            assertFalse(target.isValid());
            assertNull(relationships.getFirstTarget(source, fixture.type));
            assertSame(laterTarget, relationships.getFirstTarget(laterSource, fixture.type));
            assertEquals(1, relationships.getIncomingCount(laterTarget, fixture.type));
        }
    }

    @Test
    void aBufferedRetargetWithAnInvalidatedDestinationKeepsTheExistingLink() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            var destination = fixture.addEntity();
            var laterSource = fixture.addEntity();
            var laterTarget = fixture.addEntity();
            var initial = new LinkData("initial");
            relationships.addTarget(fixture.store, source, fixture.type, target, initial);
            fixture.action.action = (store, buffer) -> {
                buffer.removeEntity(destination, RemoveReason.REMOVE);
                relationships.retarget(buffer, source, fixture.type, target, destination);
                relationships.addTarget(buffer, laterSource, fixture.type, laterTarget);
            };

            assertDoesNotThrow(() -> fixture.store.tick(0.05f));

            assertFalse(destination.isValid());
            assertSame(target, relationships.getFirstTarget(source, fixture.type));
            assertSame(initial, relationships.getData(source, fixture.type, target));
            assertEquals(1, relationships.getIncomingCount(target, fixture.type));
            assertSame(laterTarget, relationships.getFirstTarget(laterSource, fixture.type));
            assertEquals(1, relationships.getIncomingCount(laterTarget, fixture.type));
        }
    }

    private static void applyBufferedMutation(
        String operation,
        CommandBuffer<Object> buffer,
        Fixture fixture,
        Ref<Object> source,
        Ref<Object> target,
        Ref<Object> destination
    ) {
        switch (operation) {
            case "add" -> relationships.addTarget(buffer, source, fixture.type, target);
            case "put" -> relationships.putTarget(buffer, source, fixture.type, target);
            case "remove" -> relationships.removeTarget(buffer, source, fixture.type, target);
            case "retarget" -> relationships.retarget(buffer, source, fixture.type, target, destination);
            default -> throw new AssertionError(operation);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"remove", "retarget"})
    void aBufferedCommandSkipsAnInvalidatedLinkedEntityBeforeCheckingItsPrecondition(String operation) {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var linked = fixture.addEntity();
            var missing = fixture.addEntity();
            var destination = fixture.addEntity();
            var initial = new LinkData("initial");
            relationships.addTarget(fixture.store, source, fixture.type, linked, initial);
            fixture.action.action = (store, buffer) -> {
                buffer.removeEntity(missing, RemoveReason.REMOVE);
                applyMissingPairCommand(operation, buffer, fixture, source, linked, missing, destination);
            };

            assertDoesNotThrow(() -> fixture.store.tick(0.05f), operation);

            assertFalse(missing.isValid());
            assertSame(linked, relationships.getFirstTarget(source, fixture.type));
            assertSame(initial, relationships.getData(source, fixture.type, linked));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"add", "remove", "retarget"})
    void aBufferedCommandOnLiveLinkedEntitiesFailsItsPreconditionAndChangesNothing(String operation) {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var linked = fixture.addEntity();
            var missing = fixture.addEntity();
            var destination = fixture.addEntity();
            var initial = new LinkData("initial");
            relationships.addTarget(fixture.store, source, fixture.type, linked, initial);
            fixture.action.action = (store, buffer) ->
                applyMissingPairCommand(operation, buffer, fixture, source, linked, missing, destination);

            assertThrows(IllegalStateException.class, () -> fixture.store.tick(0.05f), operation);

            assertSame(linked, relationships.getFirstTarget(source, fixture.type));
            assertSame(initial, relationships.getData(source, fixture.type, linked));
        }
    }

    @Test
    void aBufferedTryRemoveAcceptsAMissingPairWhileEveryLinkedEntityIsAlive() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var missing = fixture.addEntity();
            fixture.action.action = (store, buffer) ->
                relationships.tryRemoveTarget(buffer, source, fixture.type, missing);

            assertDoesNotThrow(() -> fixture.store.tick(0.05f));

            assertNull(relationships.getFirstTarget(source, fixture.type));
        }
    }

    private static void applyMissingPairCommand(
        String operation,
        CommandBuffer<Object> buffer,
        Fixture fixture,
        Ref<Object> source,
        Ref<Object> linked,
        Ref<Object> missing,
        Ref<Object> destination
    ) {
        switch (operation) {
            case "add" -> relationships.addTarget(buffer, source, fixture.type, linked);
            case "remove" -> relationships.removeTarget(buffer, source, fixture.type, missing);
            case "retarget" -> relationships.retarget(buffer, source, fixture.type, missing, destination);
            default -> throw new AssertionError(operation);
        }
    }

    @Test
    void deletionInATraversalIsRejectedAndChangesNothing() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            relationships.addTarget(fixture.store, source, fixture.type, target);

            relationships.forEachTarget(source, fixture.type, ignored -> {
                assertThrows(IllegalStateException.class,
                    () -> fixture.store.removeEntity(target, RemoveReason.REMOVE));
                assertTrue(target.isValid());
                assertSame(target, relationships.getFirstTarget(source, fixture.type));
            });

            assertTrue(target.isValid());
            assertSame(target, relationships.getFirstTarget(source, fixture.type));
            fixture.store.removeEntity(target, RemoveReason.REMOVE);
            assertFalse(target.isValid());
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void invalidArgumentsFailBeforeSubmissionEvenForUnchangedPairs() {
        try (var fixture = new Fixture()) {
            var source = fixture.addEntity();
            var target = fixture.addEntity();
            var data = new LinkData("initial");
            var otherStore = fixture.registry.addStore(new Object(), EmptyResourceStorage.get());
            var other = Objects.requireNonNull(otherStore.addEntity(Archetype.empty(), AddReason.SPAWN));
            relationships.addTarget(fixture.store, source, fixture.type, target, data);

            relationships.forEachTarget(source, fixture.type, ignored -> {
                assertThrows(IllegalArgumentException.class,
                    () -> relationships.removeTarget(fixture.store, other, fixture.type, target));
            });
            fixture.action.action = (store, buffer) -> {
                assertThrows(NullPointerException.class, () -> relationships.addTarget(null, source, fixture.type, target, data));
                assertThrows(IllegalArgumentException.class,
                    () -> relationships.putTarget(buffer, source, ((GenericRelationshipType) fixture.type), target, "wrong data"));
            };
            fixture.store.tick(0.05f);

            assertSame(target, relationships.getFirstTarget(source, fixture.type));
            assertSame(data, relationships.getData(source, fixture.type, target));
            assertEquals(1, relationships.getIncomingCount(target, fixture.type));
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final ComponentType<Object, Trigger> triggerType = registry.registerComponent(Trigger.class, Trigger::new);
        private final RelationshipTypeRegistry<Object> types = new RelationshipTypeRegistry<>(registry);
        private final RelationshipType<Object, LinkData> type = types.registerRelationship(
            LinkData.class,
            RelationshipTraits.defaults().exclusive());
        private final ActionSystem action = new ActionSystem(triggerType);
        private final Store<Object> store;

        private Fixture() {
            registry.registerSystem(action);
            store = registry.addStore(new Object(), EmptyResourceStorage.get());
            Objects.requireNonNull(store.addEntity(Archetype.of(triggerType), AddReason.SPAWN));
        }

        private Ref<Object> addEntity() {
            return Objects.requireNonNull(store.addEntity(Archetype.empty(), AddReason.SPAWN));
        }

        @Override
        public void close() {
            registry.shutdown();
        }
    }

    private static final class ActionSystem extends EntityTickingSystem<Object> {
        private final Query<Object> query;
        private BiConsumer<Store<Object>, CommandBuffer<Object>> action;

        private ActionSystem(ComponentType<Object, Trigger> triggerType) {
            query = Archetype.of(triggerType);
        }

        @Override
        public Query<Object> getQuery() {
            return query;
        }

        @Override
        public void tick(
            float seconds,
            int index,
            @Nonnull ArchetypeChunk<Object> archetypeChunk,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            Objects.requireNonNull(action, "action").accept(store, commandBuffer);
        }
    }

    private static final class FailingAddListener extends OutgoingAddListener {
        private Ref<Object> expectedSource;
        private Ref<Object> expectedTarget;
        private boolean armed;
        private Ref<Object> observedTarget;
        private int observedIncomingCount = -1;

        private FailingAddListener(GenericRelationshipType<Object, Object, LinkData> type) {
            super(type);
        }

        @Override
        public void onComponentAdded(
            @Nonnull Ref<Object> ref,
            @Nonnull OutgoingLink<Object, Object> component,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            if (!armed) {
                return;
            }
            armed = false;
            observedTarget = relationships.getFirstTarget(expectedSource, type);
            observedIncomingCount = relationships.getIncomingCount(expectedTarget, type);
            throw new CallbackFailure();
        }

    }

    private static final class ReentrantAddListener extends OutgoingAddListener {
        private final Ref<Object> firstSource;
        private final Ref<Object> firstTarget;
        private final Ref<Object> secondSource;
        private final Ref<Object> secondTarget;
        private boolean armed;
        private Ref<Object> observedFirstTarget;
        private int observedFirstIncomingCount = -1;
        private Ref<Object> observedSecondTarget;
        private int observedSecondIncomingCount = -1;

        private ReentrantAddListener(
            GenericRelationshipType<Object, Object, LinkData> type,
            Ref<Object> firstSource,
            Ref<Object> firstTarget,
            Ref<Object> secondSource,
            Ref<Object> secondTarget
        ) {
            super(type);
            this.firstSource = firstSource;
            this.firstTarget = firstTarget;
            this.secondSource = secondSource;
            this.secondTarget = secondTarget;
        }

        @Override
        public void onComponentAdded(
            @Nonnull Ref<Object> ref,
            @Nonnull OutgoingLink<Object, Object> component,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
            if (!armed) {
                return;
            }
            armed = false;
            observedFirstTarget = relationships.getFirstTarget(firstSource, type);
            observedFirstIncomingCount = relationships.getIncomingCount(firstTarget, type);
            observedSecondTarget = relationships.getFirstTarget(secondSource, type);
            observedSecondIncomingCount = relationships.getIncomingCount(secondTarget, type);
            relationships.addTarget(commandBuffer, secondSource, type, secondTarget, new LinkData("second"));
        }

    }

    private abstract static class OutgoingAddListener
        extends RefChangeSystem<Object, OutgoingLink<Object, Object>> {
        protected final GenericRelationshipType<Object, Object, LinkData> type;

        private OutgoingAddListener(GenericRelationshipType<Object, Object, LinkData> type) {
            this.type = type;
        }

        @Override
        public final ComponentType<Object, OutgoingLink<Object, Object>> componentType() {
            return type.getSourceType();
        }

        @Override
        public final Query<Object> getQuery() {
            return Query.any();
        }

        @Override
        public final void onComponentSet(
            @Nonnull Ref<Object> ref,
            OutgoingLink<Object, Object> oldComponent,
            @Nonnull OutgoingLink<Object, Object> newComponent,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
        }

        @Override
        public final void onComponentRemoved(
            @Nonnull Ref<Object> ref,
            @Nonnull OutgoingLink<Object, Object> component,
            @Nonnull Store<Object> store,
            @Nonnull CommandBuffer<Object> commandBuffer
        ) {
        }
    }

    private static final class Trigger implements Component<Object> {
        @Override
        public Trigger clone() {
            return new Trigger();
        }
    }

    private static final class LinkData {
        private String value;

        private LinkData(String value) {
            this.value = value;
        }
    }

    private static final class CallbackFailure extends RuntimeException {
    }
}
