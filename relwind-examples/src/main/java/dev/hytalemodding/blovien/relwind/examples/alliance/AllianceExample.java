/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.examples.alliance;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.RelationshipQuery;
import dev.hytalemodding.blovien.relwind.RelationshipTraits;
import dev.hytalemodding.blovien.relwind.RelationshipType;
import dev.hytalemodding.blovien.relwind.plugin.Relwind;

import java.util.ArrayList;
import java.util.List;

/// Alliances between entities. One recursive walk reads the allies of an ally. An alliance is
/// symmetric and stored as two links, and one direction reaches the whole group. Uses
/// `addTarget`, `removeTarget`, `forEachTarget`, `hasUnresolvedTargets` and reachable queries through Relationships.
public final class AllianceExample {
    /// The type is registered without an id, which makes it a runtime type: an alliance between
    /// entities that are in the world together is not worth saving.
    public static final RelationshipTraits TRAITS = RelationshipTraits.defaults();

    /// Two hops is an ally and an ally of that ally.
    public static final int ALERT_HOPS = 2;

    /// A depth limit is required, and eight hops crosses any group a player builds by hand.
    public static final int MAX_HOPS = 8;

    private final RelationshipType<EntityStore, Void> alliedWith;

    public AllianceExample(RelationshipType<EntityStore, Void> alliedWith) {
        this.alliedWith = alliedWith;
    }

    RelationshipType<EntityStore, Void> getRelationshipType() {
        return alliedWith;
    }

    /// Allies the two entities with each other and answers whether that changed anything.
    public boolean ally(Store<EntityStore> store, Ref<EntityStore> entity, Ref<EntityStore> other) {
        if (entity == other || isAllied(entity, other)) {
            return false;
        }
        var relationship = Relwind.get().getRelationships();
        relationship.addTarget(store, entity, alliedWith, other);
        relationship.addTarget(store, other, alliedWith, entity);
        return true;
    }

    /// Removes both links of one alliance and answers whether there was one to remove.
    public boolean unally(Store<EntityStore> store, Ref<EntityStore> entity, Ref<EntityStore> other) {
        if (!isAllied(entity, other)) {
            return false;
        }
        var relationship = Relwind.get().getRelationships();
        relationship.removeTarget(store, entity, alliedWith, other);
        relationship.removeTarget(store, other, alliedWith, entity);
        return true;
    }

    /// Whether the two are direct allies. An alliance whose other side left the world is unresolved
    /// and counts as not allied until it comes back.
    public boolean isAllied(Ref<EntityStore> entity, Ref<EntityStore> other) {
        return getAllies(entity).contains(other);
    }

    /// The direct allies of one entity, without following any of theirs.
    public List<Ref<EntityStore>> getAllies(Ref<EntityStore> entity) {
        var relationship = Relwind.get().getRelationships();
        var allies = new ArrayList<Ref<EntityStore>>(relationship.getTargetCount(entity, alliedWith));
        relationship.forEachTarget(entity, alliedWith, allies::add);
        return List.copyOf(allies);
    }

    /// Whether one of this entity's alliances is waiting for a linked entity that left the world.
    public boolean isWaitingForAnAlly(Ref<EntityStore> entity) {
        return Relwind.get().getRelationships().hasUnresolvedTargets(entity, alliedWith);
    }

    /// Every ally within `hops` alliances, each reported once at the distance it was first reached.
    public Reach reach(Ref<EntityStore> start, int hops) {
        var query = RelationshipQuery.enumerateReachable(
            alliedWith, RelationshipQuery.Direction.OUTGOING, hops, Query.any());
        return Relwind.get().getRelationships().fetch(start, query, results -> {
            var allies = new ArrayList<Ally>(results.size());
            for (var result : results) {
                allies.add(new Ally(result.getTarget(), result.getDepth()));
            }
            return new Reach(List.copyOf(allies), results.isTruncated());
        });
    }

    /// One reached ally and the number of alliances between it and the start of the walk.
    public record Ally(Ref<EntityStore> entity, int depth) {
    }

    /// `truncated` says the hop limit or an unresolved alliance cut the walk. The group holds more.
    public record Reach(List<Ally> allies, boolean truncated) {
    }
}
