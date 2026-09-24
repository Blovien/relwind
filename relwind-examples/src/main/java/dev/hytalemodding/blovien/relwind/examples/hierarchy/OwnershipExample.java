/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.examples.hierarchy;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.RelationshipQuery;
import dev.hytalemodding.blovien.relwind.RelationshipTraits;
import dev.hytalemodding.blovien.relwind.RelationshipType;
import dev.hytalemodding.blovien.relwind.plugin.Relwind;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/// A tree of owned creatures. Each link points from the owned creature to its owner, and
/// `onDeleteTarget(DELETE)` deletes what a deleted owner owns, one level after the other. Uses
/// `addTarget`, `retarget`, `removeTarget`, `getFirstTarget` and reachable queries through Relationships.
public final class OwnershipExample {
    /// A persistent type needs a namespaced id, because saved links carry it across plugins.
    public static final String OWNED_BY = "Example:OwnedBy";

    public static final RelationshipTraits TRAITS = RelationshipTraits.defaults().exclusive()
        .onDeleteTarget(RelationshipTraits.OnDeleteTarget.DELETE);

    /// A depth limit is required, and eight levels is more than a player builds by hand.
    public static final int LISTING_DEPTH = 8;

    private final RelationshipType<EntityStore, Void> ownedBy;

    public OwnershipExample(RelationshipType<EntityStore, Void> ownedBy) {
        this.ownedBy = ownedBy;
    }

    /// Makes `owner` the owner of `creature` and the creature the owner's nesting point.
    public void claim(Store<EntityStore> store, Ref<EntityStore> owner, Ref<EntityStore> creature) {
        attach(store, creature, owner);
        store.putComponent(owner, LastClaimed.getComponentType(), new LastClaimed(creature));
    }

    /// Makes the creature this owner claimed or nested last the owner of `creature`, then moves the
    /// nesting point onto `creature`. Answers false when there is no nesting point, and when that
    /// point is the creature itself or one below it, which is the one link that would close a loop.
    public boolean nest(Store<EntityStore> store, Ref<EntityStore> owner, Ref<EntityStore> creature) {
        var above = getLastClaimed(owner, store);
        if (above == null || isUnder(above, creature)) {
            return false;
        }
        attach(store, creature, above);
        store.putComponent(owner, LastClaimed.getComponentType(), new LastClaimed(creature));
        return true;
    }

    /// Removes the creature's own owner link. Whatever that creature owns stays attached to it.
    public boolean release(Store<EntityStore> store, Ref<EntityStore> creature) {
        var relationship = Relwind.get().getRelationships();
        var owner = relationship.getFirstTarget(creature, ownedBy);
        if (owner == null) {
            return false;
        }
        relationship.removeTarget(store, creature, ownedBy, owner);
        return true;
    }

    /// Every creature below `owner`, each reported once at the depth it was first reached.
    public Listing getListing(Ref<EntityStore> owner) {
        var query = RelationshipQuery.enumerateReachable(
            ownedBy, RelationshipQuery.Direction.INCOMING, LISTING_DEPTH, Query.any());
        return Relwind.get().getRelationships().fetch(owner, query, results -> {
            var owned = new ArrayList<Owned>(results.size());
            for (var result : results) {
                owned.add(new Owned(result.getTarget(), result.getDepth()));
            }
            return new Listing(List.copyOf(owned), results.isTruncated());
        });
    }

    /// Removes the creature from the Store and answers how many creatures the cascade takes with it.
    /// The count is bounded by [#LISTING_DEPTH], while the cascade follows the whole subtree.
    public int dismiss(Store<EntityStore> store, Ref<EntityStore> creature) {
        int owned = getListing(creature).owned().size();
        store.removeEntity(creature, RemoveReason.REMOVE);
        return owned;
    }

    /// Dismissing a creature leaves its Ref in LastClaimed.
    @Nullable
    public Ref<EntityStore> getLastClaimed(Ref<EntityStore> owner, Store<EntityStore> store) {
        var claimed = store.getComponent(owner, LastClaimed.getComponentType());
        if (claimed == null || claimed.getCreature() == null || !claimed.getCreature().isValid()) {
            return null;
        }
        return claimed.getCreature();
    }

    private boolean isUnder(Ref<EntityStore> creature, Ref<EntityStore> root) {
        // this walk ends at the top owner as long as no link closes a loop
        // only [#nest] checks for one
        var relationship = Relwind.get().getRelationships();
        for (var above = creature; above != null; above = relationship.getFirstTarget(above, ownedBy)) {
            if (above == root) {
                return true;
            }
        }
        return false;
    }

    private void attach(Store<EntityStore> store, Ref<EntityStore> creature, Ref<EntityStore> owner) {
        var relationship = Relwind.get().getRelationships();
        var current = relationship.getFirstTarget(creature, ownedBy);
        if (current == null) {
            relationship.addTarget(store, creature, ownedBy, owner);
        } else if (current != owner) {
            relationship.retarget(store, creature, ownedBy, current, owner);
        }
    }

    /// One creature below the listed owner, and the number of links between the two.
    public record Owned(Ref<EntityStore> creature, int depth) {
    }

    /// `truncated` says the depth limit or an unresolved link cut the walk. The tree holds more.
    public record Listing(List<Owned> owned, boolean truncated) {
    }
}
