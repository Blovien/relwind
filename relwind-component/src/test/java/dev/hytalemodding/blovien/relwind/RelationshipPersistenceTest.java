/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;


import com.hypixel.hytale.component.BridgeStoreFixture;
import com.hypixel.hytale.component.BridgeStoreFixture.Blocks;
import com.hypixel.hytale.component.BridgeStoreFixture.Entities;

import java.util.IdentityHashMap;

import static dev.hytalemodding.blovien.relwind.RelationshipTestFixtures.*;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.exception.CodecException;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.WorldEventSystem;
import com.hypixel.hytale.codec.codecs.BsonDocumentCodec;
import com.hypixel.hytale.codec.codecs.UUIDBinaryCodec;
import com.hypixel.hytale.codec.validation.ValidationResults;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonValue;
import org.bson.BsonString;
import org.bson.BsonArray;
import org.bson.BsonBinary;
import org.bson.BsonJavaScriptWithScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/// A saved link is written with everything a later load needs, and a source that comes back reads
/// its links again, including the ones whose target lives in another Store.
class RelationshipPersistenceTest {
    private static final Relationships relationships = new Relationships();

    // TODO: Migrate BSON_DOCUMENT and BsonDocumentCodec usages when Hytale supplies an equivalent
    // for arbitrary BSON payloads. Shared-source Codec only records a future buffer replacement.

    @Test
    void aSavedLinkRecordCarriesTheLinkSchema() {
        var targetId = UUID.randomUUID();

        var saved = schemaSource(targetId, 7);

        var component = saved.getDocument("Components").getDocument(RelationshipPersistence.COMPONENT_ID);
        assertFalse(component.containsKey("Content"), saved.toString());
        var records = component.getArray("Links");
        assertEquals(1, records.size());
        var record = records.get(0).asDocument();
        assertEquals(1, record.getInt32("Version").getValue(), record.toString());
        assertEquals("relwind:test/schema", record.getString("Type").getValue());
        assertEquals(targetId, record.getBinary("Target").asUuid());
        assertEquals("PreserveSource", record.getString("CleanupDisposition").getValue());
        assertEquals(BsonDocument.parse("{Value: 7}"), record.getDocument("Payload"));
        assertEquals("ENTITIES", record.getString("TargetInstallation").getValue());
    }

    @Test
    void aSavedLinkRecordRestoresThroughItsSchema() {
        var targetId = UUID.randomUUID();
        var saved = schemaSource(targetId, 7);

        try (var restored = new Fixture()) {
            var type = schemaType(restored.types);
            var target = restored.add(targetId);
            var source = restored.add(saved);

            restored.persistence.restore(source);

            assertSame(target, relationships.getFirstTarget(source, type));
            assertEquals(7, relationships.getData(source, type, target).value);
        }
    }

    private static BsonDocument schemaSource(UUID targetId, int value) {
        try (var original = new Fixture()) {
            var source = original.add(UUID.randomUUID());
            var target = original.add(targetId);
            var type = schemaType(original.types);
            relationships.addTarget(original.store, source, type, target, new Slot(value));
            return original.registry.serialize(original.store.copySerializableEntity(source));
        }
    }

    private static GenericRelationshipType<Object, Object, Slot> schemaType(RelationshipTypeRegistry<Object> types) {
        return types.registerRelationship("relwind:test/schema", Slot.class, Slot.CODEC,
            RelationshipTraits.defaults().retainOnTransfer().retainOnDeactivation());
    }

    @Test
    void deletingTheSourceTraitSavesTheCascadeSourceDisposition() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var target = fixture.add(UUID.randomUUID());
            var type = fixture.types.registerRelationship("relwind:test/cascade-schema",
                RelationshipTraits.defaults().onDeleteTarget(RelationshipTraits.OnDeleteTarget.DELETE));
            relationships.addTarget(fixture.store, source, type, target);

            var saved = fixture.registry.serialize(fixture.store.copySerializableEntity(source));

