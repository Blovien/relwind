/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.StoreFixture.Position;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HierarchyScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Relationships apply to native children as well as roots. A relationship link does not itself
/// establish Hytale parenting.
class NativeHierarchyTest {
    private final Relationships relationships = new Relationships();

    @Test
    void tickingMatchesExplicitIterationBeforeAndAfterReparenting() {
        try (var fixture = new StoreFixture()) {
            var type = new RelationshipTypeRegistry<>(fixture.registry())
                .registerRelationship(RelationshipRules.single());
            var parent = fixture.addEntity(new Position(), null);
            var source = fixture.addEntity(new Position(), null);
            var target = fixture.addEntity(new Position(), null);
            relationships.addTarget(fixture.store(), source, type, target);
            var query = RelationshipQuery.of(type, Query.any());
            var ticked = new ArrayList<Ref<Object>>();
            fixture.registry().registerSystem(new RelationshipTickingSystem<Object, Void>() {
                @Override
                public RelationshipQuery.Definition<Object, Void> getQuery() {
                    return query;
                }

                @Override
                protected void tickRelationship(float seconds, RelationshipResult<Object, Void> result,
                                                Store<Object> store, CommandBuffer<Object> commands) {
                    ticked.add(result.getSource());
                }
            });

            fixture.tick(0.05f);

            assertEquals(List.of(source), ticked);

            ticked.clear();
            fixture.store().setParent(source, parent);
            var iterated = new ArrayList<Ref<Object>>();
            relationships.forEach(fixture.store(), query, (result, commands) -> iterated.add(result.getSource()));

            assertEquals(List.of(source), iterated);

            fixture.tick(0.05f);

            assertEquals(List.of(source), ticked, "A native child must still receive relationship ticks");

            ticked.clear();
            fixture.store().setParent(source, null);
            fixture.tick(0.05f);

            assertEquals(List.of(source), ticked);
        }
    }

    @Test
    void deletingANativeChildCascadesIntoItsRelationshipSource() {
        try (var fixture = new StoreFixture()) {
            var type = new RelationshipTypeRegistry<>(fixture.registry())
                .registerRelationship(RelationshipRules.single().cascadeSource());
            var parent = fixture.addEntity(new Position(), null);
            var source = fixture.addEntity(new Position(), null);
            var target = fixture.addEntity(new Position(), null);
            fixture.store().setParent(target, parent);
            relationships.addTarget(fixture.store(), source, type, target);

            fixture.store().removeEntity(target, RemoveReason.REMOVE);

            assertFalse(target.isValid());
            assertFalse(source.isValid(), "Deleting a child target must execute relationship deletion rules");
            assertTrue(parent.isValid());
        }
    }

    @Test
    void pluginCanExplicitlyRestrictItsTicksToRoots() {
        try (var fixture = new StoreFixture()) {
            var type = new RelationshipTypeRegistry<>(fixture.registry())
                .registerRelationship(RelationshipRules.single());
            var parent = fixture.addEntity(new Position(), null);
            var child = fixture.addEntity(new Position(), null);
            var target = fixture.addEntity(new Position(), null);
            fixture.store().setParent(child, parent);
            relationships.addTarget(fixture.store(), parent, type, target);
            relationships.addTarget(fixture.store(), child, type, target);
            var ticked = new ArrayList<Ref<Object>>();
            fixture.registry().registerSystem(new RelationshipTickingSystem<Object, Void>() {
                @Override
                public HierarchyScope getHierarchyScope() {
                    return HierarchyScope.ROOT;
                }

                @Override
                public RelationshipQuery.Definition<Object, Void> getQuery() {
                    return RelationshipQuery.of(type, Query.any());
                }

                @Override
                protected void tickRelationship(float seconds, RelationshipResult<Object, Void> result,
                                                Store<Object> store, CommandBuffer<Object> commands) {
                    ticked.add(result.getSource());
                }
            });

            fixture.tick(0.05f);

            assertEquals(List.of(parent), ticked);
        }
    }

    @Test
    void replacingLinkDataOnANativeChildAnnouncesTheChange() {
        try (var fixture = new StoreFixture()) {
            var type = new RelationshipTypeRegistry<>(fixture.registry()).registerRelationship(
                fixture.positionType(), new RelationshipDataObserver<Object, Position>() { },
                RelationshipRules.single());
            var parent = fixture.addEntity(new Position(), null);
            var child = fixture.addEntity(new Position(), null);
            var target = fixture.addEntity(new Position(), null);
            fixture.store().setParent(child, parent);
            var initial = new Position(1, 2);
            relationships.addTarget(fixture.store(), child, type, target, initial);
            var changes = new ArrayList<Position>();
            var announcedSources = new ArrayList<Ref<Object>>();
            var announcedTargets = new ArrayList<Ref<Object>>();
            var announcedOldData = new ArrayList<Position>();
            fixture.registry().registerSystem(new RelationshipChangeSystem<Object, Position>(type) {
                @Override
                protected void onRelationshipSet(LinkedEntity<Object> source, LinkedEntity<Object> linkedTarget,
                                                 Position oldData, Position data, Store<Object> store,
                                                 CommandBuffer<Object> commands) {
                    announcedSources.add(source.reference());
                    announcedTargets.add(linkedTarget.reference());
                    announcedOldData.add(oldData);
                    changes.add(data);
                }
            });
            var replacement = new Position(3, 4);

            fixture.store().putComponent(child, fixture.positionType(), replacement);

            assertEquals(List.of(replacement), changes);
            assertSame(child, announcedSources.getFirst());
            assertSame(target, announcedTargets.getFirst());
            assertSame(initial, announcedOldData.getFirst());
            assertSame(replacement, relationships.getData(child, type, target));
        }
    }
}
