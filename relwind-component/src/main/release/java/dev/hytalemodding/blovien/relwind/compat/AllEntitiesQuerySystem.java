/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.compat;

import com.hypixel.hytale.component.system.QuerySystem;

/// Hytale 0.6 has no native hierarchy scope. Entity queries already include every matching entity.
/// The pre-release build supplies the same interface with Hytale 0.7's all-entities scope.
public interface AllEntitiesQuerySystem<ECS_TYPE> extends QuerySystem<ECS_TYPE> {
}