            var record = saved.getDocument("Components").getDocument(RelationshipPersistence.COMPONENT_ID)
                .getArray("Links").get(0).asDocument();
            assertEquals("CascadeSource", record.getString("CleanupDisposition").getValue());
        }
    }

    @Test
    void aCascadeSourceRecordRestoresIntoATypeThatDeletesTheSource() {
        try (var fixture = new Fixture()) {
            var targetId = UUID.randomUUID();
            var target = fixture.add(targetId);
            var source = fixture.add(UUID.randomUUID());
            var type = fixture.types.registerRelationship("relwind:test/cascade-schema",
                RelationshipTraits.defaults().onDeleteTarget(RelationshipTraits.OnDeleteTarget.DELETE));
            var saved = records(savedRecord("relwind:test/cascade-schema", targetId, "CascadeSource"));
            fixture.store.addComponent(source, fixture.persistence.getComponentType(), decoded(saved));

            fixture.persistence.restore(source);

            assertSame(target, relationships.getFirstTarget(source, type));
        }
    }

    @Test
    void linkReadsExcludeAnAwayTarget() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var targetId = UUID.randomUUID();
            var target = fixture.add(targetId);
            var type = fixture.persistent("relwind:test/away-read");
            relationships.addTarget(fixture.store, source, type, target);
            fixture.tracker.onEntityUnloaded(targetId, target, UnloadReason.DEACTIVATION);
            var outgoing = new ArrayList<Ref<?>>();
            var incoming = new ArrayList<Ref<?>>();

            var present = relationships.hasTarget(source, type, target);
            relationships.forEachLink(source, (visitedType, visitedTarget, data) -> outgoing.add(visitedTarget));
            relationships.forEachIncomingLink(target, (visitedType, visitedSource, data) -> incoming.add(visitedSource));

            assertEquals(false, present);
            assertEquals(List.of(), outgoing);
            assertEquals(List.of(), incoming);
            assertEquals(true, relationships.hasUnresolvedTargets(source, type));
        }
    }

    @Test
    void clearTargetsRemovesLoadedAndAwayLinksBeforeAnnouncingTheirRemoval() {
        try (var fixture = new Fixture()) {
            var sourceId = UUID.randomUUID();
            var firstId = UUID.randomUUID();
            var secondId = UUID.randomUUID();
            var awayId = UUID.randomUUID();
            var source = fixture.add(sourceId);
            var first = fixture.add(firstId);
            var second = fixture.add(secondId);
            var away = fixture.add(awayId);
            var type = fixture.types.registerRelationship("relwind:test/clear", Slot.class, Slot.CODEC,
                RelationshipTraits.defaults().retainOnDeactivation());
            relationships.addTarget(fixture.store, source, type, first, new Slot(1));
            relationships.addTarget(fixture.store, source, type, second, new Slot(2));
            relationships.addTarget(fixture.store, source, type, away, new Slot(3));
            fixture.tracker.onEntityUnloaded(awayId, away, UnloadReason.DEACTIVATION);
            var removals = new ArrayList<ClearedLink>();
            fixture.registry.registerSystem(new RelationshipChangeSystem<Object, Slot>(type) {
                @Override
                protected void onRelationshipRemoved(LinkedEntity<Object> from, LinkedEntity<Object> to,
                    Slot data, Store<Object> store, CommandBuffer<Object> commands) {
                    assertSame(source, from.reference());
                    assertEquals(sourceId, from.identity());
                    assertEquals(0, relationships.getTargetCount(source, type));
                    assertEquals(0, relationships.getIncomingCount(first, type));
                    assertEquals(0, relationships.getIncomingCount(second, type));
                    assertEquals(false, relationships.hasUnresolvedTargets(source, type));
                    assertEquals(null, store.getComponent(source, fixture.persistence.getComponentType()));
                    removals.add(new ClearedLink(to.reference(), to.identity(), data.value));
                }
            });

            relationships.clearTargets(fixture.store, source, type);
            fixture.tracker.onEntityLoaded(awayId, away);

            assertEquals(3, removals.size());
            assertEquals(Set.of(new ClearedLink(first, firstId, 1), new ClearedLink(second, secondId, 2),
                new ClearedLink(null, awayId, 3)), Set.copyOf(removals));
            assertEquals(0, relationships.getTargetCount(source, type));
        }
    }

    @Test
    void clearTargetsPreservesOtherTypesAndUnownedSavedRecords() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var target = fixture.add(UUID.randomUUID());
            var otherId = UUID.randomUUID();
            var otherTarget = fixture.add(otherId);
            var cleared = fixture.persistent("relwind:test/cleared");
            var retained = fixture.persistent("relwind:test/retained");
            relationships.addTarget(fixture.store, source, cleared, target);
            relationships.addTarget(fixture.store, source, retained, otherTarget);
            fixture.tracker.onEntityUnloaded(otherId, otherTarget, UnloadReason.DEACTIVATION);
            var metadata = fixture.store.getComponent(source, fixture.persistence.getComponentType());
            var unowned = savedRecord(cleared.getDescriptor().id(), UUID.randomUUID(), "FutureDisposition");
            var saved = metadata.getContent();
            saved.getArray("Links").add(unowned);
            fixture.store.replaceComponent(source, fixture.persistence.getComponentType(), decoded(saved));

            relationships.clearTargets(fixture.store, source, cleared);

            var remaining = fixture.store.getComponent(source, fixture.persistence.getComponentType()).getContent();
            assertEquals(2, remaining.getArray("Links").size());
            assertEquals(true, remaining.getArray("Links").contains(unowned));
            assertEquals(true, relationships.hasUnresolvedTargets(source, retained));
        }
    }

    @Test
    void putReplacesAnExclusiveLoadedTargetAndAnnouncesBothDataValues() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var oldId = UUID.randomUUID();
            var newId = UUID.randomUUID();
            var oldTarget = fixture.add(oldId);
            var newTarget = fixture.add(newId);
            var type = fixture.types.registerRelationship("relwind:test/exclusive-put", Slot.class, Slot.CODEC,
                RelationshipTraits.defaults().exclusive().retainOnDeactivation());
            var initial = new Slot(1);
            var replacement = new Slot(2);
            relationships.addTarget(fixture.store, source, type, oldTarget, initial);
            var observer = new PutObserver();
            fixture.registry.registerSystem(observer);

            relationships.putTarget(fixture.store, source, type, newTarget, replacement);

            assertSame(newTarget, relationships.getFirstTarget(source, type));
            assertEquals(1, relationships.getTargetCount(source, type));
            assertSame(replacement, relationships.getData(source, type, newTarget));
            assertEquals(0, relationships.getIncomingCount(oldTarget, type));
            assertEquals(1, relationships.getIncomingCount(newTarget, type));
            assertEquals(1, observer.changes.size());
            var change = observer.changes.getFirst();
            assertSame(RelationshipChangeSystem.Kind.RETARGETED, change.kind);
            assertSame(oldTarget, change.oldTarget.reference());
            assertEquals(oldId, change.oldTarget.identity());
            assertSame(newTarget, change.target.reference());
            assertSame(initial, change.oldData);
            assertSame(replacement, change.data);
            var saved = fixture.store.getComponent(source, fixture.persistence.getComponentType()).getContent()
                .getArray("Links");
            assertEquals(1, saved.size());
            assertEquals(newId, saved.get(0).asDocument().getBinary("Target").asUuid());
            assertEquals(2, saved.get(0).asDocument().getDocument("Payload").getInt32("Value").getValue());
        }
    }

    @Test
    void putReplacesAnExclusiveAwayTargetWithoutRestoringItOnReturn() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var oldId = UUID.randomUUID();
            var newId = UUID.randomUUID();
            var oldTarget = fixture.add(oldId);
            var newTarget = fixture.add(newId);
            var type = fixture.types.registerRelationship("relwind:test/exclusive-away-put", Slot.class, Slot.CODEC,
                RelationshipTraits.defaults().exclusive().retainOnDeactivation());
            var initial = new Slot(1);
            var replacement = new Slot(2);
            relationships.addTarget(fixture.store, source, type, oldTarget, initial);
            fixture.tracker.onEntityUnloaded(oldId, oldTarget, UnloadReason.DEACTIVATION);
            var observer = new PutObserver();
            fixture.registry.registerSystem(observer);

            relationships.putTarget(fixture.store, source, type, newTarget, replacement);

            assertSame(newTarget, relationships.getFirstTarget(source, type));
            assertEquals(1, relationships.getTargetCount(source, type));
            assertSame(replacement, relationships.getData(source, type, newTarget));
            assertEquals(false, relationships.hasUnresolvedTargets(source, type));
            assertEquals(1, observer.changes.size());
            var change = observer.changes.getFirst();
            assertSame(RelationshipChangeSystem.Kind.RETARGETED, change.kind);
            assertEquals(null, change.oldTarget.reference());
            assertEquals(oldId, change.oldTarget.identity());
            assertSame(newTarget, change.target.reference());
            assertSame(initial, change.oldData);
            assertSame(replacement, change.data);
            var saved = fixture.store.getComponent(source, fixture.persistence.getComponentType()).getContent()
                .getArray("Links");
            assertEquals(1, saved.size());
            assertEquals(newId, saved.get(0).asDocument().getBinary("Target").asUuid());
            assertEquals(2, saved.get(0).asDocument().getDocument("Payload").getInt32("Value").getValue());
            fixture.tracker.onEntityLoaded(oldId, oldTarget);
            assertSame(newTarget, relationships.getFirstTarget(source, type));
            assertEquals(0, relationships.getIncomingCount(oldTarget, type));
            assertEquals(1, relationships.getIncomingCount(newTarget, type));
            assertEquals(1, observer.changes.size());
        }
    }

    private static final class PutObserver
        extends WorldEventSystem<Object, RelationshipChangeSystem.ChangeEvent<Object, Slot>> {
        private final List<RelationshipChangeSystem.ChangeEvent<Object, Slot>> changes = new ArrayList<>();

        @SuppressWarnings({"unchecked", "rawtypes"})
        private PutObserver() {
            super((Class) RelationshipChangeSystem.ChangeEvent.class);
        }

        @Override
        public void handle(Store<Object> store, CommandBuffer<Object> commands,
            RelationshipChangeSystem.ChangeEvent<Object, Slot> change) {
            changes.add(change);
        }
    }

    private record ClearedLink(Ref<Object> target, Object identity, int data) { }

    @Test
    void aRecordNamingAnotherInstallationNeverUsesTheLocalIdentityCodec() {
        var decodedTargets = new ArrayList<BsonValue>();
        var codec = new UUIDBinaryCodec() {
            @Override
            public UUID decode(BsonValue value, ExtraInfo info) {
                decodedTargets.add(value);
                return super.decode(value, info);
            }
        };
        try (var fixture = new Fixture(codec)) {
            var source = fixture.add(UUID.randomUUID());
            var targetId = UUID.randomUUID();
            fixture.add(targetId);
            var type = fixture.persistent("relwind:test/foreign-installation");
            var record = savedRecord(type.getDescriptor().id(), targetId, "PreserveSource");
            record.put("TargetInstallation", new BsonString("CHUNK_POSITIONS"));
            var raw = records(record);
            fixture.store.addComponent(source, fixture.persistence.getComponentType(), decoded(raw));

            fixture.persistence.restore(source);

            assertEquals(0, relationships.getTargetCount(source, type));
            assertTrue(decodedTargets.isEmpty(), "only the named installation may decode a target");
            assertEquals(raw, fixture.store.getComponent(source, fixture.persistence.getComponentType()).getContent());
        }
    }

    @Test
    void theInstalledIdentityCodecWritesTheSavedTarget() {
        var targetId = UUID.randomUUID();

        var saved = installedCodecSource(targetId);

        var record = saved.getDocument("Components").getDocument(RelationshipPersistence.COMPONENT_ID)
            .getArray("Links").get(0).asDocument();
        assertEquals(new BsonString(targetId.toString()), record.get("Target"), saved.toString());
    }

    @Test
    void theInstalledIdentityCodecReadsTheSavedTarget() {
        var targetId = UUID.randomUUID();
        var saved = installedCodecSource(targetId);

        try (var restored = new Fixture(Codec.UUID_STRING)) {
            var type = restored.persistent("relwind:test/installed-codec");
            var target = restored.add(targetId);
            var source = restored.add(saved);

            restored.persistence.restore(source);

            assertSame(target, relationships.getFirstTarget(source, type));
        }
    }

    private static BsonDocument installedCodecSource(UUID targetId) {
        try (var original = new Fixture(Codec.UUID_STRING)) {
            var source = original.add(UUID.randomUUID());
            var target = original.add(targetId);
            var type = original.persistent("relwind:test/installed-codec");
            relationships.addTarget(original.store, source, type, target);
            return original.registry.serialize(original.store.copySerializableEntity(source));
        }
    }

    @Test
    void aNonUuidIdentityRoundTripsThroughSaveAndRestore() {
        var targetTag = new Tag("target");
        BsonDocument saved;
        try (var original = new TaggedFixture()) {
            var source = original.add(new Tag("source"));
            var target = original.add(targetTag);
            var type = original.persistent("relwind:test/tagged");
            relationships.addTarget(original.store, source, type, target);
            saved = original.registry.serialize(original.store.copySerializableEntity(source));
        }

        var record = saved.getDocument("Components").getDocument(RelationshipPersistence.COMPONENT_ID)
            .getArray("Links").get(0).asDocument();
        assertEquals(new BsonDocument("Name", new BsonString("target")), record.get("Target"), saved.toString());

        try (var restored = new TaggedFixture()) {
            var type = restored.persistent("relwind:test/tagged");
            var target = restored.add(targetTag);
            var source = restored.add(saved);
            restored.persistence.restore(source);

            assertSame(target, relationships.getFirstTarget(source, type));
        }
    }

    @Test
    void aTargetTheIdentityCodecCannotReadStaysSavedAndInactive() {
        try (var fixture = new TaggedFixture()) {
            var type = fixture.persistent("relwind:test/tagged");
            var unreadable = new BsonDocument("Version", new BsonInt32(1))
                .append("Type", new BsonString("relwind:test/tagged"))
                .append("Target", new BsonString("target"))
                .append("CleanupDisposition", new BsonString("PreserveSource"))
            .append("TargetInstallation", new BsonString("ENTITIES"));
            var source = fixture.add(new Tag("source"));
            fixture.store.addComponent(source, fixture.persistence.getComponentType(), decoded(records(unreadable)));

            assertDoesNotThrow(() -> fixture.persistence.restore(source));

            assertEquals(0, relationships.getTargetCount(source, type));
            assertFalse(relationships.hasUnresolvedTargets(source, type));
            var resaved = fixture.registry.serialize(fixture.store.copySerializableEntity(source));
            var records = resaved.getDocument("Components").getDocument(RelationshipPersistence.COMPONENT_ID)
                .getArray("Links");
            assertEquals(1, records.size(), resaved.toString());
            assertEquals(new BsonString("target"), records.get(0).asDocument().get("Target"), resaved.toString());
        }
    }

    @Test
    void theRecordShapeWrittenBeforeThisSchemaYieldsNoLinks() {
        try (var fixture = new Fixture()) {
            var targetId = UUID.randomUUID();
            var target = fixture.add(targetId);
            var type = fixture.persistent("relwind:test/previous-shape");
            var previous = new BsonDocument("Content", new BsonDocument("LibraryVersion", new BsonInt32(1))
                .append("Links", new BsonArray(List.of(new BsonDocument()
                    .append("Type", new BsonString("relwind:test/previous-shape"))
                    .append("Target", new BsonString(targetId.toString()))
                    .append("CleanupDisposition", new BsonString("PreserveSource"))
            .append("TargetInstallation", new BsonString("ENTITIES"))))));
            @SuppressWarnings("unchecked")
            var metadata = (RelationshipMetadata<Object>) RelationshipMetadata.CODEC.decode(
                previous, new ExtraInfo());
            var source = fixture.add(UUID.randomUUID());
            fixture.store.addComponent(source, fixture.persistence.getComponentType(), metadata);

            assertDoesNotThrow(() -> fixture.persistence.restore(source));

            assertEquals(0, relationships.getTargetCount(source, type));
            assertEquals(0, relationships.getIncomingCount(target, type));
            assertFalse(relationships.hasUnresolvedTargets(source, type));
        }
    }

    @Test
    void closingTypesCapturesLivePayloadsBeforeNativeUnregistration() throws Exception {
        try (var fixture = new Fixture()) {
            var sourceId = UUID.randomUUID();
            var targetId = UUID.randomUUID();
            var source = fixture.add(sourceId);
            var target = fixture.add(targetId);
            var type = payloadType(fixture.types, "relwind:test/close", BsonDocument.class, Codec.BSON_DOCUMENT);
            var temporary = fixture.transientType();
            var data = BsonDocument.parse("{value: 7}");
            relationships.addTarget(fixture.store, source, type, target, data);
            relationships.addTarget(fixture.store, source, temporary, target);
            data.put("value", new BsonInt32(8));

            CompletableFuture.runAsync(fixture.types::close)
                .get(5, TimeUnit.SECONDS);
            fixture.tracker.close();

            assertNull(fixture.tracker.getRef(sourceId));
            assertThrows(IllegalStateException.class, () -> relationships.getFirstTarget(source, type));
            assertThrows(IllegalStateException.class, () -> payloadType(fixture.types, "relwind:test/closed", BsonDocument.class, null));
            assertTrue(source.isValid());
            assertTrue(target.isValid());

            var reinstalled = RelationshipInstallation
                .on(fixture.registry, ref -> ref.getStore().getComponent(ref, fixture.identityType).id, Codec.UUID_BINARY)
                .persistence((store, ref) -> { }, holder -> { }, fixture.deleted::contains)
                .install();
            var tracker = reinstalled.tracker();
            try {
                var types = reinstalled.types();
                tracker.onEntityLoaded(sourceId, source);
                tracker.onEntityLoaded(targetId, target);
                var restored = payloadType(types, "relwind:test/close", BsonDocument.class, Codec.BSON_DOCUMENT);
                var freshTemporary = types.registerRelationship(Fixture.RETAINING);
                assertSame(target, relationships.getFirstTarget(source, restored));
                assertEquals(1, relationships.getIncomingCount(target, restored));
                assertEquals(BsonDocument.parse("{value: 8}"), relationships.getData(source, restored, target));
                assertNotSame(data, relationships.getData(source, restored, target));
                assertEquals(0, relationships.getTargetCount(source, freshTemporary));
                assertEquals(0, relationships.getIncomingCount(target, freshTemporary));
                assertTrue(fixture.deleted.isEmpty());
                types.close();
            } finally {
                tracker.close();
            }
        }
    }

    @Test
    void failedCloseSnapshotLeavesTypesAndMetadataAvailableForRetry() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var target = fixture.add(UUID.randomUUID());
            var failing = new AtomicBoolean(true);
            var codec = new BsonDocumentCodec() {
                @Override
                public BsonValue encode(BsonDocument value, ExtraInfo info) {
                    if (failing.get()) throw new IllegalStateException("deliberate close snapshot failure");
                    return value;
                }
            };
            var type = payloadType(fixture.types, "relwind:test/close-failure", BsonDocument.class, codec);
            var data = BsonDocument.parse("{value: 9}");
            relationships.addTarget(fixture.store, source, type, target, data);

            assertThrows(IllegalStateException.class, fixture.types::close);
            assertDoesNotThrow(fixture.persistence.getComponentType()::validate);
            assertSame(data, relationships.getData(source, type, target));
            assertSame(target, relationships.getFirstTarget(source, type));
            assertEquals(1, relationships.getIncomingCount(target, type));

            failing.set(false);
            fixture.types.close();

            assertThrows(IllegalStateException.class, fixture.persistence.getComponentType()::validate);
            assertTrue(source.isValid());
        }
    }

    @Test
    void logicalObserversSeeCompletedPersistentStateAndReconciliationStaysSilent() {
        try (var fixture = new Fixture()) {
            var sourceId = UUID.randomUUID();
            var targetId = UUID.randomUUID();
            var destinationId = UUID.randomUUID();
            var source = fixture.add(sourceId);
            var target = fixture.add(targetId);
            var destination = fixture.add(destinationId);
            var type = payloadType(fixture.types, "test:observer-persistence", BsonDocument.class, Codec.BSON_DOCUMENT);
            var initial = BsonDocument.parse("{value: 1}");
            var replacement = BsonDocument.parse("{value: 2}");
            var deliveries = new ArrayList<String>();
            var replacedData = new ArrayList<BsonDocument>();
            fixture.registry.registerSystem(new RelationshipChangeSystem<Object, BsonDocument>(type) {
                private void assertStored(LinkedEntity<Object> from, LinkedEntity<Object> to, BsonDocument data, UUID toId) {
                    assertSame(source, from.reference());
                    assertEquals(sourceId, from.identity());
                    assertEquals(toId, to.identity());
                    assertSame(to.reference(), relationships.getFirstTarget(source, type));
                    assertSame(data, relationships.getData(source, type, to.reference()));
                    assertEquals(1, relationships.getIncomingCount(to.reference(), type));
                    assertFalse(relationships.hasUnresolvedTargets(source, type));
                    var links = fixture.store.getComponent(source, fixture.persistence.getComponentType())
                        .getContent().getArray("Links");
                    assertEquals(1, links.size());
                    assertEquals(to.identity(), links.get(0).asDocument().getBinary("Target").asUuid());
                    assertEquals(data, links.get(0).asDocument().getDocument("Payload"));
                }

                @Override
                protected void onRelationshipAdded(
                    LinkedEntity<Object> from,
                    LinkedEntity<Object> to,
                    BsonDocument data,
                    Store<Object> store,
                    CommandBuffer<Object> buffer
                ) {
                    assertStored(from, to, data, targetId);
                    deliveries.add("added");
                }

                @Override
                protected void onRelationshipSet(
                    LinkedEntity<Object> from,
                    LinkedEntity<Object> to,
                    BsonDocument oldData,
                    BsonDocument data,
                    Store<Object> store,
                    CommandBuffer<Object> buffer
                ) {
                    assertStored(from, to, data, targetId);
                    assertSame(replacement, data);
                    replacedData.add(oldData);
                    deliveries.add("set");
                }

                @Override
                protected void onRelationshipRetargeted(
                    LinkedEntity<Object> from,
                    LinkedEntity<Object> oldTarget,
                    LinkedEntity<Object> to,
                    BsonDocument data,
                    Store<Object> store,
                    CommandBuffer<Object> buffer
                ) {
                    assertStored(from, to, data, destinationId);
                    assertSame(target, oldTarget.reference());
                    assertEquals(targetId, oldTarget.identity());
                    assertEquals(0, relationships.getIncomingCount(target, type));
                    deliveries.add("retargeted");
                }

                @Override
                protected void onRelationshipRemoved(
                    LinkedEntity<Object> from,
                    LinkedEntity<Object> to,
                    BsonDocument data,
                    Store<Object> store,
                    CommandBuffer<Object> buffer
                ) {
                    assertEquals(sourceId, from.identity());
                    assertEquals(destinationId, to.identity());
                    assertSame(replacement, data);
                    assertEquals(0, relationships.getTargetCount(source, type));
                    assertEquals(0, relationships.getIncomingCount(destination, type));
                    assertFalse(relationships.hasUnresolvedTargets(source, type));
                    assertNull(store.getComponent(source, fixture.persistence.getComponentType()));
                    deliveries.add("removed");
                }
            });

            relationships.putTarget(fixture.store, source, type, target, initial);
            relationships.putTarget(fixture.store, source, type, target, replacement);
            replacement.put("value", new BsonInt32(3));
            relationships.putTarget(fixture.store, source, type, target, replacement);

            assertSame(initial, replacedData.get(0));
            assertSame(replacement, replacedData.get(1));

            fixture.tracker.onEntityUnloaded(targetId, target,
                UnloadReason.DEACTIVATION);

            assertTrue(relationships.hasUnresolvedTargets(source, type));

            fixture.tracker.onEntityLoaded(targetId, target);

            assertSame(replacement, relationships.getData(source, type, target));
            assertEquals(List.of("added", "set", "set"), deliveries);

            relationships.retarget(fixture.store, source, type, target, destination);
            relationships.removeTarget(fixture.store, source, type, destination);

            assertEquals(List.of("added", "set", "set", "retargeted", "removed"), deliveries);

            relationships.addTarget(fixture.store, source, type, target, initial);
            fixture.types.unregisterRelationship(type);

            assertEquals(List.of("added", "set", "set", "retargeted", "removed", "added"), deliveries);
        }
    }

    @Test
    void runtimeLinksAreNeverSavedWhilePersistentRecordNamesArePreserved() {
        try (var fixture = new Fixture()) {
            var sourceId = UUID.randomUUID();
            var savedId = UUID.randomUUID();
            var source = fixture.add(sourceId);
            var savedTarget = fixture.add(savedId);
            var liveTarget = fixture.add(UUID.randomUUID());
            var saved = fixture.persistent("relwind:test/kept");
            var live = fixture.types.registerRelationship(RelationshipTraits.defaults().exclusive());
            assertNull(live.getDescriptor().id());

            relationships.addTarget(fixture.store, source, live, liveTarget);

            assertEquals(1, relationships.getTargetCount(source, live));
            assertNull(fixture.store.getComponent(source, fixture.persistence.getComponentType()));

            relationships.addTarget(fixture.store, source, saved, savedTarget);

            assertNotNull(fixture.store.getComponent(source, fixture.persistence.getComponentType()));
            assertEquals(1, relationships.getTargetCount(source, live));

            var savedSource = fixture.registry.serialize(fixture.store.copySerializableEntity(source));
            var links = savedSource.getDocument("Components")
                .getDocument(RelationshipPersistence.COMPONENT_ID)
                .getArray("Links");
            assertEquals(1, links.size());
            var record = links.get(0).asDocument();
            assertEquals("relwind:test/kept", record.getString("Type").getValue());
            assertEquals("PreserveSource", record.getString("CleanupDisposition").getValue());
            assertEquals("ENTITIES", record.getString("TargetInstallation").getValue());

            try (var restored = new Fixture()) {
                var restoredSaved = restored.persistent("relwind:test/kept");
                var restoredLive = restored.types.registerRelationship(RelationshipTraits.defaults().exclusive());
                var target = restored.add(savedId);
                var sourceRef = restored.add(savedSource);
                restored.persistence.restore(sourceRef);
                assertSame(target, relationships.getFirstTarget(sourceRef, restoredSaved));
                assertEquals(0, relationships.getTargetCount(sourceRef, restoredLive));
            }
        }
    }

    @Test
    void metadataRoundTripsIntoAFreshStoreAndResolvesAvailableTargetsOnce() {
        var sourceId = UUID.randomUUID();
        var availableId = UUID.randomUUID();
        var unavailableId = UUID.randomUUID();
        BsonDocument savedSource;
        BsonDocument unowned;

        try (var original = new Fixture()) {
            var source = original.add(sourceId);
            var available = original.add(availableId);
            var unavailable = original.add(unavailableId);
            var transientTarget = original.add(UUID.randomUUID());
            var persistent = original.persistent("relwind:test/persistent");
            var transientType = original.transientType();

            relationships.addTarget(original.store, source, persistent, available);
            relationships.addTarget(original.store, source, persistent, unavailable);
            relationships.addTarget(original.store, source, transientType, transientTarget);

            savedSource = original.registry.serialize(original.store.copySerializableEntity(source));
            var components = savedSource.getDocument("Components");
            assertTrue(components.containsKey(RelationshipPersistence.COMPONENT_ID), savedSource.toString());
            var metadata = components.getDocument(RelationshipPersistence.COMPONENT_ID);
            assertEquals(2, metadata.getArray("Links").size());
            assertEquals("PreserveSource",
                metadata.getArray("Links").get(0).asDocument().getString("CleanupDisposition").getValue());
            // the runtime type added a third link
            assertEquals("relwind:test/persistent",
                metadata.getArray("Links").get(0).asDocument().getString("Type").getValue(),
                "only the named type's links are saved");
            assertEquals("relwind:test/persistent",
                metadata.getArray("Links").get(1).asDocument().getString("Type").getValue(),
                "only the named type's links are saved");
            unowned = savedRecord("relwind:test/persistent", unavailableId, "FutureDisposition");
            metadata.getArray("Links").add(unowned);
        }

        try (var restored = new Fixture()) {
            var persistent = restored.persistent("relwind:test/persistent");
            var available = restored.add(availableId);
            var source = restored.add(savedSource);

            restored.persistence.restore(source);
            restored.persistence.restore(source);

            assertSame(available, relationships.getFirstTarget(source, persistent));
            assertEquals(1, relationships.getTargetCount(source, persistent));
            assertEquals(1, relationships.getIncomingCount(available, persistent));
            assertTrue(relationships.hasUnresolvedTargets(source, persistent));
            var preserved = restored.registry.serialize(restored.store.copySerializableEntity(source))
                .getDocument("Components")
                .getDocument(RelationshipPersistence.COMPONENT_ID);
            assertEquals(unowned, preserved.getArray("Links").get(2));

            var unavailable = restored.add(unavailableId);

            assertEquals(2, relationships.getTargetCount(source, persistent));
            assertEquals(1, relationships.getIncomingCount(unavailable, persistent));
            assertFalse(relationships.hasUnresolvedTargets(source, persistent));

            relationships.removeTarget(restored.store, source, persistent, unavailable);

            var afterRemoval = restored.registry.serialize(restored.store.copySerializableEntity(source))
                .getDocument("Components")
                .getDocument(RelationshipPersistence.COMPONENT_ID);
            assertEquals(2, afterRemoval.getArray("Links").size());
            assertEquals(unowned, afterRemoval.getArray("Links").get(1));
        }
    }

    @Test
    void unregisteringATypeLeavesItsMetadataAvailableForLaterRegistration() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var target = fixture.add(UUID.randomUUID());
            var original = fixture.types.registerRelationship("relwind:test/reloadable", Fixture.RETAINING);
            relationships.addTarget(fixture.store, source, original, target);

            fixture.types.unregisterRelationship(original);

            var saved = fixture.registry.serialize(fixture.store.copySerializableEntity(source));
            assertTrue(saved.toString().contains("relwind:test/reloadable"));

            var replacement = fixture.types.registerRelationship("relwind:test/reloadable", Fixture.RETAINING);
            fixture.persistence.restore(source);

            assertSame(target, relationships.getFirstTarget(source, replacement));
            assertEquals(1, relationships.getIncomingCount(target, replacement));
        }
    }

    @Test
    void transferRemovalDeletesPersistentMetadataDespiteSavingBeingEnabled() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var targetId = UUID.randomUUID();
            var target = fixture.add(targetId);
            var type = fixture.types.registerRelationship(
                "relwind:test/remove-on-transfer",
                RelationshipTraits.defaults().retainOnDeactivation());
            relationships.addTarget(fixture.store, source, type, target);

            fixture.tracker.onEntityUnloaded(
                targetId,
                target,
                UnloadReason.TRANSFER
            );

            assertEquals(0, relationships.getTargetCount(source, type));
            assertNull(fixture.store.getComponent(source, fixture.persistence.getComponentType()));
        }
    }

    @Test
    void sourceTransferRemovesPersistentMetadataBeforeNotifying() {
        try (var fixture = new Fixture()) {
            var sourceId = UUID.randomUUID();
            var targetId = UUID.randomUUID();
            var source = fixture.add(sourceId);
            var target = fixture.add(targetId);
            var type = fixture.types.registerRelationship(
                "relwind:test/unloading-source",
                RelationshipTraits.defaults().retainOnDeactivation());
            relationships.addTarget(fixture.store, source, type, target);
            var events = new ArrayList<String>();
            fixture.registry.registerSystem(new RelationshipChangeSystem<Object, Void>(type) {
                @Override
                protected void onRelationshipRemoved(
                    LinkedEntity<Object> from,
                    LinkedEntity<Object> to,
                    Void data,
                    Store<Object> store,
                    CommandBuffer<Object> buffer
                ) {
                    assertSame(fixture.store, store);
                    assertSame(store, buffer.getStore());
                    assertTrue(source.isValid());
                    assertNull(from.reference());
                    assertEquals(sourceId, from.identity());
                    assertSame(target, to.reference());
                    assertEquals(targetId, to.identity());
                    assertNull(fixture.tracker.getRef(sourceId));
                    assertFalse(fixture.tracker.contains(type, sourceId, targetId));
                    assertEquals(0, relationships.getTargetCount(source, type));
                    assertEquals(0, relationships.getIncomingCount(target, type));
                    assertNull(store.getComponent(source, fixture.persistence.getComponentType()));
                    events.add("removed");
                }
            });

            fixture.tracker.onEntityUnloaded(sourceId, source,
                UnloadReason.TRANSFER);

            assertTrue(source.isValid());
            assertNull(fixture.store.getComponent(source, fixture.persistence.getComponentType()));
            assertEquals(List.of("removed"), events);
        }
    }

    @Test
    void targetRemovalUpdatesMetadataHeldByAnUnavailableSource() {
        try (var fixture = new Fixture()) {
            var sourceId = UUID.randomUUID();
            var targetId = UUID.randomUUID();
            var source = fixture.add(sourceId);
            var target = fixture.add(targetId);
            var type = fixture.types.registerRelationship(
                "relwind:test/parked-source",
                RelationshipTraits.defaults().retainOnDeactivation());
            relationships.addTarget(fixture.store, source, type, target);

            var sourceHolder = fixture.store.removeEntity(source, RemoveReason.UNLOAD);
            fixture.tracker.onEntityUnloaded(
                sourceId,
                source,
                UnloadReason.DEACTIVATION,
                sourceHolder
            );
            var targetHolder = fixture.store.removeEntity(target, RemoveReason.UNLOAD);
            fixture.tracker.onEntityUnloaded(
                targetId,
                target,
                UnloadReason.TRANSFER,
                targetHolder
            );

            assertNull(sourceHolder.getComponent(fixture.persistence.getComponentType()));
        }
    }

    @Test
    void retainedUnloadWithValidRefPreservesMetadataWithoutDirtyNotification() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var targetId = UUID.randomUUID();
            var target = fixture.add(targetId);
            var type = fixture.persistent("relwind:test/derived-detach");
            relationships.addTarget(fixture.store, source, type, target);
            var saved = fixture.registry.serialize(fixture.store.copySerializableEntity(source));
            fixture.changes = 0;

            fixture.tracker.onEntityUnloaded(targetId, target,
                UnloadReason.DEACTIVATION);

            assertEquals(0, relationships.getTargetCount(source, type));
            assertEquals(saved, fixture.registry.serialize(fixture.store.copySerializableEntity(source)));
            assertEquals(0, fixture.changes);
        }
    }

    private enum RemovalEntry {
        LOGICAL,
        LIVE_POLICY,
        HOLDER_POLICY
    }

    @ParameterizedTest
    @EnumSource(RemovalEntry.class)
    void removingTheOwnedLinkPreservesAnUnownedRecord(RemovalEntry entry) {
        try (var fixture = new Fixture()) {
            var sourceId = UUID.randomUUID();
            var targetId = UUID.randomUUID();
            var source = fixture.add(sourceId);
            var target = fixture.add(targetId);
            var type = fixture.types.registerRelationship(
                "relwind:test/envelope",
                RelationshipTraits.defaults().exclusive().retainOnDeactivation());
            relationships.addTarget(fixture.store, source, type, target);
            var unowned = savedRecord(type.getDescriptor().id(), UUID.randomUUID(), "FutureDisposition");
            var content = fixture.store.getComponent(source, fixture.persistence.getComponentType()).getContent();
            content.getArray("Links").add(unowned);
            fixture.store.replaceComponent(source, fixture.persistence.getComponentType(), decoded(content));
            var parked = parkTheSource(entry, fixture, source, sourceId);
            var events = new ArrayList<String>();
            registerRemovalObserver(entry, fixture, type, source, target, sourceId, targetId, unowned, parked, events);

            removeTheOwnedLink(entry, fixture, type, source, target, targetId);

            var metadata = heldMetadata(fixture, source, parked);
            assertNotNull(metadata);
            assertEquals(new BsonArray(List.of(unowned)), metadata.getContent().getArray("Links"));
            assertEquals(List.of("removed"), events);
        }
    }

    @Nullable
    private static Holder<Object> parkTheSource(RemovalEntry entry, Fixture fixture, Ref<Object> source, UUID sourceId) {
        if (entry != RemovalEntry.HOLDER_POLICY) return null;
        var parked = fixture.store.removeEntity(source, RemoveReason.UNLOAD);
        fixture.tracker.onEntityUnloaded(sourceId, source, UnloadReason.DEACTIVATION, parked);
        return parked;
    }

    private static void registerRemovalObserver(
        RemovalEntry entry,
        Fixture fixture,
        RelationshipType<Object, Void> type,
        Ref<Object> source,
        Ref<Object> target,
        UUID sourceId,
        UUID targetId,
        BsonDocument unowned,
        @Nullable Holder<Object> parked,
        List<String> events
    ) {
        fixture.registry.registerSystem(new RelationshipChangeSystem<Object, Void>(type) {
            @Override
            protected void onRelationshipRemoved(
                LinkedEntity<Object> from,
                LinkedEntity<Object> to,
                Void data,
                Store<Object> store,
                CommandBuffer<Object> buffer
            ) {
                assertSame(fixture.store, store);
                if (entry == RemovalEntry.LOGICAL) {
                    assertSame(source, from.reference());
                    assertSame(target, to.reference());
                } else if (entry == RemovalEntry.LIVE_POLICY) {
                    assertSame(source, from.reference());
                    assertNull(to.reference());
                } else {
                    assertNull(from.reference());
                    assertNull(to.reference());
                }
                assertEquals(sourceId, from.identity());
                assertEquals(targetId, to.identity());
                assertFalse(fixture.tracker.contains(type, sourceId, targetId));
                assertEquals(new BsonArray(List.of(unowned)),
                    heldMetadata(fixture, source, parked).getContent().getArray("Links"));
                events.add("removed");
            }
        });
    }

    private static void removeTheOwnedLink(
        RemovalEntry entry,
        Fixture fixture,
        GenericRelationshipType<Object, Object, Void> type,
        Ref<Object> source,
        Ref<Object> target,
        UUID targetId
    ) {
        if (entry == RemovalEntry.LOGICAL) {
            relationships.removeTarget(fixture.store, source, type, target);
            return;
        }
        fixture.tracker.onEntityUnloaded(targetId, target, UnloadReason.TRANSFER);
    }

    @Nullable
    private static RelationshipMetadata<Object> heldMetadata(Fixture fixture, Ref<Object> source, @Nullable Holder<Object> parked) {
        if (parked != null) {
            return parked.getComponent(fixture.persistence.getComponentType());
        }
        return fixture.store.getComponent(source, fixture.persistence.getComponentType());
    }

    @Test
    void payloadCapturePreservesAnUnknownRecordWithTheSameLinkedEntities() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var targetId = UUID.randomUUID();
            var target = fixture.add(targetId);
            var type = fixture.types.registerRelationship(
                "relwind:test/payload",
                BsonDocument.class,
                Codec.BSON_DOCUMENT,
                RelationshipTraits.defaults().exclusive().retainOnTransfer().retainOnDeactivation());
            var unknown = savedRecord(type.getDescriptor().id(), targetId, "FutureDisposition")
                .append("Payload", BsonDocument.parse("{original: [9]}"));
            var unreadable = savedRecord(type.getDescriptor().id(), targetId, "PreserveSource")
                .append("Payload", new BsonString("not a document"));
            fixture.store.addComponent(source, fixture.persistence.getComponentType(),
                decoded(records(unknown, unreadable)));

            relationships.addTarget(fixture.store, source, type, target, BsonDocument.parse("{live: [1]}"));

            var captured = fixture.registry.serialize(fixture.store.copySerializableEntity(source))
                .getDocument("Components").getDocument(RelationshipPersistence.COMPONENT_ID)
                .getArray("Links");
            assertEquals(3, captured.size());
            assertEquals(unknown, captured.get(0));
            assertEquals(unreadable, captured.get(1));
            assertEquals(BsonDocument.parse("{live: [1]}"), captured.get(2).asDocument().getDocument("Payload"));
        }
    }

    @Test
    void replacingPayloadLeavesCapturedAndRetiredObjectsDetached() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var target = fixture.add(UUID.randomUUID());
            var type = payloadType(fixture.types, "relwind:test/replacement", BsonDocument.class, Codec.BSON_DOCUMENT);
            var original = BsonDocument.parse("{nested: {values: [1]}}");
            var replacement = BsonDocument.parse("{nested: {values: [2]}}");
            relationships.addTarget(fixture.store, source, type, target, original);
            var captured = fixture.store.copySerializableEntity(source);

            relationships.putTarget(fixture.store, source, type, target, replacement);
            original.getDocument("nested").getArray("values").set(0, new BsonInt32(99));

            assertSame(replacement, relationships.getData(source, type, target));
            assertEquals(BsonDocument.parse("{nested: {values: [1]}}"), payload(fixture.registry.serialize(captured)));
            assertEquals(BsonDocument.parse("{nested: {values: [2]}}"),
                payload(fixture.registry.serialize(fixture.store.copySerializableEntity(source))));
        }
    }

    @Test
    void replacingDataOnAnUnresolvedLinkUpdatesItsNextSnapshot() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var targetId = UUID.randomUUID();
            var target = fixture.add(targetId);
            var type = payloadType(fixture.types, "relwind:test/unresolved-data", BsonDocument.class, Codec.BSON_DOCUMENT);
            var original = BsonDocument.parse("{value: 1}");
            var replacement = BsonDocument.parse("{value: 2}");
            relationships.addTarget(fixture.store, source, type, target, original);
            var deliveries = new ArrayList<String>();
            fixture.registry.registerSystem(new RelationshipChangeSystem<Object, BsonDocument>(type) {
                @Override
                protected void onRelationshipSet(
                    LinkedEntity<Object> from,
                    LinkedEntity<Object> to,
                    BsonDocument oldData,
                    BsonDocument data,
                    Store<Object> store,
                    CommandBuffer<Object> buffer
                ) {
                    assertSame(original, oldData);
                    assertSame(replacement, data);
                    assertSame(source, from.reference());
                    assertSame(target, to.reference());
                    assertEquals(targetId, to.identity());
                    assertTrue(relationships.hasUnresolvedTargets(source, type));
                    assertEquals(0, relationships.getTargetCount(source, type));
                    assertEquals(0, relationships.getIncomingCount(target, type));
                    assertEquals(replacement, payload(fixture.registry.serialize(store.copySerializableEntity(source))));
                    deliveries.add("set");
                }

                @Override
                protected void onRelationshipAdded(
                    LinkedEntity<Object> from,
                    LinkedEntity<Object> to,
                    BsonDocument data,
                    Store<Object> store,
                    CommandBuffer<Object> buffer
                ) {
                    deliveries.add("added");
                }

                @Override
                protected void onRelationshipRemoved(
                    LinkedEntity<Object> from,
                    LinkedEntity<Object> to,
                    BsonDocument data,
                    Store<Object> store,
                    CommandBuffer<Object> buffer
                ) {
                    deliveries.add("removed");
                }
            });
            fixture.tracker.onEntityUnloaded(targetId, target,
                UnloadReason.TRANSFER);

            assertTrue(relationships.hasUnresolvedTargets(source, type));

            int changes = fixture.changes;
            relationships.putTarget(fixture.store, source, type, target, replacement);

            assertEquals(BsonDocument.parse("{value: 2}"),
                payload(fixture.registry.serialize(fixture.store.copySerializableEntity(source))));
            assertEquals(changes + 1, fixture.changes);

            fixture.tracker.onEntityLoaded(targetId, target);

            assertSame(replacement, relationships.getData(source, type, target));
            assertEquals(List.of("set"), deliveries);
        }
    }

    @Test
    void aScalarPayloadIsSavedWithItsLink() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var target = fixture.add(UUID.randomUUID());
            var type = payloadType(fixture.types, "relwind:test/scalar", Integer.class, Codec.INTEGER);

            relationships.addTarget(fixture.store, source, type, target, 11);

            var saved = fixture.registry.serialize(fixture.store.copySerializableEntity(source));
            assertEquals(new BsonInt32(11), payload(saved));
        }
    }

    @Test
    void clearingAScalarPayloadWritesNoPayloadKey() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var target = fixture.add(UUID.randomUUID());
            var type = payloadType(fixture.types, "relwind:test/scalar", Integer.class, Codec.INTEGER);
            relationships.addTarget(fixture.store, source, type, target, 11);
            var captured = fixture.store.copySerializableEntity(source);

            relationships.putTarget(fixture.store, source, type, target);

            assertEquals(new BsonInt32(11), payload(fixture.registry.serialize(captured)));
            var saved = fixture.registry.serialize(fixture.store.copySerializableEntity(source));
            assertNull(payload(saved));
        }
    }

    @Test
    void codecValidationFailureDoesNotProduceASaveSnapshot() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var target = fixture.add(UUID.randomUUID());
            var codec = new BsonDocumentCodec() {
                @Override
                public BsonValue encode(BsonDocument value, ExtraInfo extraInfo) {
                    extraInfo.getValidationResults().add(
                        ValidationResults.ValidationResult.fail("deliberate validation failure"));
                    return value;
                }
            };
            var type = payloadType(fixture.types, "relwind:test/validation", BsonDocument.class, codec);
            var data = BsonDocument.parse("{value: 1}");
            relationships.addTarget(fixture.store, source, type, target, data);

            assertThrows(IllegalStateException.class, () -> fixture.store.copySerializableEntity(source));
            assertSame(data, relationships.getData(source, type, target));
        }
    }

    @Test
    void missingPayloadCodecRejectsTheMutationBeforeChangingTheGraph() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var target = fixture.add(UUID.randomUUID());
            var type = payloadType(fixture.types, "relwind:test/no-codec", BsonDocument.class, null);

            assertThrows(IllegalStateException.class, () -> relationships.addTarget(fixture.store, source, type, target, new BsonDocument()));
            assertNull(relationships.getFirstTarget(source, type));
            assertNull(fixture.store.getComponent(source, fixture.persistence.getComponentType()));
            assertEquals(0, fixture.changes);
        }
    }

    @Test
    void savedPayloadsIsolateBinaryArraysAndJavaScriptScopes() {
        var bytes = new byte[] {1, 2};
        var scope = BsonDocument.parse("{values: [7]}");
        var record = savedRecord("relwind:test/isolated", UUID.randomUUID(), "PreserveSource")
            .append("Payload", new BsonDocument("Bytes", new BsonBinary(bytes))
                .append("Script", new BsonJavaScriptWithScope("return values", scope)));
        var metadata = decoded(records(record));
        var captured = metadata.clone();

        bytes[0] = 9;
        scope.getArray("values").set(0, new BsonInt32(9));
        var exposed = metadata.getContent();
        exposed.getArray("Links").get(0).asDocument().getDocument("Payload").getBinary("Bytes").getData()[1] = 9;
        exposed.getArray("Links").get(0).asDocument().getDocument("Payload").get("Script").asJavaScriptWithScope()
            .getScope().getArray("values").set(0, new BsonInt32(9));

        var livePayload = RelationshipMetadata.CODEC.encode(metadata, new ExtraInfo())
            .getArray("Links").get(0).asDocument().getDocument("Payload");
        assertArrayEquals(new byte[] {1, 2}, livePayload.getBinary("Bytes").getData());
        assertEquals(BsonDocument.parse("{values: [7]}"), livePayload.get("Script").asJavaScriptWithScope().getScope());
        var clonedPayload = RelationshipMetadata.CODEC.encode(captured, new ExtraInfo())
            .getArray("Links").get(0).asDocument().getDocument("Payload");
        assertArrayEquals(new byte[] {1, 2}, clonedPayload.getBinary("Bytes").getData());
        assertEquals(BsonDocument.parse("{values: [7]}"), clonedPayload.get("Script").asJavaScriptWithScope().getScope());
    }

    @Test
    void liveMetadataRefusesToReadItsContentOffTheStoreThread() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var target = fixture.add(UUID.randomUUID());
            var type = payloadType(fixture.types, "relwind:test/access", BsonDocument.class, Codec.BSON_DOCUMENT);
            relationships.addTarget(fixture.store, source, type, target, BsonDocument.parse("{value: 1}"));
            var metadata = fixture.store.getComponent(source, fixture.persistence.getComponentType());

            var failure = assertThrows(ExecutionException.class,
                () -> CompletableFuture.supplyAsync(metadata::getContent).get());

            assertInstanceOf(IllegalStateException.class, failure.getCause());
        }
    }

    @Test
    void aCapturedHolderEncodesOffTheStoreThread() throws Exception {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var target = fixture.add(UUID.randomUUID());
            var type = payloadType(fixture.types, "relwind:test/access", BsonDocument.class, Codec.BSON_DOCUMENT);
            relationships.addTarget(fixture.store, source, type, target, BsonDocument.parse("{value: 1}"));
            var captured = fixture.store.copySerializableEntity(source);

            var encoded = CompletableFuture.supplyAsync(() -> fixture.registry.serialize(captured)).get();

            assertEquals(BsonDocument.parse("{value: 1}"), payload(encoded));
        }
    }

    @Test
    void encodedPayloadRecordsAreNotActivatedAsLinksWithMissingData() {
        var sourceId = UUID.randomUUID();
        var targetId = UUID.randomUUID();
        var expected = BsonDocument.parse("{nested: [1]}");
        BsonDocument saved;
        try (var fixture = new Fixture()) {
            var source = fixture.add(sourceId);
            var target = fixture.add(targetId);
            var type = payloadType(fixture.types, "relwind:test/saved-data", BsonDocument.class, Codec.BSON_DOCUMENT);
            relationships.addTarget(fixture.store, source, type, target, expected);
            saved = fixture.registry.serialize(fixture.store.copySerializableEntity(source));
        }
        try (var fixture = new Fixture()) {
            var type = payloadType(fixture.types, "relwind:test/saved-data", BsonDocument.class, Codec.BSON_DOCUMENT);
            var target = fixture.add(targetId);
            var source = fixture.add(saved);
            fixture.persistence.restore(source);

            assertSame(target, relationships.getFirstTarget(source, type));
            assertEquals(expected, relationships.getData(source, type, target));
            assertEquals(expected, payload(fixture.registry.serialize(fixture.store.copySerializableEntity(source))));
        }
    }

    @Test
    void confirmedDeletionRemovesReadableRecordsWithoutDecodingPayloads() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var target = fixture.add(UUID.randomUUID());
            var targetId = fixture.store.getComponent(target, fixture.identityType).id;
            var type = payloadType(fixture.types, "relwind:test/deleted", BsonDocument.class, Codec.BSON_DOCUMENT);
            relationships.addTarget(fixture.store, source, type, target, BsonDocument.parse("{value: 7}"));
            fixture.types.unregisterRelationship(type);
            var metadata = fixture.store.getComponent(source, fixture.persistence.getComponentType());
            var raw = metadata.getContent();
            var unknown = raw.getArray("Links").get(0).asDocument().clone();
            unknown.put("CleanupDisposition", new BsonString("FutureDisposition"));
            raw.getArray("Links").add(unknown);
            fixture.store.replaceComponent(source, fixture.persistence.getComponentType(), decoded(raw));
            fixture.deleted.add(targetId);

            fixture.persistence.restore(source);

            var remaining = fixture.store.getComponent(source, fixture.persistence.getComponentType()).getContent();
            assertEquals(1, remaining.getArray("Links").size());
            assertEquals(unknown, remaining.getArray("Links").get(0));
            assertEquals(2, fixture.changes, "cleanup must notify native saving once");
        }
    }

    @Test
    void readableCascadeDeletesSourceWithoutARegisteredType() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var deleted = UUID.randomUUID();
            var raw = records(savedRecord("missing:kind", deleted, "CascadeSource")
                .append("Payload", BsonDocument.parse("{unknown: 1}")));
            fixture.store.addComponent(source, fixture.persistence.getComponentType(), decoded(raw));
            fixture.deleted.add(deleted);

            fixture.persistence.restore(source);

            assertFalse(source.isValid(), "readable cleanup is independent of payload activation");
        }
    }

    @Test
    void missingDeletionEvidenceLeavesACascadeRecordRecoverable() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var target = UUID.randomUUID();
            var raw = records(savedRecord("missing:kind", target, "CascadeSource")
                .append("Payload", BsonDocument.parse("{unknown: 1}")));
            fixture.store.addComponent(source, fixture.persistence.getComponentType(), decoded(raw));

            fixture.persistence.restore(source);

            assertTrue(source.isValid());
            assertEquals(raw, fixture.store.getComponent(source, fixture.persistence.getComponentType()).getContent());
            assertEquals(0, fixture.changes);
        }
    }

    private enum Snapshot {
        CLONE,
        FREEZE
    }

    @ParameterizedTest
    @EnumSource(Snapshot.class)
    void cloningOrFreezingKeepsSlotPayloads(Snapshot snapshot) {
        try (var fixture = new Fixture()) {
            var slotted = fixture.types.registerRelationship(
                "relwind:test/slotted",
                Slot.class,
                Slot.CODEC,
                RelationshipTraits.defaults().retainOnTransfer().retainOnDeactivation());
            var source = fixture.add(UUID.randomUUID());
            var target = fixture.add(UUID.randomUUID());
            relationships.addTarget(fixture.store, source, slotted, target, new Slot(5));
            var metadata = fixture.store.getComponent(source, fixture.persistence.getComponentType());

            var copied = copyOf(snapshot, fixture, metadata, slotted);

            assertEquals(5, record(copied, "relwind:test/slotted").getDocument("Payload")
                .getInt32("Value").getValue());
        }
    }

    private static BsonDocument copyOf(
        Snapshot snapshot,
        Fixture fixture,
        RelationshipMetadata<Object> metadata,
        GenericRelationshipType<Object, Object, Slot> slotted
    ) {
        if (snapshot == Snapshot.CLONE) {
            return metadata.clone().getContent();
        }
        fixture.types.unregisterRelationship(slotted);
        return metadata.getContent();
    }

    private static BsonDocument record(BsonDocument content, String typeId) {
        for (var value : content.getArray("Links")) {
            if (value.isDocument() && typeId.equals(value.asDocument().getString("Type").getValue())) {
                return value.asDocument();
            }
        }
        throw new AssertionError("No saved record for " + typeId);
    }

    private static <T> RelationshipType<Object, T> payloadType(
        RelationshipTypeRegistry<Object> types,
        String id,
        Class<T> dataClass,
        @Nullable Codec<T> codec
    ) {
        return types.registerRelationship(id, dataClass, codec,
            RelationshipTraits.defaults().exclusive().retainOnTransfer().retainOnDeactivation());
    }

    @Nullable
    private static BsonValue payload(BsonDocument entity) {
        return entity.getDocument("Components").getDocument(RelationshipPersistence.COMPONENT_ID)
            .getArray("Links").get(0).asDocument().get("Payload");
    }

    /// One saved record in the shape LinkRecord writes.
    private static BsonDocument savedRecord(String typeId, UUID target, String cleanupDisposition) {
        return new BsonDocument("Version", new BsonInt32(1))
            .append("Type", new BsonString(typeId))
            .append("Target", Codec.UUID_BINARY.encode(target, new ExtraInfo()))
            .append("CleanupDisposition", new BsonString(cleanupDisposition))
            .append("TargetInstallation", new BsonString("ENTITIES"));
    }

    private static BsonDocument records(BsonDocument... records) {
        return new BsonDocument("Links", new BsonArray(List.of(records)));
    }

    @SuppressWarnings("unchecked")
    private static RelationshipMetadata<Object> decoded(BsonDocument content) {
        return (RelationshipMetadata<Object>) RelationshipMetadata.CODEC.decode(
            content, new ExtraInfo());
    }

    private static final class Fixture implements AutoCloseable {
        private final Set<UUID> deleted = new HashSet<>();
        private int changes;
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final ComponentType<Object, Identity> identityType = registry.registerComponent(
            Identity.class,
            "RelwindTestIdentity",
            Identity.CODEC
        );
        private final Store<Object> store = registry.addStore(new Object(), EmptyResourceStorage.get());
        private final RelationshipInstallation<UUID> installation;
        private final RelationshipTracker<Object, UUID> tracker;
        private final RelationshipTypeRegistry<Object> types;
        private final RelationshipPersistence<Object> persistence;

        private Fixture() {
            this(Codec.UUID_BINARY);
        }

        private Fixture(Codec<UUID> identityCodec) {
            installation = RelationshipInstallation
                .on(registry, ref -> store.getComponent(ref, identityType).id, identityCodec)
                .persistence((ignoredStore, ignoredRef) -> changes++, ignored -> { }, deleted::contains)
                .install();
            tracker = installation.tracker();
            types = installation.types();
            persistence = installation.persistence();
        }

        private Ref<Object> add(UUID id) {
            var holder = registry.newHolder();
            holder.putComponent(identityType, new Identity(id));
            var ref = store.addEntity(holder, AddReason.LOAD);
            tracker.onEntityLoaded(id, ref);
            return ref;
        }

        private Ref<Object> add(BsonDocument document) {
            var holder = registry.deserialize(document);
            var ref = store.addEntity(holder, AddReason.LOAD);
            tracker.onEntityLoaded(store.getComponent(ref, identityType).id, ref);
            return ref;
        }

        /// The same traits make a persistent type with an id and a runtime type without one.
        private static final RelationshipTraits RETAINING =
            RelationshipTraits.defaults().retainOnTransfer().retainOnDeactivation();

        private GenericRelationshipType<Object, Object, Void> persistent(String id) {
            return types.registerRelationship(id, RETAINING);
        }

        private GenericRelationshipType<Object, Object, Void> transientType() {
            return types.registerRelationship(RETAINING);
        }

        @Override
        public void close() {
            tracker.close();
            registry.shutdown();
        }
    }

    /// The same installation with a Tag identity.
    private static final class TaggedFixture implements AutoCloseable {
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final ComponentType<Object, TagIdentity> identityType = registry.registerComponent(
            TagIdentity.class,
            "RelwindTestTagIdentity",
            TagIdentity.CODEC
        );
        private final Store<Object> store = registry.addStore(new Object(), EmptyResourceStorage.get());
        private final RelationshipInstallation<Tag> installation = RelationshipInstallation
            .on(registry, (Ref<Object> ref) -> store.getComponent(ref, identityType).tag(), Tag.CODEC)
            .persistence((ignoredStore, ignoredRef) -> { }, ignored -> { }, ignored -> false)
            .install();
        private final RelationshipTracker<Object, Tag> tracker = installation.tracker();
        private final RelationshipTypeRegistry<Object> types = installation.types();
        private final RelationshipPersistence<Object> persistence = installation.persistence();

        private Ref<Object> add(Tag tag) {
            var holder = registry.newHolder();
            holder.putComponent(identityType, new TagIdentity(tag));
            var ref = store.addEntity(holder, AddReason.LOAD);
            tracker.onEntityLoaded(tag, ref);
            return ref;
        }

        private Ref<Object> add(BsonDocument document) {
            var holder = registry.deserialize(document);
            var ref = store.addEntity(holder, AddReason.LOAD);
            tracker.onEntityLoaded(store.getComponent(ref, identityType).tag(), ref);
            return ref;
        }

        private GenericRelationshipType<Object, Object, Void> persistent(String id) {
            return types.registerRelationship(id, Fixture.RETAINING);
        }

        @Override
        public void close() {
            tracker.close();
            registry.shutdown();
        }
    }

    /// An identity saved as a document.
    private record Tag(String name) {
        private static final Codec<Tag> CODEC = new Codec<>() {
            @Override
            public Tag decode(@Nonnull BsonValue value, ExtraInfo extraInfo) {
                if (!value.isDocument() || !value.asDocument().containsKey("Name")) {
                    throw new CodecException("Not a tag");
                }
                return new Tag(value.asDocument().getString("Name").getValue());
            }

            @Nonnull @Override
            public BsonValue encode(@Nonnull Tag tag, ExtraInfo extraInfo) {
                return new BsonDocument("Name", new BsonString(tag.name));
            }

            @Nonnull @Override
            public Schema toSchema(@Nonnull SchemaContext context) {
                return new ObjectSchema();
            }
        };
    }

    private static final class TagIdentity implements Component<Object> {
        private static final BuilderCodec<TagIdentity> CODEC = BuilderCodec.builder(
            TagIdentity.class, TagIdentity::new
        ).append(
            new KeyedCodec<>("Name", Codec.STRING),
            (identity, name) -> identity.name = name,
            identity -> identity.name
        ).add().build();

        private String name;

        private TagIdentity() {
        }

        private TagIdentity(Tag tag) {
            name = tag.name();
        }

        private Tag tag() {
            return new Tag(name);
        }

        @Override
        public TagIdentity clone() {
            return new TagIdentity(tag());
        }
    }

    private static final class Slot {
        private static final BuilderCodec<Slot> CODEC = BuilderCodec.builder(Slot.class, Slot::new)
            .versioned()
            .codecVersion(0)
            .append(new KeyedCodec<>("Value", Codec.INTEGER), (slot, value) -> slot.value = value, slot -> slot.value)
            .setVersionRange(0, 0)
            .add()
            .build();

        private int value;

        private Slot() {
        }

        private Slot(int value) {
            this.value = value;
        }
    }

    private static final class Identity implements Component<Object> {
        private static final BuilderCodec<Identity> CODEC = BuilderCodec.builder(Identity.class, Identity::new)
            .append(new KeyedCodec<>("UUID", Codec.UUID_BINARY), (identity, id) -> identity.id = id, identity -> identity.id)
            .add()
            .build();

        private UUID id;

        private Identity() {
        }

        private Identity(UUID id) {
            this.id = id;
        }

        @Override
        public Component<Object> clone() {
            return new Identity(id);
        }
    }

    @Test
    void aSavedBridgeLinkNamesItsTargetInstallation() {
        try (var linked = new LinkedInstallations()) {
            var anchoredTo = linked.entityTypes.registerRelationship(
                "relwind:test/anchoredTo",
                linked.blockTypes,
                RelationshipTraits.defaults());
            var source = linked.entity("source");
            var block = linked.block(7);

            relationships.addTarget(linked.world.entityStore(), source, anchoredTo, block);
            linked.runTransitions();

            var records = linked.world.entityStore()
                .getComponent(source, linked.persistence.getComponentType()).getRecords();
            assertEquals(1, records.size());
            var record = records.get(0);
            assertEquals("CHUNK_POSITIONS", record.getTargetInstallation());
            // the target belongs to the block installation
            assertEquals(7, record.getTargetIdentity(Codec.INTEGER));
            assertNull(record.getTargetIdentity(Codec.STRING));
        }
    }

    @Test
    void aSavedBridgeLinkIsReadWithTheNamedInstallationsCodec() {
        try (var linked = new LinkedInstallations()) {
            var anchoredTo = linked.entityTypes.registerRelationship(
                "relwind:test/anchoredTo",
                linked.blockTypes,
                RelationshipTraits.defaults());
            var source = linked.entity("source");
            var block = linked.block(7);
            relationships.addTarget(linked.world.entityStore(), source, anchoredTo, block);
            linked.runTransitions();
            var metadata = linked.world.entityStore()
                .getComponent(source, linked.persistence.getComponentType());
            var reloaded = RelationshipMetadata.CODEC.decode(metadata.getContent(), new ExtraInfo());
            @SuppressWarnings("unchecked")
            var loaded = (RelationshipMetadata<Entities>) reloaded;
            assertEquals("CHUNK_POSITIONS", loaded.getRecords().get(0).getTargetInstallation());
            var restored = linked.entity("restored");
            linked.world.entityStore().addComponent(restored, linked.persistence.getComponentType(), loaded);

            linked.persistence.restore(restored);
            linked.runTransitions();

            assertEquals(1, relationships.getTargetCount(restored, anchoredTo));
            assertSame(block, relationships.getFirstTarget(restored, anchoredTo));
        }
    }

    @Test
    void aBridgeSourceLoadedAfterItsTargetWasDeletedClearsThatLinkAndKeepsTheOthers() {
        try (var linked = new LinkedInstallations()) {
            var anchoredTo = linked.entityTypes.registerRelationship(
                "relwind:test/anchoredTo",
                linked.blockTypes,
                RelationshipTraits.defaults());
            var source = linked.entity("source");
            var removed = linked.block(7);
            var kept = linked.block(8);
            relationships.addTarget(linked.world.entityStore(), source, anchoredTo, removed);
            relationships.addTarget(linked.world.entityStore(), source, anchoredTo, kept);
            linked.runTransitions();
            var saved = linked.savedRecords(source);

            // the block is deleted while the source is away
            linked.unloadEntity("source", source);
            linked.runTransitions();
            linked.deleteBlock(7, removed);

            var loaded = linked.load("loaded", saved);

            assertTrue(loaded.isValid());
            assertEquals(1, relationships.getTargetCount(loaded, anchoredTo));
            assertSame(kept, relationships.getFirstTarget(loaded, anchoredTo));
            var records = linked.world.entityStore()
                .getComponent(loaded, linked.persistence.getComponentType()).getRecords();
            assertEquals(1, records.size());
            assertEquals(8, records.get(0).getTargetIdentity(Codec.INTEGER));
            // the evidence came from the block Store of this world
            assertTrue(linked.deletionEvidence.contains(linked.world.blockStore()));
        }
    }

    private enum RestorePath {
        COMMAND_BUFFER,
        WITHOUT_A_COMMAND_BUFFER
    }

    @ParameterizedTest
    @EnumSource(RestorePath.class)
    void aCascadingBridgeSourceIsDeletedHoweverItIsRestored(RestorePath path) {
        try (var linked = new LinkedInstallations()) {
            var anchoredTo = linked.entityTypes.registerRelationship(
                "relwind:test/anchoredTo",
                linked.blockTypes,
                RelationshipTraits.defaults().exclusive().onDeleteTarget(RelationshipTraits.OnDeleteTarget.DELETE));
            var source = linked.entity("source");
            var removed = linked.block(7);
            relationships.addTarget(linked.world.entityStore(), source, anchoredTo, removed);
            linked.runTransitions();
            var saved = linked.savedRecords(source);
            linked.unloadEntity("source", source);
            linked.runTransitions();
            linked.deleteBlock(7, removed);

            var restored = restoreTheSource(path, linked, saved);

            assertFalse(restored.isValid());
        }
    }

    private static Ref<Entities> restoreTheSource(RestorePath path, LinkedInstallations linked, BsonDocument saved) {
        if (path == RestorePath.WITHOUT_A_COMMAND_BUFFER) {
            return linked.load("loaded", saved);
        }
        // restoring through a command buffer marks the cascade before the Store compacts its Refs
        var admitted = linked.entity("admitted");
        linked.world.entityStore()
            .addComponent(admitted, linked.persistence.getComponentType(), LinkedInstallations.decoded(saved));
        var buffer = linked.fixture.entityCommandBuffer(linked.world);

        linked.persistence.restore(buffer, admitted);
        BridgeStoreFixture.consume(buffer);
        linked.runTransitions();
        return admitted;
    }

    @Test
    void aBridgeRecordTheNamedInstallationCannotReadStaysRetained() {
        try (var linked = new LinkedInstallations()) {
            var anchoredTo = linked.entityTypes.registerRelationship(
                "relwind:test/anchoredTo",
                linked.blockTypes,
                RelationshipTraits.defaults().exclusive().onDeleteTarget(RelationshipTraits.OnDeleteTarget.DELETE));
            // the block installation reads integer identities, and this record names a String target
            var unreadable = new LinkRecord(anchoredTo.getDescriptor().id(), Codec.STRING, "7",
                LinkRecord.CASCADE_SOURCE, "CHUNK_POSITIONS");
            var metadata = new RelationshipMetadata<Entities>();
            metadata.addRecord(unreadable);
            linked.deletedBlocks.add(7);

            var loaded = linked.entity("loaded");
            linked.world.entityStore().addComponent(loaded, linked.persistence.getComponentType(), metadata);
            linked.persistence.restore(loaded);
            linked.runTransitions();

            assertTrue(loaded.isValid());
            var records = linked.world.entityStore()
                .getComponent(loaded, linked.persistence.getComponentType()).getRecords();
            assertEquals(1, records.size());
            assertEquals("CHUNK_POSITIONS", records.get(0).getTargetInstallation());
            assertTrue(linked.deletionEvidence.isEmpty(), "an unreadable target names no identity to test");
        }
    }

    private enum UnknownPeer {
        ANSWERS_WITH_NO_STORE,
        THROWS
    }

    private static Function<Store<?>, Store<Blocks>> peerLookup(UnknownPeer unknownPeer) {
        if (unknownPeer == UnknownPeer.THROWS) {
            return peer -> {
                throw new IllegalStateException("Unknown world");
            };
        }
        return peer -> null;
    }

    @ParameterizedTest
    @EnumSource(UnknownPeer.class)
    void aBridgeRecordWhoseWorldIsUnknownStaysForTheNextLoad(UnknownPeer unknownPeer) {
        try (var fixture = new BridgeStoreFixture()) {
            var world = fixture.addWorld("overworld");
            // the block identity cannot find the world of a peer Store
            var entityTypes = new RelationshipTypeRegistry<Entities>(fixture.entityRegistry());
            var blockTypes = new RelationshipTypeRegistry<Blocks>(fixture.blockRegistry());
            var entityIds = new IdentityHashMap<Ref<Entities>, String>();
            var blockIds = new IdentityHashMap<Ref<Blocks>, Integer>();
            var entityTracker = entityTypes.installTracker(
                TestPersistenceIdentity.of(entityIds::get, Codec.STRING), TestStoreRuntime.inline());
            var blockTracker = blockTypes.installTracker(new TestPersistenceIdentity<>((store, ref) -> blockIds.get(ref),
                Codec.INTEGER, "CHUNK_POSITIONS", (store, id) -> true, peerLookup(unknownPeer)), TestStoreRuntime.inline());
            var persistence = entityTypes.installPersistence(
                entityTracker);
            blockTypes.installPersistence(blockTracker);
            var anchoredTo = entityTypes.registerRelationship(
                "relwind:test/anchoredTo",
                blockTypes,
                RelationshipTraits.defaults().exclusive().onDeleteTarget(RelationshipTraits.OnDeleteTarget.DELETE));
            var metadata = new RelationshipMetadata<Entities>();
            metadata.addRecord(new LinkRecord(anchoredTo.getDescriptor().id(), Codec.INTEGER, 7,
                LinkRecord.CASCADE_SOURCE, "CHUNK_POSITIONS"));
            var source = fixture.addEntity(world);
            entityIds.put(source, "source");
            entityTracker.onEntityLoaded("source", source);
            world.entityStore().addComponent(source, persistence.getComponentType(), metadata);

            persistence.restore(source);

            assertTrue(source.isValid());
            var records = world.entityStore().getComponent(source, persistence.getComponentType()).getRecords();
            assertEquals(1, records.size());
            assertEquals(7, records.get(0).getTargetIdentity(Codec.INTEGER));
        }
    }

    @Test
    void recordsSavedByAnEarlierReleaseRestoreTheirSameStoreAndBridgeLinks() throws IOException {
        var envelope = BsonDocument.parse(recordsOfAnEarlierRelease());
        var sameSaved = envelope.getDocument("same");
        var bridgeSaved = envelope.getDocument("bridge");
        assertEquals("ENTITIES",
            sameSaved.getArray("Links").get(0).asDocument().getString("TargetInstallation").getValue());
        assertEquals("CHUNK_POSITIONS",
            bridgeSaved.getArray("Links").get(0).asDocument().getString("TargetInstallation").getValue());
        try (var linked = new LinkedInstallations()) {
            var same = linked.entityTypes.registerRelationship(
                "relwind:test/baselineSame", String.class, Codec.STRING, RelationshipTraits.defaults().exclusive());
            var bridge = linked.entityTypes.registerRelationship(
                "relwind:test/baselineBridge", linked.blockTypes, String.class, Codec.STRING,
                RelationshipTraits.defaults().exclusive());
            var sameTarget = linked.entity("same-target");
            var bridgeTarget = linked.block(7);

            var sameSource = linked.load("same-source", sameSaved);
            var bridgeSource = linked.load("bridge-source", bridgeSaved);

            assertSame(sameTarget, relationships.getFirstTarget(sameSource, same));
            assertEquals("same-data", relationships.getData(sameSource, same, sameTarget));
            assertSame(bridgeTarget, relationships.getFirstTarget(bridgeSource, bridge));
            assertEquals("bridge-data", relationships.getData(bridgeSource, bridge, bridgeTarget));
        }
    }

    /// The records this repository wrote at the revision the resource file names.
    private static String recordsOfAnEarlierRelease() throws IOException {
        try (var saved = RelationshipPersistenceTest.class.getResourceAsStream("/earlier-release-records.json")) {
            assertNotNull(saved, "the saved records of the earlier release are missing");
            return new String(saved.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
