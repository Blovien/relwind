/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/// A condition on an entity and its links, usable wherever Hytale takes a Query. A condition that
/// needs a linked entity which is not loaded answers unknown, and {@link #not(Query)} leaves it unknown
/// instead of turning it true.
public abstract class RelationshipQuery<ECS_TYPE> implements Query<ECS_TYPE> {
    RelationshipQuery() {
    }

    @Nonnull @SafeVarargs
    public static <ECS_TYPE> RelationshipQuery<ECS_TYPE> and(Query<ECS_TYPE>... queries) {
        return new AndQuery<>(compileAll(queries));
    }

    @Nonnull @SafeVarargs
    public static <ECS_TYPE> RelationshipQuery<ECS_TYPE> or(Query<ECS_TYPE>... queries) {
        return new OrQuery<>(compileAll(queries));
    }

    @Nonnull
    public static <ECS_TYPE> RelationshipQuery<ECS_TYPE> not(Query<ECS_TYPE> query) {
        return new NotQuery<>(compile(query));
    }

    /// @throws IllegalArgumentException if a bridge targetQuery contains a relationship condition
    @Nonnull
    public static <SOURCE, TARGET> RelationshipQuery<SOURCE> exists(
        GenericRelationshipType<SOURCE, TARGET, ?> type,
        Query<TARGET> targetQuery
    ) {
        if (type.getTargetRelationshipTypeRegistry() != type.getRelationshipTypeRegistry()) return new BridgeExistsQuery<>(type, targetQuery);
        @SuppressWarnings("unchecked")
        var sameType = (GenericRelationshipType<SOURCE, SOURCE, ?>) type;
        @SuppressWarnings("unchecked")
        var sameQuery = (Query<SOURCE>) targetQuery;
        return new ExistsQuery<>(sameType, compile(sameQuery));
    }

    /// Must recognise the same combinators {@link #compile} looks inside.
    private static <ECS_TYPE> boolean hasRelationshipCondition(Query<ECS_TYPE> query) {
        if (query instanceof RelationshipQuery<ECS_TYPE>) {
            return true;
        }
        if (query.getClass() == com.hypixel.hytale.component.query.AndQuery.class) {
            return hasAnyRelationshipCondition(NativeQueryAccess.andOperands(query));
        }
        if (query.getClass() == com.hypixel.hytale.component.query.OrQuery.class) {
            return hasAnyRelationshipCondition(NativeQueryAccess.orOperands(query));
        }
        if (query.getClass() == com.hypixel.hytale.component.query.NotQuery.class) {
            return hasRelationshipCondition(NativeQueryAccess.notOperand(query));
        }
        return false;
    }

    private static <ECS_TYPE> boolean hasAnyRelationshipCondition(Query<ECS_TYPE>[] operands) {
        for (var operand : operands) {
            if (hasRelationshipCondition(operand)) {
                return true;
            }
        }
        return false;
    }

    public enum Direction {
        /// Follows links from each source to its targets.
        OUTGOING,
        /// Follows links from each target back to its sources without changing the stored links.
        INCOMING
    }

    /// Tests the linked entities within maxDepth hops. The start never matches, even in a cycle.
    /// Integer.MAX_VALUE lets the visited set alone end the walk.
    @Nonnull
    public static <ECS_TYPE> RelationshipQuery<ECS_TYPE> reachable(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type,
        Direction direction,
        int maxDepth,
        Query<ECS_TYPE> reachedQuery
    ) {
        return reachable(type, direction, maxDepth, Query.any(), reachedQuery);
    }

    /// A linked entity must match throughQuery to be expanded further. The start and the matches need not.
    @Nonnull
    public static <ECS_TYPE> RelationshipQuery<ECS_TYPE> reachable(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type,
        Direction direction,
        int maxDepth,
        Query<ECS_TYPE> throughQuery,
        Query<ECS_TYPE> reachedQuery
    ) {
        return new ReachableQuery<>(type, direction, maxDepth, compile(throughQuery), compile(reachedQuery));
    }

    /// Reports each linked entity once, at the depth it was first found, in breadth first order. The
    /// start is never reported, even when a cycle reaches it.
    @Nonnull
    public static <ECS_TYPE, LINK_DATA> ReachableEnumeration<ECS_TYPE, LINK_DATA> enumerateReachable(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
        Direction direction,
        int maxDepth,
        Query<ECS_TYPE> reachedQuery
    ) {
        return enumerateReachable(type, direction, maxDepth, Query.any(), reachedQuery);
    }

    @Nonnull
    public static <ECS_TYPE, LINK_DATA> ReachableEnumeration<ECS_TYPE, LINK_DATA> enumerateReachable(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
        Direction direction,
        int maxDepth,
        Query<ECS_TYPE> throughQuery,
        Query<ECS_TYPE> reachedQuery
    ) {
        return new ReachableEnumeration<>(type, direction, maxDepth, compile(throughQuery), compile(reachedQuery));
    }

    @Nonnull
    public static <ECS_TYPE, LINK_DATA> Binding<ECS_TYPE, LINK_DATA> enumerate(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
        Query<ECS_TYPE> targetQuery
    ) {
        return new Binding<>(type, compile(targetQuery));
    }

    /// A system caches the definition it returns. Returning a different one later changes nothing.
    @Nonnull
    public static <ECS_TYPE, LINK_DATA> Definition<ECS_TYPE, LINK_DATA> of(
        Query<ECS_TYPE> sourceQuery,
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
        Query<ECS_TYPE> targetQuery
    ) {
        return new Definition<>(sourceQuery, type, targetQuery);
    }

    @Nonnull
    public static <ECS_TYPE, LINK_DATA> Definition<ECS_TYPE, LINK_DATA> of(
        Query<ECS_TYPE> sourceQuery,
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type
    ) {
        return of(sourceQuery, type, Query.any());
    }

