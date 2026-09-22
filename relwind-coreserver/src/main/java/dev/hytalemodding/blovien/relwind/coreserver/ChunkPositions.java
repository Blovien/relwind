/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/// Relwind reads a block entity and marks it for saving through these chunk Store components.
/// LegacyModule and BlockModule register them on a running server.
record ChunkPositions(
    ComponentType<ChunkStore, ChunkSection> section,
    ComponentType<ChunkStore, BlockComponentSection> blockComponents,
    ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockState
) {
    @Nonnull
    static ChunkPositions installed() {
        return new ChunkPositions(
            ChunkSection.getComponentType(),
            BlockComponentSection.getComponentType(),
            BlockModule.BlockStateInfo.getComponentType()
        );
    }

    /// The world block position of `ref`, or null when `ref` is not a block entity.
    @Nullable
    Vector3i getPositionOf(ComponentAccessor<ChunkStore> chunks, Ref<ChunkStore> ref) {
        var block = chunks.getComponent(ref, blockState);
        if (block == null) return null;
        var holding = getSectionOf(chunks, block);
        if (holding == null) return null;
        var index = block.getIndex();
        // a fresh Vector3i each call, because the directory keys on its value
        return new Vector3i(
            holding.getX() * ChunkUtil.SIZE + ChunkUtil.xFromIndex(index),
            holding.getY() * ChunkUtil.SIZE + ChunkUtil.yFromIndex(index),
            holding.getZ() * ChunkUtil.SIZE + ChunkUtil.zFromIndex(index));
    }

    /// Marks the section that saves `ref`, and the block entry inside it when `ref` is a block.
    void markNeedsSaving(ComponentAccessor<ChunkStore> chunks, Ref<ChunkStore> ref) {
        var block = chunks.getComponent(ref, blockState);
        if (block != null) {
            markBlockNeedsSaving(chunks, block);
            return;
        }
        var here = chunks.getComponent(ref, section);
        if (here != null) here.markNeedsSaving();
    }

    void markNeedsSaving(Holder<ChunkStore> parked) {
        var block = parked.getComponent(blockState);
        if (block == null || !block.getSectionRef().isValid()) return;
        markBlockNeedsSaving(block.getSectionRef().getStore(), block);
    }

    private void markBlockNeedsSaving(ComponentAccessor<ChunkStore> chunks, BlockModule.BlockStateInfo block) {
        var sectionRef = block.getSectionRef();
        if (!sectionRef.isValid()) return;
        var holders = chunks.getComponent(sectionRef, blockComponents);
        if (holders == null) return;
        // marks the section and the block's dirty entry, the way BlockStateInfo.markNeedsSaving does
        holders.markBlockNeedsSaving((short) block.getIndex());
    }

    @Nullable
    private ChunkSection getSectionOf(ComponentAccessor<ChunkStore> chunks, BlockModule.BlockStateInfo block) {
        var sectionRef = block.getSectionRef();
        return sectionRef.isValid() ? chunks.getComponent(sectionRef, section) : null;
    }
}
