/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.component.BridgeStoreFixture;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.StoreFixture;
import com.hypixel.hytale.component.BridgeStoreFixture.Blocks;
import com.hypixel.hytale.component.BridgeStoreFixture.Entities;

import java.util.Arrays;
import java.util.Set;
import static dev.hytalemodding.blovien.relwind.RelationshipTestFixtures.*;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Store;

import javax.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.bson.BsonDocument;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A registration fixes the linked entities, the link data and the rules of one relationship type, and
/// refuses a declaration the installation cannot keep.
class RelationshipTypeRegistrationTest {
    private static final Relationships relationships = new Relationships();

    private final ComponentRegistry<Object> componentRegistry = new ComponentRegistry<>();
    private final Store<Object> store = componentRegistry.addStore(new Object(), EmptyResourceStorage.get());

    @AfterEach
    void shutDownRegistry() {
        componentRegistry.shutdown();
    }

    @Test
    void registeringOneTypeAddsItsStorageAndAnImmutableMarker() {
        var relationshipTypes = new RelationshipTypeRegistry<>(componentRegistry);
        int componentCount = componentRegistry.getData().getComponentSize();

        var type = relationshipTypes.registerRelationship(
            "relwind:test/follows", RelationshipRules.single().retainOnTransfer());

        var markerType = type.getSourceType();
        var marker = componentRegistry.createComponent(markerType);
        assertEquals(componentCount + 2, componentRegistry.getData().getComponentSize());
        assertSame(OutgoingLink.class, markerType.getTypeClass());
        assertEquals("relwind:test/follows", type.getDescriptor().id());
        assertEquals(RelationshipRules.Survival.RETAIN, type.getDescriptor().getTransfer());
        assertSame(markerType, type.getSourceType());

        var first = store.addEntity(componentRegistry.newHolder(), AddReason.LOAD);
        var second = store.addEntity(componentRegistry.newHolder(), AddReason.LOAD);
        marker.add(first, null);
        marker.add(second, null);
        var copy = marker.clone();

        marker.clear();

        assertEquals(2, copy.size());
    }

    @Test
    void registeringTheSameIdTwiceIsRejectedWhateverTheRestOfTheDeclarationSays() {
        var relationshipTypes = new RelationshipTypeRegistry<>(componentRegistry);
        int componentCount = componentRegistry.getData().getComponentSize();
        relationshipTypes.registerRelationship(
            "relwind:test/follows", RelationshipRules.single().retainOnTransfer());

        var duplicate = assertThrows(
            IllegalArgumentException.class,
            () -> relationshipTypes.registerRelationship(
                "relwind:test/follows", RelationshipRules.single().retainOnTransfer())
        );
        var duplicateId = assertThrows(
            IllegalArgumentException.class,
            () -> relationshipTypes.registerRelationship("relwind:test/follows", Integer.class, null,
                RelationshipRules.multiple().retainOnDeactivation())
        );

        assertTrue(duplicate.getMessage().contains("relwind:test/follows"));
        assertTrue(duplicate.getMessage().contains("already registered"));
        assertTrue(duplicateId.getMessage().contains("relwind:test/follows"));
        assertTrue(duplicateId.getMessage().contains("already registered"));
        assertEquals(componentCount + 2, componentRegistry.getData().getComponentSize());
    }

    @Test
    void distinctRegistrationsReceiveDistinctMarkerTypes() {
        var relationshipTypes = new RelationshipTypeRegistry<>(componentRegistry);
        int componentCount = componentRegistry.getData().getComponentSize();

        var followsType = relationshipTypes.registerRelationship(
            "relwind:test/follows", RelationshipRules.single());
        var likesType = relationshipTypes.registerRelationship("relwind:test/likes", Integer.class, null,
            RelationshipRules.multiple().retainOnTransfer().retainOnDeactivation());
        var followsMarker = followsType.getSourceType();
        var likesMarker = likesType.getSourceType();

        assertNotSame(followsMarker, likesMarker);
        assertEquals(componentCount + 4, componentRegistry.getData().getComponentSize());
        assertSame(followsMarker, followsType.getSourceType());
        assertSame(likesMarker, likesType.getSourceType());
    }