    @Nonnull
    public static <ECS_TYPE, LINK_DATA> Definition<ECS_TYPE, LINK_DATA> of(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
        Query<ECS_TYPE> targetQuery
    ) {
        return of(Query.any(), type, targetQuery);
    }

    @Nonnull
    public static <ECS_TYPE, LINK_DATA, C extends Component<ECS_TYPE>> ComponentChange<ECS_TYPE, LINK_DATA, C> of(
        Query<ECS_TYPE> sourceQuery,
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
        Query<ECS_TYPE> targetQuery,
        ComponentType<ECS_TYPE, C> componentType
    ) {
        return new ComponentChange<>(sourceQuery, type, targetQuery, componentType);
    }

    @Nullable
    private static <ECS_TYPE, DATA> DATA getLinkData(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type,
        OutgoingLink<ECS_TYPE, ECS_TYPE> outgoing,
        int index
    ) {
        @SuppressWarnings("unchecked")
        var dataClass = (Class<DATA>) type.getDescriptor().linkDataClass();
        return outgoing.getData(index, dataClass);
    }

    @Nonnull
    private static <ECS_TYPE> List<RelationshipQuery<ECS_TYPE>> compileAll(Query<ECS_TYPE>[] queries) {
        Objects.requireNonNull(queries, "queries");
        var conditions = new ArrayList<RelationshipQuery<ECS_TYPE>>(queries.length);
        for (var query : queries) {
            conditions.add(compile(query));
        }
        return List.copyOf(conditions);
    }

    @Nonnull
    private static <ECS_TYPE> RelationshipQuery<ECS_TYPE> compile(Query<ECS_TYPE> query) {
        Objects.requireNonNull(query, "query");
        if (query instanceof RelationshipQuery<ECS_TYPE> relationshipQuery) {
            return relationshipQuery;
        }
        if (query.getClass() == com.hypixel.hytale.component.query.AndQuery.class) {
            return new AndQuery<>(compileAll(NativeQueryAccess.andOperands(query)));
        }
        if (query.getClass() == com.hypixel.hytale.component.query.OrQuery.class) {
            return new OrQuery<>(compileAll(NativeQueryAccess.orOperands(query)));
        }
        if (query.getClass() == com.hypixel.hytale.component.query.NotQuery.class) {
            return new NotQuery<>(compile(NativeQueryAccess.notOperand(query)));
        }
        return new NativeQuery<>(query);
    }

    @Override
    public final boolean test(Archetype<ECS_TYPE> archetype) {
        return getPossibility(archetype, Admission.HOLDERS) != Truth.FALSE;
    }

    /// Admits only archetypes whose loaded entities can match. A loaded entity without a type's
    /// storage component cannot satisfy a positive condition on that type. {@link #test} keeps the
    /// tracker check because parked and decoded holders carry links without storage components.
    final boolean testLoaded(Archetype<ECS_TYPE> archetype) {
        return getPossibility(archetype, Admission.LOADED) != Truth.FALSE;
    }

    enum Admission {
        HOLDERS,
        LOADED
    }

    @Nonnull
    static Truth linkPossibility(boolean hasStorage, RelationshipTypeRegistry<?> registry, Admission admission) {
        return hasStorage || (admission == Admission.HOLDERS && registry.getTracker() != null)
            ? Truth.UNKNOWN : Truth.FALSE;
    }

    boolean containsRecursion() {
        return false;
    }

    @Nonnull abstract Truth getPossibility(Archetype<ECS_TYPE> archetype, Admission admission);

    @Nonnull abstract Truth evaluate(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation);

    abstract void emit(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation, Runnable match);

    @Override
    public abstract boolean requiresComponentType(ComponentType<ECS_TYPE, ?> componentType);

    @Override
    public abstract void validateRegistry(ComponentRegistry<ECS_TYPE> registry);

    @Override
    public abstract void validate();

    /// The whole query a relationship system returns from `getQuery()`. A nested definition
    /// enumerates its matching links. Use {@link #exists} when a nested relationship only filters.
    public static class Definition<ECS_TYPE, LINK_DATA> extends RelationshipQuery<ECS_TYPE> {
        private final Query<ECS_TYPE> sourceQuery;
        private final GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type;
        private final Binding<ECS_TYPE, LINK_DATA> binding;
        private final RelationshipQuery<ECS_TYPE> condition;

        private Definition(
            Query<ECS_TYPE> sourceQuery,
            GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
            Query<ECS_TYPE> targetQuery
        ) {
            this.sourceQuery = Objects.requireNonNull(sourceQuery, "sourceQuery");
            this.type = Objects.requireNonNull(type, "type");
            binding = enumerate(type, targetQuery);
            condition = and(sourceQuery, binding);
        }

        @SuppressWarnings("unused")
        @Nonnull
        public final Query<ECS_TYPE> getSourceQuery() {
            return sourceQuery;
        }

        @Nonnull
        public final GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> getRelationshipType() {
            return type;
        }

        @Nonnull
        final Binding<ECS_TYPE, LINK_DATA> getBinding() {
            return binding;
        }

        @Override
        boolean containsRecursion() {
            return condition.containsRecursion();
        }

        @Nonnull @Override
        Truth getPossibility(Archetype<ECS_TYPE> archetype, Admission admission) {
            return condition.getPossibility(archetype, admission);
        }

        @Nonnull @Override
        Truth evaluate(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation) {
            return condition.evaluate(store, entity, evaluation);
        }

        @Override
        void emit(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation, Runnable match) {
            condition.emit(store, entity, evaluation, match);
        }

        @Override
        public boolean requiresComponentType(ComponentType<ECS_TYPE, ?> componentType) {
            return condition.requiresComponentType(componentType);
        }

