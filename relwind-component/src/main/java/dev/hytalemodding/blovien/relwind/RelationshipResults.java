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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;

/// Borrowed until the callback returns. The collection, iterator and elements are reused. Copy
/// any values needed afterward. Only one iterator may be active per batch; use indexed access
/// for nested iteration of the same batch.
public final class RelationshipResults<ECS_TYPE, LINK_DATA> implements Iterable<RelationshipResult<ECS_TYPE, LINK_DATA>> {
    private final ArrayList<RelationshipResult<ECS_TYPE, LINK_DATA>> results = new ArrayList<>();
    private final ArrayList<RelationshipQuery.Evaluation<ECS_TYPE>> evaluations = new ArrayList<>();
    private final RelationshipQuery.Evaluation<ECS_TYPE> evaluation = new RelationshipQuery.Evaluation<>();
    private final BorrowedIterator iterator = new BorrowedIterator();
    private final Collector collector = new Collector();
    private final ReachedCollector reachedCollector = new ReachedCollector();
    @Nullable
    private RelationshipResult<ECS_TYPE, LINK_DATA> callbackResult;
    private int size;
    private boolean truncated;

    RelationshipResults() {
    }

    /// Each nested callback borrows a separate batch. Native system callbacks can run on different threads.
    static final class Pool<ECS_TYPE, LINK_DATA> {
        private final ThreadLocal<ArrayDeque<RelationshipResults<ECS_TYPE, LINK_DATA>>> available =
            ThreadLocal.withInitial(ArrayDeque::new);

        RelationshipResults<ECS_TYPE, LINK_DATA> borrow() {
            var results = available.get().pollFirst();
            return results == null ? new RelationshipResults<>() : results;
        }

        void release(RelationshipResults<ECS_TYPE, LINK_DATA> results) {
            results.clear();
            available.get().addFirst(results);
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /// False for a complete search, including one that matched nothing.
    public boolean isTruncated() {
        return truncated;
    }

    @Nonnull
    public RelationshipResult<ECS_TYPE, LINK_DATA> get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(index);
        }
        return results.get(index);
    }

    /// Per-result callbacks reuse one view while batch readers retain independently stored rows.
    RelationshipResult<ECS_TYPE, LINK_DATA> getCallbackResult(int index) {
        if (callbackResult == null) callbackResult = new RelationshipResult<>();
        callbackResult.copyFrom(get(index));
        return callbackResult;
    }

    @Nonnull @Override
    public Iterator<RelationshipResult<ECS_TYPE, LINK_DATA>> iterator() {
        iterator.index = 0;
        return iterator;
    }

    @Nonnull
    RelationshipQuery.Evaluation<ECS_TYPE> begin() {
        clear();
        evaluation.begin();
        return evaluation;
    }

    void markTruncated() {
        truncated = true;
    }

    void add(
        @Nullable Ref<ECS_TYPE> source,
        Ref<ECS_TYPE> target,
        @Nullable LINK_DATA data,
        int depth,
        RelationshipQuery.Evaluation<ECS_TYPE> currentEvaluation
    ) {
        RelationshipResult<ECS_TYPE, LINK_DATA> result;
        RelationshipQuery.Evaluation<ECS_TYPE> resultEvaluation;
        if (size == results.size()) {
            result = new RelationshipResult<>();
            resultEvaluation = new RelationshipQuery.Evaluation<>();
            results.add(result);
            evaluations.add(resultEvaluation);
        } else {
            result = results.get(size);
            resultEvaluation = evaluations.get(size);
        }
        resultEvaluation.copyFrom(currentEvaluation);
        result.set(source, currentEvaluation.sourceHolder, target, data, depth);
        result.setEvaluation(resultEvaluation);
        size++;
    }

    void end() {
        evaluation.end();
        collector.currentEvaluation = null;
        collector.primary = null;
        reachedCollector.source = null;
        reachedCollector.target = null;
        reachedCollector.data = null;
        reachedCollector.currentEvaluation = null;
    }

    void clear() {
        if (callbackResult != null) callbackResult.clear();
        for (int i = 0; i < size; i++) {
            results.get(i).clear();
            evaluations.get(i).end();
        }
        size = 0;
        truncated = false;
        iterator.index = 0;
        end();
    }

    /// Each linked entity a reachable enumeration reports produces exactly one result.
    @Nonnull
    Runnable getReachedCollector(
        Ref<ECS_TYPE> source,
        Ref<ECS_TYPE> target,
        @Nullable LINK_DATA data,
        int depth,
        RelationshipQuery.Evaluation<ECS_TYPE> currentEvaluation
    ) {
        reachedCollector.source = source;
        reachedCollector.target = target;
        reachedCollector.data = data;
        reachedCollector.depth = depth;
        reachedCollector.collected = false;
        reachedCollector.currentEvaluation = currentEvaluation;
        return reachedCollector;
    }

    @Nonnull
    Runnable getCollector(
        RelationshipQuery.Binding<ECS_TYPE, LINK_DATA> primary,
        RelationshipQuery.Evaluation<ECS_TYPE> currentEvaluation
    ) {
        collector.primary = primary;
        collector.currentEvaluation = currentEvaluation;
        return collector;
    }

    private final class ReachedCollector implements Runnable {
        @Nullable
        private Ref<ECS_TYPE> source;
        @Nullable
        private Ref<ECS_TYPE> target;
        @Nullable
        private LINK_DATA data;
        private int depth;
        private boolean collected;
        @Nullable
        private RelationshipQuery.Evaluation<ECS_TYPE> currentEvaluation;

        @Override
        public void run() {
            if (collected) {
                return;
            }
            collected = true;
            assert source != null && target != null && currentEvaluation != null;
            add(source, target, data, depth, currentEvaluation);
        }
    }

    private final class BorrowedIterator implements Iterator<RelationshipResult<ECS_TYPE, LINK_DATA>> {
        private int index;

        @Override
        public boolean hasNext() {
            return index < size;
        }

        @Nonnull @Override
        public RelationshipResult<ECS_TYPE, LINK_DATA> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return results.get(index++);
        }
    }

    private final class Collector implements Runnable {
        @Nullable
        private RelationshipQuery.Binding<ECS_TYPE, LINK_DATA> primary;
        @Nullable
        private RelationshipQuery.Evaluation<ECS_TYPE> currentEvaluation;

        @Override
        public void run() {
            assert currentEvaluation != null && primary != null;
            if (currentEvaluation.markUnique()) {
                var link = currentEvaluation.get(primary);
                assert link != null && link.target != null;
                add(link.source, link.target, primary.getDataClass().cast(link.data), 1, currentEvaluation);
            }
        }
    }
}
