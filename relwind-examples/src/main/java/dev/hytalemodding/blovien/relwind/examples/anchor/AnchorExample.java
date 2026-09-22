/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.examples.anchor;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.GenericRelationshipType;
import dev.hytalemodding.blovien.relwind.RelationshipRules;
import dev.hytalemodding.blovien.relwind.plugin.Relwind;
import org.joml.Vector3d;

import javax.annotation.Nullable;

/// A player's anchor: one saved link from a player to a block entity the player can travel back
/// to. Uses `addTarget`, `retarget`, `removeTarget`, `getFirstTarget` and `hasUnresolvedTargets`
/// on a bridge type registered with the chunk relationship type registry as its target.
public final class AnchorExample {
    /// A persistent type needs a namespaced id, because saved links carry it across plugins.
    public static final String ANCHORED_TO = "Example:AnchoredTo";

    /// `retainOnTransfer` lets the anchor follow a player between worlds, and `retainOnDeactivation`
    /// keeps it while the player is logged out or the section holding the block entity is unloaded.
    public static final RelationshipRules RULES =
        RelationshipRules.single().retainOnTransfer().retainOnDeactivation();

    private final GenericRelationshipType<EntityStore, ChunkStore, Void> anchoredTo;

    public AnchorExample(GenericRelationshipType<EntityStore, ChunkStore, Void> anchoredTo) {
        this.anchoredTo = anchoredTo;
    }

    /// Makes `block` the player's anchor, replacing whatever the player anchored to before.
    /// Call this only when [#getStatus] is not [Status.Away], because a single-target type refuses
    /// a new target while it holds an unresolved one.
    public void setAnchor(Store<EntityStore> players, Ref<EntityStore> player, Ref<ChunkStore> block) {
        var relationship = Relwind.get().getRelationships();
        var current = relationship.getFirstTarget(player, anchoredTo);
        if (current == null) {
            relationship.addTarget(players, player, anchoredTo, block);
        } else if (current != block) {
            relationship.retarget(players, player, anchoredTo, current, block);
        }
    }

    /// Removes the player's anchor and reports whether there was a resolved one to remove.
    /// An anchor that is away stays, because removing a link needs the reference of its target.
    public boolean clearAnchor(Store<EntityStore> players, Ref<EntityStore> player) {
        var relationship = Relwind.get().getRelationships();
        var anchor = relationship.getFirstTarget(player, anchoredTo);
        if (anchor == null) {
            return false;
        }
        relationship.removeTarget(players, player, anchoredTo, anchor);
        return true;
    }

    public Status getStatus(Ref<EntityStore> player, Store<ChunkStore> chunks) {
        var relationship = Relwind.get().getRelationships();
        var anchor = relationship.getFirstTarget(player, anchoredTo);
        if (anchor == null) {
            return relationship.hasUnresolvedTargets(player, anchoredTo) ? new Status.Away() : new Status.Unset();
        }
        var at = getPositionOf(anchor, chunks);
        // the block entity is registered but its section is not readable
        return at == null ? new Status.Away() : at;
    }

    /// Queues the teleport through the same `Teleport` component the built-in teleport commands add.
    public void travelTo(Store<EntityStore> players, Ref<EntityStore> player, Status.At anchor) {
        // the centre of the block's upper face, where the player lands on top of the anchor
        var destination = new Vector3d(anchor.x() + 0.5, anchor.y() + 1.0, anchor.z() + 0.5);
        players.putComponent(player, Teleport.getComponentType(),
            Teleport.createForPlayer(destination, Rotation3f.NaN));
    }

    @Nullable
    private Status.At getPositionOf(Ref<ChunkStore> block, Store<ChunkStore> chunks) {
        var state = chunks.getComponent(block, BlockModule.BlockStateInfo.getComponentType());
        if (state == null) {
            return null;
        }
        var sectionRef = state.getSectionRef();
        var section = sectionRef.isValid() ? chunks.getComponent(sectionRef, ChunkSection.getComponentType()) : null;
        if (section == null) {
            return null;
        }
        int index = state.getIndex();
        return new Status.At(
            ChunkUtil.worldCoordFromLocalCoord(section.getX(), ChunkUtil.xFromIndex(index)),
            ChunkUtil.worldCoordFromLocalCoord(section.getY(), ChunkUtil.yFromIndex(index)),
            ChunkUtil.worldCoordFromLocalCoord(section.getZ(), ChunkUtil.zFromIndex(index)));
    }

    /// What `/relwind anchor status` has to report.
    public sealed interface Status {
        record Unset() implements Status {
        }

        /// The anchor is kept, but its section is not loaded. Relwind hands out no reference for it.
        record Away() implements Status {
        }

        /// The anchor is set and its block entity is loaded, at this world block position.
        record At(int x, int y, int z) implements Status {
        }
    }
}