        @Override
        public void validateRegistry(ComponentRegistry<ECS_TYPE> registry) {
            condition.validateRegistry(registry);
        }

        @Override
        public void validate() {
            condition.validate();
        }
    }

    /// A definition that also names the component a change system observes.
    public static final class ComponentChange<ECS_TYPE, LINK_DATA, C extends Component<ECS_TYPE>>
        extends Definition<ECS_TYPE, LINK_DATA> {
        private final ComponentType<ECS_TYPE, C> componentType;

        private ComponentChange(
            Query<ECS_TYPE> sourceQuery,
            GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
            Query<ECS_TYPE> targetQuery,
            ComponentType<ECS_TYPE, C> componentType
        ) {
            super(sourceQuery, type, targetQuery);
            this.componentType = Objects.requireNonNull(componentType, "componentType");
        }

        @Nonnull
        public ComponentType<ECS_TYPE, C> getComponentType() {
            return componentType;
        }

        @Override
        public boolean requiresComponentType(ComponentType<ECS_TYPE, ?> requiredType) {
            return requiredType == componentType || super.requiresComponentType(requiredType);
        }

        @Override
        public void validateRegistry(ComponentRegistry<ECS_TYPE> registry) {
            super.validateRegistry(registry);
            componentType.validateRegistry(registry);
        }

        @Override
        public void validate() {
            super.validate();
            componentType.validate();
        }
    }

    enum Truth {
        FALSE,
        TRUE,
        UNKNOWN
    }

    static final class Evaluation<ECS_TYPE> {
        private final IdentityHashMap<Binding<ECS_TYPE, ?>, BoundLink<ECS_TYPE>> links = new IdentityHashMap<>();
        private final ArrayList<Binding<ECS_TYPE, ?>> bindings = new ArrayList<>();
        private final ArrayList<BoundLink<ECS_TYPE>> orderedLinks = new ArrayList<>();
        private final ArrayDeque<BoundLink<ECS_TYPE>> availableLinks = new ArrayDeque<>();
        private final ArrayList<Snapshot<ECS_TYPE>> delivered = new ArrayList<>();
        private final ArrayDeque<AndContinuation<ECS_TYPE>> availableContinuations = new ArrayDeque<>();
        private final ArrayDeque<HolderRead<ECS_TYPE>> availableHolderReads = new ArrayDeque<>();
        @Nullable
        private ReachableQuery.Visited<ECS_TYPE> visited;
        private final ArrayList<Ref<ECS_TYPE>> reached = new ArrayList<>();
        private int deliveredCount;
        @Nullable
        Ref<ECS_TYPE> callbackSource;
        @Nullable
        Archetype<ECS_TYPE> sourceArchetype;
        @Nullable
        ComponentType<ECS_TYPE, ?> suppliedType;
        @Nullable
        Component<ECS_TYPE> suppliedComponent;
        @Nullable
        Holder<ECS_TYPE> sourceHolder;

        ReachableQuery.Visited<ECS_TYPE> getVisited() {
            if (visited == null) visited = new ReachableQuery.Visited<>();
            return visited;
        }

        /// Keeps the breadth first order and its capacity between traversals. {@link #getVisited()}
        /// decides which linked entities are new.
        @Nonnull
        ArrayList<Ref<ECS_TYPE>> getReached() {
            return reached;
        }

        @Nullable @SuppressWarnings("unchecked")
        <C extends Component<ECS_TYPE>> C getComponent(
            Store<ECS_TYPE> store,
            @Nullable Ref<ECS_TYPE> entity,
            ComponentType<ECS_TYPE, C> type
        ) {
            if (entity == null) {
                return sourceHolder == null ? null : sourceHolder.getComponent(type);
            }
            return entity == callbackSource && type == suppliedType
                ? (C) suppliedComponent : store.getComponent(entity, type);
        }

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        boolean isAccessible(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity) {
            return entity == null ? sourceHolder != null : entity.isValid() && entity.getStore() == store;
        }

        @Nonnull
        Archetype<ECS_TYPE> getArchetype(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity) {
            if (entity == null) {
                return Objects.requireNonNull(sourceHolder, "sourceHolder").getArchetype();
            }
            if (entity == callbackSource) {
                assert sourceArchetype != null;
                return sourceArchetype;
            }
            return store.getArchetype(entity);
        }

        boolean hasUnresolvedTargets(@Nullable Ref<ECS_TYPE> entity, GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type) {
            if (entity == null) {
                return false;
            }
            var tracker = type.getRelationshipTypeRegistry().getTracker();
            return tracker != null && tracker.hasUnresolvedOutgoing(type, entity);
        }

        @Nonnull
        Truth readHolder(
            Store<ECS_TYPE> store,
            GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type,
            RelationshipQuery<ECS_TYPE> targetQuery,
            @Nullable Binding<ECS_TYPE, ?> binding,
            @Nullable Runnable match
        ) {
            var read = availableHolderReads.pollFirst();
            if (read == null) read = new HolderRead<>();
            read.store = store;
            read.evaluation = this;
            read.targetQuery = targetQuery;
            read.binding = binding;
            read.match = match;
            read.truth = Truth.FALSE;
            try {
                assert sourceHolder != null;
                var outgoing = sourceHolder.getComponent(type.getSourceType());
                if (outgoing != null) {
                    for (int i = 0; i < outgoing.size(); i++) {
                        read.accept(outgoing.getTarget(i), getLinkData(type, outgoing, i));
                    }
                }
                var tracker = type.getRelationshipTypeRegistry().getTracker();
                if (tracker != null) {
                    read.supplied = outgoing;
                    tracker.readHolderLinks(type, sourceHolder, store, read);
                }
                return read.truth;
            } finally {
                read.clear();
                availableHolderReads.addFirst(read);
            }
        }

