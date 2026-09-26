/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.BiConsumer;

/// A breadth first walk reaches each linked entity at its shortest depth. The visited set belongs to
/// the borrowed evaluation and keeps its capacity between searches.
final class ReachableQuery<ECS_TYPE> extends RelationshipQuery<ECS_TYPE> {
    private final GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type;
    private final Direction direction;
    private final int maxDepth;
    private final RelationshipQuery<ECS_TYPE> throughQuery;
    private final RelationshipQuery<ECS_TYPE> reachedQuery;

    ReachableQuery(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type,
        Direction direction,
        int maxDepth,
        RelationshipQuery<ECS_TYPE> throughQuery,
        RelationshipQuery<ECS_TYPE> reachedQuery
    ) {
        this.type = Objects.requireNonNull(type, "type");
        this.direction = Objects.requireNonNull(direction, "direction");
        if (maxDepth < 1) throw new IllegalArgumentException("maxDepth must be at least one");
        this.maxDepth = maxDepth;
        this.throughQuery = throughQuery;
        this.reachedQuery = reachedQuery;
        if (throughQuery.containsRecursion() || reachedQuery.containsRecursion()) {
            throw new IllegalArgumentException("A recursive condition cannot contain another recursive condition");
        }
    }

    @Override
    boolean containsRecursion() {
        return true;
    }

    private ComponentType<ECS_TYPE, ?> getLinkType() {
        return direction == Direction.OUTGOING ? type.getSourceType() : type.getIncomingType();
    }

    @Nonnull @Override
    Truth getPossibility(Archetype<ECS_TYPE> archetype, Admission admission) {
        return linkPossibility(getLinkType().test(archetype), type.getRelationshipTypeRegistry(), admission);
    }

    @Nonnull @Override
    Truth evaluate(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation) {
        if (!evaluation.isAccessible(store, entity)) return Truth.UNKNOWN;
        var visited = evaluation.getVisited();
        visited.add(entity);
        var result = Truth.FALSE;
        try {
            int next = 0;
            for (int depth = 0; depth < maxDepth && next < visited.order.size(); depth++) {
                int end = visited.order.size();
                while (next < end) {
                    var current = visited.order.get(next++);
                    if (depth != 0) {
                        var through = throughQuery.evaluate(store, current, evaluation);
                        if (through == Truth.UNKNOWN) result = Truth.UNKNOWN;
                        if (through != Truth.TRUE) continue;
                    }
                    var tracker = type.getRelationshipTypeRegistry().getTracker();
                    if (current != null && tracker != null && (direction == Direction.OUTGOING
                        ? tracker.hasUnresolvedOutgoing(type, current) : tracker.hasUnresolvedIncoming(type, current))) {
                        result = Truth.UNKNOWN;
                    }
                    if (direction == Direction.OUTGOING) {
                        if (current == null) {
                            var truth = visited.readHolder(this, store, evaluation);
                            if (truth == Truth.TRUE) return Truth.TRUE;
                            if (truth == Truth.UNKNOWN) result = Truth.UNKNOWN;
                        }
                        var outgoing = evaluation.getComponent(store, current, type.getSourceType());
                        if (outgoing == null) continue;
                        for (int i = 0; i < outgoing.size(); i++) {
                            var truth = visit(store, outgoing.getTarget(i), evaluation);
                            if (truth == Truth.TRUE) return Truth.TRUE;
                            if (truth == Truth.UNKNOWN) result = Truth.UNKNOWN;
                        }
                    } else {
                        var incoming = evaluation.getComponent(store, current, type.getIncomingType());
                        if (incoming == null) continue;
                        for (int i = 0; i < incoming.size(); i++) {
                            var linkedEntity = incoming.getSource(i);
                            var truth = visit(store, linkedEntity, evaluation);
                            if (truth == Truth.TRUE) return Truth.TRUE;
                            if (truth == Truth.UNKNOWN) result = Truth.UNKNOWN;
                        }
                    }
                }
            }
            return result;
        } finally {
            visited.clear();
        }
    }

    private Truth visit(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> linkedEntity, Evaluation<ECS_TYPE> evaluation) {
        if (linkedEntity == null || !linkedEntity.isValid() || linkedEntity.getStore() != store) return Truth.UNKNOWN;
        return evaluation.getVisited().add(linkedEntity) ? reachedQuery.evaluate(store, linkedEntity, evaluation) : Truth.FALSE;
    }

    @Override
    void emit(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation, Runnable match) {
        match.run();
    }

    @Override
    public boolean requiresComponentType(ComponentType<ECS_TYPE, ?> componentType) {
        return getLinkType() == componentType || throughQuery.requiresComponentType(componentType)
            || reachedQuery.requiresComponentType(componentType);
    }

    @Override
    public void validateRegistry(ComponentRegistry<ECS_TYPE> registry) {
        getLinkType().validateRegistry(registry);
        throughQuery.validateRegistry(registry);
        reachedQuery.validateRegistry(registry);
    }

    @Override
    public void validate() {
        getLinkType().validate();
        throughQuery.validate();
        reachedQuery.validate();
    }

    static final class Visited<ECS_TYPE> implements BiConsumer<Ref<ECS_TYPE>, Object> {
        private final IdentityHashMap<Ref<ECS_TYPE>, Boolean> identities = new IdentityHashMap<>();
        private final ArrayList<Ref<ECS_TYPE>> order = new ArrayList<>();

        @Nullable
        private ReachableQuery<ECS_TYPE> query;
        @Nullable
        private Store<ECS_TYPE> store;
        @Nullable
        private Evaluation<ECS_TYPE> evaluation;
        private Truth holderTruth = Truth.FALSE;

        Truth readHolder(ReachableQuery<ECS_TYPE> query, Store<ECS_TYPE> store, Evaluation<ECS_TYPE> evaluation) {
            var tracker = query.type.getRelationshipTypeRegistry().getTracker();
            if (tracker == null) return Truth.FALSE;
            this.query = query;
            this.store = store;
            this.evaluation = evaluation;
            holderTruth = Truth.FALSE;
            try {
                assert evaluation.sourceHolder != null;
                tracker.readHolderLinks(query.type, evaluation.sourceHolder, store, this);
                return holderTruth;
            } finally {
                this.query = null;
                this.store = null;
                this.evaluation = null;
            }
        }

        @Override
        public void accept(@Nullable Ref<ECS_TYPE> linkedEntity, @Nullable Object data) {
            if (holderTruth == Truth.TRUE) return;
            assert query != null && store != null && evaluation != null;
            var truth = query.visit(store, linkedEntity, evaluation);
            if (truth != Truth.FALSE) holderTruth = truth;
        }

        boolean add(@Nullable Ref<ECS_TYPE> linkedEntity) {
            if (identities.put(linkedEntity, Boolean.TRUE) != null) return false;
            order.add(linkedEntity);
            return true;
        }

        void clear() {
            identities.clear();
            order.clear();
        }
    }
}
