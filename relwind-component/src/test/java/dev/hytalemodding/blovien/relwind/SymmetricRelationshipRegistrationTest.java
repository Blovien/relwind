/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.ComponentRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SymmetricRelationshipRegistrationTest {
    private final ComponentRegistry<Object> sourceRegistry = new ComponentRegistry<>();
    private final ComponentRegistry<Object> targetRegistry = new ComponentRegistry<>();
    private final RelationshipTypeRegistry<Object> sourceTypes = new RelationshipTypeRegistry<>(sourceRegistry);
    private final RelationshipTypeRegistry<Object> targetTypes = new RelationshipTypeRegistry<>(targetRegistry);

    @AfterEach
    void shutDownRegistries() {
        sourceRegistry.shutdown();
        targetRegistry.shutdown();
    }

    @Test
    void symmetricLeavesItsOriginalTraitsUnchanged() {
        var original = RelationshipTraits.defaults();

        var symmetric = original.symmetric();

        assertAll(
            () -> assertEquals(false, original.isSymmetric()),
            () -> assertEquals(true, symmetric.isSymmetric()));
    }

    @Test
    void symmetricPreservesPreviouslySelectedTraits() {
        var original = RelationshipTraits.defaults().exclusive()
            .onDeleteTarget(RelationshipTraits.OnDeleteTarget.DELETE)
            .retainOnTransfer().retainOnDeactivation().retainSourceStorage();

        var symmetric = original.symmetric();

        assertAll(
            () -> assertEquals(false, original.isSymmetric()),
            () -> assertEquals(true, symmetric.isSymmetric()),
            () -> assertEquals(true, symmetric.isExclusive()),
            () -> assertEquals(RelationshipTraits.OnDeleteTarget.DELETE, symmetric.getOnDeleteTarget()),
            () -> assertEquals(RelationshipTraits.Survival.RETAIN, symmetric.getTransfer()),
            () -> assertEquals(RelationshipTraits.Survival.RETAIN, symmetric.getTemporaryDeactivation()),
            () -> assertEquals(RelationshipTraits.SourceRetention.RETAIN, symmetric.getSourceRetention()));
    }

    @ParameterizedTest
    @MethodSource("traitBuilders")
    void otherTraitBuildersPreserveSymmetric(UnaryOperator<RelationshipTraits> builder) {
        var original = RelationshipTraits.defaults().symmetric();

        var changed = builder.apply(original);

        assertEquals(true, changed.isSymmetric());
    }

    private static Stream<UnaryOperator<RelationshipTraits>> traitBuilders() {
        return Stream.of(
            RelationshipTraits::exclusive,
            traits -> traits.onDeleteTarget(RelationshipTraits.OnDeleteTarget.DELETE),
            RelationshipTraits::retainOnTransfer,
            RelationshipTraits::retainOnDeactivation,
            RelationshipTraits::retainSourceStorage);
    }

    @Test
    void registeringASymmetricTypeCarriesTheTraitToItsDescriptor() {
        var traits = RelationshipTraits.defaults().symmetric();

        var type = sourceTypes.registerRelationship(traits);

        assertEquals(true, type.getDescriptor().isSymmetric());
    }

    @Test
    void registeringASymmetricBridgeNamesBothConflictingTraits() {
        var traits = RelationshipTraits.defaults().symmetric();

        var rejected = assertThrows(IllegalArgumentException.class,
            () -> sourceTypes.registerRelationship(targetTypes, traits));

        assertAll(
            () -> assertEquals(true, rejected.getMessage().contains("symmetric")),
            () -> assertEquals(true, rejected.getMessage().contains("bridge")));
    }

    @Test
    void registeringASymmetricExclusiveTypeNamesBothConflictingTraits() {
        var traits = RelationshipTraits.defaults().symmetric().exclusive();

        var rejected = assertThrows(IllegalArgumentException.class,
            () -> sourceTypes.registerRelationship(traits));

        assertAll(
            () -> assertEquals(true, rejected.getMessage().contains("symmetric")),
            () -> assertEquals(true, rejected.getMessage().contains("exclusive")));
    }

    @Test
    void registeringASymmetricTypeWithDeletingTargetsNamesBothConflictingTraits() {
        var traits = RelationshipTraits.defaults().symmetric()
            .onDeleteTarget(RelationshipTraits.OnDeleteTarget.DELETE);

        var rejected = assertThrows(IllegalArgumentException.class,
            () -> sourceTypes.registerRelationship(traits));

        assertAll(
            () -> assertEquals(true, rejected.getMessage().contains("symmetric")),
            () -> assertEquals(true, rejected.getMessage().contains("onDeleteTarget(DELETE)")));
    }
}
