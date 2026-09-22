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

/// The targets of one source and their link data. The first target is held in a field and any
/// further ones share an array of target and data pairs.
public final class OutgoingLink<SOURCE, TARGET> implements Component<SOURCE> {
    private static final int INDEX_TARGETS = 16;
    private static final int RETAIN_TARGETS = 64;

    @Nullable
    private Ref<TARGET> target;

    @Nullable
    private Object data;

    /// Additional target/data pairs, allocated only when a second link is added.
    @Nullable
    private AdditionalLinks<TARGET> additionalLinks;

    OutgoingLink() {
    }

    OutgoingLink(Ref<TARGET> target, @Nullable Object data) {
        this.target = Objects.requireNonNull(target, "target");
        this.data = data;
    }

    @Nullable
    Ref<TARGET> getTarget() {
        return target;
    }

    @Nonnull
    Ref<TARGET> getTarget(int index) {
        assert target != null && index >= 0 && index < size();
        if (index == 0) return target;
        assert additionalLinks != null;
        return additionalLinks.getTarget(index - 1);
    }

    int size() {
        if (target == null) {
            return 0;
        }

        return 1 + (additionalLinks == null ? 0 : additionalLinks.size);
    }

    boolean contains(Ref<TARGET> candidate) {
        return target == candidate || additionalLinks != null && additionalLinks.find(candidate) >= 0;
    }

    void add(Ref<TARGET> addedTarget, @Nullable Object addedData) {
        if (contains(addedTarget)) {
            return;
        }
        if (target == null) {
            target = addedTarget;
            data = addedData;
            return;
        }
        if (additionalLinks == null) {
            additionalLinks = new AdditionalLinks<>();
        }
        additionalLinks.add(addedTarget, addedData);
    }

    void remove(Ref<TARGET> removedTarget) {
        if (target == removedTarget) {
            if (additionalLinks == null || additionalLinks.size == 0) {
                target = null;
                data = null;
            } else {
                int last = additionalLinks.size - 1;
                target = additionalLinks.getTarget(last);
                data = additionalLinks.getData(last);
                additionalLinks.remove(last);
            }
            return;
        }
        int index = additionalLinks == null ? -1 : additionalLinks.find(removedTarget);
        if (index < 0) {
            return;
        }
        assert additionalLinks != null;
        additionalLinks.remove(index);
    }

    void clear() {
        target = null;
        data = null;
        if (additionalLinks != null) {
            additionalLinks.clear();
        }
    }

    void retarget(Ref<TARGET> oldTarget, Ref<TARGET> newTarget) {
        if (target == oldTarget) {
            target = newTarget;
            return;
        }
        int index = additionalLinks == null ? -1 : additionalLinks.find(oldTarget);
        if (index < 0) {
            return;
        }
        assert additionalLinks != null;
        additionalLinks.retarget(index, newTarget);
    }

    @Nullable
    <LINK_DATA> LINK_DATA getData(Class<LINK_DATA> dataClass) {
        return data == null ? null : dataClass.cast(data);
    }

    @Nullable
    <LINK_DATA> LINK_DATA getData(Ref<TARGET> requestedTarget, Class<LINK_DATA> dataClass) {
        if (target == requestedTarget) {
            return getData(dataClass);
        }
        int index = additionalLinks == null ? -1 : additionalLinks.find(requestedTarget);
        if (index < 0) {
            return null;
        }
        assert additionalLinks != null;
        var value = additionalLinks.getData(index);
        return value == null ? null : dataClass.cast(value);
    }

    @Nullable
    <LINK_DATA> LINK_DATA getData(int index, Class<LINK_DATA> dataClass) {
        if (index == 0) return getData(dataClass);
        assert additionalLinks != null;
        var value = additionalLinks.getData(index - 1);
        return value == null ? null : dataClass.cast(value);
    }

    void setData(Ref<TARGET> requestedTarget, @Nullable Object replacement) {
        if (target == requestedTarget) {
            data = replacement;
            return;
        }
        int index = additionalLinks == null ? -1 : additionalLinks.find(requestedTarget);
        if (index < 0) {
            return;
        }
        assert additionalLinks != null;
        additionalLinks.setData(index, replacement);
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Nonnull @Override
    public OutgoingLink<SOURCE, TARGET> clone() {
        if (target == null) {
            return new OutgoingLink<>();
        }
        var clone = new OutgoingLink<SOURCE, TARGET>(target, data);
        clone.additionalLinks = additionalLinks == null ? null : additionalLinks.copy();
        return clone;
    }

    private static final class AdditionalLinks<TARGET> {
        // four slots hold two target and data pairs, and the capacity doubles when full
        private Object[] links = new Object[4];
        private int size;
        @Nullable
        private Reference2IntOpenHashMap<Ref<TARGET>> positions;

        @Nonnull @SuppressWarnings("unchecked")
        private Ref<TARGET> getTarget(int index) {
            return (Ref<TARGET>) links[index << 1];
        }

        @Nullable
        private Object getData(int index) {
            return links[(index << 1) + 1];
        }

        private void setData(int index, @Nullable Object replacement) {
            links[(index << 1) + 1] = replacement;
        }

        private int find(Ref<TARGET> requestedTarget) {
            if (positions != null) {
                return positions.getInt(requestedTarget);
            }
            // a small group is scanned by reference identity, and a larger one uses the index above
            for (int i = 0; i < size; i++) {
                if (links[i << 1] == requestedTarget) {
                    return i;
                }
            }
            return -1;
        }

        private void add(Ref<TARGET> addedTarget, @Nullable Object addedData) {
            int offset = size << 1;
            if (offset == links.length) {
                links = Arrays.copyOf(links, links.length << 1);
            }
            links[offset] = addedTarget;
            links[offset + 1] = addedData;
            if (positions != null) {
                positions.put(addedTarget, size);
            }
            size++;
            if (positions == null && size >= INDEX_TARGETS) {
                rebuildPositions();
            }
        }

        private void remove(int index) {
            int last = --size;
            if (positions != null) {
                positions.removeInt(getTarget(index));
            }
            if (index != last) {
                int source = last << 1;
                int destination = index << 1;
                links[destination] = links[source];
                links[destination + 1] = links[source + 1];
                if (positions != null) {
                    positions.put(getTarget(index), index);
                }
            }
            Arrays.fill(links, last << 1, (last + 1) << 1, null);
            if (positions != null && size <= INDEX_TARGETS / 2) {
                positions = null;
            }
            if (links.length > RETAIN_TARGETS * 2 && size * 2 <= links.length / 4) {
                links = Arrays.copyOf(links, Math.max(RETAIN_TARGETS * 2, links.length / 2));
            }
        }

        private void retarget(int index, Ref<TARGET> newTarget) {
            if (positions != null) {
                positions.removeInt(getTarget(index));
            }
            links[index << 1] = newTarget;
            if (positions != null) {
                positions.put(newTarget, index);
            }
        }

        private void clear() {
            Arrays.fill(links, null);
            size = 0;
            if (positions != null) {
                positions.clear();
            }
        }

        private void rebuildPositions() {
            positions = new Reference2IntOpenHashMap<>(size);
            positions.defaultReturnValue(-1);
            for (int i = 0; i < size; i++) {
                positions.put(getTarget(i), i);
            }
        }

        @Nonnull
        private AdditionalLinks<TARGET> copy() {
            var clone = new AdditionalLinks<TARGET>();
            clone.links = Arrays.copyOf(links, links.length);
            clone.size = size;
            if (positions != null) {
                clone.rebuildPositions();
            }
            return clone;
        }
    }
}
