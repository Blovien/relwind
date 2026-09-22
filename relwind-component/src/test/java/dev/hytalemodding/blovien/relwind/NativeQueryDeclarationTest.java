/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Player;
import com.hypixel.hytale.component.StoreFixture.Position;
import com.hypixel.hytale.component.StoreFixture.Weapon;
import com.hypixel.hytale.component.query.Query;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A relationship condition nests inside Hytale's own query combinators, which keep a linked entity
/// they cannot read unknown instead of false.
class NativeQueryDeclarationTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void emptyTargetConditionsDoNotAdmitInvalidLinkedEntities() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "invalid-target", Void.class);
            var source = fixture.addEntity(new Position(), null);
            var target = fixture.addEntity(new Position(), null);
            relationships.addTarget(fixture.store(), source, follows, target);
            fixture.store().removeEntity(target, RemoveReason.UNLOAD);
            var unknown = RelationshipQuery.exists(follows, RelationshipQuery.and());

            assertEquals(0, (int) relationships.fetch(source, RelationshipQuery.of(follows, RelationshipQuery.and()), results -> results.size()));
            assertEquals(RelationshipQuery.Truth.UNKNOWN,
                unknown.evaluate(fixture.store(), source, new RelationshipQuery.Evaluation<>()));
        }
    }

    @ParameterizedTest
    @EnumSource(Delivery.class)
    void tickAndFetchDeliverEveryTypedBindingOfADefinitionNestedOnBothSides(Delivery delivery) {
        try (var fixture = new StoreFixture()) {
            var graph = mentoredWeaponOwners(fixture);
            var rows = new ArrayList<Row>();

            deliver(delivery, fixture, graph, rows);

            assertEquals(Set.of(
                new Row(graph.alice(), graph.bob(), "follow", graph.firstMentor(), graph.sword(), 7, graph.blue(), "blue"),
                new Row(graph.alice(), graph.bob(), "follow", graph.firstMentor(), graph.sword(), 7, graph.red(), "red"),
                new Row(graph.alice(), graph.bob(), "follow", graph.secondMentor(), graph.sword(), 7, graph.blue(), "blue"),
                new Row(graph.alice(), graph.bob(), "follow", graph.secondMentor(), graph.sword(), 7, graph.red(), "red")
            ), new HashSet<>(rows));
            assertEquals(4, rows.size());
        }
    }

    private static void deliver(Delivery delivery, StoreFixture fixture, Graph graph, ArrayList<Row> rows) {
        switch (delivery) {
            case TICK -> {
                fixture.registry().registerSystem(
                    new RecordingSystem<>(graph.query(), result -> rows.add(row(result, graph))));
                fixture.tick(0.05f);
            }
            case FETCH -> relationships.fetch(graph.alice(), graph.query(), results -> {
                results.forEach(result -> rows.add(row(result, graph)));
                return null;
            });
        }
    }

    private enum Delivery {
        TICK,
        FETCH
    }

    @Test
    void unregisteringAComponentOfANestedConditionUnregistersTheSystem() {
        try (var fixture = new StoreFixture()) {
            var magicType = fixture.registry().registerComponent(Magic.class, Magic::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "follows", String.class);
            var upgradedBy = register(types, "upgradedBy", String.class);
            var upgrade = RelationshipQuery.of(upgradedBy, magicType);
            var system = new RecordingSystem<>(RelationshipQuery.of(follows, upgrade), result -> { });
            fixture.registry().registerSystem(system);
            assertTrue(fixture.registry().hasSystem(system));

            fixture.registry().unregisterComponent(magicType);

            assertFalse(fixture.registry().hasSystem(system));
        }
    }

    @Test
    void nativeNegationOnTheSourceDoesNotRejectAnArchetypeWithNonmatchingLinks() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "follows", Void.class);
            var owns = register(types, "owns", Void.class);
            var alice = fixture.addEntity(new Position(), null);
            var charlie = fixture.addEntity(new Position(), null);
            var dave = fixture.addEntity(new Position(), null);
            var bob = fixture.addEntity(new Position(), new Player("Bob"));
            var ordinary = fixture.addEntity(new Position(), null);
            relationships.addTarget(fixture.store(), alice, follows, bob);
            relationships.addTarget(fixture.store(), charlie, follows, bob);
            relationships.addTarget(fixture.store(), dave, follows, bob);
            relationships.addTarget(fixture.store(), alice, owns, ordinary);
            relationships.addTarget(fixture.store(), charlie, owns, bob);
            var query = RelationshipQuery.of(
                Query.and(fixture.positionType(), Query.not(RelationshipQuery.exists(owns, fixture.playerType()))),
                follows,
                fixture.playerType()
            );
            var sources = new ArrayList<Ref<Object>>();
            fixture.registry().registerSystem(new RecordingSystem<>(query, result -> sources.add(result.getSource())));

            fixture.tick(0.05f);

            assertEquals(Set.of(alice, dave), new HashSet<>(sources));
            assertEquals(2, sources.size());
        }
    }

    @Test
    void nativeBooleanCompositionPreservesUnknownInsteadOfTreatingItAsFalse() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = register(types, "follows", Void.class);
            var owns = register(types, "owns", Void.class);
            var alice = fixture.addEntity(new Position(), null);
            var bob = fixture.addEntity(new Position(), new Player("Bob"));
            var unavailable = fixture.addEntity(new Position(), new Player("Unavailable"));
            relationships.addTarget(fixture.store(), alice, follows, bob);
            relationships.addTarget(fixture.store(), bob, owns, unavailable);
            fixture.store().removeEntity(unavailable, RemoveReason.UNLOAD);
            var unknown = RelationshipQuery.exists(owns, fixture.playerType());

            assertEquals(1, (int) relationships.fetch(alice, RelationshipQuery.of(follows, Query.or(unknown, fixture.playerType())), results -> results.size()));
            assertEquals(0, (int) relationships.fetch(alice, RelationshipQuery.of(follows, Query.not(unknown)), results -> results.size()));
            assertEquals(0, (int) relationships.fetch(alice, RelationshipQuery.of(follows, Query.and(unknown, Query.not(fixture.playerType()))), results -> results.size()));
            assertEquals(0, (int) relationships.fetch(alice, RelationshipQuery.of(follows, Query.or(unknown, Query.not(unknown))), results -> results.size()));
        }
    }

    private static Graph mentoredWeaponOwners(StoreFixture fixture) {
        var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
        var magicType = fixture.registry().registerComponent(Magic.class, Magic::new);
        var types = new RelationshipTypeRegistry<>(fixture.registry());
        var follows = register(types, "follows", String.class);
        var owns = register(types, "owns", Integer.class);
        var upgradedBy = register(types, "upgradedBy", String.class);
        var mentoredBy = register(types, "mentoredBy", Void.class);
        var alice = fixture.addEntity(new Position(), null);
        var bob = fixture.addEntity(new Position(), new Player("Bob"));
        var firstMentor = fixture.addEntity(new Position(), new Player("First"));
        var secondMentor = fixture.addEntity(new Position(), new Player("Second"));
        var sword = add(fixture, weaponType, new Weapon());
        var bow = add(fixture, weaponType, new Weapon());
        var blue = add(fixture, magicType, new Magic());
        var red = add(fixture, magicType, new Magic());
        var ordinary = fixture.addEntity(new Position(), null);
        relationships.addTarget(fixture.store(), alice, follows, bob, "follow");
        relationships.addTarget(fixture.store(), bob, owns, sword, 7);
        relationships.addTarget(fixture.store(), bob, owns, bow, 9);
        relationships.addTarget(fixture.store(), sword, upgradedBy, blue, "blue");
        relationships.addTarget(fixture.store(), sword, upgradedBy, red, "red");
        relationships.addTarget(fixture.store(), bow, upgradedBy, ordinary, "ordinary");
        relationships.addTarget(fixture.store(), alice, mentoredBy, firstMentor);
        relationships.addTarget(fixture.store(), alice, mentoredBy, secondMentor);
        var mentor = RelationshipQuery.of(mentoredBy, fixture.playerType());
        var upgrade = RelationshipQuery.of(upgradedBy, magicType);
        var weapon = RelationshipQuery.of(owns, Query.and(weaponType, Query.or(upgrade, upgrade)));
        var query = RelationshipQuery.of(
            Query.and(fixture.positionType(), Query.not(fixture.playerType()), mentor),
            follows,
            Query.and(fixture.playerType(), Query.or(weapon, weapon))
        );
        return new Graph(alice, bob, firstMentor, secondMentor, sword, blue, red, mentor, upgrade, weapon, query);
    }

    private static Row row(RelationshipResult<Object, String> result, Graph graph) {
        return new Row(
            result.getSource(), result.getTarget(), result.getData(), result.getTarget(graph.mentor()),
            result.getTarget(graph.weapon()), result.getData(graph.weapon()),
            result.getTarget(graph.upgrade()), result.getData(graph.upgrade())
        );
    }

    private record Graph(
        Ref<Object> alice, Ref<Object> bob, Ref<Object> firstMentor, Ref<Object> secondMentor,
        Ref<Object> sword, Ref<Object> blue, Ref<Object> red,
        RelationshipQuery.Definition<Object, Void> mentor,
        RelationshipQuery.Definition<Object, String> upgrade,
        RelationshipQuery.Definition<Object, Integer> weapon,
        RelationshipQuery.Definition<Object, String> query
    ) {
    }

    private static <DATA> GenericRelationshipType<Object, Object, DATA> register(
        RelationshipTypeRegistry<Object> types,
        String name,
        Class<DATA> dataClass
    ) {
        return types.registerRelationship(
            "relwind:test/" + name,
            dataClass,
            null,
            RelationshipRules.multiple().retainOnTransfer().retainOnDeactivation());
    }

    private static <C extends Component<Object>> Ref<Object> add(
        StoreFixture fixture,
        ComponentType<Object, C> type,
        C component
    ) {
        var holder = fixture.registry().newHolder();
        holder.putComponent(type, component);
        return fixture.store().addEntity(holder, AddReason.SPAWN);
    }

    private record Row(
        Ref<Object> source, Ref<Object> target, String followData, Ref<Object> mentor,
        Ref<Object> weapon, Integer ownershipData, Ref<Object> upgrade, String upgradeData
    ) {
    }

    private static final class RecordingSystem<DATA> extends RelationshipTickingSystem<Object, DATA> {
        private final RelationshipQuery.Definition<Object, DATA> query;
        private final Consumer<RelationshipResult<Object, DATA>> callback;

        private RecordingSystem(
            RelationshipQuery.Definition<Object, DATA> query,
            Consumer<RelationshipResult<Object, DATA>> callback
        ) {
            this.query = query;
            this.callback = callback;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, DATA> getQuery() {
            return query;
        }

        @Override
        protected void tickRelationship(
            float seconds,
            RelationshipResult<Object, DATA> result,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            callback.accept(result);
        }
    }

    private static final class Magic implements Component<Object> {
        @Override
        public Component<Object> clone() {
            return new Magic();
        }
    }
}
