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
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import org.bson.BsonBinary;
import org.bson.BsonJavaScriptWithScope;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;

/// One saved link. The codec below is the only description of the saved shape, and a record
/// written by an older schema is not read.
final class LinkRecord {
    /// The codec rejects a record written with a higher version than this.
    static final int VERSION = 1;

    static final String PRESERVE_SOURCE = "PreserveSource";
    static final String CASCADE_SOURCE = "CascadeSource";

    /// Keeps a value this build cannot interpret.
    private static final Codec<BsonValue> OPAQUE = new Codec<>() {
        @Override
        public BsonValue decode(BsonValue value, ExtraInfo extraInfo) {
            return copy(value);
        }

        @Nonnull @Override
        public BsonValue encode(BsonValue value, ExtraInfo extraInfo) {
            return copy(value);
        }

        @SuppressWarnings("deprecation")
        @Override
        public BsonValue decodeJson(RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
            // TODO: Replace when Hytale provides a non-deprecated reader for arbitrary BSON values.
            //  hytale-shared-source Codec.decodeJson still uses this fallback; unknown payloads need it too.
            return RawJsonReader.readBsonValue(reader);
        }

        @Nonnull @Override
        public Schema toSchema(SchemaContext context) {
            return new ObjectSchema();
        }
    };

    static final BuilderCodec<LinkRecord> CODEC = BuilderCodec.builder(LinkRecord.class, LinkRecord::new)
        .versioned()
        .codecVersion(VERSION)
        .append(
            new KeyedCodec<>("Type", Codec.STRING),
            (record, typeId) -> record.typeId = typeId,
            record -> record.typeId
        )
        .add()
        .append(
            new KeyedCodec<>("Target", OPAQUE),
            (record, target) -> record.target = target,
            record -> record.target
        )
        .add()
        .append(
            new KeyedCodec<>("CleanupDisposition", Codec.STRING),
            (record, disposition) -> record.cleanupDisposition = disposition,
            record -> record.cleanupDisposition
        )
        .add()
        .append(
            new KeyedCodec<>("Payload", OPAQUE),
            (record, payload) -> record.payload = payload,
            record -> record.payload
        )
        .add()
        .append(
            new KeyedCodec<>("TargetInstallation", Codec.STRING),
            (record, installation) -> record.targetInstallation = installation,
            record -> record.targetInstallation
        )
        .add()
        .build();

    static final ArrayCodec<LinkRecord> ARRAY_CODEC = new ArrayCodec<>(CODEC, LinkRecord[]::new);

    @Nullable
    private String typeId;
    @Nullable
    private BsonValue target;
    @Nullable
    private String cleanupDisposition;
    @Nullable
    private BsonValue payload;
    @Nullable
    private String targetInstallation;

    LinkRecord() {
    }

    <ID> LinkRecord(
        @Nullable String typeId,
        Codec<ID> identityCodec,
        ID target,
        String cleanupDisposition,
        String targetInstallation
    ) {
        this.typeId = typeId;
        this.target = copy(identityCodec.encode(target, new ExtraInfo()));
        this.cleanupDisposition = cleanupDisposition;
        this.targetInstallation = java.util.Objects.requireNonNull(targetInstallation, "targetInstallation");
    }

    private LinkRecord(LinkRecord original) {
        typeId = original.typeId;
        target = original.target == null ? null : copy(original.target);
        cleanupDisposition = original.cleanupDisposition;
        payload = original.payload == null ? null : copy(original.payload);
        targetInstallation = original.targetInstallation;
    }

    @Nullable
    String getTypeId() {
        return typeId;
    }

    /// Null when this codec cannot read the identity. The record stays saved and inactive.
    @Nullable
    <ID> ID getTargetIdentity(Codec<ID> identityCodec) {
        if (target == null || targetInstallation == null) {
            return null;
        }
        try {
            return identityCodec.decode(copy(target), new ExtraInfo());
        } catch (RuntimeException failure) {
            return null;
        }
    }

    @Nullable
    String getCleanupDisposition() {
        return cleanupDisposition;
    }

    /// Null when the link carries no data.
    @Nullable
    BsonValue getPayload() {
        return payload;
    }

    void setPayload(@Nullable BsonValue payload) {
        this.payload = payload;
    }

    /// Which installation's identity codec reads the target. A link inside one Store names it too.
    @Nullable
    String getTargetInstallation() {
        return targetInstallation;
    }

    @Nonnull
    static String getCleanupDisposition(GenericRelationshipType<?, ?, ?> type) {
        return getCleanupDisposition(type.getDescriptor());
    }

    @Nonnull
    static String getCleanupDisposition(RelationshipDescriptor<?, ?> descriptor) {
        return switch (descriptor.getOnDeleteTarget()) {
            case REMOVE -> LinkRecord.PRESERVE_SOURCE;
            case DELETE -> LinkRecord.CASCADE_SOURCE;
        };
    }

    /// Does not compare the target. Only the installed codec can read a target identity.
    boolean isNamed(@Nullable String typeId, String cleanupDisposition) {
        return typeId != null && typeId.equals(this.typeId) && cleanupDisposition.equals(this.cleanupDisposition);
    }

    @Nonnull
    LinkRecord copy() {
        return new LinkRecord(this);
    }

    /// A caller can still hold and edit the value it handed over.
    @Nonnull
    static BsonValue copy(BsonValue value) {
        return switch (value.getBsonType()) {
            case DOCUMENT -> value.asDocument().clone();
            case ARRAY -> value.asArray().clone();
            case BINARY -> new BsonBinary(value.asBinary().getType(), value.asBinary().getData().clone());
            // here for completeness...
            case JAVASCRIPT_WITH_SCOPE -> new BsonJavaScriptWithScope(
                value.asJavaScriptWithScope().getCode(), value.asJavaScriptWithScope().getScope().clone());
            default -> value;
        };
    }
}
