/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Objects;

/// Borrowed until the callback returns. Copy any values needed afterward. Entity references and
/// link data remain live references, not copies of their state.
public final class RelationshipResult<ECS_TYPE, LINK_DATA> {
    @Nullable
    private Ref<ECS_TYPE> source;
    @Nullable
    private Holder<ECS_TYPE> holder;
    @Nullable
    private Ref<ECS_TYPE> target;
    @Nullable
    private LINK_DATA data;
    private int depth;
    @Nullable
    private RelationshipQuery.Evaluation<ECS_TYPE> evaluation;

    RelationshipResult() {
    }

    /// Null when a holder relationship system supplied the source. For a reachable enumeration this is the
    /// traversal start, including when following incoming links.
    @Nullable
    public Ref<ECS_TYPE> getSource() {
        Objects.requireNonNull(target, "target");
        return source;
    }

    /// The source holder when a holder relationship system produced this result, and null otherwise.
    @Nullable
    public Holder<ECS_TYPE> getHolder() {
        Objects.requireNonNull(target, "target");
        return holder;
    }

    /// For a reachable enumeration this is the reached entity, which can be an original link's source
    /// when following incoming links.
    @Nonnull
    public Ref<ECS_TYPE> getTarget() {
        return Objects.requireNonNull(target, "target");
    }

    @Nullable
    public LINK_DATA getData() {
        return data;
    }

    /// One for a directly linked target. A reachable enumeration reports the first depth it was found at.
    public int getDepth() {
        Objects.requireNonNull(target, "target");
        return depth;
    }

    /// Whether the matching branch of the query filled this binding.
    public boolean has(RelationshipQuery.Binding<ECS_TYPE, ?> binding) {
        Objects.requireNonNull(binding, "binding");
        var link = evaluation == null ? null : evaluation.get(binding);
        return link != null && link.present;
    }

    /// Null when the matching branch did not fill this binding.
    @Nullable
    public Ref<ECS_TYPE> getSource(RelationshipQuery.Binding<ECS_TYPE, ?> binding) {
        var link = getBoundLink(binding);
        return link == null ? null : link.source;
    }

    @Nullable
    public Ref<ECS_TYPE> getTarget(RelationshipQuery.Binding<ECS_TYPE, ?> binding) {
        var link = getBoundLink(binding);
        return link == null ? null : link.target;
    }

    /// Use {@link #has} to distinguish an absent binding from null data.
    @Nullable
    public <DATA> DATA getData(RelationshipQuery.Binding<ECS_TYPE, DATA> binding) {
        var link = getBoundLink(binding);
        return link == null || link.data == null ? null : binding.getDataClass().cast(link.data);
    }

    public boolean has(RelationshipQuery.Definition<ECS_TYPE, ?> query) {
        return has(Objects.requireNonNull(query, "query").getBinding());
    }

    @Nullable
    public Ref<ECS_TYPE> getSource(RelationshipQuery.Definition<ECS_TYPE, ?> query) {
        return getSource(Objects.requireNonNull(query, "query").getBinding());
    }

    @Nullable
    public Ref<ECS_TYPE> getTarget(RelationshipQuery.Definition<ECS_TYPE, ?> query) {
        return getTarget(Objects.requireNonNull(query, "query").getBinding());
    }

    @Nullable
    public <DATA> DATA getData(RelationshipQuery.Definition<ECS_TYPE, DATA> query) {
        return getData(Objects.requireNonNull(query, "query").getBinding());
    }

    void set(
        @Nullable Ref<ECS_TYPE> source,
        @Nullable Holder<ECS_TYPE> holder,
        Ref<ECS_TYPE> target,
        @Nullable LINK_DATA data,
        int depth
    ) {
        this.source = source;
        this.holder = holder;
        this.target = Objects.requireNonNull(target, "target");
        this.data = data;
        this.depth = depth;
    }

    void setEvaluation(RelationshipQuery.Evaluation<ECS_TYPE> evaluation) {
        this.evaluation = evaluation;
    }

    void clear() {
        source = null;
        holder = null;
        target = null;
        data = null;
        depth = 0;
        evaluation = null;
    }

    /// The batch retains its evaluation throughout the callback, so the view need not copy it.
    void copyFrom(RelationshipResult<ECS_TYPE, LINK_DATA> other) {
        set(other.getSource(), other.getHolder(), other.getTarget(), other.getData(), other.getDepth());
        evaluation = other.evaluation;
    }

    @Nullable
    private RelationshipQuery.BoundLink<ECS_TYPE> getBoundLink(RelationshipQuery.Binding<ECS_TYPE, ?> binding) {
        Objects.requireNonNull(binding, "binding");
        var link = evaluation == null ? null : evaluation.get(binding);
        return link == null || !link.present ? null : link;
    }
}
