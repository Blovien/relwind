/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import javax.annotation.Nonnull;

import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;

/// Controlled identities, deletion evidence and peer Stores for component tests.
public record TestPersistenceIdentity<ECS_TYPE, ID>(
    BiFunction<Store<ECS_TYPE>, Ref<ECS_TYPE>, ID> identities,
    Codec<ID> identityCodec,
    String installationName,
    BiPredicate<Store<ECS_TYPE>, ID> deleted,
    Function<Store<?>, Store<ECS_TYPE>> beside
) implements PersistenceIdentity<ECS_TYPE, ID> {
    public static <ECS_TYPE, ID> TestPersistenceIdentity<ECS_TYPE, ID> of(Function<Ref<ECS_TYPE>, ID> identity, Codec<ID> codec) {
        return new TestPersistenceIdentity<>((store, ref) -> identity.apply(ref), codec,
            "ENTITIES", (store, id) -> false, peer -> null);
    }

    @Override
    public ID getIdentity(Store<ECS_TYPE> store, Ref<ECS_TYPE> ref) {
        return identities.apply(store, ref);
    }

    @Nonnull
    @Override
    public Codec<ID> getIdentityCodec() {
        return identityCodec;
    }

    @Nonnull
    @Override
    public String getInstallationName() {
        return installationName;
    }

    @Override
    public boolean isDeleted(Store<ECS_TYPE> store, ID id) {
        return deleted.test(store, id);
    }

    @Override
    public Store<ECS_TYPE> storeBeside(Store<?> peer) {
        return beside.apply(peer);
    }
}
