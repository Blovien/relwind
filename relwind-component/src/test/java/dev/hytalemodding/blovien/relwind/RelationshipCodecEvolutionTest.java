/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.EmptyResourceStorage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A saved link record survives a change of schema: an older record decodes, an unreadable payload
/// stays intact and inactive, and a record from a newer version is refused.
class RelationshipCodecEvolutionTest {
    private static final Relationships relationships = new Relationships();

    @Test
    void aSameStoreLinkSavesItsTargetInstallationName() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var target = fixture.add(UUID.randomUUID());
            var type = register(fixture.types, SlotData.CODEC_V1);

            relationships.addTarget(fixture.store, source, type, target);

            var record = fixture.store.getComponent(source, fixture.persistence.getComponentType()).getRecords().getFirst();
            assertEquals("ENTITIES", record.getTargetInstallation());
            assertEquals("ENTITIES", LinkRecord.CODEC.encode(record, new ExtraInfo()).getString("TargetInstallation").getValue());
        }
    }

    @Test
    void aRecordWithoutAnInstallationNameStaysSavedAndInactive() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var targetId = UUID.randomUUID();
            fixture.add(targetId);
            var rawRecord = record(targetId, BsonDocument.parse("{Version: 0, Value: 7}"));
            rawRecord.remove("TargetInstallation");
            var raw = records(rawRecord);
            fixture.store.addComponent(source, fixture.persistence.getComponentType(), decoded(raw));
            var type = register(fixture.types, SlotData.CODEC_V1);

            fixture.persistence.restore(source);

            assertEquals(0, relationships.getTargetCount(source, type));
            assertEquals(raw, RelationshipMetadata.CODEC.encode(
                fixture.store.getComponent(source, fixture.persistence.getComponentType()), new ExtraInfo()));
        }
    }

    @Test
    void theLinkRecordCodecRoundTripsEveryField() {
        var target = UUID.randomUUID();
        var saved = new BsonDocument("Version", new BsonInt32(1))
            .append("Type", new BsonString("relwind:test/record"))
            .append("Target", Codec.UUID_BINARY.encode(target, new ExtraInfo()))
            .append("CleanupDisposition", new BsonString("CascadeSource"))
            .append("Payload", BsonDocument.parse("{Value: 7}"))
            .append("TargetInstallation", new BsonString("relwind:test/chunks"));

        var record = LinkRecord.CODEC.decode(saved, new ExtraInfo());

        assertEquals("relwind:test/record", record.getTypeId());
        assertEquals(target, record.getTargetIdentity(Codec.UUID_BINARY));
        assertEquals("CascadeSource", record.getCleanupDisposition());
        assertEquals(BsonDocument.parse("{Value: 7}"), record.getPayload());
        assertEquals("relwind:test/chunks", record.getTargetInstallation());
        assertEquals(saved, LinkRecord.CODEC.encode(record, new ExtraInfo()));
    }

    @Test
    void aNamedRecordWithoutAPayloadWritesNoPayloadKey() {
        var target = UUID.randomUUID();
        var record = new LinkRecord("relwind:test/record", Codec.UUID_BINARY, target, "PreserveSource", "ENTITIES");

        var encoded = LinkRecord.CODEC.encode(record, new ExtraInfo());

        assertEquals(new BsonDocument("Version", new BsonInt32(1))
            .append("Type", new BsonString("relwind:test/record"))
            .append("Target", Codec.UUID_BINARY.encode(target, new ExtraInfo()))
            .append("CleanupDisposition", new BsonString("PreserveSource"))
            .append("TargetInstallation", new BsonString("ENTITIES")), encoded);
        assertNull(LinkRecord.CODEC.decode(encoded, new ExtraInfo()).getPayload());
    }

    @Test
    void theLinkRecordCodecRejectsARecordFromANewerVersion() {
        var saved = new BsonDocument("Version", new BsonInt32(2))
            .append("Type", new BsonString("relwind:test/record"))
            .append("Target", Codec.UUID_BINARY.encode(UUID.randomUUID(), new ExtraInfo()))
            .append("CleanupDisposition", new BsonString("PreserveSource"))
            .append("TargetInstallation", new BsonString("ENTITIES"));

        assertThrows(IllegalArgumentException.class, () -> LinkRecord.CODEC.decode(saved, new ExtraInfo()));
    }

    @Test
    void aRecordFieldAddedByALaterVersionIsIgnoredRatherThanRejected() {
        var target = UUID.randomUUID();
        var saved = new BsonDocument("Version", new BsonInt32(1))
            .append("Type", new BsonString("relwind:test/record"))
            .append("Target", Codec.UUID_BINARY.encode(target, new ExtraInfo()))
            .append("CleanupDisposition", new BsonString("PreserveSource"))
            .append("TargetInstallation", new BsonString("ENTITIES"))
            .append("LaterField", new BsonString("unknown"));

        var record = assertDoesNotThrow(() -> LinkRecord.CODEC.decode(saved, new ExtraInfo()));

        assertEquals(target, record.getTargetIdentity(Codec.UUID_BINARY));
        assertFalse(LinkRecord.CODEC.encode(record, new ExtraInfo()).containsKey("LaterField"));
    }

    @Test
    void oldPayloadVersionDecodesIntoALiveLink() {
        try (var fixture = new Fixture()) {
            var sourceId = UUID.randomUUID();
            var targetId = UUID.randomUUID();
            var target = fixture.add(targetId);
            var source = fixture.add(sourceId);
            var raw = records(record(targetId, BsonDocument.parse("{Version: 0, Value: 7}")));
            fixture.store.addComponent(source, fixture.persistence.getComponentType(), decoded(raw));
            var type = register(fixture.types, SlotData.CODEC_V1);

            assertDoesNotThrow(() -> fixture.persistence.restore(source));

            assertEquals(1, relationships.getTargetCount(source, type));
            var data = relationships.getData(source, type, target);
            assertEquals(7, data.value);
            assertEquals(0, data.extra);
        }
    }

    @Test
    void undecodablePayloadsStayInactiveAndIntactWithoutThrowing() {
        try (var fixture = new Fixture()) {
            var sourceId = UUID.randomUUID();
            var goodId = UUID.randomUUID();
            var futureId = UUID.randomUUID();
            var brokenId = UUID.randomUUID();
            var goodTarget = fixture.add(goodId);
            fixture.add(futureId);
            fixture.add(brokenId);
            var source = fixture.add(sourceId);
            var good = record(goodId, BsonDocument.parse("{Version: 0, Value: 7}"));
            var future = record(futureId, BsonDocument.parse("{Version: 99, Value: 99}"));
            var broken = record(brokenId, new BsonString("not a document"));
            var raw = records(good, future, broken);
            fixture.store.addComponent(source, fixture.persistence.getComponentType(), decoded(raw));
            var type = register(fixture.types, SlotData.CODEC_V1);

            assertDoesNotThrow(() -> fixture.persistence.restore(source));
            assertDoesNotThrow(() -> fixture.persistence.restore(source));

            assertEquals(1, relationships.getTargetCount(source, type));
            assertEquals(7, relationships.getData(source, type, goodTarget).value);
            assertFalse(relationships.hasUnresolvedTargets(source, type));
            var captured = content(fixture.registry.serialize(fixture.store.copySerializableEntity(source)));
            var savedGood = captured.getArray("Links").get(0).asDocument();
            assertEquals(good.getString("Type"), savedGood.getString("Type"));
            assertEquals(good.getBinary("Target"), savedGood.getBinary("Target"));
            assertEquals(good.getString("CleanupDisposition"), savedGood.getString("CleanupDisposition"));
            assertEquals(1, savedGood.getDocument("Payload").getInt32("Version").getValue(),
                "a decoded link is saved through the current codec version");
            assertEquals(7, savedGood.getDocument("Payload").getInt32("Value").getValue());
            assertEquals(0, savedGood.getDocument("Payload").getInt32("Extra").getValue());
            assertEquals(future, captured.getArray("Links").get(1));
            assertEquals(broken, captured.getArray("Links").get(2));
            assertEquals(7, good.getDocument("Payload").getInt32("Value").getValue());
        }
    }

    @Test
    void preservedRecordDecodesAfterACompatibleReregistration() {
        try (var fixture = new Fixture()) {
            var sourceId = UUID.randomUUID();
            var targetId = UUID.randomUUID();
            var target = fixture.add(targetId);
            var source = fixture.add(sourceId);
            var raw = records(record(targetId, BsonDocument.parse("{Version: 1, Value: 7, Extra: 42}")));
            fixture.store.addComponent(source, fixture.persistence.getComponentType(), decoded(raw));
            var old = register(fixture.types, SlotData.CODEC_V0);

            assertDoesNotThrow(() -> fixture.persistence.restore(source));

            assertEquals(0, relationships.getTargetCount(source, old));
            var preserved = content(fixture.registry.serialize(fixture.store.copySerializableEntity(source)));
            assertEquals(1, preserved.getArray("Links").size());

            fixture.types.unregisterRelationship(old);
            var current = register(fixture.types, SlotData.CODEC_V1);
            assertDoesNotThrow(() -> fixture.persistence.restore(source));

            assertEquals(1, relationships.getTargetCount(source, current));
            var data = relationships.getData(source, current, target);
            assertEquals(7, data.value);
            assertEquals(42, data.extra);
        }
    }

    @Test
    void writtenRecordsContainNoPayloadVersionOnAnyWritePath() {
        try (var fixture = new Fixture()) {
            var source = fixture.add(UUID.randomUUID());
            var target = fixture.add(UUID.randomUUID());
            var type = register(fixture.types, SlotData.CODEC_V1);
            var data = new SlotData();
            data.value = 7;
            data.extra = 42;

            relationships.addTarget(fixture.store, source, type, target, data);

            var captured = content(fixture.registry.serialize(fixture.store.copySerializableEntity(source)));
            assertEquals(1, captured.getArray("Links").size());
            var record = captured.getArray("Links").get(0).asDocument();
            assertFalse(record.containsKey("PayloadVersion"), "capture must not write PayloadVersion");
            assertTrue(record.containsKey("Payload"));
            assertTrue(record.getDocument("Payload").containsKey("Version"),
                "the versioned codec still carries its own version inside the payload");

            fixture.types.unregisterRelationship(type);

            var frozen = content(fixture.registry.serialize(fixture.store.copySerializableEntity(source)));
            assertEquals(1, frozen.getArray("Links").size());
            assertFalse(frozen.getArray("Links").get(0).asDocument().containsKey("PayloadVersion"),
                "freeze must not write PayloadVersion");

            var replacement = register(fixture.types, SlotData.CODEC_V1);
            var second = fixture.add(UUID.randomUUID());
            var secondData = new SlotData();
            secondData.value = 1;
            relationships.addTarget(fixture.store, source, replacement, second, secondData);

            var added = content(fixture.registry.serialize(fixture.store.copySerializableEntity(source)));
            assertEquals(2, added.getArray("Links").size());
            assertFalse(added.getArray("Links").get(0).asDocument().containsKey("PayloadVersion"),
                "the re-registered record must not write PayloadVersion");
            assertFalse(added.getArray("Links").get(1).asDocument().containsKey("PayloadVersion"),
                "the added record must not write PayloadVersion");
        }
    }

    private static BsonDocument record(UUID target, BsonValue payload) {
        return new BsonDocument("Version", new BsonInt32(1))
            .append("Type", new BsonString(EVOLUTION_ID))
            .append("Target", Codec.UUID_BINARY.encode(target, new ExtraInfo()))
            .append("CleanupDisposition", new BsonString("PreserveSource"))
            .append("TargetInstallation", new BsonString("ENTITIES"))
            .append("Payload", payload);
    }

    private static BsonDocument records(BsonDocument... records) {
        return new BsonDocument("Links", new BsonArray(java.util.List.of(records)));
    }

    @SuppressWarnings("unchecked")
    private static RelationshipMetadata<Object> decoded(BsonDocument content) {
        return (RelationshipMetadata<Object>) RelationshipMetadata.CODEC.decode(content, new ExtraInfo());
    }

    private static final String EVOLUTION_ID = "relwind:test/evolution";

    private static GenericRelationshipType<Object, Object, SlotData> register(
        RelationshipTypeRegistry<Object> types,
        Codec<SlotData> codec
    ) {
        return types.registerRelationship(EVOLUTION_ID, SlotData.class, codec,
            RelationshipRules.multiple().retainOnTransfer().retainOnDeactivation());
    }

    private static BsonDocument content(BsonDocument entity) {
        return entity.getDocument("Components").getDocument(RelationshipPersistence.COMPONENT_ID);
    }

    static final class SlotData {
        static final BuilderCodec<SlotData> CODEC_V0 = BuilderCodec.builder(SlotData.class, SlotData::new)
            .versioned()
            .codecVersion(0)
            .append(new KeyedCodec<>("Value", Codec.INTEGER), (slotData, value) -> slotData.value = value, slotData -> slotData.value)
            .setVersionRange(0, 0)
            .add()
            .build();
        static final BuilderCodec<SlotData> CODEC_V1 = BuilderCodec.builder(SlotData.class, SlotData::new)
            .versioned()
            .codecVersion(1)
            .append(new KeyedCodec<>("Value", Codec.INTEGER), (slotData, value) -> slotData.value = value, slotData -> slotData.value)
            .setVersionRange(0, 1)
            .add()
            .append(new KeyedCodec<>("Extra", Codec.INTEGER), (slotData, value) -> slotData.extra = value, slotData -> slotData.extra)
            .setVersionRange(1, 1)
            .add()
            .build();

        int value;
        int extra;
    }

    private static final class Fixture implements AutoCloseable {
        private int changes;
        private final ComponentRegistry<Object> registry = new ComponentRegistry<>();
        private final ComponentType<Object, Identity> identityType = registry.registerComponent(
            Identity.class,
            "RelwindTestIdentity",
            Identity.CODEC
        );
        private final Store<Object> store = registry.addStore(new Object(), EmptyResourceStorage.get());
        private final RelationshipInstallation<UUID> installation = RelationshipInstallation
            .on(registry, ref -> store.getComponent(ref, identityType).id, Codec.UUID_BINARY)
            .persistence((ignoredStore, ignoredRef) -> changes++, holder -> { }, id -> false)
            .install();
        private final RelationshipTracker<Object, UUID> tracker = installation.tracker();
        private final RelationshipTypeRegistry<Object> types = installation.types();
        private final RelationshipPersistence<Object> persistence = installation.persistence();

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

        @Override
        public void close() {
            tracker.close();
            registry.shutdown();
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
}
