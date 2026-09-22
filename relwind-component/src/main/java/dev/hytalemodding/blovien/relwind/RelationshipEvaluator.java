/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import javax.annotation.Nullable;

import java.util.Objects;

final class RelationshipEvaluator {
    private RelationshipEvaluator() {
    }

    static <ECS_TYPE, LINK_DATA> void evaluate(
        Store<ECS_TYPE> store,
        Ref<ECS_TYPE> source,
        RelationshipQuery.Definition<ECS_TYPE, LINK_DATA> query,
        RelationshipResults<ECS_TYPE, LINK_DATA> results
    ) {
        evaluateDefinition(store, source, null, query, null, null, results);
    }

    static <ECS_TYPE, LINK_DATA> void evaluate(
        Store<ECS_TYPE> store,
        Holder<ECS_TYPE> source,
        RelationshipQuery.Definition<ECS_TYPE, LINK_DATA> query,
        RelationshipResults<ECS_TYPE, LINK_DATA> results
    ) {
        evaluateDefinition(store, null, source, query, null, null, results);
    }

    static <ECS_TYPE, LINK_DATA> void evaluate(
        Store<ECS_TYPE> store,
        Ref<ECS_TYPE> source,
        RelationshipQuery.Definition<ECS_TYPE, LINK_DATA> query,
        ComponentType<ECS_TYPE, ?> suppliedType,
        Component<ECS_TYPE> suppliedComponent,
        RelationshipResults<ECS_TYPE, LINK_DATA> results
    ) {
        evaluateDefinition(store, source, null, query, suppliedType, suppliedComponent, results);
    }

    private static <ECS_TYPE, LINK_DATA> void evaluateDefinition(
        Store<ECS_TYPE> store,
        @Nullable Ref<ECS_TYPE> source,
        @Nullable Holder<ECS_TYPE> holder,
        RelationshipQuery.Definition<ECS_TYPE, LINK_DATA> query,
        @Nullable ComponentType<ECS_TYPE, ?> suppliedType,
        @Nullable Component<ECS_TYPE> suppliedComponent,
        RelationshipResults<ECS_TYPE, LINK_DATA> results
    ) {
        var evaluation = results.begin();
        try {
            if (source != null) {
                source.validate(store);
            } else {
                Objects.requireNonNull(holder, "source");
                evaluation.sourceHolder = holder;
            }
            query.validateRegistry(store.getRegistry());
            query.validate();
            if (suppliedType != null) {
                assert source != null;
                evaluation.callbackSource = source;
                var archetype = store.getArchetype(source);
                evaluation.sourceArchetype = archetype.contains(suppliedType)
                    ? archetype : Archetype.add(archetype, suppliedType);
                evaluation.suppliedType = suppliedType;
                evaluation.suppliedComponent = suppliedComponent;
            }
            if (query.evaluate(store, source, evaluation) == RelationshipQuery.Truth.TRUE) {
                query.emit(store, source, evaluation, results.getCollector(query.getBinding(), evaluation));
            }
        } catch (RuntimeException | Error failure) {
            results.clear();
            throw failure;
        } finally {
            evaluation.callbackSource = null;
            evaluation.sourceArchetype = null;
            evaluation.suppliedType = null;
            evaluation.suppliedComponent = null;
            evaluation.sourceHolder = null;
            results.end();
        }
    }

    static <ECS_TYPE, LINK_DATA> void evaluate(
        Store<ECS_TYPE> store,
        Ref<ECS_TYPE> start,
        RelationshipQuery.ReachableEnumeration<ECS_TYPE, LINK_DATA> enumeration,
        RelationshipResults<ECS_TYPE, LINK_DATA> results
    ) {
        var evaluation = results.begin();
        try {
            enumeration.validate(store);
            start.validate(store);
            enumeration.traverse(store, start, evaluation, results);
        } catch (RuntimeException | Error failure) {
            results.clear();
            throw failure;
        } finally {
            results.end();
        }
    }

}
