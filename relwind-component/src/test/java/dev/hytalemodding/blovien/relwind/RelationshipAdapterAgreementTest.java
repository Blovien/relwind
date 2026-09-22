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
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Position;
import com.hypixel.hytale.component.StoreFixture.Weapon;
import com.hypixel.hytale.component.system.EcsEvent;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// One query answers the same nested matches whichever way a plugin asks for it: on the Store tick,
/// on an event, or through a direct fetch.
class RelationshipAdapterAgreementTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void tickingEventAndFetchReportTheSameNestedMatches() {
        try (var fixture = new StoreFixture()) {
            var weaponType = fixture.registry().registerComponent(Weapon.class, Weapon::new);
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var follows = types.registerRelationship("relwind:test/follows", RelationshipRules.multiple());
            var owns = types.registerRelationship("relwind:test/owns", RelationshipRules.multiple());
            var weapon = RelationshipQuery.enumerate(owns, weaponType);
            var targetQuery = RelationshipQuery.and(fixture.positionType(), weapon);
            var ticking = new TickingMatches(follows, targetQuery, weapon);
            var event = new EventMatches(follows, targetQuery, weapon);
            fixture.registry().registerSystem(ticking);
            fixture.registry().registerSystem(event);

            var alice = fixture.addEntity(new Position(1, 2), null);
            var bob = fixture.addEntity(new Position(3, 4), null);
            var carol = fixture.addEntity(new Position(5, 6), null);
            var sword = addWeapon(fixture, weaponType);
            var bow = addWeapon(fixture, weaponType);
            var wand = addWeapon(fixture, weaponType);
            relationships.addTarget(fixture.store(), alice, follows, bob);
            relationships.addTarget(fixture.store(), alice, follows, carol);
            relationships.addTarget(fixture.store(), bob, owns, sword);
            relationships.addTarget(fixture.store(), bob, owns, bow);
            relationships.addTarget(fixture.store(), carol, owns, wand);

            fixture.tick(0.05f);
            fixture.store().invoke(alice, new MatchEvent());
            var fetchMatches = relationships.fetch(alice, RelationshipQuery.of(follows, targetQuery), fetched -> {
                var matches = new ArrayList<Match>();
                for (var result : fetched) {
                    matches.add(new Match(result.getTarget(), result.getTarget(weapon)));
                }
                return matches;
            });

            var expected = Set.of(
                new Match(bob, sword),
                new Match(bob, bow),
                new Match(carol, wand)
            );
            assertEquals(expected, new HashSet<>(ticking.matches));
            assertEquals(expected, new HashSet<>(event.matches));
            assertEquals(expected, new HashSet<>(fetchMatches));
            assertEquals(3, ticking.matches.size());
            assertEquals(3, event.matches.size());
            assertEquals(3, fetchMatches.size());
            assertEquals(1, event.calls);
        }
    }

    private static Ref<Object> addWeapon(StoreFixture fixture, ComponentType<Object, Weapon> weaponType) {
        return Objects.requireNonNull(fixture.store().addEntity(Archetype.of(weaponType), AddReason.SPAWN));
    }

    private static final class TickingMatches extends RelationshipTickingSystem<Object, Void> {
        private final RelationshipQuery.Definition<Object, Void> query;
        private final RelationshipQuery.Binding<Object, Void> weapon;
        private final ArrayList<Match> matches = new ArrayList<>();

        private TickingMatches(
            GenericRelationshipType<Object, Object, Void> follows,
            RelationshipQuery<Object> targetQuery,
            RelationshipQuery.Binding<Object, Void> weapon
        ) {
            this.query = RelationshipQuery.of(Archetype.of(follows.getSourceType()), follows, targetQuery);
            this.weapon = weapon;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Void> getQuery() {
            return query;
        }

        @Override
        protected void tickRelationship(
            float seconds,
            RelationshipResult<Object, Void> result,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer
        ) {
            matches.add(new Match(result.getTarget(), result.getTarget(weapon)));
        }
    }

    public static final class EventMatches extends RelationshipEventSystem<Object, Void, MatchEvent> {
        private final RelationshipQuery.Definition<Object, Void> query;
        private final RelationshipQuery.Binding<Object, Void> weapon;
        private final ArrayList<Match> matches = new ArrayList<>();
        private int calls;

        private EventMatches(
            GenericRelationshipType<Object, Object, Void> follows,
            RelationshipQuery<Object> targetQuery,
            RelationshipQuery.Binding<Object, Void> weapon
        ) {
            super(MatchEvent.class);
            this.query = RelationshipQuery.of(Archetype.of(follows.getSourceType()), follows, targetQuery);
            this.weapon = weapon;
        }

        @NonNullDecl
        @Override
        public RelationshipQuery.Definition<Object, Void> getQuery() {
            return query;
        }

        @Override
        protected void handleRelationship(
            RelationshipResults<Object, Void> results,
            Store<Object> store,
            CommandBuffer<Object> commandBuffer,
            MatchEvent event
        ) {
            calls++;
            for (var result : results) {
                matches.add(new Match(result.getTarget(), result.getTarget(weapon)));
            }
        }
    }

    public static final class MatchEvent extends EcsEvent {
    }

    private record Match(Ref<Object> target, Ref<Object> weapon) {
    }
}