    @Test
    void persistentSlotDataRequiresAVersionedBuilderCodec() {
        var relationshipTypes = new RelationshipTypeRegistry<>(componentRegistry);
        var accepted = slotType(relationshipTypes, "relwind:test/slot", SlotData.CODEC);
        assertSame(SlotData.CODEC, accepted.getDescriptor().codec());
        relationshipTypes.unregisterRelationship(accepted);

        var rejected = assertThrows(IllegalArgumentException.class,
            () -> slotType(relationshipTypes, "relwind:test/slot", unversionedSlotCodec()));

        assertTrue(rejected.getMessage().contains("relwind:test/slot"));
    }

    @Test
    void aPersistentTypeRejectsARawDocumentCodec() {
        var relationshipTypes = new RelationshipTypeRegistry<>(componentRegistry);

        // TODO: Replace BSON_DOCUMENT when Hytale supplies an equivalent raw document codec.
        // Shared-source Codec only records a future buffer replacement.
        var rawRejected = assertThrows(IllegalArgumentException.class,
            () -> relationshipTypes.registerRelationship(
                "relwind:test/raw",
                BsonDocument.class,
                Codec.BSON_DOCUMENT,
                RelationshipRules.multiple().retainOnTransfer().retainOnDeactivation()));

        assertTrue(rawRejected.getMessage().contains("relwind:test/raw"));
    }

    @Test
    void registeringADataComponentFixesTheLinkDataClassAndKeepsNoCodec() {
        var relationshipTypes = new RelationshipTypeRegistry<>(componentRegistry);
        var mountType = componentRegistry.registerComponent(MountData.class, MountData::new);

        var mounted = relationshipTypes.registerRelationship(
            mountType, new MountObserver(), RelationshipRules.single());

        assertSame(MountData.class, mounted.getDescriptor().linkDataClass());
        assertSame(mountType, mounted.getDescriptor().getDataComponentType());
        assertNull(mounted.getDescriptor().codec());
    }

    @Test
    void aDataComponentIsRejectedForATypeWithMultipleTargets() {
        var relationshipTypes = new RelationshipTypeRegistry<>(componentRegistry);
        var mountType = componentRegistry.registerComponent(MountData.class, MountData::new);

        var multipleTargets = assertThrows(IllegalArgumentException.class,
            () -> relationshipTypes.registerRelationship(
                mountType, new MountObserver(), RelationshipRules.multiple()));

        assertTrue(multipleTargets.getMessage().contains("single target"));
    }

    @Test
    void aDataComponentBelongsToOneRelationshipTypeWhateverClassObservesIt() {
        var relationshipTypes = new RelationshipTypeRegistry<>(componentRegistry);
        var mountType = componentRegistry.registerComponent(MountData.class, MountData::new);
        relationshipTypes.registerRelationship(
            mountType, new MountObserver(), RelationshipRules.single());

        var shared = assertThrows(IllegalArgumentException.class,
            () -> relationshipTypes.registerRelationship(
                mountType, new OtherMountObserver(), RelationshipRules.single()));

        assertTrue(shared.getMessage().contains("already carries"));
    }

    @Test
    void aPersistentDataComponentWithoutACodecIsRejected() {
        var relationshipTypes = new RelationshipTypeRegistry<>(componentRegistry);
        var withoutCodec = componentRegistry.registerComponent(MountData.class, MountData::new);

        var rejected = assertThrows(IllegalArgumentException.class,
            () -> relationshipTypes.registerRelationship(
                "relwind:test/mounted",
                withoutCodec,
                new MountObserver(),
                RelationshipRules.single()));

        assertTrue(rejected.getMessage().contains("relwind:test/mounted"));
        assertTrue(rejected.getMessage().contains("codec"));
    }

    @Test
    void aPersistentDataComponentWithACodecIsAccepted() {
        var relationshipTypes = new RelationshipTypeRegistry<>(componentRegistry);
        var withCodec = componentRegistry.registerComponent(MountData.class, "RelwindTestMount", MountData.CODEC);

        var accepted = relationshipTypes.registerRelationship(
            "relwind:test/seated",
            withCodec,
            new MountObserver(),
            RelationshipRules.single());

        assertSame(withCodec, accepted.getDescriptor().getDataComponentType());
    }

