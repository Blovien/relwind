/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Player;
import com.hypixel.hytale.component.StoreFixture.Position;
import com.hypixel.hytale.component.query.Query;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Add and removal callbacks evaluate the holder of an entity before the Store admits it and after
/// it is gone, reading its link data from the holder itself.
class RelationshipHolderSystemTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void removingAnEntityEvaluatesItsHolderAfterTheStoreDropsIt() {
        try (var fixture = new StoreFixture()) {
            var follows = register(new RelationshipTypeRegistry<>(fixture.registry()), "relwind:test/holder-callback");
            var system = new RecordingHolderSystem(
                fixture.positionType(),
                fixture.playerType(),
                follows
            );
            fixture.registry().registerSystem(system);
            var source = fixture.addEntity(new Position(1, 2), null);
            var firstTarget = fixture.addEntity(new Position(3, 4), new Player("first"));
            var secondTarget = fixture.addEntity(new Position(5, 6), new Player("second"));
            relationships.addTarget(fixture.store(), source, follows, firstTarget);
            relationships.addTarget(fixture.store(), source, follows, secondTarget);

            var holder = fixture.store().removeEntity(source, RemoveReason.UNLOAD);

            assertFalse(source.isValid());
            assertEquals(2, fixture.store().getEntityCount());
            assertEquals(java.util.List.of(RemoveReason.UNLOAD), system.removeReasons);
            assertEquals(java.util.List.of(2), system.removeEntityCounts);
            assertSame(holder, system.removeHolder);
            assertEquals(java.util.Set.of(firstTarget, secondTarget), system.removeTargets);
            assertNull(system.removeSource);
        }
    }

    @Test
    void theResultsLentToTheRemovalCallbackExpireWhenItReturns() {
        try (var fixture = new StoreFixture()) {
            var follows = register(new RelationshipTypeRegistry<>(fixture.registry()), "relwind:test/holder-callback");
            var system = new RecordingHolderSystem(
                fixture.positionType(),
                fixture.playerType(),
                follows
            );
            fixture.registry().registerSystem(system);
            var source = fixture.addEntity(new Position(1, 2), null);
            var firstTarget = fixture.addEntity(new Position(3, 4), new Player("first"));
            var secondTarget = fixture.addEntity(new Position(5, 6), new Player("second"));
            relationships.addTarget(fixture.store(), source, follows, firstTarget);
            relationships.addTarget(fixture.store(), source, follows, secondTarget);

            fixture.store().removeEntity(source, RemoveReason.UNLOAD);

            assertEquals(java.util.List.of(RemoveReason.UNLOAD), system.removeReasons);
            assertEquals(0, system.borrowedResults.size());
            assertThrows(NullPointerException.class, system.borrowedResult::getHolder);
        }
    }

    @Test
    void addingAHolderEvaluatesItBeforeTheStoreAdmitsIt() {
        try (var fixture = new StoreFixture()) {
            var follows = register(new RelationshipTypeRegistry<>(fixture.registry()), "relwind:test/holder-callback");
            var system = new RecordingHolderSystem(
                fixture.positionType(),
                fixture.playerType(),
                follows
            );
            fixture.registry().registerSystem(system);
            var source = fixture.addEntity(new Position(1, 2), null);
            var firstTarget = fixture.addEntity(new Position(3, 4), new Player("first"));
            var secondTarget = fixture.addEntity(new Position(5, 6), new Player("second"));
            relationships.addTarget(fixture.store(), source, follows, firstTarget);
            relationships.addTarget(fixture.store(), source, follows, secondTarget);
            var holder = fixture.store().removeEntity(source, RemoveReason.UNLOAD);

            var replacement = Objects.requireNonNull(fixture.store().addEntity(holder, AddReason.LOAD));

            assertEquals(3, fixture.store().getEntityCount());
            assertEquals(java.util.List.of(AddReason.LOAD), system.addReasons);
            assertEquals(java.util.List.of(2), system.addEntityCounts);
            assertSame(holder, system.addHolder);
            assertEquals(java.util.Set.of(firstTarget, secondTarget), system.addTargets);
            assertNull(system.addSource);
            assertNotSame(source, replacement);
        }
    }

    @Test
    void theRemovalCallbackReadsTheLinkDataFromTheHolderThatCarriesTheOutgoingLink() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var saddleType = fixture.registry().registerComponent(Saddle.class, Saddle::new);
            var mounted = types.registerRelationship(saddleType, new SaddleObserver(), RelationshipRules.single());
            var system = new MountedHolderSystem(fixture.positionType(), fixture.playerType(), mounted);
            fixture.registry().registerSystem(system);
            var rider = fixture.addEntity(new Position(1, 2), null);
            var mount = fixture.addEntity(new Position(3, 4), new Player("mount"));
            var saddle = new Saddle();
            relationships.addTarget(fixture.store(), rider, mounted, mount, saddle);

            var holder = fixture.store().removeEntity(rider, RemoveReason.UNLOAD);

            assertSame(saddle, holder.getComponent(saddleType));
            assertSame(holder, system.removeHolder);
            assertSame(mount, system.removeTarget);
            assertSame(saddle, system.removeData);
        }
    }

    @Test
    void theAddCallbackReadsTheLinkDataFromTheHolderThatCarriesTheOutgoingLink() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var saddleType = fixture.registry().registerComponent(Saddle.class, Saddle::new);
            var mounted = types.registerRelationship(saddleType, new SaddleObserver(), RelationshipRules.single());
            var system = new MountedHolderSystem(fixture.positionType(), fixture.playerType(), mounted);
            fixture.registry().registerSystem(system);
            var rider = fixture.addEntity(new Position(1, 2), null);
            var mount = fixture.addEntity(new Position(3, 4), new Player("mount"));
            var saddle = new Saddle();
            relationships.addTarget(fixture.store(), rider, mounted, mount, saddle);
            var holder = fixture.store().removeEntity(rider, RemoveReason.UNLOAD);

            fixture.store().addEntity(holder, AddReason.LOAD);

            assertSame(holder, system.addHolder);
            assertSame(mount, system.addTarget);
            assertSame(saddle, system.addData);
        }
    }

    private static GenericRelationshipType<Object, Object, Void> register(RelationshipTypeRegistry<Object> types, String id) {
        return types.registerRelationship(
            id,
            RelationshipRules.multiple().retainOnTransfer().retainOnDeactivation());
    }

    /// Link data of a single target type, carried by a component on the source.
    public static final class Saddle implements com.hypixel.hytale.component.Component<Object> {
        private int seat;

        @Override
        public Saddle clone() {
            var copy = new Saddle();
            copy.seat = seat;
            return copy;
        }
    }

    public static final class MountedHolderSystem extends RelationshipHolderSystem<Object, Saddle> {
        private final com.hypixel.hytale.component.ComponentType<Object, Position> positionType;
        private final com.hypixel.hytale.component.ComponentType<Object, Player> playerType;
        private final GenericRelationshipType<Object, Object, Saddle> mounted;
        private Holder<Object> addHolder;
        private Holder<Object> removeHolder;
        private Ref<Object> addTarget;
        private Ref<Object> removeTarget;
        private Saddle addData;
        private Saddle removeData;

        private MountedHolderSystem(
            com.hypixel.hytale.component.ComponentType<Object, Position> positionType,
            com.hypixel.hytale.component.ComponentType<Object, Player> playerType,
            GenericRelationshipType<Object, Object, Saddle> mounted
        ) {
            this.positionType = positionType;
            this.playerType = playerType;
            this.mounted = mounted;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Saddle> getQuery() {
            return RelationshipQuery.of(Query.and(positionType), mounted, Query.and(playerType));
        }

        @Override
        protected void onEntityAdd(RelationshipResults<Object, Saddle> results, AddReason reason, Store<Object> store) {
            for (var result : results) {
                addHolder = result.getHolder();
                addTarget = result.getTarget();
                addData = result.getData();
            }
        }

        @Override
        protected void onEntityRemoved(RelationshipResults<Object, Saddle> results, RemoveReason reason, Store<Object> store) {
            for (var result : results) {
                removeHolder = result.getHolder();
                removeTarget = result.getTarget();
                removeData = result.getData();
            }
        }
    }

    public static final class RecordingHolderSystem extends RelationshipHolderSystem<Object, Void> {
        private final com.hypixel.hytale.component.ComponentType<Object, Position> positionType;
        private final com.hypixel.hytale.component.ComponentType<Object, Player> playerType;
        private final GenericRelationshipType<Object, Object, Void> follows;
        private final ArrayList<AddReason> addReasons = new ArrayList<>();
        private final ArrayList<RemoveReason> removeReasons = new ArrayList<>();
        private final ArrayList<Integer> addEntityCounts = new ArrayList<>();
        private final ArrayList<Integer> removeEntityCounts = new ArrayList<>();
        private final HashSet<Ref<Object>> addTargets = new HashSet<>();
        private final HashSet<Ref<Object>> removeTargets = new HashSet<>();
        private Holder<Object> addHolder;
        private Holder<Object> removeHolder;
        private Ref<Object> addSource;
        private Ref<Object> removeSource;
        private RelationshipResults<Object, Void> borrowedResults;
        private RelationshipResult<Object, Void> borrowedResult;

        private RecordingHolderSystem(
            com.hypixel.hytale.component.ComponentType<Object, Position> positionType,
            com.hypixel.hytale.component.ComponentType<Object, Player> playerType,
            GenericRelationshipType<Object, Object, Void> follows
        ) {
            this.positionType = positionType;
            this.playerType = playerType;
            this.follows = follows;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Void> getQuery() {
            return RelationshipQuery.of(Query.and(positionType), follows, Query.and(playerType));
        }

        @Override
        protected void onEntityAdd(RelationshipResults<Object, Void> results, AddReason reason, Store<Object> store) {
            addReasons.add(reason);
            addEntityCounts.add(store.getEntityCount());
            for (var result : results) {
                addHolder = result.getHolder();
                addSource = result.getSource();
                addTargets.add(result.getTarget());
            }
        }

        @Override
        protected void onEntityRemoved(RelationshipResults<Object, Void> results, RemoveReason reason, Store<Object> store) {
            removeReasons.add(reason);
            removeEntityCounts.add(store.getEntityCount());
            borrowedResults = results;
            borrowedResult = results.get(0);
            for (var result : results) {
                removeHolder = result.getHolder();
                removeSource = result.getSource();
                removeTargets.add(result.getTarget());
            }
        }
    }

    private static final class SaddleObserver extends RelationshipDataObserver<Object, Saddle> {
    }
}
