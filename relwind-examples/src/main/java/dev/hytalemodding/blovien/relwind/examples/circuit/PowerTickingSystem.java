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
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import dev.hytalemodding.blovien.relwind.RelationshipChunkTickingSystem;
import dev.hytalemodding.blovien.relwind.RelationshipQuery;
import dev.hytalemodding.blovien.relwind.RelationshipResult;
import dev.hytalemodding.blovien.relwind.plugin.Relwind;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static dev.hytalemodding.blovien.relwind.examples.RelwindExamplePlugin.POWERS;
import static dev.hytalemodding.blovien.relwind.examples.circuit.CircuitExample.MAX_DEPTH;

/// Propagates the signal once per tick. The relationship adapter visits the block entities
/// that hold a wire, per archetype chunk.
public final class PowerTickingSystem extends RelationshipChunkTickingSystem<ChunkStore, Signal> {
    /// Each hop drops the strength by one, reaching fifteen blocks beyond a source.
    public static final int SOURCE_STRENGTH = 16;

    private final RelationshipQuery.Definition<ChunkStore, Signal> query = RelationshipQuery.of(Query.any(), POWERS, Query.any());
    private final RelationshipQuery.ReachableEnumeration<ChunkStore, Signal> propagationQuery =
        RelationshipQuery.enumerateReachable(
                POWERS,
                RelationshipQuery.Direction.OUTGOING,
                MAX_DEPTH,
                Query.any());
    private final Set<Ref<ChunkStore>> tickedSources = new LinkedHashSet<>();
    private final List<Wire> tickedWires = new ArrayList<>();

    @Nonnull
    @Override
    public RelationshipQuery.Definition<ChunkStore, Signal> getQuery() {
        return query;
    }

    /// Collect all wires before writing strengths, so one source cannot clear another's work.
    @Override
    public void tick(float seconds, int systemIndex, Store<ChunkStore> chunks) {
        tickedSources.clear();
        tickedWires.clear();
        super.tick(seconds, systemIndex, chunks);
        endTick(chunks);
    }

    @Override
    protected void tickRelationship(
        float seconds,
        int sourceIndex,
        ArchetypeChunk<ChunkStore> sourceChunk,
        RelationshipResult<ChunkStore, Signal> result,
        Store<ChunkStore> chunks,
        CommandBuffer<ChunkStore> commands
    ) {
        visitWire(sourceChunk.getReferenceTo(sourceIndex), result.getTarget(), chunks);
    }

    /// Records one wire the ticking system visited, and its source when that source feeds the network.
    private void visitWire(Ref<ChunkStore> from, Ref<ChunkStore> to, Store<ChunkStore> chunks) {
        tickedWires.add(new Wire(from, to));
        if (chunks.getComponent(from, Source.getComponentType()) != null) {
            tickedSources.add(from);
        }
    }

    /// Recomputes the network from the wires that were visited. Every block loses last tick's
    /// strength first, and a block no source reaches ends the tick unpowered.
    private void endTick(Store<ChunkStore> chunks) {
        clearPowered(chunks);
        var relationship = Relwind.get().getRelationships();
        for (var source : tickedSources) {
            power(chunks, source, SOURCE_STRENGTH);
            relationship.fetch(source, propagationQuery, results -> {
                for (var reached : results) {
                    power(chunks, reached.getTarget(), SOURCE_STRENGTH - reached.getDepth());
                }
                return null;
            });
        }
        for (var wire : tickedWires) {
            var signal = relationship.getData(wire.from(), POWERS, wire.to());
            var powered = chunks.getComponent(wire.to(), Powered.getComponentType());
            int strength = powered == null ? 0 : powered.getStrength();
            if (signal != null && signal.strength() != strength) {
                relationship.putTarget(chunks, wire.from(), POWERS, wire.to(), new Signal(strength));
            }
        }
    }

    /// Raises a block entity to this strength, keeping the larger value when two sources reach it.
    private void power(Store<ChunkStore> chunks, Ref<ChunkStore> block, int strength) {
        if (strength <= 0) {
            return;
        }
        var powered = chunks.getComponent(block, Powered.getComponentType());
        if (powered != null && powered.getStrength() >= strength) {
            return;
        }
        chunks.putComponent(block, Powered.getComponentType(), new Powered(strength));
    }

    private void clearPowered(Store<ChunkStore> chunks) {
        chunks.forEachChunk(Powered.getComponentType(), (chunk, commands) -> {
            for (int index = 0; index < chunk.size(); index++) {
                commands.removeComponent(chunk.getReferenceTo(index), Powered.getComponentType());
            }
        });
    }

    private record Wire(Ref<ChunkStore> from, Ref<ChunkStore> to) {
    }
}