    @Test
    void registeringAPersistentTypeRequiresANamespacedId() {
        var relationshipTypes = new RelationshipTypeRegistry<>(componentRegistry);
        var namespaced = relationshipTypes.registerRelationship(
            "relwind:test/namespaced", RelationshipRules.single());
        assertEquals("relwind:test/namespaced", namespaced.getDescriptor().id());

        var rejected = assertThrows(
            IllegalArgumentException.class,
            () -> relationshipTypes.registerRelationship("not-namespaced", RelationshipRules.single())
        );

        assertTrue(rejected.getMessage().contains("namespaced"));
        assertDoesNotThrow(() -> relationshipTypes.registerRelationship(RelationshipRules.single()));
    }

    /// A null codec means a holder never serializes that storage.
    @Test
    void runtimeStorageIsUnnamed() {
        var relationshipTypes = new RelationshipTypeRegistry<>(componentRegistry);

        var first = relationshipTypes.registerRelationship(RelationshipRules.single());
        var second = relationshipTypes.registerRelationship(RelationshipRules.single());
        var saved = relationshipTypes.registerRelationship(
            "relwind:test/saved-storage",
            RelationshipRules.single());

        assertNull(first.getDescriptor().id());
        assertNull(second.getDescriptor().id());
        assertEquals("relwind:test/saved-storage", saved.getDescriptor().id());
    }

    @Test
    void distinctRuntimeRegistrationsReceiveDistinctStorage() {
        var relationshipTypes = new RelationshipTypeRegistry<>(componentRegistry);

        var first = relationshipTypes.registerRelationship(RelationshipRules.single());
        var second = relationshipTypes.registerRelationship(RelationshipRules.single());

        assertNotSame(first.getSourceType(), second.getSourceType());
        assertNotSame(first.getIncomingType(), second.getIncomingType());
    }

    /// None of these data classes would meet a saved type's codec rules.
    @ParameterizedTest
    @ValueSource(classes = {BsonDocument.class, Integer.class, SlotData.class})
    void aRuntimeTypeCarriesAnyLinkDataClassAndKeepsNoCodec(Class<?> dataClass) {
        var relationshipTypes = new RelationshipTypeRegistry<>(componentRegistry);
        var retaining = RelationshipRules.multiple().retainOnTransfer().retainOnDeactivation();

        var type = relationshipTypes.registerRelationship(dataClass, retaining);

        assertSame(dataClass, type.getDescriptor().linkDataClass());
        assertNull(type.getDescriptor().codec());
    }

    @Test
    void theDataOverloadsRejectATypeRegisteredWithoutLinkData() {
        try (var fixture = new StoreFixture()) {
            var types = new RelationshipTypeRegistry<>(fixture.registry());
            var linked = types.registerRelationship(RelationshipRules.single());
            var source = fixture.addEntity(new StoreFixture.Position(1, 2), null);
            var target = fixture.addEntity(new StoreFixture.Position(3, 4), null);

            assertThrows(IllegalStateException.class,
                () -> relationships.addTarget(fixture.store(), source, linked, target, null));
            assertThrows(IllegalStateException.class,
                () -> relationships.putTarget(fixture.store(), source, linked, target, null));
        }
    }

    @ParameterizedTest
    @EnumSource(RelationshipRules.Cardinality.class)
    void unconfiguredRulesDeclareTheirFactoryCardinalityAndNoRetention(
        RelationshipRules.Cardinality cardinality
    ) {
        var rules = unconfiguredRules(cardinality);

        assertEquals(cardinality, rules.getCardinality());
        assertEquals(RelationshipRules.Survival.REMOVE, rules.getTransfer());
        assertEquals(RelationshipRules.Survival.REMOVE, rules.getTemporaryDeactivation());
        assertEquals(RelationshipRules.TargetDeletion.PRESERVE_SOURCE, rules.getTargetDeletion());
        assertEquals(RelationshipRules.SourceRetention.RELEASE, rules.getSourceRetention());
    }

    private static RelationshipRules unconfiguredRules(RelationshipRules.Cardinality cardinality) {
        return switch (cardinality) {
            case SINGLE_TARGET -> RelationshipRules.single();
            case MULTIPLE_TARGETS -> RelationshipRules.multiple();
        };
    }

