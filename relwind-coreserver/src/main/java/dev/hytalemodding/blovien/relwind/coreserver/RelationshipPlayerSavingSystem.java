/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.RunWhenPausedSystem;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.Dirty;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSavingSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.RelationshipMetadata;

import javax.annotation.Nonnull;

import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

/// Saves a player again when Hytale's own player saving consumed the dirty flags and then skipped
/// the write because another save was queued. Player.toHolder() shares the live components and
/// Player.clone() is unsupported.
final class RelationshipPlayerSavingSystem extends TickingSystem<EntityStore> implements RunWhenPausedSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final ComponentType<EntityStore, Pending> pendingType;
    private final Query<EntityStore> query;
    private final ComponentType<EntityStore, RelationshipMetadata<EntityStore>> metadataType;

    RelationshipPlayerSavingSystem(
        ComponentType<EntityStore, Pending> pendingType,
        ComponentType<EntityStore, RelationshipMetadata<EntityStore>> metadataType
    ) {
        this.pendingType = pendingType;
        this.metadataType = metadataType;
        query = Query.and(pendingType, Player.getComponentType(), Dirty.getComponentType());
    }

    @Nonnull @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return Set.of(new SystemDependency<>(Order.AFTER, PlayerSavingSystems.TickingSystem.class));
    }

    @Override
    public void tick(float dt, int systemIndex, Store<EntityStore> store) {
        var world = store.getExternalData().getWorld();
        if (world.isSavingLocked() || !world.getWorldConfig().isSavingPlayers()) return;
        store.forEachChunk(query, (chunk, commands) -> {
            for (int index = 0; index < chunk.size(); index++) {
                var ref = chunk.getReferenceTo(index);
                var dirty = java.util.Objects.requireNonNull(chunk.getComponent(index, Dirty.getComponentType()));
                var previous = dirty.getSavingFuture();
                if (previous != null) {
                    if (!previous.isDone()) continue;
                    try {
                        previous.join();
                    } catch (CompletionException | CancellationException failure) {
                        dirty.markDirty();
                        LOGGER.at(Level.SEVERE).withCause(failure).log("Failed to save player relationships");
                    }
                    dirty.clearSaving(previous);
                }
                if (!dirty.isDirty()) {
                    commands.removeComponent(ref, pendingType);
                    continue;
                }
                if (dirty.pollCooldown(dt) > 0) continue;
                var player = java.util.Objects.requireNonNull(chunk.getComponent(index, Player.getComponentType()));
                var snapshot = player.toHolder();
                var metadata = snapshot.getComponent(metadataType);
                if (metadata != null) snapshot.putComponent(metadataType, metadata.clone());
                var future = player.saveConfig(world, snapshot, true);
                dirty.consumeDirty();
                dirty.setSaving(future);
            }
        });
    }

    public static final class Pending implements Component<EntityStore> {
        static final Pending INSTANCE = new Pending();

        /// @throws IllegalStateException if no installation is registered
        @Nonnull
        public static ComponentType<EntityStore, Pending> getComponentType() {
            return CoreServerIntegration.get().getPlayerPendingComponentType();
        }

        @SuppressWarnings("MethodDoesntCallSuperMethod")
        @Nonnull @Override
        public Component<EntityStore> clone() {
            return this;
        }
    }
}
