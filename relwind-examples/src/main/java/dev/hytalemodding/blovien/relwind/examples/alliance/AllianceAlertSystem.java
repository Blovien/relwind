/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.examples.alliance;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.ecs.UseEntityEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.RelationshipEventSystem;
import dev.hytalemodding.blovien.relwind.RelationshipQuery;
import dev.hytalemodding.blovien.relwind.RelationshipResults;

import javax.annotation.Nonnull;

/// Tells the allies of an entity that it used something. Hytale raises [UseEntityEvent] on the
/// entity that performs the interaction. An entity with no resolved alliance within
/// [AllianceExample#ALERT_HOPS] hops never reaches the callback.
public final class AllianceAlertSystem extends RelationshipEventSystem<EntityStore, Void, UseEntityEvent.Post> {
    private final AllianceExample alliance;
    private final RelationshipQuery.Definition<EntityStore, Void> query;

    public AllianceAlertSystem(AllianceExample alliance) {
        super(UseEntityEvent.Post.class);
        this.alliance = alliance;
        this.query = RelationshipQuery.of(
            RelationshipQuery.reachable(alliance.getRelationshipType(), RelationshipQuery.Direction.OUTGOING, AllianceExample.ALERT_HOPS, Query.any()),
            alliance.getRelationshipType(),
            Query.any());
    }

    @Nonnull
    @Override
    public RelationshipQuery.Definition<EntityStore, Void> getQuery() {
        return query;
    }

    @Override
    protected void handleRelationship(
        RelationshipResults<EntityStore, Void> results,
        Store<EntityStore> store,
        CommandBuffer<EntityStore> commands,
        UseEntityEvent.Post event
    ) {
        // the source of every result is the entity the event fired for, which is where the walk starts
        var user = results.get(0).getSource();
        if (user == null) {
            return;
        }
        for (var ally : alliance.reach(user, AllianceExample.ALERT_HOPS).allies()) {
            deliver(store, ally.entity(), ally.depth(), user);
        }
    }

    /// Sends chat alerts to players while allowing the traversal to pass through creatures.
    private static void deliver(Store<EntityStore> store, Ref<EntityStore> ally, int depth, Ref<EntityStore> user) {
        var player = store.getComponent(ally, PlayerRef.getComponentType());
        if (player == null) {
            return;
        }
        var acting = store.getComponent(user, PlayerRef.getComponentType());
        player.sendMessage(Message.raw("Your ally " + (acting == null ? "nearby" : acting.getUsername())
            + " used something, " + depth + (depth == 1 ? " alliance" : " alliances") + " away."));
    }
}
