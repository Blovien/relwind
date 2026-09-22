/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.query.AndQuery;
import com.hypixel.hytale.component.query.NotQuery;
import com.hypixel.hytale.component.query.OrQuery;
import com.hypixel.hytale.component.query.Query;

import javax.annotation.Nonnull;

import java.lang.reflect.Field;

/// Reads the operands of Hytale's AndQuery, OrQuery and NotQuery through cached reflection fields.
/// Do this when a query is built, not on every read of a system's query.
final class NativeQueryAccess {
    private static final Field AND_OPERANDS = getField(AndQuery.class, "queries", Query[].class);
    private static final Field OR_OPERANDS = getField(OrQuery.class, "queries", Query[].class);
    private static final Field NOT_OPERAND = getField(NotQuery.class, "query", Query.class);

    private NativeQueryAccess() {
    }

    @Nonnull @SuppressWarnings("unchecked")
    static <ECS_TYPE> Query<ECS_TYPE>[] andOperands(Query<ECS_TYPE> query) {
        return (Query<ECS_TYPE>[]) read(AND_OPERANDS, query);
    }

    @Nonnull @SuppressWarnings("unchecked")
    static <ECS_TYPE> Query<ECS_TYPE>[] orOperands(Query<ECS_TYPE> query) {
        return (Query<ECS_TYPE>[]) read(OR_OPERANDS, query);
    }

    @Nonnull @SuppressWarnings("unchecked")
    static <ECS_TYPE> Query<ECS_TYPE> notOperand(Query<ECS_TYPE> query) {
        return (Query<ECS_TYPE>) read(NOT_OPERAND, query);
    }

    @Nonnull
    private static Field getField(Class<?> type, String name, Class<?> expectedType) {
        try {
            var field = type.getDeclaredField(name);
            if (field.getType() != expectedType || !field.trySetAccessible()) {
                throw new IllegalStateException("Unsupported Hytale query layout: " + type.getName());
            }
            return field;
        } catch (NoSuchFieldException failure) {
            throw new IllegalStateException("Unsupported Hytale query layout: " + type.getName(), failure);
        }
    }

    @Nonnull
    private static Object read(Field field, Object query) {
        try {
            return field.get(query);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot read Hytale query composition", failure);
        }
    }
}