        void begin() {
            deliveredCount = 0;
        }

        void end() {
            clearLinks();
            for (int i = 0; i < orderedLinks.size(); i++) {
                availableLinks.addLast(orderedLinks.get(i));
            }
            links.clear();
            bindings.clear();
            orderedLinks.clear();
            for (int i = 0; i < deliveredCount; i++) {
                delivered.get(i).clear();
            }
            deliveredCount = 0;
        }

        private void clearLinks() {
            for (BoundLink<ECS_TYPE> link : orderedLinks) {
                link.source = null;
                link.target = null;
                link.data = null;
                link.present = false;
            }
        }

        void copyFrom(Evaluation<ECS_TYPE> other) {
            clearLinks();
            for (int i = 0; i < other.orderedLinks.size(); i++) {
                var otherLink = other.orderedLinks.get(i);
                if (otherLink.present) {
                    assert otherLink.target != null;
                    set(other.bindings.get(i), otherLink.source, otherLink.target, otherLink.data);
                }
            }
        }

        void set(Binding<ECS_TYPE, ?> binding, @Nullable Ref<ECS_TYPE> source, Ref<ECS_TYPE> target, @Nullable Object data) {
            var link = links.get(binding);
            if (link == null) {
                link = availableLinks.pollFirst();
                if (link == null) link = new BoundLink<>();
                links.put(binding, link);
                bindings.add(binding);
                orderedLinks.add(link);
            }
            link.source = source;
            link.target = target;
            link.data = data;
            link.present = true;
        }

        void clear(Binding<ECS_TYPE, ?> binding) {
            var link = links.get(binding);
            assert link != null;
            link.source = null;
            link.target = null;
            link.data = null;
            link.present = false;
        }

        @Nullable
        BoundLink<ECS_TYPE> get(Binding<ECS_TYPE, ?> binding) {
            return links.get(binding);
        }

        boolean markUnique() {
            for (int i = 0; i < deliveredCount; i++) {
                if (delivered.get(i).matches(this)) {
                    return false;
                }
            }
            Snapshot<ECS_TYPE> snapshot;
            if (deliveredCount == delivered.size()) {
                snapshot = new Snapshot<>();
                delivered.add(snapshot);
            } else {
                snapshot = delivered.get(deliveredCount);
            }
            deliveredCount++;
            snapshot.capture(this);
            return true;
        }

        @Nonnull
        AndContinuation<ECS_TYPE> getContinuation() {
            var continuation = availableContinuations.pollFirst();
            return continuation == null ? new AndContinuation<>() : continuation;
        }

        void release(AndContinuation<ECS_TYPE> continuation) {
            continuation.clear();
            availableContinuations.addFirst(continuation);
        }
    }

    private static final class HolderRead<ECS_TYPE> implements java.util.function.BiConsumer<Ref<ECS_TYPE>, Object> {
        @Nullable
        private Store<ECS_TYPE> store;
        @Nullable
        private Evaluation<ECS_TYPE> evaluation;
        @Nullable
        private RelationshipQuery<ECS_TYPE> targetQuery;
        @Nullable
        private Binding<ECS_TYPE, ?> binding;
        @Nullable
        private Runnable match;
        @Nullable
        private OutgoingLink<ECS_TYPE, ECS_TYPE> supplied;
        private Truth truth = Truth.FALSE;

        @Override
        public void accept(@Nullable Ref<ECS_TYPE> target, @Nullable Object data) {
            assert store != null && evaluation != null && targetQuery != null;
            if (supplied != null && target != null && supplied.contains(target)) return;
            if (match == null && truth == Truth.TRUE) return;
            var current = target == null || !target.isValid() || target.getStore() != store
                ? Truth.UNKNOWN : targetQuery.evaluate(store, target, evaluation);
            if (current != Truth.TRUE) {
                if (current == Truth.UNKNOWN && truth != Truth.TRUE) truth = Truth.UNKNOWN;
                return;
            }
            truth = Truth.TRUE;
            if (match == null) return;
            assert binding != null;
            var existing = evaluation.get(binding);
            if (existing != null && existing.present) {
                if (existing.source == null && existing.target == target) {
                    targetQuery.emit(store, target, evaluation, match);
                }
                return;
            }
            evaluation.set(binding, null, target, data);
            try {
                targetQuery.emit(store, target, evaluation, match);
            } finally {
                evaluation.clear(binding);
            }
        }

        private void clear() {
            store = null;
            evaluation = null;
            targetQuery = null;
            binding = null;
            match = null;
            supplied = null;
        }
    }

    private static final class AndContinuation<ECS_TYPE> implements Runnable {
        @Nullable
        private AndQuery<ECS_TYPE> query;
        private int index;
        @Nullable
        private Store<ECS_TYPE> store;
        @Nullable
        private Ref<ECS_TYPE> entity;
        @Nullable
        private Evaluation<ECS_TYPE> evaluation;
        @Nullable
        private Runnable match;

        private void set(
            AndQuery<ECS_TYPE> query,
            int index,
            Store<ECS_TYPE> store,
            @Nullable Ref<ECS_TYPE> entity,
            Evaluation<ECS_TYPE> evaluation,
            Runnable match
        ) {
            this.query = query;
            this.index = index;
            this.store = store;
            this.entity = entity;
            this.evaluation = evaluation;
            this.match = match;
        }

        @Override
        public void run() {
            assert query != null && store != null && evaluation != null && match != null;
            query.emit(index, store, entity, evaluation, match);
        }

        private void clear() {
            query = null;
            store = null;
            entity = null;
            evaluation = null;
            match = null;
        }
    }

    static final class BoundLink<ECS_TYPE> {
        @Nullable
        Ref<ECS_TYPE> source;
        @Nullable
        Ref<ECS_TYPE> target;
        @Nullable
        Object data;
        boolean present;
    }

