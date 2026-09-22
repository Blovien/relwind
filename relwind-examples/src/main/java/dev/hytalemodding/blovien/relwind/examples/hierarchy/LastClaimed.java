/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.examples.hierarchy;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.examples.RelwindExamplePlugin;

import javax.annotation.Nullable;

/// The creature an owner claimed or nested last, which is where `/relwind own nest` attaches the
/// next one. It has no codec, and a session starts with no nesting point.
public final class LastClaimed implements Component<EntityStore> {
    @Nullable
    private Ref<EntityStore> creature;

    public LastClaimed() {
    }

    public LastClaimed(Ref<EntityStore> creature) {
        this.creature = creature;
    }

    public static ComponentType<EntityStore, LastClaimed> getComponentType() {
        return RelwindExamplePlugin.get().getLastClaimedComponentType();
    }

    @Nullable
    public Ref<EntityStore> getCreature() {
        return creature;
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Override
    public LastClaimed clone() {
        var copy = new LastClaimed();
        copy.creature = creature;
        return copy;
    }
}
