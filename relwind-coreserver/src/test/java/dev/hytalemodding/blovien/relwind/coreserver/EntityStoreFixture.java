/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.IComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.function.consumer.BooleanConsumer;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.RelationshipMetadata;
import dev.hytalemodding.blovien.relwind.RelationshipTracker;
import dev.hytalemodding.blovien.relwind.StoreInstallation;
import dev.hytalemodding.blovien.relwind.StoreRuntime;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/// One entity Store registry carrying the two component types the composition root registers.
/// The root itself needs a running server.
final class EntityStoreFixture implements AutoCloseable {
    private final ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
    private final List<BooleanConsumer> pluginShutdown = new ArrayList<>();
    private final Tombstones.Installation<EntityStore, UUID> deletions;
    private final StoreInstallation<EntityStore, UUID> installation;
    private final ComponentType<EntityStore, RelationshipMetadata<EntityStore>> metadata;

    EntityStoreFixture() {
        this(false);
    }

    /// Registers through a plugin's ComponentRegistryProxy when `throughProxy`. A test can then
    /// run the shutdown tasks PluginBase runs for a disabled plugin.
    EntityStoreFixture(boolean throughProxy) {
        IComponentRegistry<EntityStore> registrar = throughProxy ? new ComponentRegistryProxy<>(pluginShutdown, registry) : registry;
        // the native modules own these two on a running server. They go before the Relwind ones,
        // which a shutdown test releases from the end of the registry
        registry.registerComponent(UUIDComponent.class, () -> new UUIDComponent(new UUID(0, 0)));
        registry.registerComponent(PlayerRef.class, () -> {
            throw new UnsupportedOperationException();
        });
        deletions = new Tombstones.Installation<>(
            registry, registrar, Tombstones.ENTITY_RESOURCE_ID, CoreServerTracker.IDENTITY_CODEC);
        registrar.registerComponent(
            RelationshipPlayerSavingSystem.Pending.class, () -> RelationshipPlayerSavingSystem.Pending.INSTANCE);
        installation = new StoreInstallation<>(
            registry, registrar, new EntityPersistenceIdentity(deletions), new BareRuntime());
        metadata = installation.installPersistence().getComponentType();
    }

    @Nonnull
    ComponentRegistry<EntityStore> registry() {
        return registry;
    }

    /// Stops runtime tracking, leaving registrations to the plugin shutdown tasks.
    void runIntegrationClose() {
        installation.getTracker().close();
    }

    /// Runs the shutdown tasks from the last one back, as PluginBase.cleanup does.
    void runPluginShutdown() {
        for (int index = pluginShutdown.size() - 1; index >= 0; index--) {
            pluginShutdown.get(index).accept(false);
        }
    }

    void shutDownRegistry() {
        registry.shutdown();
    }

    @Nonnull
    StoreInstallation<EntityStore, UUID> installation() {
        return installation;
    }

    @Nonnull
    ComponentType<EntityStore, RelationshipMetadata<EntityStore>> metadata() {
        return metadata;
    }

    @Override
    public void close() {
        installation.close();
        deletions.close();
        registry.shutdown();
    }

    /// The production runtime needs the running server's entity module, and this fixture registers
    /// types only.
    private static final class BareRuntime implements StoreRuntime<EntityStore> {
        @Override
        public void execute(Store<EntityStore> store, Runnable action) {
            store.assertThread();
            store.assertWriteProcessing();
            action.run();
        }

        @Override
        public void markNeedsSaving(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref) { }

        @Override
        public void markNeedsSaving(Holder<EntityStore> holder) { }

        @Override
        public boolean isDeletionSupported() {
            return true;
        }

        @Nonnull
        @Override
        public RefSystem<EntityStore> getTransitionSystem(RelationshipTracker<EntityStore, ?> installed) {
            return new RefSystem<>() {
                @Override
                public Query<EntityStore> getQuery() {
                    return Query.not(Query.any());
                }

                @Override
                public void onEntityAdded(
                    Ref<EntityStore> ref,
                    AddReason reason,
                    Store<EntityStore> store,
                    CommandBuffer<EntityStore> buffer
                ) { }

                @Override
                public void onEntityRemove(
                    Ref<EntityStore> ref,
                    RemoveReason reason,
                    Store<EntityStore> store,
                    CommandBuffer<EntityStore> buffer
                ) { }
            };
        }
    }
}
