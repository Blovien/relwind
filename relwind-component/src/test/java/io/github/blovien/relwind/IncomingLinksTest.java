/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package io.github.blovien.relwind;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.StoreFixture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncomingLinksTest {
    @Test
    void smallGroupsReuseClearedStorageThroughSingletonAndEmptyStates() {
        try (var fixture = new StoreFixture()) {
            var first = fixture.addEntity(new StoreFixture.Position(1, 0), null);
            var second = fixture.addEntity(new StoreFixture.Position(2, 0), null);
            var links = new IncomingLinks<Object, Object>();

            links.add(first);
            assertNull(links.sources);
            links.add(second);

            var storage = links.sources;
            assertTrue(links.remove(first));
            assertSame(second, links.getSource(0));
            assertSame(storage, links.sources);
            for (var ref : storage) {
                assertNull(ref);
            }

            var copy = links.clone();
            assertNotSame(storage, copy.sources);
            assertTrue(links.remove(second));
            links.add(first);
            links.add(second);

            assertSame(storage, links.sources);
            assertSame(second, copy.getSource(0));
            assertEquals(1, copy.size());

            links.clear();
            assertEquals(0, links.size());
            assertNull(links.source);
            assertNull(links.sources);
        }
    }

    @Test
    void groupsAboveTheRetentionBoundReleaseTheirArrayAfterShrinkingToOne() {
        try (var fixture = new StoreFixture()) {
            var refs = new ArrayList<Ref<Object>>();
            var links = new IncomingLinks<Object, Object>();

            for (int i = 0; i < 9; i++) {
                var ref = fixture.addEntity(new StoreFixture.Position(i, 0), null);
                refs.add(ref);
                links.add(ref);
            }

            for (int i = 0; i < refs.size() - 1; i++) {
                assertTrue(links.remove(refs.get(i)));
            }

            assertEquals(1, links.size());
            assertSame(refs.getLast(), links.getSource(0));
            assertNull(links.sources);
        }
    }
}
