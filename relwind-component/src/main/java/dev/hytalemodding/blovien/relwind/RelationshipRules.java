/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import javax.annotation.Nonnull;

/// The policies shared by the links of one relationship type. Start from {@link #single()} or
/// {@link #multiple()} and add what differs. Every policy method returns a new value and leaves
/// this one alone. A shared constant is safe to keep.
public final class RelationshipRules {
    private static final RelationshipRules SINGLE = new RelationshipRules(
        Cardinality.SINGLE_TARGET, Survival.REMOVE, Survival.REMOVE,
        TargetDeletion.PRESERVE_SOURCE, SourceRetention.RELEASE);
    private static final RelationshipRules MULTIPLE = new RelationshipRules(
        Cardinality.MULTIPLE_TARGETS, Survival.REMOVE, Survival.REMOVE,
        TargetDeletion.PRESERVE_SOURCE, SourceRetention.RELEASE);

    private final Cardinality cardinality;
    private final Survival transfer;
    private final Survival temporaryDeactivation;
    private final TargetDeletion targetDeletion;
    private final SourceRetention sourceRetention;

    private RelationshipRules(
        Cardinality cardinality,
        Survival transfer,
        Survival temporaryDeactivation,
        TargetDeletion targetDeletion,
        SourceRetention sourceRetention
    ) {
        this.cardinality = cardinality;
        this.transfer = transfer;
        this.temporaryDeactivation = temporaryDeactivation;
        this.targetDeletion = targetDeletion;
        this.sourceRetention = sourceRetention;
    }

    @Nonnull
    public static RelationshipRules single() {
        return SINGLE;
    }

    @Nonnull
    public static RelationshipRules multiple() {
        return MULTIPLE;
    }

    /// Keeps the links when their source moves to another Store.
    @Nonnull
    public RelationshipRules retainOnTransfer() {
        return transfer == Survival.RETAIN ? this
            : new RelationshipRules(cardinality, Survival.RETAIN, temporaryDeactivation, targetDeletion,
                sourceRetention);
    }

    /// Keeps the links while a linked entity is temporarily unavailable.
    @Nonnull
    public RelationshipRules retainOnDeactivation() {
        return temporaryDeactivation == Survival.RETAIN ? this
            : new RelationshipRules(cardinality, transfer, Survival.RETAIN, targetDeletion, sourceRetention);
    }

    /// Deletes a linked source when its target is permanently deleted.
    @Nonnull
    public RelationshipRules cascadeSource() {
        return targetDeletion == TargetDeletion.CASCADE_SOURCE ? this
            : new RelationshipRules(cardinality, transfer, temporaryDeactivation,
                TargetDeletion.CASCADE_SOURCE, sourceRetention);
    }

    /// Keeps the empty source storage after the final link of a source is removed.
    @Nonnull
    public RelationshipRules retainSourceStorage() {
        return sourceRetention == SourceRetention.RETAIN ? this
            : new RelationshipRules(cardinality, transfer, temporaryDeactivation, targetDeletion,
                SourceRetention.RETAIN);
    }

    @Nonnull
    public Cardinality getCardinality() {
        return cardinality;
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
    public TargetDeletion getTargetDeletion() {
        return targetDeletion;
    }

    @Nonnull
    public SourceRetention getSourceRetention() {
        return sourceRetention;
    }

    public enum Cardinality {
        SINGLE_TARGET,
        MULTIPLE_TARGETS
    }

    public enum Survival {
        REMOVE,
        RETAIN
    }

    public enum TargetDeletion {
        PRESERVE_SOURCE,
        CASCADE_SOURCE
    }

    public enum SourceRetention {
        RELEASE,
        RETAIN
    }
}
