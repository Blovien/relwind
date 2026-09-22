/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.examples.circuit;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import dev.hytalemodding.blovien.relwind.examples.RelwindExamplePlugin;

/// The strength a block entity carries this tick. A block without this component is unpowered.
/// It has no codec. The circuit is never saved and is recomputed from the links.
public final class Powered implements Component<ChunkStore> {
    private int strength;

    public Powered() {
    }

    Powered(int strength) {
        this.strength = strength;
    }

    public static ComponentType<ChunkStore, Powered> getComponentType() {
        return RelwindExamplePlugin.get().getPoweredComponentType();
    }

    public int getStrength() {
        return strength;
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Override
    public Powered clone() {
        return new Powered(strength);
    }
}
