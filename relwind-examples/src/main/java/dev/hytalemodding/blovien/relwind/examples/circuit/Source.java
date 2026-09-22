/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.examples.circuit;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import dev.hytalemodding.blovien.relwind.examples.RelwindExamplePlugin;

/// Marks a block entity that feeds the network. Every tick starts a traversal at each marked block.
public final class Source implements Component<ChunkStore> {
    public static ComponentType<ChunkStore, Source> getComponentType() {
        return RelwindExamplePlugin.get().getSourceComponentType();
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Override
    public Source clone() {
        return new Source();
    }
}
