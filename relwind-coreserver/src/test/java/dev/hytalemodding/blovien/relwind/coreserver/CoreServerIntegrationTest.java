/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.coreserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/// Installation access without a running server.
class CoreServerIntegrationTest {
    /// A component class reads its own type through the composition root.
    @Test
    void theIntegrationIsAbsentBeforeOneIsRegistered() {
        assertThrows(IllegalStateException.class, CoreServerIntegration::get);
    }
}