    @ParameterizedTest
    @CsvSource(value = {"relwind:test/defaults, true", "null, false"}, nullValues = "null")
    void anUnconfiguredRegistrationDeclaresSingleLinksWithoutDataOrRetention(
        @Nullable String id, boolean persistent
    ) {
        var relationshipTypes = new RelationshipTypeRegistry<>(componentRegistry);

        var type = registerUnconfigured(relationshipTypes, id);

        assertEquals(id, type.getDescriptor().id());
        assertEquals(persistent, type.getDescriptor().isPersistent());
        assertEquals(RelationshipRules.Cardinality.SINGLE_TARGET, type.getDescriptor().getCardinality());
        assertSame(Void.class, type.getDescriptor().linkDataClass());
        assertNull(type.getDescriptor().codec());
        assertNull(type.getDescriptor().getDataComponentType());
        assertEquals(RelationshipRules.Survival.REMOVE, type.getDescriptor().getTransfer());
        assertEquals(RelationshipRules.Survival.REMOVE, type.getDescriptor().getTemporaryDeactivation());
        assertEquals(RelationshipRules.TargetDeletion.PRESERVE_SOURCE, type.getDescriptor().getTargetDeletion());
        assertEquals(RelationshipRules.SourceRetention.RELEASE, type.getDescriptor().getSourceRetention());
    }

    private static RelationshipType<Object, Void> registerUnconfigured(
        RelationshipTypeRegistry<Object> types,
        @Nullable String id
    ) {
        if (id == null) {
            return types.registerRelationship(RelationshipRules.single());
        }
        return types.registerRelationship(id, RelationshipRules.single());
    }

    /// A shared rules constant is safe to pass to more than one registration.
    @ParameterizedTest
    @MethodSource("policyMethods")
    void aPolicyMethodChangesOnlyItsOwnPolicyAndLeavesItsSourceAlone(
        Function<RelationshipRules, RelationshipRules> policy,
        Map<String, Object> changed
    ) {
        var base = RelationshipRules.single();

        var declared = policies(policy.apply(base));

        assertEquals(defaultPoliciesWith(changed), declared);
        assertEquals(policies(RelationshipRules.single()), policies(base),
            "the policy method must leave the value it was called on unchanged");
    }

    private static Stream<Arguments> policyMethods() {
        return Stream.of(
            policyMethod("retainOnTransfer", RelationshipRules::retainOnTransfer,
                Map.of("transfer", RelationshipRules.Survival.RETAIN)),
            policyMethod("retainOnDeactivation", RelationshipRules::retainOnDeactivation,
                Map.of("temporaryDeactivation", RelationshipRules.Survival.RETAIN)),
            policyMethod("cascadeSource", RelationshipRules::cascadeSource,
                Map.of("targetDeletion", RelationshipRules.TargetDeletion.CASCADE_SOURCE)),
            policyMethod("retainSourceStorage", RelationshipRules::retainSourceStorage,
                Map.of("sourceRetention", RelationshipRules.SourceRetention.RETAIN)));
    }

    private static Arguments policyMethod(
        String name,
        Function<RelationshipRules, RelationshipRules> policy,
        Map<String, Object> changed
    ) {
        return Arguments.argumentSet(name, policy, changed);
    }

    private static Map<String, Object> defaultPoliciesWith(Map<String, Object> changed) {
        var policies = policies(RelationshipRules.single());
        policies.putAll(changed);
        return policies;
    }

    private static Map<String, Object> policies(RelationshipRules rules) {
        var policies = new LinkedHashMap<String, Object>();
        policies.put("cardinality", rules.getCardinality());
        policies.put("transfer", rules.getTransfer());
        policies.put("temporaryDeactivation", rules.getTemporaryDeactivation());
        policies.put("targetDeletion", rules.getTargetDeletion());
        policies.put("sourceRetention", rules.getSourceRetention());
        return policies;
    }

