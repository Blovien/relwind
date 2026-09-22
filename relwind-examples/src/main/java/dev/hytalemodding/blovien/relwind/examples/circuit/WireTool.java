/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.examples.circuit;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.examples.RelwindExamplePlugin;

import javax.annotation.Nullable;

/// The wire tool of one player: the last two block entities they used and whether the next pair
/// should be wired together. It lives on the player entity the block-use event is dispatched to.
public final class WireTool implements Component<EntityStore> {
    private boolean armed;
    @Nullable
    private Ref<ChunkStore> previousUsed;
    @Nullable
    private Ref<ChunkStore> lastUsed;

    public static ComponentType<EntityStore, WireTool> getComponentType() {
        return RelwindExamplePlugin.get().getWireToolComponentType();
    }

    /// Arms the tool. The next two blocks the player uses are the pair to wire.
    void arm() {
        armed = true;
        previousUsed = null;
        lastUsed = null;
    }

    void disarm() {
        armed = false;
    }

    boolean isArmed() {
        return armed;
    }

    /// Records a used block and returns the block used before it, or null when there is none.
    @Nullable
    Ref<ChunkStore> replaceLastUsed(Ref<ChunkStore> block) {
        previousUsed = lastUsed;
        lastUsed = block;
        return previousUsed;
    }

    @Nullable
    Ref<ChunkStore> getPreviousUsed() {
        return previousUsed;
    }

    @Nullable
    Ref<ChunkStore> getLastUsed() {
        return lastUsed;
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Override
    public WireTool clone() {
        var copy = new WireTool();
        copy.armed = armed;
        copy.previousUsed = previousUsed;
        copy.lastUsed = lastUsed;
        return copy;
    }
}
