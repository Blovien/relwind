/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import javax.annotation.Nonnull;

import java.util.Objects;

/// The traits shared by the links of one relationship type.
/// Start from {@link #defaults()}. Each builder returns a new value and leaves its receiver unchanged.
public final class RelationshipTraits {
    private static final RelationshipTraits DEFAULTS = new RelationshipTraits(
        false, false, Survival.REMOVE, Survival.REMOVE, OnDeleteTarget.REMOVE, SourceRetention.RELEASE);

    private final boolean exclusive;
    private final boolean symmetric;
    private final Survival transfer;
    private final Survival temporaryDeactivation;
    private final OnDeleteTarget onDeleteTarget;
    private final SourceRetention sourceRetention;

    private RelationshipTraits(
        boolean exclusive,
        boolean symmetric,
        Survival transfer,
        Survival temporaryDeactivation,
        OnDeleteTarget onDeleteTarget,
        SourceRetention sourceRetention
    ) {
        this.exclusive = exclusive;
        this.symmetric = symmetric;
        this.transfer = transfer;
        this.temporaryDeactivation = temporaryDeactivation;
        this.onDeleteTarget = onDeleteTarget;
        this.sourceRetention = sourceRetention;
    }

    @Nonnull
    public static RelationshipTraits defaults() {
        return DEFAULTS;
    }

    /// Allows each source at most one target of this type.
    @Nonnull
    public RelationshipTraits exclusive() {
        return new RelationshipTraits(true, symmetric, transfer, temporaryDeactivation, onDeleteTarget, sourceRetention);
    }

    /// Keeps a twin link in the opposite direction when a command changes a link.
    @Nonnull
    public RelationshipTraits symmetric() {
        return new RelationshipTraits(exclusive, true, transfer, temporaryDeactivation, onDeleteTarget, sourceRetention);
    }

    @Nonnull
    public RelationshipTraits onDeleteTarget(OnDeleteTarget onDeleteTarget) {
        return new RelationshipTraits(exclusive, symmetric, transfer, temporaryDeactivation,
            Objects.requireNonNull(onDeleteTarget, "onDeleteTarget"), sourceRetention);
    }

    /// Keeps the links when their source moves to another Store.
    @Nonnull
    public RelationshipTraits retainOnTransfer() {
        return new RelationshipTraits(exclusive, symmetric, Survival.RETAIN, temporaryDeactivation, onDeleteTarget,
            sourceRetention);
    }

    /// Keeps the links while a linked entity is temporarily unavailable.
    @Nonnull
    public RelationshipTraits retainOnDeactivation() {
        return new RelationshipTraits(exclusive, symmetric, transfer, Survival.RETAIN, onDeleteTarget, sourceRetention);
    }

    /// Keeps the empty source storage after the final link of a source is removed.
    @Nonnull
    public RelationshipTraits retainSourceStorage() {
        return new RelationshipTraits(exclusive, symmetric, transfer, temporaryDeactivation, onDeleteTarget,
            SourceRetention.RETAIN);
    }

    public boolean isExclusive() {
        return exclusive;
    }

    public boolean isSymmetric() {
        return symmetric;
    }

    @Nonnull
    public OnDeleteTarget getOnDeleteTarget() {
        return onDeleteTarget;
    }

    @Nonnull
    public Survival getTransfer() {
        return transfer;
    }

    @Nonnull
    public Survival getTemporaryDeactivation() {
        return temporaryDeactivation;
    }

    @Nonnull
    public SourceRetention getSourceRetention() {
        return sourceRetention;
    }

    public enum OnDeleteTarget {
        REMOVE,
        DELETE
    }

    public enum Survival {
        REMOVE,
        RETAIN
    }

    public enum SourceRetention {
        RELEASE,
        RETAIN
    }
}