    private static GenericRelationshipType<Object, Object, SlotData> slotType(
        RelationshipTypeRegistry<Object> types,
        String id,
        Codec<SlotData> codec
    ) {
        return types.registerRelationship(id, SlotData.class, codec,
            RelationshipRules.multiple().retainOnTransfer().retainOnDeactivation());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aChunkIdentityRejectsEarlierCascadeBridgesAcrossTheSharedInstallation(boolean registeredBySibling) {
        var sourceRegistry = new ComponentRegistry<Object>();
        var targets = new RelationshipTypeRegistry<>(componentRegistry);
        var installer = new RelationshipTypeRegistry<>(sourceRegistry);
        var sibling = new RelationshipTypeRegistry<>(sourceRegistry);
        try {
            var declaring = registeredBySibling ? sibling : installer;
            var cascade = declaring.registerRelationship(targets, RelationshipRules.single().cascadeSource());
            var chunks = new TestPersistenceIdentity<Object, Integer>((store, ref) -> 1,
                Codec.INTEGER, "CHUNK_POSITIONS", (store, id) -> false, peer -> null);

            var failure = assertThrows(IllegalArgumentException.class,
                () -> installer.installTracker(chunks, TestStoreRuntime.<Object>inline().withoutDeletion()));

            assertTrue(failure.getMessage().contains("never deletes a linked entity"), failure.getMessage());
            assertNull(installer.getTracker());
            assertNull(sibling.getTracker());
            assertDoesNotThrow(cascade.getSourceType()::validate);
            assertDoesNotThrow(cascade.getIncomingType()::validate);
            var entities = TestPersistenceIdentity.<Object, Integer>of(ref -> 1, Codec.INTEGER);
            var tracker = assertDoesNotThrow(() -> installer.installTracker(entities, TestStoreRuntime.inline()));
            assertSame(tracker, installer.getTracker());
            assertSame(tracker, sibling.getTracker());
            assertDoesNotThrow(() -> declaring.registerRelationship(
                targets,
                RelationshipRules.single().cascadeSource()));
        } finally {
            sibling.close();
            installer.close();
            targets.close();
            sourceRegistry.shutdown();
        }
    }

    @Test
    void aBridgeTypeWithAChunkStoreSourceRejectsCascadingSourceDeletion() {
        var chunkRegistry = new ComponentRegistry<Object>();
        chunkRegistry.addStore(new Object(), EmptyResourceStorage.get());
        var entityTypes = new RelationshipTypeRegistry<>(
            componentRegistry);
        var chunkTypes = new RelationshipTypeRegistry<>(
            chunkRegistry);
        try {
            chunkTypes.installTracker(new TestPersistenceIdentity<>((store, ref) -> 1,
                Codec.INTEGER, "CHUNK_POSITIONS", (store, id) -> false, peer -> null),
                TestStoreRuntime.<Object>inline().withoutDeletion());
            var rejected = assertThrows(IllegalArgumentException.class, () -> chunkTypes.registerRelationship(
                entityTypes,
                RelationshipRules.single().cascadeSource()));
            assertTrue(rejected.getMessage().contains("never deletes a linked entity"), rejected.getMessage());

            // an entity source may cascade, because deleting a block entity can delete its sources
            var anchoredTo = entityTypes.registerRelationship(
                chunkTypes,
                RelationshipRules.single().cascadeSource());
            assertEquals(RelationshipRules.TargetDeletion.CASCADE_SOURCE,
                anchoredTo.getDescriptor().getTargetDeletion());
        } finally {
            chunkTypes.close();
            entityTypes.close();
            chunkRegistry.shutdown();
        }
    }

    @Test
    void cascadeRejectionFollowsTheRuntimeCapabilityAndNotTheInstallationName() {
        assertTrue(TestStoreRuntime.inline().isDeletionSupported());
        assertFalse(TestStoreRuntime.inline().withoutDeletion().isDeletionSupported());

        var sourceRegistry = new ComponentRegistry<Object>();
        sourceRegistry.addStore(new Object(), EmptyResourceStorage.get());
        var targets = new RelationshipTypeRegistry<>(componentRegistry);
        var sources = new RelationshipTypeRegistry<>(sourceRegistry);
        try {
            sources.installTracker(new TestPersistenceIdentity<Object, Integer>((store, ref) -> 1,
                Codec.INTEGER, "CHUNK_POSITIONS", (store, id) -> false, peer -> null),
                TestStoreRuntime.inline());

            var cascading = sources.registerRelationship(targets, RelationshipRules.single().cascadeSource());

            assertEquals(RelationshipRules.TargetDeletion.CASCADE_SOURCE,
                cascading.getDescriptor().getTargetDeletion());
        } finally {
            sources.close();
            targets.close();
            sourceRegistry.shutdown();
        }
    }

    /// Without persistence, a named type would be silently never saved.
    @Test
    void aRegistryDeclaredWithoutPersistenceRejectsNamedTypesAndKeepsRuntimeTypes() {
        var installation = StoreInstallation.withoutPersistence(componentRegistry,
            TestPersistenceIdentity.<Object, Integer>of(ref -> 1, Codec.INTEGER), TestStoreRuntime.inline());
        var targetRegistry = new ComponentRegistry<Object>();
        targetRegistry.addStore(new Object(), EmptyResourceStorage.get());
        var targets = new RelationshipTypeRegistry<>(targetRegistry);
        try {
            var types = installation.getRelationshipTypeRegistry();

            var rejected = assertThrows(IllegalArgumentException.class, () -> types.registerRelationship(
                "relwind:test/never-saved",
                RelationshipRules.single()));
            var rejectedBridge = assertThrows(IllegalArgumentException.class, () -> types.registerRelationship(
                "relwind:test/never-saved-bridge",
                targets,
                RelationshipRules.single()));

            assertTrue(rejected.getMessage().contains("saves no links"), rejected.getMessage());
            assertTrue(rejectedBridge.getMessage().contains("saves no links"), rejectedBridge.getMessage());
            assertNull(types.getRegisteredType("relwind:test/never-saved"));
            assertDoesNotThrow(() -> types.registerRelationship(RelationshipRules.single()));
            assertDoesNotThrow(() -> types.registerRelationship(targets, RelationshipRules.single()));
            assertThrows(IllegalStateException.class, installation::installPersistence);
        } finally {
            targets.close();
            installation.close();
            targetRegistry.shutdown();
        }
    }

    @Test
    void aSavedBridgeTypeRetainsItsLinksAcrossTransferAndDeactivation() {
        try (var bridge = new BridgeTypes()) {
            var saved = bridge.entityTypes.registerRelationship(
                "relwind:test/saved",
                bridge.chunkTypes,
                RelationshipRules.single().retainOnDeactivation().retainOnTransfer());

            assertTrue(saved.getDescriptor().isPersistent());
            assertEquals(RelationshipRules.Survival.RETAIN, saved.getDescriptor().getTemporaryDeactivation());
            assertEquals(RelationshipRules.Survival.RETAIN, saved.getDescriptor().getTransfer());
        }
    }

    /// A saved bridge type follows the id rules of every saved type.
    @Test
    void aSavedBridgeTypeRejectsAnUnnamespacedId() {
        try (var bridge = new BridgeTypes()) {
            assertThrows(IllegalArgumentException.class, () -> bridge.entityTypes.registerRelationship(
                "unnamespaced",
                bridge.chunkTypes,
                RelationshipRules.single()));
        }
    }

    /// A saved bridge type follows the codec rules of every saved type.
    @Test
    void aSavedBridgeTypeRejectsAnUnversionedLinkDataCodec() {
        try (var bridge = new BridgeTypes()) {
            assertThrows(IllegalArgumentException.class, () -> bridge.entityTypes.registerRelationship(
                "relwind:test/unversioned",
                bridge.chunkTypes,
                SlotData.class,
                unversionedSlotCodec(),
                RelationshipRules.multiple()));
        }
    }

    @Test
    void aBridgeTypeRejectsTheRegistryItIsDeclaredOn() {
        try (var bridge = new BridgeTypes()) {
            assertThrows(IllegalArgumentException.class, () -> bridge.entityTypes.registerRelationship(
                bridge.entityTypes,
                RelationshipRules.single()));
        }
    }

    private final class BridgeTypes implements AutoCloseable {
        private final ComponentRegistry<Object> chunkRegistry = new ComponentRegistry<>();
        private final RelationshipTypeRegistry<Object> entityTypes =
            new RelationshipTypeRegistry<>(componentRegistry);
        private final RelationshipTypeRegistry<Object> chunkTypes;

        private BridgeTypes() {
            chunkRegistry.addStore(new Object(), EmptyResourceStorage.get());
            chunkTypes = new RelationshipTypeRegistry<>(chunkRegistry);
        }

        @Override
        public void close() {
            chunkTypes.close();
            entityTypes.close();
            chunkRegistry.shutdown();
        }
    }

    private static BuilderCodec<SlotData> unversionedSlotCodec() {
        return BuilderCodec.builder(SlotData.class, SlotData::new)
            .append(new KeyedCodec<>("Value", Codec.INTEGER), (data, value) -> data.value = value, data -> data.value)
            .add()
            .build();
    }

    static final class MountObserver extends RelationshipDataObserver<Object, MountData> {
    }

    static final class OtherMountObserver extends RelationshipDataObserver<Object, MountData> {
    }

    static final class MountData implements Component<Object> {
        static final BuilderCodec<MountData> CODEC = BuilderCodec.builder(MountData.class, MountData::new)
            .append(new KeyedCodec<>("Seat", Codec.INTEGER), (data, seat) -> data.seat = seat, data -> data.seat)
            .add()
            .build();

        int seat;

        @Override
        public MountData clone() {
            var copy = new MountData();
            copy.seat = seat;
            return copy;
        }
    }

    static final class SlotData {
        static final BuilderCodec<SlotData> CODEC = BuilderCodec.builder(SlotData.class, SlotData::new)
            .versioned()
            .codecVersion(1)
            .append(new KeyedCodec<>("Value", Codec.INTEGER), (data, value) -> data.value = value, data -> data.value)
            .setVersionRange(0, 1)
            .add()
            .append(new KeyedCodec<>("Extra", Codec.INTEGER), (data, value) -> data.extra = value, data -> data.extra)
            .setVersionRange(1, 1)
            .add()
            .build();

        int value;
        int extra;
    }

    @Test
    void aBridgeRegistrationStoresOutgoingLinksInTheSourceRegistryAndIncomingLinksInTheTarget() {
        try (var fixture = new BridgeStoreFixture()) {
            fixture.addWorld("overworld");
            var entityTypes = entityTypes(fixture);
            var blockTypes = blockTypes(fixture);
            int entityComponents = fixture.entityRegistry().getData().getComponentSize();
            int blockComponents = fixture.blockRegistry().getData().getComponentSize();

            GenericRelationshipType<Entities, Blocks, Void> anchoredTo = entityTypes.registerRelationship(
                blockTypes,
                RelationshipRules.single());

            assertEquals(entityComponents + 1, fixture.entityRegistry().getData().getComponentSize());
            assertEquals(blockComponents + 1, fixture.blockRegistry().getData().getComponentSize());
            assertSame(OutgoingLink.class, anchoredTo.getSourceType().getTypeClass());
            assertSame(IncomingLinks.class, anchoredTo.getIncomingType().getTypeClass());
            anchoredTo.getSourceType().validateRegistry(fixture.entityRegistry());
            anchoredTo.getIncomingType().validateRegistry(fixture.blockRegistry());
        }
    }

    @Test
    void eachRegistrationFixesItsLinkedEntityAndDataTypesWithoutATypeWitness() {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("overworld");
            RelationshipTypeRegistry<Entities> entities = RelationshipTestFixtures.entityTypes(fixture);
            RelationshipTypeRegistry<Blocks> blocks = RelationshipTestFixtures.blockTypes(fixture);

            RelationshipType<Entities, Void> follows =
                entities.registerRelationship(RelationshipRules.single());
            RelationshipType<Entities, String> owes =
                entities.registerRelationship(String.class, RelationshipRules.single());
            GenericRelationshipType<Entities, Blocks, Void> anchoredTo =
                entities.registerRelationship(blocks, RelationshipRules.single());

            var source = fixture.addEntity(world);
            var friend = fixture.addEntity(world);
            var block = fixture.addBlock(world);

            relationships.addTarget(world.entityStore(), source, follows, friend);
            relationships.addTarget(world.entityStore(), source, owes, friend, "two coins");
            relationships.addTarget(world.entityStore(), source, anchoredTo, block);

            // these assignments compile with no cast
            Ref<Entities> followed = relationships.getFirstTarget(source, follows);
            String owed = relationships.getData(source, owes, friend);
            Ref<Blocks> anchor = relationships.getFirstTarget(source, anchoredTo);

            assertSame(friend, followed);
            assertEquals("two coins", owed);
            assertSame(block, anchor);

            // a registration is unregistered through the handle it returned
            entities.unregisterRelationship(follows);
            entities.unregisterRelationship(owes);
            entities.unregisterRelationship(anchoredTo);
        }
    }
}
