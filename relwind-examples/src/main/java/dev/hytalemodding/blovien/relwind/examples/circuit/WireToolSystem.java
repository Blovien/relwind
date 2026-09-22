/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.examples.circuit;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;

/// Turns block use into wiring. Only a player who ran `/relwind circuit wire` at least once
/// carries the tool.
public final class WireToolSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Post> {
    private final CircuitExample circuit;

    public WireToolSystem(CircuitExample circuit) {
        super(UseBlockEvent.Post.class);
        this.circuit = circuit;
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return WireTool.getComponentType();
    }

    @Override
    public void handle(
        int index,
        ArchetypeChunk<EntityStore> chunk,
        Store<EntityStore> entities,
        CommandBuffer<EntityStore> commands,
        UseBlockEvent.Post event
    ) {
        var user = chunk.getReferenceTo(index);
        var tool = chunk.getComponent(index, WireTool.getComponentType());
        if (tool == null) {
            return;
        }
        var world = entities.getExternalData().getWorld();
        var position = event.getTargetBlock();
        var block = BlockModule.getBlockEntity(world, position.x, position.y, position.z);
        if (block == null) {
            report(user, entities);
            return;
        }
        // the wire is a command on the chunk Store, which is not the Store being processed here
        var previous = tool.replaceLastUsed(block);
        if (tool.isArmed() && previous != null) {
            circuit.wire(world.getChunkStore().getStore(), previous, block);
            tool.disarm();
        }
    }

    private static void report(Ref<EntityStore> user, Store<EntityStore> entities) {
        var player = entities.getComponent(user, PlayerRef.getComponentType());
        if (player != null) {
            player.sendMessage(Message.raw("That block has no block entity, so it cannot join a circuit."));
        }
    }
}