    private static final class Snapshot<ECS_TYPE> {
        // most queries bind one link
        private final IdentityHashMap<Binding<ECS_TYPE, ?>, Ref<ECS_TYPE>> sources = new IdentityHashMap<>(1);
        private final IdentityHashMap<Binding<ECS_TYPE, ?>, Ref<ECS_TYPE>> targets = new IdentityHashMap<>(1);

        private void capture(Evaluation<ECS_TYPE> evaluation) {
            sources.clear();
            targets.clear();
            for (int i = 0; i < evaluation.orderedLinks.size(); i++) {
                var link = evaluation.orderedLinks.get(i);
                if (link.present) {
                    var binding = evaluation.bindings.get(i);
                    sources.put(binding, link.source);
                    targets.put(binding, link.target);
                }
            }
        }

        private void clear() {
            sources.clear();
            targets.clear();
        }

        private boolean matches(Evaluation<ECS_TYPE> evaluation) {
            int present = 0;
            for (int i = 0; i < evaluation.orderedLinks.size(); i++) {
                var link = evaluation.orderedLinks.get(i);
                if (!link.present) {
                    continue;
                }
                present++;
                var binding = evaluation.bindings.get(i);
                if (sources.get(binding) != link.source || targets.get(binding) != link.target) {
                    return false;
                }
            }
            return present == sources.size();
        }
    }

    private static final class NativeQuery<ECS_TYPE> extends RelationshipQuery<ECS_TYPE> {
        private final Query<ECS_TYPE> query;

        private NativeQuery(Query<ECS_TYPE> query) {
            this.query = query;
        }

        @Nonnull @Override
        Truth getPossibility(Archetype<ECS_TYPE> archetype, Admission admission) {
            return query.test(archetype) ? Truth.TRUE : Truth.FALSE;
        }

        @Nonnull @Override
        Truth evaluate(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation) {
            if (!evaluation.isAccessible(store, entity)) {
                return Truth.UNKNOWN;
            }
            return query.test(evaluation.getArchetype(store, entity)) ? Truth.TRUE : Truth.FALSE;
        }

        @Override
        void emit(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation, Runnable match) {
            match.run();
        }

        @Override
        public boolean requiresComponentType(ComponentType<ECS_TYPE, ?> componentType) {
            return query.requiresComponentType(componentType);
        }

        @Override
        public void validateRegistry(ComponentRegistry<ECS_TYPE> registry) {
            query.validateRegistry(registry);
        }

        @Override
        public void validate() {
            query.validate();
        }
    }

    private static final class AndQuery<ECS_TYPE> extends RelationshipQuery<ECS_TYPE> {
        private final List<RelationshipQuery<ECS_TYPE>> conditions;

        private AndQuery(List<RelationshipQuery<ECS_TYPE>> conditions) {
            this.conditions = List.copyOf(conditions);
        }

        @Override
        boolean containsRecursion() {
            return conditions.stream().anyMatch(RelationshipQuery::containsRecursion);
        }

        @Nonnull @Override
        Truth getPossibility(Archetype<ECS_TYPE> archetype, Admission admission) {
            var result = Truth.TRUE;
            for (RelationshipQuery<ECS_TYPE> condition : conditions) {
                var current = condition.getPossibility(archetype, admission);
                if (current == Truth.FALSE) return Truth.FALSE;
                if (current == Truth.UNKNOWN) result = Truth.UNKNOWN;
            }
            return result;
        }

        @Nonnull @Override
        Truth evaluate(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation) {
            var result = Truth.TRUE;
            for (RelationshipQuery<ECS_TYPE> condition : conditions) {
                var current = condition.evaluate(store, entity, evaluation);
                if (current == Truth.FALSE) {
                    return Truth.FALSE;
                }
                if (current == Truth.UNKNOWN) {
                    result = Truth.UNKNOWN;
                }
            }
            return result;
        }

        @Override
        void emit(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation, Runnable match) {
            emit(0, store, entity, evaluation, match);
        }

        private void emit(
            int index,
            Store<ECS_TYPE> store,
            @Nullable Ref<ECS_TYPE> entity,
            Evaluation<ECS_TYPE> evaluation,
            Runnable match
        ) {
            if (index == conditions.size()) {
                match.run();
                return;
            }
            var continuation = evaluation.getContinuation();
            continuation.set(this, index + 1, store, entity, evaluation, match);
            try {
                conditions.get(index).emit(store, entity, evaluation, continuation);
            } finally {
                evaluation.release(continuation);
            }
        }

