/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.examples.circuit;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.RelationshipQuery;
import dev.hytalemodding.blovien.relwind.plugin.Relwind;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;

import static dev.hytalemodding.blovien.relwind.examples.RelwindExamplePlugin.POWERS;

/// A block signal network: wires join block entities, marked sources feed them, and every tick
/// [PowerTickingSystem] recomputes how much signal each block carries. Nothing needs saving, and this runtime type has
/// no id. Reads and changes wires through Relationships and follows them with reachable queries.
public final class CircuitExample {
    /// Bounds the diagnostic walk, including wires beyond the signal's reach.
    public static final int MAX_DEPTH = 32;

    public void markSource(Store<ChunkStore> chunks, Ref<ChunkStore> block) {
        chunks.putComponent(block, Source.getComponentType(), new Source());
    }

    /// `putTarget` inserts or replaces. A pair that already exists starts a fresh signal.
    public void wire(Store<ChunkStore> chunks, Ref<ChunkStore> from, Ref<ChunkStore> to) {
        Relwind.get().getRelationships().putTarget(chunks, from, POWERS, to, new Signal(0));
    }

    /// Removes the wire between two block entities and reports whether there was one.
    public boolean unwire(Store<ChunkStore> chunks, Ref<ChunkStore> from, Ref<ChunkStore> to) {
        var relationship = Relwind.get().getRelationships();
        if (relationship.getData(from, POWERS, to) == null) {
            return false;
        }
        relationship.removeTarget(chunks, from, POWERS, to);
        return true;
    }

    /// The strength a block entity carries, or zero when it is unpowered.
    public int getStrength(Ref<ChunkStore> block, Store<ChunkStore> chunks) {
        var powered = chunks.getComponent(block, Powered.getComponentType());
        return powered == null ? 0 : powered.getStrength();
    }

    /// Whether a source can be reached by following wires backwards, even beyond the signal's reach.
    public boolean hasReachableSource(Ref<ChunkStore> block) {
        var query = RelationshipQuery.enumerateReachable(
            POWERS, RelationshipQuery.Direction.INCOMING, MAX_DEPTH, Source.getComponentType());
        return Relwind.get().getRelationships().fetch(block, query, results -> !results.isEmpty());
    }

    /// The strength each wire reaching a block entity carried on the last tick, in the order the
    /// incoming wires are stored. A wire no tick has reached yet carries zero.
    @Nonnull
    public List<Integer> getIncomingStrengths(Ref<ChunkStore> block) {
        // one hop reports the wires that end at this block and nothing behind them
        var query = RelationshipQuery.enumerateReachable(
            POWERS, RelationshipQuery.Direction.INCOMING, 1, Query.any());
        return Relwind.get().getRelationships().fetch(block, query, results -> {
            var strengths = new ArrayList<Integer>(results.size());
            for (var wire : results) {
                var signal = wire.getData();
                strengths.add(signal == null ? 0 : signal.strength());
            }
            return strengths;
        });
    }

    @Nonnull
    WireTool ensureWireTool(Store<EntityStore> entities, Ref<EntityStore> player) {
        var tool = entities.getComponent(player, WireTool.getComponentType());
        if (tool == null) {
            tool = new WireTool();
            entities.putComponent(player, WireTool.getComponentType(), tool);
        }
        return tool;
    }
}
