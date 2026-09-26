/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.BridgeStoreFixture;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EcsEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static dev.hytalemodding.blovien.relwind.RelationshipTestFixtures.blockTypes;
import static dev.hytalemodding.blovien.relwind.RelationshipTestFixtures.entityTypes;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RelationshipWildcardQueryTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void existsAnyMatchesTheTargetOfTheSecondType() {
        try (var fixture = new Fixture()) {
            var source = fixture.entity();
            relationships.addTarget(fixture.store, source, fixture.r, fixture.entity(), "miss");
            relationships.addTarget(fixture.store, source, fixture.s, fixture.matching(), 2);

            var truth = evaluate(fixture.store, source, RelationshipQuery.existsAny(fixture.types, fixture.marker));

            assertEquals(RelationshipQuery.Truth.TRUE, truth);
        }
    }

    @Test
    void existsAnyRejectsTargetsThatAllFailItsCondition() {
        try (var fixture = new Fixture()) {
            var source = fixture.entity();
            relationships.addTarget(fixture.store, source, fixture.r, fixture.entity(), "miss");
            relationships.addTarget(fixture.store, source, fixture.s, fixture.entity(), 2);

            var truth = evaluate(fixture.store, source, RelationshipQuery.existsAny(fixture.types, fixture.marker));

            assertEquals(RelationshipQuery.Truth.FALSE, truth);
        }
    }

    @Test
    void existsAnyRejectsASourceWithoutLinks() {
        try (var fixture = new Fixture()) {
            var source = fixture.entity();

            var truth = evaluate(fixture.store, source, RelationshipQuery.existsAny(fixture.types, fixture.marker));

            assertEquals(RelationshipQuery.Truth.FALSE, truth);
        }
    }

    @Test
    void anAwayLinkKeepsExistsAnyUnknownUnderNegation() {
        try (var fixture = new Fixture()) {
            var source = fixture.entity();
            var away = fixture.matching();
            relationships.addTarget(fixture.store, source, fixture.r, away, "away");
            relationships.addTarget(fixture.store, source, fixture.s, fixture.entity(), 2);
            fixture.park(away, false);
            var query = RelationshipQuery.existsAny(fixture.types, fixture.marker);

            var positive = evaluate(fixture.store, source, query);
            var negative = evaluate(fixture.store, source, RelationshipQuery.not(query));

            assertAll(
                () -> assertEquals(RelationshipQuery.Truth.UNKNOWN, positive),
                () -> assertEquals(RelationshipQuery.Truth.UNKNOWN, negative));
        }
    }

    @Test
    void enumerateAnyKeepsEachTypesDataWhenTheyShareATarget() {
        try (var fixture = new Fixture()) {
            var source = fixture.entity();
            var target = fixture.matching();
            fixture.linkBoth(source, target);
            var ignored = fixture.types.registerRelationship(RelationshipTraits.defaults());
            relationships.addTarget(fixture.store, source, ignored, fixture.entity());
            var binding = RelationshipQuery.enumerateAny(fixture.types, fixture.marker);
            var query = RelationshipQuery.of(RelationshipQuery.or(binding, binding), fixture.anchor);

            var matches = relationships.fetch(source, query, results -> copy(results, binding));

            assertEquals(2, matches.size());
            assertEquals(Set.of(new Match(fixture.r, source, target, "first"),
                new Match(fixture.s, source, target, 2)), Set.copyOf(matches));
        }
    }

    @Test
    void aRepeatedWildcardBindingEmitsEachTypeOnlyOnce() {
        try (var fixture = new Fixture()) {
            var source = fixture.entity();
            var target = fixture.matching();
            fixture.linkBoth(source, target);
            var binding = RelationshipQuery.enumerateAny(fixture.types, fixture.marker);
            var condition = RelationshipQuery.and(binding, binding);

            var matches = emit(fixture.store, source, condition, binding);

            assertEquals(2, matches.size());
            assertEquals(Set.of(new Match(fixture.r, source, target, "first"),
                new Match(fixture.s, source, target, 2)), Set.copyOf(matches));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aWildcardTickingSystemAdmitsOnlySourcesWithASpannedType(boolean secondType) {
        try (var fixture = new Fixture()) {
            var source = fixture.entity();
            var unlinked = fixture.entity();
            var target = fixture.matching();
            relationships.addTarget(fixture.store, source, fixture.anchor, target);
            relationships.addTarget(fixture.store, unlinked, fixture.anchor, target);
            fixture.linkOne(source, target, secondType);
            var system = new Ticks(RelationshipQuery.of(
                RelationshipQuery.existsAny(fixture.types, fixture.marker), fixture.anchor));
            fixture.registry.registerSystem(system);

            var sourceAdmitted = system.test(fixture.registry, fixture.store.getArchetype(source));
            var unlinkedAdmitted = system.test(fixture.registry, fixture.store.getArchetype(unlinked));
            var holderAdmitted = system.getQuery().test(fixture.store.getArchetype(unlinked));
            fixture.store.tick(0.05f);

            assertAll(
                () -> assertEquals(true, sourceAdmitted),
                () -> assertEquals(false, unlinkedAdmitted),
                () -> assertEquals(true, holderAdmitted),
                () -> assertEquals(List.of(source), system.sources));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existsAnyReadsTheSavedLinksOfAParkedOrDecodedHolder(boolean decoded) {
        try (var fixture = new Fixture()) {
            var source = fixture.entity();
            var target = fixture.matching();
            relationships.addTarget(fixture.store, source, fixture.s, target, 2);
            var holder = fixture.park(source, decoded);
            var system = new HolderEvents(RelationshipQuery.of(
                RelationshipQuery.existsAny(fixture.types, fixture.marker), fixture.s), null);
            fixture.registry.registerSystem(system);

            fixture.store.invoke(holder, new Event());

            assertAll(
                () -> assertEquals(null, holder.getComponent(fixture.s.getSourceType())),
                () -> assertEquals(1, system.calls),
                () -> assertSame(holder, system.holder),
                () -> assertSame(target, system.target));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void enumerateAnyKeepsSavedTypesDistinctOnAParkedOrDecodedHolder(boolean decoded) {
        try (var fixture = new Fixture()) {
            var source = fixture.entity();
            var target = fixture.matching();
            fixture.linkBoth(source, target);
            var holder = fixture.park(source, decoded);
            var binding = RelationshipQuery.enumerateAny(fixture.types, fixture.marker);
            var system = new HolderEvents(RelationshipQuery.of(
                RelationshipQuery.or(binding, binding), fixture.s), binding);
            fixture.registry.registerSystem(system);

            fixture.store.invoke(holder, new Event());

            assertEquals(2, system.matches.size());
            assertEquals(Set.of(new Match(fixture.r, null, target, "first"),
                new Match(fixture.s, null, target, 2)), Set.copyOf(system.matches));
        }
    }

    @Test
    void aWildcardIncludesTypesRegisteredAfterItWasBuilt() {
        try (var fixture = new Fixture()) {
            var source = fixture.entity();
            var query = RelationshipQuery.existsAny(fixture.types, fixture.marker);
            var later = fixture.types.registerRelationship(RelationshipTraits.defaults());
            relationships.addTarget(fixture.store, source, later, fixture.matching());

            var truth = evaluate(fixture.store, source, query);

            assertEquals(RelationshipQuery.Truth.TRUE, truth);
        }
    }

    @Test
    void aWildcardExcludesTypesFromAnotherRelationshipRegistry() {
        try (var fixture = new Fixture()) {
            var source = fixture.entity();
            relationships.addTarget(fixture.store, source, fixture.anchor, fixture.matching());

            var truth = evaluate(fixture.store, source, RelationshipQuery.existsAny(fixture.types, fixture.marker));

            assertEquals(RelationshipQuery.Truth.FALSE, truth);
        }
    }

    @Test
    void aWildcardExcludesBridgeTypesRegisteredBesideSameStoreTypes() {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("wildcard");
            var sources = entityTypes(fixture);
            sources.registerRelationship(RelationshipTraits.defaults());
            var bridge = sources.registerRelationship(blockTypes(fixture), RelationshipTraits.defaults());
            var source = fixture.addEntity(world);
            relationships.addTarget(world.entityStore(), source, bridge, fixture.addBlock(world));

            var truth = evaluate(world.entityStore(), source, RelationshipQuery.existsAny(sources, Query.any()));

            assertEquals(RelationshipQuery.Truth.FALSE, truth);
        }
    }

    @Test
    void unregisteringOneWildcardTypeKeepsTheSystemUsingTheOthers() {
        try (var fixture = new Fixture()) {
            var source = fixture.entity();
            fixture.linkBoth(source, fixture.matching());
            var system = new Ticks(RelationshipQuery.of(
                RelationshipQuery.existsAny(fixture.types, fixture.marker), fixture.anchor));
            fixture.registry.registerSystem(system);

            fixture.types.unregisterRelationship(fixture.r);
            fixture.store.tick(0.05f);

            assertAll(
                () -> assertEquals(false, system.unregistered),
                () -> assertEquals(List.of(source), system.sources));
        }
    }

    @Test
    void wildcardConditionsAndBindingsReadTheRemovedComponentSuppliedToACallback() {
        try (var fixture = new Fixture()) {
            var source = fixture.entity();
            var target = fixture.matching();
            relationships.addTarget(fixture.store, source, fixture.anchor, target);
            relationships.addTarget(fixture.store, source, fixture.r, target, "removed");
            var binding = RelationshipQuery.enumerateAny(fixture.types, fixture.marker);
            var query = RelationshipQuery.of(RelationshipQuery.and(
                RelationshipQuery.existsAny(fixture.types, fixture.marker), binding),
                fixture.anchor, Query.any(), fixture.r.getSourceType());
            var system = new Changes(query, binding);
            fixture.registry.registerSystem(system);

            relationships.removeTarget(fixture.store, source, fixture.r, target);

            assertEquals(List.of(new Match(fixture.r, source, target, "removed")), system.matches);
        }
    }

    private static <ECS_TYPE> RelationshipQuery.Truth evaluate(
        Store<ECS_TYPE> store, Ref<ECS_TYPE> source, RelationshipQuery<ECS_TYPE> query
    ) {
        var evaluation = new RelationshipQuery.Evaluation<ECS_TYPE>();
        query.validateRegistry(store.getRegistry());
        query.validate();
        return query.evaluate(store, source, evaluation);
    }

    private static List<Match> emit(Store<Object> store, Ref<Object> source,
        RelationshipQuery<Object> query, RelationshipQuery.Binding<Object, Object> binding) {
        var evaluation = new RelationshipQuery.Evaluation<Object>();
        var row = new RelationshipResult<Object, Object>();
        row.setEvaluation(evaluation);
        var matches = new ArrayList<Match>();
        query.emit(store, source, evaluation, () -> matches.add(copy(row, binding)));
        return matches;
    }

    private static List<Match> copy(RelationshipResults<Object, ?> results,
        RelationshipQuery.Binding<Object, Object> binding) {
        var matches = new ArrayList<Match>();
        for (var row : results) matches.add(copy(row, binding));
        return matches;
    }

    private static Match copy(RelationshipResult<Object, ?> row, RelationshipQuery.Binding<Object, Object> binding) {
        return new Match(row.getRelationshipType(binding), row.getSource(binding), row.getTarget(binding), row.getData(binding));
    }

    private record Match(GenericRelationshipType<Object, Object, ?> type,
        @Nullable Ref<Object> source, Ref<Object> target, Object data) { }

    private static final class Fixture implements AutoCloseable {
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final ComponentType<Object, Marker> marker = registry.registerComponent(Marker.class, Marker::new);
        private final Store<Object> store = registry.addStore(new Object(), EmptyResourceStorage.get());
        private final IdentityHashMap<Ref<Object>, Integer> identities = new IdentityHashMap<>();
        private final RelationshipInstallation<Integer> installation = RelationshipInstallation
            .on(registry, identities::get, Codec.INTEGER)
            .persistence((context, ref) -> { }, holder -> { }, id -> false).install();
        private final RelationshipTypeRegistry<Object> types = installation.types();
        private final RelationshipTracker<Object, Integer> tracker = installation.tracker();
        private final RelationshipType<Object, String> r = types.registerRelationship(
            "relwind:wildcard/r", String.class, Codec.STRING, RelationshipTraits.defaults().exclusive().retainOnDeactivation());
        private final RelationshipType<Object, Integer> s = types.registerRelationship(
            "relwind:wildcard/s", Integer.class, Codec.INTEGER, RelationshipTraits.defaults().exclusive().retainOnDeactivation());
        private final RelationshipType<Object, Void> anchor = new RelationshipTypeRegistry<>(registry)
            .registerRelationship(RelationshipTraits.defaults());

        private Ref<Object> entity() {
            var ref = store.addEntity(registry.newHolder(), AddReason.SPAWN);
            int id = identities.size() + 1;
            identities.put(ref, id);
            tracker.onEntityLoaded(id, ref);
            return ref;
        }

        private Ref<Object> matching() {
            var ref = entity();
            store.addComponent(ref, marker, new Marker());
            return ref;
        }

        private void linkOne(Ref<Object> source, Ref<Object> target, boolean secondType) {
            if (secondType) relationships.addTarget(store, source, s, target, 2);
            else relationships.addTarget(store, source, r, target, "first");
        }

        private void linkBoth(Ref<Object> source, Ref<Object> target) {
            relationships.addTarget(store, source, anchor, target);
            relationships.addTarget(store, source, r, target, "first");
            relationships.addTarget(store, source, s, target, 2);
        }

        private Holder<Object> park(Ref<Object> source, boolean decoded) {
            var saved = decoded ? registry.serialize(store.copySerializableEntity(source)) : null;
            var holder = store.removeEntity(source, RemoveReason.UNLOAD);
            tracker.onEntityUnloaded(identities.get(source), source, UnloadReason.DEACTIVATION, holder);
            return decoded ? registry.deserialize(saved) : holder;
        }

        @Override
        public void close() {
            tracker.close();
            registry.shutdown();
        }
    }

    private static final class Marker implements Component<Object> {
        @Override
        public Component<Object> clone() {
            return new Marker();
        }
    }

    private static final class Ticks extends RelationshipTickingSystem<Object, Void> {
        private final RelationshipQuery.Definition<Object, Void> query;
        private final List<Ref<Object>> sources = new ArrayList<>();
        private boolean unregistered;

        private Ticks(RelationshipQuery.Definition<Object, Void> query) { this.query = query; }
        @Nonnull @Override
        public RelationshipQuery.Definition<Object, Void> getQuery() { return query; }
        @Override
        protected void onRelationshipSystemUnregistered() { unregistered = true; }
        @Override
        protected void tickRelationship(float seconds, RelationshipResult<Object, Void> result,
            Store<Object> store, CommandBuffer<Object> commands) { sources.add(result.getSource()); }
    }

    private static final class Event extends EcsEvent { }

    private static final class HolderEvents extends RelationshipHolderEventSystem<Object, Integer, Event> {
        private final RelationshipQuery.Definition<Object, Integer> query;
        private final RelationshipQuery.Binding<Object, Object> binding;
        private int calls;
        private Holder<Object> holder;
        private Ref<Object> target;
        private List<Match> matches;

        private HolderEvents(RelationshipQuery.Definition<Object, Integer> query,
            @Nullable RelationshipQuery.Binding<Object, Object> binding) {
            super(Event.class);
            this.query = query;
            this.binding = binding;
        }
        @Nonnull @Override
        public RelationshipQuery.Definition<Object, Integer> getQuery() { return query; }
        @Override
        protected void handleRelationship(RelationshipResults<Object, Integer> results,
            Store<Object> store, CommandBuffer<Object> commands, Event event) {
            calls++;
            holder = results.get(0).getHolder();
            target = results.get(0).getTarget();
            if (binding != null) matches = copy(results, binding);
        }
    }

    private static final class Changes extends RelationshipRefChangeSystem<Object, Void, OutgoingLink<Object, Object>> {
        private final RelationshipQuery.ComponentChange<Object, Void, OutgoingLink<Object, Object>> query;
        private final RelationshipQuery.Binding<Object, Object> binding;
        private List<Match> matches;

        private Changes(RelationshipQuery.ComponentChange<Object, Void, OutgoingLink<Object, Object>> query,
            RelationshipQuery.Binding<Object, Object> binding) { this.query = query; this.binding = binding; }
        @Nonnull @Override
        public RelationshipQuery.ComponentChange<Object, Void, OutgoingLink<Object, Object>> getQuery() { return query; }
        @Override
        protected void onComponentAdded(RelationshipResults<Object, Void> results, OutgoingLink<Object, Object> component,
            Store<Object> store, CommandBuffer<Object> commands) { }
        @Override
        protected void onComponentSet(RelationshipResults<Object, Void> results, OutgoingLink<Object, Object> oldComponent,
            OutgoingLink<Object, Object> newComponent, Store<Object> store, CommandBuffer<Object> commands) { }
        @Override
        protected void onComponentRemoved(RelationshipResults<Object, Void> results, OutgoingLink<Object, Object> component,
            Store<Object> store, CommandBuffer<Object> commands) { matches = copy(results, binding); }
    }
}