        @Override
        public boolean requiresComponentType(ComponentType<ECS_TYPE, ?> componentType) {
            for (RelationshipQuery<ECS_TYPE> condition : conditions) {
                if (condition.requiresComponentType(componentType)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void validateRegistry(ComponentRegistry<ECS_TYPE> registry) {
            for (RelationshipQuery<ECS_TYPE> condition : conditions) {
                condition.validateRegistry(registry);
            }
        }

        @Override
        public void validate() {
            for (RelationshipQuery<ECS_TYPE> condition : conditions) {
                condition.validate();
            }
        }
    }

    private static final class OrQuery<ECS_TYPE> extends RelationshipQuery<ECS_TYPE> {
        private final List<RelationshipQuery<ECS_TYPE>> alternatives;

        private OrQuery(List<RelationshipQuery<ECS_TYPE>> alternatives) {
            if (alternatives.isEmpty()) {
                throw new IllegalArgumentException("At least one relationship query must be provided");
            }
            this.alternatives = List.copyOf(alternatives);
        }

        @Override
        boolean containsRecursion() {
            return alternatives.stream().anyMatch(RelationshipQuery::containsRecursion);
        }

        @Nonnull @Override
        Truth getPossibility(Archetype<ECS_TYPE> archetype, Admission admission) {
            var result = Truth.FALSE;
            for (RelationshipQuery<ECS_TYPE> alternative : alternatives) {
                var current = alternative.getPossibility(archetype, admission);
                if (current == Truth.TRUE) return Truth.TRUE;
                if (current == Truth.UNKNOWN) result = Truth.UNKNOWN;
            }
            return result;
        }

        @Nonnull @Override
        Truth evaluate(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation) {
            var result = Truth.FALSE;
            for (RelationshipQuery<ECS_TYPE> alternative : alternatives) {
                var current = alternative.evaluate(store, entity, evaluation);
                if (current == Truth.TRUE) {
                    return Truth.TRUE;
                }
                if (current == Truth.UNKNOWN) {
                    result = Truth.UNKNOWN;
                }
            }
            return result;
        }

        @Override
        void emit(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation, Runnable match) {
            for (RelationshipQuery<ECS_TYPE> alternative : alternatives) {
                if (alternative.evaluate(store, entity, evaluation) == Truth.TRUE) {
                    alternative.emit(store, entity, evaluation, match);
                }
            }
        }

        @Override
        public boolean requiresComponentType(ComponentType<ECS_TYPE, ?> componentType) {
            for (RelationshipQuery<ECS_TYPE> alternative : alternatives) {
                if (alternative.requiresComponentType(componentType)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void validateRegistry(ComponentRegistry<ECS_TYPE> registry) {
            for (RelationshipQuery<ECS_TYPE> alternative : alternatives) {
                alternative.validateRegistry(registry);
            }
        }

        @Override
        public void validate() {
            for (RelationshipQuery<ECS_TYPE> alternative : alternatives) {
                alternative.validate();
            }
        }
    }

    private static final class NotQuery<ECS_TYPE> extends RelationshipQuery<ECS_TYPE> {
        private final RelationshipQuery<ECS_TYPE> condition;

        private NotQuery(RelationshipQuery<ECS_TYPE> condition) {
            this.condition = Objects.requireNonNull(condition, "condition");
        }

        @Override
        boolean containsRecursion() {
            return condition.containsRecursion();
        }

        @Nonnull @Override
        Truth getPossibility(Archetype<ECS_TYPE> archetype, Admission admission) {
            return switch (condition.getPossibility(archetype, admission)) {
                case FALSE -> Truth.TRUE;
                case TRUE -> Truth.FALSE;
                case UNKNOWN -> Truth.UNKNOWN;
            };
        }

        @Nonnull @Override
        Truth evaluate(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation) {
            return switch (condition.evaluate(store, entity, evaluation)) {
                case FALSE -> Truth.TRUE;
                case TRUE -> Truth.FALSE;
                case UNKNOWN -> Truth.UNKNOWN;
            };
        }

        @Override
        void emit(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation, Runnable match) {
            match.run();
        }

        @Override
        public boolean requiresComponentType(ComponentType<ECS_TYPE, ?> componentType) {
            return condition.requiresComponentType(componentType);
        }

        @Override
        public void validateRegistry(ComponentRegistry<ECS_TYPE> registry) {
            condition.validateRegistry(registry);
        }

        @Override
        public void validate() {
            condition.validate();
        }
    }

    @Nonnull
    private static <ECS_TYPE> Truth evaluateTargets(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type,
        RelationshipQuery<ECS_TYPE> targetQuery,
        Store<ECS_TYPE> store,
        @Nullable Ref<ECS_TYPE> entity,
        Evaluation<ECS_TYPE> evaluation
    ) {
        if (!evaluation.isAccessible(store, entity)) {
            return Truth.UNKNOWN;
        }
        if (entity == null) {
            return evaluation.readHolder(store, type, targetQuery, null, null);
        }
        var outgoing = evaluation.getComponent(store, entity, type.getSourceType());
        var result = evaluation.hasUnresolvedTargets(entity, type) ? Truth.UNKNOWN : Truth.FALSE;
        if (outgoing == null) {
            return result;
        }
        for (int i = 0; i < outgoing.size(); i++) {
            var target = outgoing.getTarget(i);
            var current = !target.isValid() || target.getStore() != store
                ? Truth.UNKNOWN : targetQuery.evaluate(store, target, evaluation);
            if (current == Truth.TRUE) {
                return Truth.TRUE;
            }
            if (current == Truth.UNKNOWN) {
                result = Truth.UNKNOWN;
            }
        }
        return result;
    }

    private static final class ExistsQuery<ECS_TYPE> extends RelationshipQuery<ECS_TYPE> {
        private final GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type;
        private final RelationshipQuery<ECS_TYPE> targetQuery;

        private ExistsQuery(GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type, RelationshipQuery<ECS_TYPE> targetQuery) {
            this.type = Objects.requireNonNull(type, "type");
            this.targetQuery = Objects.requireNonNull(targetQuery, "targetQuery");
        }

        @Override
        boolean containsRecursion() {
            return targetQuery.containsRecursion();
        }

        @Nonnull @Override
        Truth getPossibility(Archetype<ECS_TYPE> archetype, Admission admission) {
            return linkPossibility(type.getSourceType().test(archetype), type.getRelationshipTypeRegistry(), admission);
        }

        @Nonnull @Override
        Truth evaluate(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation) {
            return evaluateTargets(type, targetQuery, store, entity, evaluation);
        }

        @Override
        void emit(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation, Runnable match) {
            match.run();
        }

        @Override
        public boolean requiresComponentType(ComponentType<ECS_TYPE, ?> componentType) {
            return type.getSourceType() == componentType || targetQuery.requiresComponentType(componentType);
        }

        @Override
        public void validateRegistry(ComponentRegistry<ECS_TYPE> registry) {
            type.getSourceType().validateRegistry(registry);
            targetQuery.validateRegistry(registry);
        }

        @Override
        public void validate() {
            type.getSourceType().validate();
            targetQuery.validate();
        }
    }

    private static final class BridgeExistsQuery<SOURCE, TARGET> extends RelationshipQuery<SOURCE> {
        private final GenericRelationshipType<SOURCE, TARGET, ?> type;
        private final Query<TARGET> targetQuery;

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static boolean hasUnresolved(RelationshipTracker tracker, GenericRelationshipType type, Ref entity) {
            return tracker.hasUnresolvedOutgoing(type, entity);
        }

        private BridgeExistsQuery(GenericRelationshipType<SOURCE, TARGET, ?> type, Query<TARGET> targetQuery) {
            this.type = Objects.requireNonNull(type, "type");
            this.targetQuery = Objects.requireNonNull(targetQuery, "targetQuery");
            if (hasRelationshipCondition(targetQuery)) {
                throw new IllegalArgumentException("Bridge relationship type '" + type.getDescriptor().id()
                    + "' takes a native target query, and a relationship condition on a bridge target"
                    + " is a later specification");
            }
        }

        @Nonnull @Override
        Truth getPossibility(Archetype<SOURCE> archetype, Admission admission) {
            return linkPossibility(type.getSourceType().test(archetype), type.getRelationshipTypeRegistry(), admission);
        }

        @Nonnull @Override
        Truth evaluate(Store<SOURCE> store, @Nullable Ref<SOURCE> entity, Evaluation<SOURCE> evaluation) {
            if (!evaluation.isAccessible(store, entity)) {
                return Truth.UNKNOWN;
            }
            var outgoing = evaluation.getComponent(store, entity, type.getSourceType());
            var tracker = type.getRelationshipTypeRegistry().getTracker();
            var result = entity != null && tracker != null && hasUnresolved(tracker, type, entity)
                ? Truth.UNKNOWN : Truth.FALSE;
            if (outgoing == null) {
                return result;
            }
            for (int i = 0; i < outgoing.size(); i++) {
                var target = outgoing.getTarget(i);
                if (!target.isValid()) {
                    result = Truth.UNKNOWN;
                    continue;
                }
                if (targetQuery.test(target.getStore().getArchetype(target))) {
                    return Truth.TRUE;
                }
            }
            return result;
        }

        @Override
        void emit(Store<SOURCE> store, @Nullable Ref<SOURCE> entity, Evaluation<SOURCE> evaluation, Runnable match) {
            match.run();
        }

        @Override
        public boolean requiresComponentType(ComponentType<SOURCE, ?> componentType) {
            return type.getSourceType() == componentType;
        }

        @Override
        public void validateRegistry(ComponentRegistry<SOURCE> registry) {
            type.getSourceType().validateRegistry(registry);
        }

        @Override
        public void validate() {
            type.getSourceType().validate();
            type.getIncomingType().validate();
            targetQuery.validateRegistry(type.getTargetRelationshipTypeRegistry().getComponentRegistry());
            targetQuery.validate();
        }
    }

    /// One enumerated relationship. Each result carries its source, its target and its link data.
    public static final class Binding<ECS_TYPE, LINK_DATA> extends RelationshipQuery<ECS_TYPE> {
        private final GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type;
        private final RelationshipQuery<ECS_TYPE> targetQuery;

        private Binding(GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type, RelationshipQuery<ECS_TYPE> targetQuery) {
            this.type = Objects.requireNonNull(type, "type");
            this.targetQuery = Objects.requireNonNull(targetQuery, "targetQuery");
        }

        @Override
        boolean containsRecursion() {
            return targetQuery.containsRecursion();
        }

        @Nonnull @Override
        Truth getPossibility(Archetype<ECS_TYPE> archetype, Admission admission) {
            return linkPossibility(type.getSourceType().test(archetype), type.getRelationshipTypeRegistry(), admission);
        }

        @Nonnull @Override
        Truth evaluate(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation) {
            return evaluateTargets(type, targetQuery, store, entity, evaluation);
        }

        @Override
        void emit(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> entity, Evaluation<ECS_TYPE> evaluation, Runnable match) {
            if (entity == null) {
                evaluation.readHolder(store, type, targetQuery, this, match);
                return;
            }
            var outgoing = evaluation.getComponent(store, entity, type.getSourceType());
            if (outgoing == null) {
                return;
            }
            var existing = evaluation.get(this);
            if (existing != null && existing.present) {
                if (existing.source != entity) {
                    return;
                }
                for (int i = 0; i < outgoing.size(); i++) {
                    var target = outgoing.getTarget(i);
                    if (target == existing.target && target.isValid() && target.getStore() == store
                        && targetQuery.evaluate(store, target, evaluation) == Truth.TRUE) {
                        targetQuery.emit(store, target, evaluation, match);
                        return;
                    }
                }
                return;
            }
            for (int i = 0; i < outgoing.size(); i++) {
                var target = outgoing.getTarget(i);
                if (!target.isValid() || target.getStore() != store
                    || targetQuery.evaluate(store, target, evaluation) != Truth.TRUE) {
                    continue;
                }
                evaluation.set(this, entity, target, getLinkData(type, outgoing, i));
                try {
                    targetQuery.emit(store, target, evaluation, match);
                } finally {
                    evaluation.clear(this);
                }
            }
        }

        @Override
        public boolean requiresComponentType(ComponentType<ECS_TYPE, ?> componentType) {
            return type.getSourceType() == componentType || targetQuery.requiresComponentType(componentType);
        }

        @Override
        public void validateRegistry(ComponentRegistry<ECS_TYPE> registry) {
            type.getSourceType().validateRegistry(registry);
            targetQuery.validateRegistry(registry);
        }

        @Override
        public void validate() {
            type.getSourceType().validate();
            targetQuery.validate();
        }

        @Nonnull
        Class<LINK_DATA> getDataClass() {
            return type.getDescriptor().linkDataClass();
        }
    }

    /// A traversal that reports every linked entity reachable from one start entity. Each result carries
    /// the depth it was first found at, and only a depth one result carries the link data. The
    /// start is never reported, even when a cycle reaches it.
    public static final class ReachableEnumeration<ECS_TYPE, LINK_DATA> {
        private final GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type;
        private final Direction direction;
        private final int maxDepth;
        private final RelationshipQuery<ECS_TYPE> throughQuery;
        private final RelationshipQuery<ECS_TYPE> reachedQuery;

        private ReachableEnumeration(
            GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
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

        @Nonnull
        public GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> getRelationshipType() {
            return type;
        }

        @SuppressWarnings("unused")
        @Nonnull
        public Direction getDirection() {
            return direction;
        }

        @SuppressWarnings("unused")
        public int getMaxDepth() {
            return maxDepth;
        }

        void validate(Store<ECS_TYPE> store) {
            type.validate(store);
            var registry = store.getRegistry();
            throughQuery.validateRegistry(registry);
            throughQuery.validate();
            reachedQuery.validateRegistry(registry);
            reachedQuery.validate();
        }

        void traverse(
            Store<ECS_TYPE> store,
            Ref<ECS_TYPE> start,
            Evaluation<ECS_TYPE> evaluation,
            RelationshipResults<ECS_TYPE, LINK_DATA> results
        ) {
            var visited = evaluation.getVisited();
            var reached = evaluation.getReached();
            visited.add(start);
            reached.add(start);
            try {
                int next = 0;
                // the last pass adds nothing, it only finds out whether a linked entity lay beyond maxDepth
                for (int depth = 0; depth <= maxDepth && next < reached.size(); depth++) {
                    int end = reached.size();
                    while (next < end) {
                        var current = reached.get(next++);
                        if (depth != 0) {
                            var through = throughQuery.evaluate(store, current, evaluation);
                            if (through == Truth.UNKNOWN) results.markTruncated();
                            if (through != Truth.TRUE) continue;
                        }
                        if (expand(store, start, current, depth, evaluation, results)) return;
                    }
                }
            } finally {
                visited.clear();
                reached.clear();
            }
        }

        /// Returns true when a linked entity beyond maxDepth ended the search.
        private boolean expand(
            Store<ECS_TYPE> store,
            Ref<ECS_TYPE> start,
            Ref<ECS_TYPE> current,
            int depth,
            Evaluation<ECS_TYPE> evaluation,
            RelationshipResults<ECS_TYPE, LINK_DATA> results
        ) {
            var tracker = type.getRelationshipTypeRegistry().getTracker();
            if (tracker != null && (direction == Direction.OUTGOING
                ? tracker.hasUnresolvedOutgoing(type, current) : tracker.hasUnresolvedIncoming(type, current))) {
                results.markTruncated();
            }
            if (direction == Direction.OUTGOING) {
                var outgoing = evaluation.getComponent(store, current, type.getSourceType());
                if (outgoing == null) return false;
                for (int i = 0; i < outgoing.size(); i++) {
                    var linkedEntity = outgoing.getTarget(i);
                    if (!isAvailable(store, linkedEntity)) {
                        results.markTruncated();
                        continue;
                    }
                    var data = depth == 0 ? outgoing.getData(i, type.getDescriptor().linkDataClass()) : null;
                    if (discover(store, start, linkedEntity, data, depth + 1, evaluation, results)) return true;
                }
                return false;
            }
            var incoming = evaluation.getComponent(store, current, type.getIncomingType());
            if (incoming == null) return false;
            for (int i = 0; i < incoming.size(); i++) {
                var linkedEntity = incoming.getSource(i);
                if (!isAvailable(store, linkedEntity)) {
                    results.markTruncated();
                    continue;
                }
                var data = depth == 0 ? getIncomingData(store, linkedEntity, current, evaluation) : null;
                if (discover(store, start, linkedEntity, data, depth + 1, evaluation, results)) return true;
            }
            return false;
        }

        private boolean discover(
            Store<ECS_TYPE> store,
            Ref<ECS_TYPE> start,
            Ref<ECS_TYPE> linkedEntity,
            @Nullable LINK_DATA data,
            int depth,
            Evaluation<ECS_TYPE> evaluation,
            RelationshipResults<ECS_TYPE, LINK_DATA> results
        ) {
            if (!evaluation.getVisited().add(linkedEntity)) return false;
            if (depth > maxDepth) {
                results.markTruncated();
                return true;
            }
            evaluation.getReached().add(linkedEntity);
            var match = reachedQuery.evaluate(store, linkedEntity, evaluation);
            if (match == Truth.UNKNOWN) results.markTruncated();
            if (match != Truth.TRUE) return false;
            reachedQuery.emit(store, linkedEntity, evaluation, results.getReachedCollector(start, linkedEntity, data, depth, evaluation));
            return false;
        }

        /// Link data lives on the source.
        @Nullable
        private LINK_DATA getIncomingData(
            Store<ECS_TYPE> store,
            Ref<ECS_TYPE> source,
            Ref<ECS_TYPE> target,
            Evaluation<ECS_TYPE> evaluation
        ) {
            var outgoing = evaluation.getComponent(store, source, type.getSourceType());
            return outgoing == null ? null : outgoing.getData(target, type.getDescriptor().linkDataClass());
        }

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        private boolean isAvailable(Store<ECS_TYPE> store, @Nullable Ref<ECS_TYPE> linkedEntity) {
            return linkedEntity != null && linkedEntity.isValid() && linkedEntity.getStore() == store;
        }
    }
}
