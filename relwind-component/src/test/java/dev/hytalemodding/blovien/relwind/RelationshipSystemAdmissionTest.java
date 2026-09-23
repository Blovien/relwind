/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture.Position;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EcsEvent;
import com.hypixel.hytale.component.system.QuerySystem;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// With a tracker installed, entity-iterating relationship systems admit only archetypes whose
/// entities can match, while the query itself still admits holders without storage.
class RelationshipSystemAdmissionTest {
    private static final Relationships relationships = new Relationships();

    @ParameterizedTest
    @MethodSource("systems")
    void aTrackedSystemAdmitsOnlyTheArchetypeOfAnEntityWithSourceStorage(
        Function<Fixture, QuerySystem<Object>> factory
    ) {
        try (var fixture = new Fixture()) {
            var system = factory.apply(fixture);
            var unlinked = fixture.add();
            var source = fixture.add();
            relationships.addTarget(fixture.store, source, fixture.type, fixture.add());

            var admitsUnlinked = system.test(fixture.registry, fixture.store.getArchetype(unlinked));
            var admitsSource = system.test(fixture.registry, fixture.store.getArchetype(source));

            assertAll(
                () -> assertFalse(admitsUnlinked, "the archetype without source storage"),
                () -> assertTrue(admitsSource, "the archetype with source storage"));
        }
    }

    @Test
    void aTrackedQueryStillAdmitsTheArchetypeOfAnEntityWithoutSourceStorage() {
        try (var fixture = new Fixture()) {
            var unlinked = fixture.add();

            var admits = RelationshipQuery.of(Query.any(), fixture.type, Query.any())
                .test(fixture.store.getArchetype(unlinked));

            assertTrue(admits);
        }
    }

    private static Stream<Named<Function<Fixture, QuerySystem<Object>>>> systems() {
        return Stream.of(
            Named.of("RelationshipTickingSystem", RelationshipSystemAdmissionTest::tickingSystem),
            Named.of("RelationshipChunkTickingSystem", RelationshipSystemAdmissionTest::chunkTickingSystem),
            Named.of("RelationshipEventSystem", RelationshipSystemAdmissionTest::eventSystem),
            Named.of("RelationshipRefSystem", RelationshipSystemAdmissionTest::refSystem),
            Named.of("RelationshipRefChangeSystem", RelationshipSystemAdmissionTest::refChangeSystem));
    }

    private static QuerySystem<Object> tickingSystem(Fixture fixture) {
        var query = RelationshipQuery.of(Query.any(), fixture.type, Query.any());
        return new RelationshipTickingSystem<Object, Void>() {
            @Nonnull @Override
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
            }
        };
    }

    private static QuerySystem<Object> chunkTickingSystem(Fixture fixture) {
        var query = RelationshipQuery.of(Query.any(), fixture.type, Query.any());
        return new RelationshipChunkTickingSystem<Object, Void>() {
            @Nonnull @Override
            public RelationshipQuery.Definition<Object, Void> getQuery() {
                return query;
            }

            @Override
            protected void tickRelationship(
                float seconds,
                int sourceIndex,
                ArchetypeChunk<Object> sourceChunk,
                RelationshipResult<Object, Void> result,
                Store<Object> store,
                CommandBuffer<Object> commandBuffer
            ) {
            }
        };
    }

    private static QuerySystem<Object> eventSystem(Fixture fixture) {
        var query = RelationshipQuery.of(Query.any(), fixture.type, Query.any());
        return new RelationshipEventSystem<Object, Void, TestEvent>(TestEvent.class) {
            @Nonnull @Override
            public RelationshipQuery.Definition<Object, Void> getQuery() {
                return query;
            }

            @Override
            protected void handleRelationship(
                RelationshipResults<Object, Void> results,
                Store<Object> store,
                CommandBuffer<Object> commandBuffer,
                TestEvent event
            ) {
            }
        };
    }

    private static QuerySystem<Object> refSystem(Fixture fixture) {
        var query = RelationshipQuery.of(Query.any(), fixture.type, Query.any());
        return new RelationshipRefSystem<Object, Void>() {
            @Nonnull @Override
            public RelationshipQuery.Definition<Object, Void> getQuery() {
                return query;
            }

            @Override
            protected void onEntityAdded(
                RelationshipResults<Object, Void> results,
                AddReason reason,
                Store<Object> store,
                CommandBuffer<Object> commandBuffer
            ) {
            }

            @Override
            protected void onEntityRemove(
                RelationshipResults<Object, Void> results,
                RemoveReason reason,
                Store<Object> store,
                CommandBuffer<Object> commandBuffer
            ) {
            }
        };
    }

    private static QuerySystem<Object> refChangeSystem(Fixture fixture) {
        var query = RelationshipQuery.ComponentChange.of(Query.any(), fixture.type, Query.any(), fixture.positionType);
        return new RelationshipRefChangeSystem<Object, Void, Position>() {
            @Nonnull @Override
            public RelationshipQuery.ComponentChange<Object, Void, Position> getQuery() {
                return query;
            }

            @Override
            protected void onComponentAdded(
                RelationshipResults<Object, Void> results,
                Position component,
                Store<Object> store,
                CommandBuffer<Object> commandBuffer
            ) {
            }

            @Override
            protected void onComponentSet(
                RelationshipResults<Object, Void> results,
                @Nullable Position oldComponent,
                Position newComponent,
                Store<Object> store,
                CommandBuffer<Object> commandBuffer
            ) {
            }

            @Override
            protected void onComponentRemoved(
                RelationshipResults<Object, Void> results,
                Position component,
                Store<Object> store,
                CommandBuffer<Object> commandBuffer
            ) {
            }
        };
    }

    private static final class Fixture implements AutoCloseable {
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final ComponentType<Object, Position> positionType =
            registry.registerComponent(Position.class, Position::new);
        private final RelationshipTypeRegistry<Object> types = new RelationshipTypeRegistry<>(registry);
        private final IdentityHashMap<Ref<Object>, UUID> identities = new IdentityHashMap<>();
        private final RelationshipTracker<Object, UUID> tracker = types.installTracker(
            TestPersistenceIdentity.of(identities::get, Codec.UUID_BINARY),
            TestStoreRuntime.inline());
        private final RelationshipType<Object, Void> type = types.registerRelationship(RelationshipRules.multiple());
        private final Store<Object> store = registry.addStore(new Object(), EmptyResourceStorage.get());

        private Ref<Object> add() {
            var ref = store.addEntity(Archetype.empty(), AddReason.SPAWN);
            var id = UUID.randomUUID();
            identities.put(ref, id);
            tracker.onEntityLoaded(id, ref);
            return ref;
        }

        @Override
        public void close() {
            tracker.close();
            registry.shutdown();
        }
    }

    private static final class TestEvent extends EcsEvent {
    }
}
