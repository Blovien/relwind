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
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.logger.HytaleLogger;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// The saved [LinkRecord] values of one linked entity, plus the payloads already decoded for a
/// registered type. A record this build cannot decode keeps the value it was loaded with and
/// stays out of the decoded links.
public final class RelationshipMetadata<ECS_TYPE> implements Component<ECS_TYPE> {
    @SuppressWarnings("rawtypes")
    public static final BuilderCodec<RelationshipMetadata> CODEC = BuilderCodec.builder(
        RelationshipMetadata.class,
        RelationshipMetadata::new
    ).append(
        new KeyedCodec<>("Links", LinkRecord.ARRAY_CODEC),
        RelationshipMetadata::setRecords,
        RelationshipMetadata::encodeRecords
    ).add().build();

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final List<LinkRecord> records = new ArrayList<>();
    private final Map<LinkRecord, Payload<?>> payloads = new IdentityHashMap<>();
    private final Map<String, Decoded> decoded = new HashMap<>();
    private Runnable checkAccess = () -> { };

    public RelationshipMetadata() {
    }

    private RelationshipMetadata(RelationshipMetadata<ECS_TYPE> original) {
        original.checkAccess.run();
        Collections.addAll(records, original.encodeRecords());
    }

    /// Checks the source Store's access rule, then returns a fresh document the caller may edit.
    @Nonnull
    public BsonDocument getContent() {
        checkAccess.run();
        return CODEC.encode(this, new ExtraInfo());
    }

    // Hytale runs a codec on an ECS worker that already holds exclusive access
    @Nonnull
    private LinkRecord[] encodeRecords() {
        var snapshot = new LinkRecord[records.size()];
        for (int i = 0; i < snapshot.length; i++) {
            var record = records.get(i);
            var copy = record.copy();
            var payload = payloads.get(record);
            if (payload != null) copy.setPayload(payload.encode());
            snapshot[i] = copy;
        }
        return snapshot;
    }

    private void setRecords(LinkRecord[] records) {
        this.records.clear();
        Collections.addAll(this.records, Objects.requireNonNull(records, "records"));
        payloads.clear();
        decoded.clear();
        checkAccess = () -> { };
    }

    @Nonnull
    List<LinkRecord> getRecords() {
        return Collections.unmodifiableList(records);
    }

    boolean isEmpty() {
        return records.isEmpty();
    }

    void addRecord(LinkRecord record) {
        records.add(record);
        decoded.clear();
    }

    boolean removeRecord(LinkRecord record) {
        if (!records.remove(record)) return false;
        payloads.remove(record);
        decoded.clear();
        return true;
    }

    @Nonnull
    RelationshipMetadata<ECS_TYPE> newMutableCopy() {
        var replacement = new RelationshipMetadata<ECS_TYPE>();
        for (var record : records) {
            var copy = record.copy();
            replacement.records.add(copy);
            var payload = payloads.get(record);
            if (payload != null) replacement.payloads.put(copy, payload);
        }
        replacement.checkAccess = checkAccess;
        return replacement;
    }

    boolean hasBindings(GenericRelationshipType<ECS_TYPE, ?, ?> type) {
        for (var payload : payloads.values()) {
            if (payload.type() == type) return true;
        }
        return false;
    }

