/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.Ref;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;

/// Which loaded entity currently has each identity.
public final class IdentityIndex<ECS_TYPE, ID> {
    private final Map<ID, Ref<ECS_TYPE>> refs = new HashMap<>();

    @Nonnull
    public synchronized PutResult put(ID id, Ref<ECS_TYPE> ref) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ref, "ref").validate();
        var current = refs.get(id);
        if (current != null && current != ref && current.isValid()) {
            return PutResult.DUPLICATE;
        }
        refs.put(id, ref);
        return PutResult.ACCEPTED;
    }

    public synchronized void remove(ID id, Ref<ECS_TYPE> ref) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ref, "ref");
        var current = refs.get(id);
        if (current == ref) {
            refs.remove(id);
        }
    }

    @Nullable
    public synchronized Ref<ECS_TYPE> getRef(ID id) {
        Objects.requireNonNull(id, "id");
        var ref = refs.get(id);
        if (ref != null && !ref.isValid()) {
            return null;
        }
        return ref;
    }

    public synchronized boolean isCurrent(ID id, Ref<ECS_TYPE> ref) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ref, "ref");
        return refs.get(id) == ref;
    }

    synchronized List<Ref<ECS_TYPE>> getLoadedRefs() {
        return List.copyOf(refs.values());
    }

    synchronized void clear() {
        refs.clear();
    }

    public enum PutResult {
        ACCEPTED,
        DUPLICATE
    }

}
