/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.IComponentRegistry;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;

import javax.annotation.Nonnull;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/// The confirmed deletions of one world's Store, keyed the way that Store identifies a linked entity.
/// Hytale saves them with the Store's other resources.
final class Tombstones<ECS_TYPE, ID> implements Resource<ECS_TYPE> {
    static final String ENTITY_RESOURCE_ID = "RelwindTombstones";
    static final String CHUNK_RESOURCE_ID = "RelwindBlockDeletions";

    private final Set<ID> linkedEntities = new HashSet<>();

    @Nonnull
    static <ECS_TYPE, ID> BuilderCodec<Tombstones<ECS_TYPE, ID>> getCodec(Codec<ID> identityCodec) {
        @SuppressWarnings("unchecked")
        var identities = new ArrayCodec<>(identityCodec, size -> (ID[]) new Object[size]);
        @SuppressWarnings("unchecked")
        var tombstonesClass = (Class<Tombstones<ECS_TYPE, ID>>) (Class<?>) Tombstones.class;
        return BuilderCodec.builder(
            tombstonesClass, Tombstones::new
        ).append(
            new KeyedCodec<>("Identities", identities),
            Tombstones::restore,
            Tombstones::getSnapshot
        ).add().build();
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Nonnull
    @Override
    public synchronized Tombstones<ECS_TYPE, ID> clone() {
        var copy = new Tombstones<ECS_TYPE, ID>();
        copy.linkedEntities.addAll(linkedEntities);
        return copy;
    }

    synchronized void record(ID id) {
        linkedEntities.add(Objects.requireNonNull(id, "id"));
    }

    synchronized boolean contains(ID id) {
        return linkedEntities.contains(Objects.requireNonNull(id, "id"));
    }

    @Nonnull @SuppressWarnings("unchecked")
    private synchronized ID[] getSnapshot() {
        return linkedEntities.toArray(size -> (ID[]) new Object[size]);
    }

    private synchronized void restore(ID[] saved) {
        linkedEntities.clear();
        for (var id : saved) {
            record(id);
        }
    }

    /// The tombstones of every world, for one installation of one Store kind.
    /// Closing releases the resource, which discards what the Store has not saved yet.
    static final class Installation<ECS_TYPE, ID> {
        private final ComponentRegistry<ECS_TYPE> registry;
        private final ResourceType<ECS_TYPE, Tombstones<ECS_TYPE, ID>> type;
        private boolean closed;

        Installation(ComponentRegistry<ECS_TYPE> registry, String resourceId, Codec<ID> identityCodec) {
            this(registry, registry, resourceId, identityCodec);
        }

        /// Registers through `registrar`, the ComponentRegistryProxy a plugin wraps `registry` with.
        /// Closing unregisters on `registry` itself. A second installation can then take over.
        Installation(
            ComponentRegistry<ECS_TYPE> registry,
            IComponentRegistry<ECS_TYPE> registrar,
            String resourceId,
            Codec<ID> identityCodec
        ) {
            this.registry = Objects.requireNonNull(registry, "registry");
            type = registrar.registerResource(Tombstones.class, Objects.requireNonNull(resourceId, "resourceId"),
                getCodec(Objects.requireNonNull(identityCodec, "identityCodec")));
        }

        /// @throws IllegalStateException if this installation is closed
        @Nonnull
        Tombstones<ECS_TYPE, ID> getTombstonesIn(Store<ECS_TYPE> store) {
            if (closed) throw new IllegalStateException("Tombstones are released");
            return store.getResource(type);
        }

        void close() {
            if (closed) return;
            closed = true;
            registry.unregisterResource(type);
        }
    }
}
