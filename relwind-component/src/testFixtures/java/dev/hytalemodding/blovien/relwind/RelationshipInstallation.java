/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/// One relationship installation for tests, over the production `StoreInstallation` with the same
/// builder the component suites already use. The caller keeps ownership of the ComponentRegistry
/// and of closing what it installed.
public final class RelationshipInstallation<ID> {
    private final StoreInstallation<Object, ID> installation;
    private final RelationshipTracker<Object, ID> tracker;
    @Nullable
    private final RelationshipPersistence<Object> persistence;

    private RelationshipInstallation(
        StoreInstallation<Object, ID> installation,
        RelationshipTracker<Object, ID> tracker,
        @Nullable RelationshipPersistence<Object> persistence
    ) {
        this.installation = installation;
        this.tracker = tracker;
        this.persistence = persistence;
    }

    @Nonnull
    public static Builder<Integer> on(ComponentRegistry<Object> registry, Function<Ref<Object>, Integer> identity) {
        return new Builder<>(registry, identity, Codec.INTEGER);
    }

    @Nonnull
    public static <ID> Builder<ID> on(
        ComponentRegistry<Object> registry,
        Function<Ref<Object>, ID> identity,
        Codec<ID> identityCodec
    ) {
        return new Builder<>(registry, identity, Objects.requireNonNull(identityCodec, "identityCodec"));
    }

    @Nonnull
    public RelationshipTypeRegistry<Object> types() {
        return installation.getRelationshipTypeRegistry();
    }

    @Nonnull
    public RelationshipTracker<Object, ID> tracker() {
        return tracker;
    }

    @Nonnull
    public RelationshipPersistence<Object> persistence() {
        return Objects.requireNonNull(persistence, "This installation has no persistence");
    }

    public static final class Builder<ID> {
        private final ComponentRegistry<Object> registry;
        private final Function<Ref<Object>, ID> identity;
        private final Codec<ID> identityCodec;
        @Nullable
        private BiConsumer<Store<Object>, Runnable> transitions;
        @Nullable
        private Persistence<ID> persistence;

        private Builder(ComponentRegistry<Object> registry, Function<Ref<Object>, ID> identity, Codec<ID> identityCodec) {
            this.registry = Objects.requireNonNull(registry, "registry");
            this.identity = Objects.requireNonNull(identity, "identity");
            this.identityCodec = identityCodec;
        }

        @Nonnull
        public Builder<ID> transitions(BiConsumer<Store<Object>, Runnable> transitions) {
            this.transitions = Objects.requireNonNull(transitions, "transitions");
            return this;
        }

        @Nonnull
        public Builder<ID> persistence(
            BiConsumer<Store<Object>, Ref<Object>> sourceChanged,
            Consumer<Holder<Object>> holderChanged,
            Predicate<ID> deleted
        ) {
            persistence = new Persistence<>(sourceChanged, holderChanged, deleted);
            return this;
        }

        @Nonnull
        public RelationshipInstallation<ID> install() {
            var adapter = new TestPersistenceIdentity<Object, ID>(
                (_, ref) -> identity.apply(ref), identityCodec, "ENTITIES",
                (_, id) -> persistence != null && persistence.deleted.test(id), _ -> null);
            var runtime = TestStoreRuntime.of(transitions,
                persistence == null ? null : persistence.sourceChanged,
                persistence == null ? null : persistence.holderChanged);
            var installation = new StoreInstallation<>(registry, adapter, runtime);
            var tracker = installation.getTracker();
            if (persistence != null) installation.installPersistence();
            return new RelationshipInstallation<>(installation, tracker,
                persistence == null ? null : installation.getRelationshipTypeRegistry().getPersistence());
        }
    }

    private record Persistence<ID>(
        BiConsumer<Store<Object>, Ref<Object>> sourceChanged,
        Consumer<Holder<Object>> holderChanged,
        Predicate<ID> deleted
    ) {
    }
}
