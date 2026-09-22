/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.compat;

import com.hypixel.hytale.component.system.HierarchyScope;
import com.hypixel.hytale.component.system.QuerySystem;

/// The default for Relwind's entity queries includes roots and native children, just like explicit
/// relationship iteration. Plugin systems may override the native hierarchy scope to narrow it.
/// The release build supplies the same interface without the API introduced in Hytale 0.7.
public interface AllEntitiesQuerySystem<ECS_TYPE> extends QuerySystem<ECS_TYPE> {
    @Override
    default HierarchyScope getHierarchyScope() {
        return HierarchyScope.ALL;
    }
}
