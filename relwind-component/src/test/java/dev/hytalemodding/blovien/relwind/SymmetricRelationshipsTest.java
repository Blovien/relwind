/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.BridgeStoreFixture;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Position;
import com.hypixel.hytale.component.system.WorldEventSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SymmetricRelationshipsTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void addCreatesBothDirectionsBeforeAnnouncingEither() {
        try (var fixture = new Fixture()) {
            fixture.observer.check = () -> fixture.assertPair(fixture.first, fixture.second, "new");

            relationships.addTarget(fixture.store(), fixture.first, fixture.type, fixture.second, "new");

            assertEquals(List.of(fixture.second), fixture.targets(fixture.first));
            assertEquals(List.of(fixture.first), fixture.targets(fixture.second));
            assertEquals(List.of(RelationshipChangeSystem.Kind.ADDED, RelationshipChangeSystem.Kind.ADDED),
                fixture.observer.kinds());
        }
    }

    @Test
    void addRejectsAnExistingMainDirectionWithoutChangingEitherLink() {
        try (var fixture = new Fixture()) {
            relationships.addTarget(fixture.store(), fixture.first, fixture.type, fixture.second, "old");
            fixture.observer.changes.clear();

            assertThrows(IllegalStateException.class,
                () -> relationships.addTarget(fixture.store(), fixture.first, fixture.type, fixture.second, "new"));

            fixture.assertPair(fixture.first, fixture.second, "old");
            assertEquals(List.of(), fixture.observer.changes);
        }
    }

    @Test
    void addFillsAMissingMainDirectionAndUpdatesTheExistingTwinData() {
        try (var fixture = new Fixture()) {
            fixture.directed(fixture.second, fixture.first, "old");

            relationships.addTarget(fixture.store(), fixture.first, fixture.type, fixture.second, "new");

            fixture.assertPair(fixture.first, fixture.second, "new");
            assertEquals(List.of(RelationshipChangeSystem.Kind.ADDED, RelationshipChangeSystem.Kind.SET),
                fixture.observer.kinds());
            assertEquals("old", fixture.observer.changes.get(1).oldData);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false,ADDED,ADDED", "true,false,SET,ADDED", "false,true,ADDED,SET", "true,true,SET,SET"})
    void putCreatesMissingDirectionsAndSetsExistingData(boolean forward, boolean reverse,
        RelationshipChangeSystem.Kind forwardKind, RelationshipChangeSystem.Kind reverseKind) {
        try (var fixture = new Fixture()) {
            fixture.seed(forward, reverse);

            relationships.putTarget(fixture.store(), fixture.first, fixture.type, fixture.second, "new");

            fixture.assertPair(fixture.first, fixture.second, "new");
            assertEquals(List.of(forwardKind, reverseKind), fixture.observer.kinds());
            assertEquals(fixture.first, fixture.observer.changes.getFirst().source.reference());
            assertEquals(fixture.second, fixture.observer.changes.getLast().source.reference());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void removeFromEitherDirectionRemovesBothBeforeAnnouncing(boolean strict) {
        try (var fixture = new Fixture()) {
            relationships.addTarget(fixture.store(), fixture.first, fixture.type, fixture.second, "old");
            fixture.observer.changes.clear();
            fixture.observer.check = () -> fixture.assertAbsent(fixture.first, fixture.second);

            fixture.remove(fixture.second, fixture.first, strict);

            fixture.assertAbsent(fixture.first, fixture.second);
            assertEquals(List.of(RelationshipChangeSystem.Kind.REMOVED, RelationshipChangeSystem.Kind.REMOVED),
                fixture.observer.kinds());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false,0", "true,false,1", "false,true,1"})
    void tryRemoveRemovesAnyRemainingDirection(boolean forward, boolean reverse, int changes) {
        try (var fixture = new Fixture()) {
            fixture.seed(forward, reverse);

            relationships.tryRemoveTarget(fixture.store(), fixture.first, fixture.type, fixture.second);

            fixture.assertAbsent(fixture.first, fixture.second);
            assertEquals(changes, fixture.observer.changes.size());
        }
    }

    @Test
    void strictRemoveRejectsAMissingMainDirectionAndPreservesItsTwin() {
        try (var fixture = new Fixture()) {
            fixture.directed(fixture.second, fixture.first, "old");

            assertThrows(IllegalStateException.class,
                () -> relationships.removeTarget(fixture.store(), fixture.first, fixture.type, fixture.second));

            assertEquals(List.of(fixture.first), fixture.targets(fixture.second));
            assertEquals(List.of(), fixture.observer.changes);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void retargetMovesTheMainLinkAndUpdatesEitherTwinState(boolean oldTwin, boolean newTwin) {
        try (var fixture = new Fixture()) {
            fixture.directed(fixture.first, fixture.second, "carried");
            fixture.seedTwin(fixture.second, oldTwin);
            fixture.seedTwin(fixture.third, newTwin);

            relationships.retarget(fixture.store(), fixture.first, fixture.type, fixture.second, fixture.third);

            fixture.assertPair(fixture.first, fixture.third, "carried");
            fixture.assertAbsent(fixture.first, fixture.second);
            assertSame(RelationshipChangeSystem.Kind.RETARGETED, fixture.observer.changes.getFirst().kind);
            assertSame(fixture.second, fixture.observer.changes.getFirst().oldTarget.reference());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retargetToOrFromSelfLeavesExactlyTheNewPair(boolean fromSelf) {
        try (var fixture = new Fixture()) {
            var oldTarget = fixture.target(fromSelf);
            var newTarget = fixture.target(!fromSelf);
            relationships.addTarget(fixture.store(), fixture.first, fixture.type, oldTarget, "carried");
            fixture.observer.changes.clear();

            relationships.retarget(fixture.store(), fixture.first, fixture.type, oldTarget, newTarget);

            fixture.assertPair(fixture.first, newTarget, "carried");
            assertEquals(List.of(newTarget), fixture.targets(fixture.first));
            assertEquals(false, relationships.hasTarget(fixture.first, fixture.type, oldTarget));
        }
    }

    @Test
    void clearRemovesAllSourcePairsAndPreservesOtherPairs() {
        try (var fixture = new Fixture()) {
            relationships.addTarget(fixture.store(), fixture.first, fixture.type, fixture.second, "one");
            relationships.addTarget(fixture.store(), fixture.first, fixture.type, fixture.third, "two");
            relationships.addTarget(fixture.store(), fixture.first, fixture.type, fixture.first, "self");
            relationships.addTarget(fixture.store(), fixture.second, fixture.type, fixture.third, "other");
            fixture.observer.changes.clear();
            fixture.observer.check = () -> assertEquals(0, relationships.getIncomingCount(fixture.first, fixture.type));

            relationships.clearTargets(fixture.store(), fixture.first, fixture.type);

            assertEquals(List.of(), fixture.targets(fixture.first));
            fixture.assertPair(fixture.second, fixture.third, "other");
            assertEquals(List.of(fixture.third), fixture.targets(fixture.second));
            assertEquals(List.of(fixture.second), fixture.targets(fixture.third));
            assertEquals(5, fixture.observer.changes.size());
        }
    }

    @Test
    void aSelfLinkIsWrittenAndAnnouncedOnce() {
        try (var fixture = new Fixture()) {

            relationships.addTarget(fixture.store(), fixture.first, fixture.type, fixture.first, "self");

            fixture.assertPair(fixture.first, fixture.first, "self");
            assertEquals(List.of(fixture.first), fixture.targets(fixture.first));
            assertEquals(List.of(RelationshipChangeSystem.Kind.ADDED), fixture.observer.kinds());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSelfLinkIsRemovedAndAnnouncedOnce(boolean strict) {
        try (var fixture = new Fixture()) {
            relationships.addTarget(fixture.store(), fixture.first, fixture.type, fixture.first, "self");
            fixture.observer.changes.clear();

            fixture.remove(fixture.first, fixture.first, strict);

            fixture.assertAbsent(fixture.first, fixture.first);
            assertEquals(List.of(RelationshipChangeSystem.Kind.REMOVED), fixture.observer.kinds());
        }
    }

    @Test
    void aBufferedAddWritesBothDirectionsWhenTheBufferDrains() {
        try (var fixture = new Fixture()) {

            relationships.addTarget(fixture.nativeStore.commandBuffer(), fixture.first, fixture.type, fixture.second, "new");
            fixture.assertAbsent(fixture.first, fixture.second);
            BridgeStoreFixture.consume(fixture.nativeStore.commandBuffer());

            fixture.assertPair(fixture.first, fixture.second, "new");
            assertEquals(2, fixture.observer.changes.size());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final StoreFixture nativeStore = new StoreFixture();
        private final RelationshipType<Object, String> type = new RelationshipTypeRegistry<>(nativeStore.registry())
            .registerRelationship(String.class, RelationshipTraits.defaults().symmetric());
        private final Ref<Object> first = nativeStore.addEntity(new Position(1, 1), null);
        private final Ref<Object> second = nativeStore.addEntity(new Position(2, 2), null);
        private final Ref<Object> third = nativeStore.addEntity(new Position(3, 3), null);
        private final Observer observer = new Observer();

        private Fixture() {
            nativeStore.registry().registerSystem(observer);
        }

        private Store<Object> store() {
            return nativeStore.store();
        }

        private Ref<Object> target(boolean self) {
            return self ? first : second;
        }

        private void directed(Ref<Object> source, Ref<Object> target, String data) {
            RelationshipCommands.add(store(), null, type, source, target, data, null, false);
        }

        private void seed(boolean forward, boolean reverse) {
            if (forward) directed(first, second, "old forward");
            if (reverse) directed(second, first, "old reverse");
        }

        private void seedTwin(Ref<Object> source, boolean present) {
            if (present) directed(source, first, "old twin");
        }

        private void remove(Ref<Object> source, Ref<Object> target, boolean strict) {
            if (strict) relationships.removeTarget(store(), source, type, target);
            else relationships.tryRemoveTarget(store(), source, type, target);
        }

        private List<Ref<Object>> targets(Ref<Object> source) {
            var targets = new ArrayList<Ref<Object>>();
            relationships.forEachTarget(source, type, targets::add);
            return targets;
        }

        private void assertPair(Ref<Object> source, Ref<Object> target, String data) {
            assertEquals(true, relationships.hasTarget(source, type, target));
            assertEquals(true, relationships.hasTarget(target, type, source));
            assertSame(data, relationships.getData(source, type, target));
            assertSame(data, relationships.getData(target, type, source));
            assertEquals(true, incoming(target).contains(source));
            assertEquals(true, incoming(source).contains(target));
        }

        private List<Ref<Object>> incoming(Ref<Object> target) {
            var sources = new ArrayList<Ref<Object>>();
            relationships.forEachIncomingSource(target, type, sources::add);
            return sources;
        }

        private void assertAbsent(Ref<Object> source, Ref<Object> target) {
            assertEquals(false, relationships.hasTarget(source, type, target));
            assertEquals(false, relationships.hasTarget(target, type, source));
            assertEquals(false, incoming(target).contains(source));
            assertEquals(false, incoming(source).contains(target));
        }

        @Override
        public void close() {
            nativeStore.close();
        }
    }

    private static final class Observer
        extends WorldEventSystem<Object, RelationshipChangeSystem.ChangeEvent<Object, String>> {
        private final List<RelationshipChangeSystem.ChangeEvent<Object, String>> changes = new ArrayList<>();
        private Runnable check = () -> { };

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Observer() {
            super((Class) RelationshipChangeSystem.ChangeEvent.class);
        }

        @Override
        public void handle(Store<Object> store, CommandBuffer<Object> commands,
            RelationshipChangeSystem.ChangeEvent<Object, String> event) {
            check.run();
            changes.add(event);
        }

        private List<RelationshipChangeSystem.Kind> kinds() {
            return changes.stream().map(change -> change.kind).toList();
        }
    }
}
