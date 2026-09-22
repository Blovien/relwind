/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.Ref;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/// The sources linking to one target, for one relationship type. The first source is held in a
/// field and further ones use an array with an identity index for larger groups, which avoids
/// allocating a collection for a single link.
final class IncomingLinks<SOURCE, TARGET> implements Component<TARGET> {
    private static final int INDEX_SOURCES = 256;

    @Nullable
    Ref<SOURCE> source;

    @Nullable
    Ref<SOURCE>[] sources;

    private int size;

    @Nullable
    Reference2IntOpenHashMap<Ref<SOURCE>> positions;

    IncomingLinks() {
    }

    @SuppressWarnings("unchecked")
    void add(Ref<SOURCE> addedSource) {
        Objects.requireNonNull(addedSource, "addedSource");
        if (contains(addedSource)) {
            throw new IllegalStateException("Incoming source is already linked");
        }

        switch (size) {
            case 0 -> source = addedSource;
            case 1 -> {
                sources = new Ref[4];
                sources[0] = source;
                sources[1] = addedSource;
                source = null;
            }
            default -> {
                assert sources != null;
                if (size == sources.length) {
                    sources = Arrays.copyOf(sources, size << 1);
                }
                sources[size] = addedSource;
                if (positions != null) {
                    positions.put(addedSource, size);
                }
            }
        }
        size++;
    }

    boolean remove(Ref<SOURCE> removedSource) {
        if (size == 0) {
            return false;
        }

        if (size == 1) {
            if (source != removedSource) {
                return false;
            }
            source = null;
            size = 0;
            return true;
        }

        assert sources != null;
        if (positions == null && size >= INDEX_SOURCES) {
            positions = new Reference2IntOpenHashMap<>(size);
            positions.defaultReturnValue(-1);
            for (int i = 0; i < size; i++) {
                positions.put(sources[i], i);
            }
        }
        int found;
        if (positions == null) {
            found = -1;
            for (int i = 0; i < size; i++) {
                if (sources[i] == removedSource) {
                    found = i;
                    break;
                }
            }
        } else {
            found = positions.removeInt(removedSource);
        }
        if (found < 0) {
            return false;
        }
        int last = --size;
        sources[found] = sources[last];
        sources[last] = null;

        if (size == 1) {
            source = sources[0];
            sources = null;
            positions = null;
        } else if (positions != null) {
            if (found != last) {
                positions.put(sources[found], found);
            }
            if (size <= INDEX_SOURCES / 16) {
                positions = null;
            } else if ((size & (size - 1)) == 0) {
                positions.trim(size);
            }
        }
        if (sources != null && sources.length > 64 && size <= sources.length / 4) {
            sources = Arrays.copyOf(sources, Math.max(64, sources.length / 2));
        }
        return true;
    }

    int size() {
        return size;
    }

    @Nonnull
    Ref<SOURCE> getSource(int index) {
        assert index >= 0 && index < size;
        if (size == 1) {
            assert source != null;
            return source;
        }
        assert sources != null;
        return sources[index];
    }

    void forEach(Consumer<? super Ref<SOURCE>> consumer) {
        if (size == 1) {
            consumer.accept(source);
            return;
        }
        if (size == 0) return;
        assert sources != null;
        for (int i = 0; i < size; i++) {
            consumer.accept(sources[i]);
        }
    }

    void clear() {
        source = null;
        if (sources != null) {
            Arrays.fill(sources, null);
            sources = null;
        }
        positions = null;
        size = 0;
    }

    private boolean contains(Ref<SOURCE> candidate) {
        if (size == 1) {
            return source == candidate;
        }
        if (positions != null) {
            return positions.containsKey(candidate);
        }
        if (size == 0) return false;
        assert sources != null;
        for (int i = 0; i < size; i++) {
            if (sources[i] == candidate) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Nonnull @Override
    public IncomingLinks<SOURCE, TARGET> clone() {
        var clone = new IncomingLinks<SOURCE, TARGET>();
        clone.source = source;
        clone.sources = sources == null ? null : Arrays.copyOf(sources, sources.length);
        clone.size = size;
        if (positions != null) {
            assert clone.sources != null;
            clone.positions = new Reference2IntOpenHashMap<>(size);
            clone.positions.defaultReturnValue(-1);
            for (int i = 0; i < size; i++) {
                clone.positions.put(clone.sources[i], i);
            }
        }
        return clone;
    }
}