    @Nonnull
    RelationshipMetadata<ECS_TYPE> freeze(GenericRelationshipType<ECS_TYPE, ?, ?> type) {
        checkAccess.run();
        var replacement = newMutableCopy();
        var entries = replacement.payloads.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (entry.getValue().type() != type) continue;
            entry.getKey().setPayload(entry.getValue().encode());
            entries.remove();
        }
        return replacement;
    }

    // unregistration encodes under exclusive access, even off the Store thread
    RelationshipMetadata<ECS_TYPE> freezeForUnregistration() {
        var replacement = new RelationshipMetadata<ECS_TYPE>();
        Collections.addAll(replacement.records, encodeRecords());
        return replacement;
    }

    void replaceWith(RelationshipMetadata<ECS_TYPE> replacement) {
        records.clear();
        records.addAll(replacement.records);
        payloads.clear();
        payloads.putAll(replacement.payloads);
        decoded.clear();
    }

    void checkAccess(Runnable checkAccess) {
        this.checkAccess = checkAccess;
    }

    <LINK_DATA> void bind(GenericRelationshipType<ECS_TYPE, ?, LINK_DATA> type, Object target, @Nullable LINK_DATA data) {
        if (!type.getDescriptor().isPersistent()) {
            return;
        }
        if (type.getCodec() == null) return;
        for (var link : readLinks(type)) {
            if (!target.equals(link.target())) continue;
            payloads.put(link.record(), new Payload<>(type, data));
            decoded.remove(type.getDescriptor().id());
            return;
        }
        throw new IllegalStateException("Persistent relationship record is missing");
    }

    @Nonnull
    List<DecodedLink> readLinks(GenericRelationshipType<ECS_TYPE, ?, ?> type) {
        checkAccess.run();
        if (!type.getDescriptor().isPersistent()) {
            return List.of();
        }
        if (type.getRelationshipTypeRegistry().getRegisteredType(type.getDescriptor().id()) != type) return List.of();
        var previous = decoded.get(type.getDescriptor().id());
        if (previous != null && previous.type() == type) return previous.links();
        var links = new ArrayList<DecodedLink>();
        var identityCodec = getTargetIdentityCodec(type);
        var installation = getTargetInstallation(type);
        if (identityCodec != null
            && type.getDescriptor().isPersistent()) {
            var targets = new HashSet<>();
            var disposition = LinkRecord.getCleanupDisposition(type);
            for (var record : records) {
                if (!installation.equals(record.getTargetInstallation())
                    || !record.isNamed(type.getDescriptor().id(), disposition)) continue;
                // a target this installation cannot read stays saved and out of the links
                Object target = record.getTargetIdentity(identityCodec);
                if (target == null || targets.contains(target)) continue;
                Object data;
                var payload = payloads.get(record);
                if (payload != null && payload.type() == type) {
                    data = payload.data();
                } else if (type.getDescriptor().linkDataClass() == Void.class) {
                    // a type without link data saves no payload
                    data = null;
                } else if (type.getCodec() == null) {
                    // without a codec this registration cannot read what the record saved
                    continue;
                } else if (record.getPayload() == null) {
                    data = null;
                } else {
                    Payload<?> decodedPayload;
                    try {
                        decodedPayload = decode(type, record.getPayload());
                    } catch (RuntimeException failure) {
                        // an unreadable record stays intact and inactive until a registration can read it
                        continue;
                    }
                    payloads.put(record, decodedPayload);
                    data = decodedPayload.data();
                }
                targets.add(target);
                links.add(new DecodedLink(target, data, record));
            }
        }
        var result = List.copyOf(links);
        decoded.put(type.getDescriptor().id(), new Decoded(type, result));
        return result;
    }

    /// Null when the targets' installation has no tracker, and the records then stay undecoded.
    @Nullable
    private Codec<?> getTargetIdentityCodec(GenericRelationshipType<ECS_TYPE, ?, ?> type) {
        var targetTracker = type.getTargetRelationshipTypeRegistry().getTracker();
        return targetTracker == null ? null : targetTracker.getIdentityCodec();
    }

    /// Empty when the targets' installation has no tracker.
    @Nonnull
    private String getTargetInstallation(GenericRelationshipType<ECS_TYPE, ?, ?> type) {
        var targetTracker = type.getTargetRelationshipTypeRegistry().getTracker();
        return targetTracker == null ? "" : targetTracker.getPersistenceIdentity().getInstallationName();
    }

    @Nonnull
    private static <T> Payload<T> decode(GenericRelationshipType<?, ?, T> type, BsonValue value) {
        var extraInfo = new ExtraInfo();
        var data = Objects.requireNonNull(Objects.requireNonNull(type.getCodec(), "Payload codec").decode(LinkRecord.copy(value), extraInfo), "Decoded relationship payload");
        extraInfo.getValidationResults().logOrThrowValidatorExceptions(LOGGER);
        type.validateData(data);
        return new Payload<>(type, data);
    }

    record DecodedLink(Object target, @Nullable Object data, LinkRecord record) { }
    private record Decoded(GenericRelationshipType<?, ?, ?> type, List<DecodedLink> links) { }

    private record Payload<T>(GenericRelationshipType<?, ?, T> type, @Nullable T data) {
        @Nullable
        BsonValue encode() {
            try {
                if (data == null) return null;
                var extraInfo = new ExtraInfo();
                var encoded = Objects.requireNonNull(Objects.requireNonNull(type.getCodec(), "Payload codec").encode(data, extraInfo), "Encoded relationship payload");
                extraInfo.getValidationResults().logOrThrowValidatorExceptions(LOGGER);
                return LinkRecord.copy(encoded);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Could not capture relationship payload for '"
                    + type.getDescriptor().id() + "'", failure);
            }
        }
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Nonnull @Override
    public RelationshipMetadata<ECS_TYPE> clone() {
        return new RelationshipMetadata<>(this);
    }
}
