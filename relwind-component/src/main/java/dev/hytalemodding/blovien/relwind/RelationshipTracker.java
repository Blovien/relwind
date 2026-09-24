/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/// Keeps the links of a linked entity while it is away and puts them back when it returns.
/// Install it through {@link RelationshipTypeRegistry#installTracker} before any relationship is
/// created, and report every load and unload from that linked entity's Store.
public final class RelationshipTracker<ECS_TYPE, ID> {
    // one StoreRuntime per Store kind, shared by every registry over the same ComponentRegistry
    private final StoreRuntime<ECS_TYPE> runtime;
    private final BiConsumer<Store<ECS_TYPE>, Runnable> execute;
    private final RelationshipTypeRegistry<ECS_TYPE> types;
    private final PersistenceIdentity<ECS_TYPE, ID> identity;
    // a chunk position repeats in every world
    private final boolean scopedToStore;
    private final IdentityIndex<ECS_TYPE, Object> index = new IdentityIndex<>();
    // sources is keyed by the source key and incoming by the target key, each from its own side
    private final Map<Object, Object> sources = new HashMap<>();
    private final Map<Object, Object> incoming = new HashMap<>();
    private final UnresolvedIncoming unresolvedIncoming = new UnresolvedIncoming();
    private final Map<Holder<ECS_TYPE>, HeldSource> holderSources = new IdentityHashMap<>();
    private final Map<Ref<ECS_TYPE>, Pending> pending = new IdentityHashMap<>();
    // a removed link still owes a record cleanup or a cascade, once its source is back
    private final Map<Object, Object> cleanupPending = new HashMap<>();

    RelationshipTracker(
        RelationshipTypeRegistry<ECS_TYPE> types,
        PersistenceIdentity<ECS_TYPE, ID> identity,
        StoreRuntime<ECS_TYPE> runtime
    ) {
        this.runtime = runtime;
        this.execute = runtime::execute;
        this.types = types;
        this.identity = identity;
        this.scopedToStore = identity.isScopedToStore();
    }

    @Nonnull
    StoreRuntime<ECS_TYPE> getStoreRuntime() {
        return runtime;
    }

    @Nonnull
    PersistenceIdentity<ECS_TYPE, ID> getPersistenceIdentity() {
        return identity;
    }

    @Nonnull
    public Codec<ID> getIdentityCodec() {
        return identity.getIdentityCodec();
    }

    /// The key a linked entity is held under: its identity, or its identity with its Store when this
    /// installation scopes identities to one Store.
    @Nullable
    private Object keyOf(@Nullable Object id, @Nullable Store<?> store) {
        if (id == null || !scopedToStore) {
            return id;
        }
        return store == null ? null : new ScopedIdentity(store, id);
    }

    @Nonnull
    private Object keyFor(ID id, Ref<ECS_TYPE> ref) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ref, "ref");
        return Objects.requireNonNull(keyOf(id, ref.getStore()));
    }

    /// The key `ref` currently has here, or null when this installation cannot name it.
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object keyOfRef(Ref<?> ref) {
        var store = ref.getStore();
        return keyOf(((PersistenceIdentity) identity).getIdentity(store, ref), store);
    }

    /// The key a saved target takes in the Store of this kind that sits beside `peer` in its world.
    @Nullable
    private Object keyBeside(@Nullable Object id, Store<?> peer) {
        if (id == null || !scopedToStore) {
            return id;
        }
        return keyOf(id, identity.storeBeside(peer));
    }

    /// The identity inside a key. Saved records and change notifications name this, not the key.
    @Nonnull
    private static Object identityOf(Object key) {
        return key instanceof ScopedIdentity scoped ? scoped.identity() : key;
    }

    private void requireUnscopedIdentities() {
        if (scopedToStore) {
            throw new IllegalStateException("Identities of installation "
                + identity.getInstallationName()
                + " name a linked entity only inside one Store, so this call needs that Store");
        }
    }

    @Nonnull
    public synchronized IdentityIndex.PutResult onEntityLoaded(ID id, Ref<ECS_TYPE> ref) {
        var key = keyFor(id, ref);
        var result = recordLoad(key, ref);
        if (result == IdentityIndex.PutResult.ACCEPTED) {
            resolveLinks(key);
        }
        return result;
    }

    @Nonnull
    public synchronized IdentityIndex.PutResult onEntityLoaded(CommandBuffer<ECS_TYPE> commandBuffer, ID id, Ref<ECS_TYPE> ref) {
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        if (commandBuffer.getStore() != ref.getStore()) {
            throw new IllegalArgumentException("Command buffer belongs to a different store");
        }
        var key = keyFor(id, ref);
        var result = recordLoad(key, ref);
        if (result == IdentityIndex.PutResult.ACCEPTED) {
            if (hasCascadePending(key)) {
                commandBuffer.tryRemoveEntity(ref, RemoveReason.REMOVE);
                return result;
            }
            commandBuffer.run(ignored -> {
                synchronized (RelationshipTracker.this) {
                    resolveLinks(key);
                }
            });
        }
        return result;
    }

    @Nonnull
    private IdentityIndex.PutResult recordLoad(Object key, Ref<ECS_TYPE> ref) {
        var result = index.put(key, ref);
        if (result == IdentityIndex.PutResult.DUPLICATE) {
            return result;
        }
        for (var link : getAffected(key)) {
            if (link.type.getRelationshipTypeRegistry().getTracker() == this && key.equals(link.sourceId)) {
                link.sourceRef = ref;
                link.sourceStore = ref.getStore();
                setSourceHolder(link, null);
            }
            if (link.type.getTargetRelationshipTypeRegistry().getTracker() == this && key.equals(link.targetId)) {
                link.targetRef = ref;
            }
        }
        for (var link : new ArrayList<>(getPendingCleanup(key))) {
            link.sourceRef = ref;
            link.sourceStore = ref.getStore();
            setSourceHolder(link, null);
        }
        return result;
    }

    public synchronized void onEntityUnloaded(ID id, Ref<ECS_TYPE> ref, UnloadReason reason) {
        onEntityUnloaded(id, ref, reason, null);
    }

    public synchronized void onEntityUnloaded(ID id, Ref<ECS_TYPE> ref, UnloadReason reason, @Nullable Holder<ECS_TYPE> holder) {
        Objects.requireNonNull(reason, "reason");
        var key = keyFor(id, ref);
        if (!index.isCurrent(key, ref)) {
            return;
        }
        for (var link : getPendingCleanup(key)) {
            if (link.sourceRef == ref) {
                setSourceHolder(link, holder);
                link.sourceRef = null;
            }
        }
        discoverLinks(key, ref, holder);
        var affected = getAffected(key);
        for (var link : affected) {
            if (holder != null && link.sourceRef == ref) {
                setSourceHolder(link, holder);
            }
            capture(link, ref, holder);
        }
        if (reason == UnloadReason.PENDING) {
            pending.put(ref, new Pending(key, affected));
        }
        if (holder != null) {
            clearHolderReferences(holder);
        }
        index.remove(key, ref);
        for (var link : affected) {
            if (!isCurrent(link)) {
                continue;
            }
            detach(link);
            if (!isRetainedOn(link.type.getDescriptor(), reason)) {
                removeByPolicy(link, ref);
                continue;
            }
            if (reason == UnloadReason.PENDING) {
                link.pending++;
            }
            if (link.sourceRef == ref) {
                link.sourceRef = null;
            }
            if (link.targetRef == ref) {
                link.targetRef = null;
            }
            link.resolved = false;
        }
    }

    private void clearHolderReferences(Holder<ECS_TYPE> holder) {
        var archetype = holder.getArchetype();
        for (int i = archetype.getMinIndex(); i < archetype.length(); i++) {
            var componentType = archetype.get(i);
            if (componentType == null) {
                continue;
            }
            var component = holder.getComponent(componentType);
            if (component instanceof OutgoingLink<?, ?> || component instanceof IncomingLinks<?, ?>) {
                holder.removeComponent(componentType);
            }
        }
    }

    public synchronized boolean onEntityUnloading(CommandBuffer<ECS_TYPE> commandBuffer, ID id, Ref<ECS_TYPE> ref) {
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        var key = keyFor(id, ref);
        if (!index.isCurrent(key, ref)) {
            return false;
        }
        if (commandBuffer.getStore() != ref.getStore()) {
            throw new IllegalArgumentException("Command buffer belongs to a different store");
        }
        discoverLinks(key, ref, null);
        var affected = getAffected(key);
        for (var link : affected) {
            capture(link, ref, null);
        }
        commandBuffer.run(store -> prepareAfterRemoval(store, key, ref, affected));
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void discoverLinks(Object key, Ref<ECS_TYPE> ref, @Nullable Holder<ECS_TYPE> holder) {
        if (!ref.getStore().isInThread() || !ref.isValid() && holder == null) return;
        var registry = ref.getStore().getRegistry();
        for (var type : RelationshipAccessSystem.getOutgoingTypes(registry)) {
            var outgoing = readComponent(ref, holder, type.getSourceType());
            if (outgoing == null) continue;
            for (int i = 0; i < outgoing.size(); i++) {
                recordLoadedLink(type, ref, outgoing.getTarget(i), key, ref, outgoing.getData(i, Object.class));
            }
        }
        for (var type : RelationshipAccessSystem.getIncomingTypes(registry)) {
            var incoming = readComponent(ref, holder, type.getIncomingType());
            if (incoming == null) continue;
            incoming.forEach(source -> {
                if (source == ref || !source.isValid()) return;
                GenericRelationshipType sourceType = type;
                Ref sourceRef = source;
                var outgoing = (OutgoingLink) sourceRef.getStore().getComponent(sourceRef, sourceType.getSourceType());
                if (outgoing != null && outgoing.contains(ref)) {
                    recordLoadedLink(type, source, ref, key, ref, outgoing.getData(ref, Object.class));
                }
            });
        }
    }

    @Nullable
    private static <ECS_TYPE, C extends Component<ECS_TYPE>> C readComponent(
        Ref<ECS_TYPE> ref, @Nullable Holder<ECS_TYPE> holder, ComponentType<ECS_TYPE, C> type
    ) {
        if (ref.isValid()) return ref.getStore().getComponent(ref, type);
        return holder == null ? null : holder.getComponent(type);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void recordLoadedLink(GenericRelationshipType type, Ref<?> source, Ref<?> target,
        Object leavingKey, Ref<ECS_TYPE> leaving, @Nullable Object data) {
        var sourceTracker = type.getRelationshipTypeRegistry().getTracker();
        var targetTracker = type.getTargetRelationshipTypeRegistry().getTracker();
        if (sourceTracker == null || targetTracker == null) return;
        var sourceId = source == leaving && sourceTracker == this ? leavingKey
            : source.isValid() ? sourceTracker.keyOfRef(source) : null;
        var targetId = target == leaving && targetTracker == this ? leavingKey
            : target.isValid() ? targetTracker.keyOfRef(target) : null;
        if (sourceId == null || targetId == null) return;
        if (sourceTracker.find(type, sourceId, targetId) != null) return;
        var link = new Link(type, sourceId, targetId, source, target);
        link.data = data;
        file(link);
    }

    private synchronized void prepareAfterRemoval(Store<ECS_TYPE> store, Object key, Ref<ECS_TYPE> ref, List<Link> affected) {
        if (!index.isCurrent(key, ref)) {
            return;
        }
        for (var link : affected) {
            if (!isSameStore(link)) {
                continue;
            }
            if (isCurrent(link) && link.resolved) {
                assert link.sourceRef != null && link.targetRef != null;
                detachAfterUnload(store, link, ref);
                link.resolved = false;
            }
        }
    }

    /// Returns false and changes nothing when the unload already settled, or on a second call.
    public synchronized boolean onUnloadResolved(ID id, Ref<ECS_TYPE> unloaded, UnloadReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason == UnloadReason.PENDING) {
            throw new IllegalArgumentException("reason must not be PENDING");
        }
        var key = keyFor(id, unloaded);
        var transition = pending.get(unloaded);
        if (transition == null || !transition.id.equals(key)) {
            return false;
        }
        pending.remove(unloaded);
        for (var link : transition.links) {
            if (!isCurrent(link)) {
                continue;
            }
            link.pending--;
            if (!isRetainedOn(link.type.getDescriptor(), reason)) {
                removeByPolicy(link, unloaded);
            }
        }
        resolveLinks(key);
        return true;
    }

    /// Reconnects the links of `id` to linked entities that are already loaded. Call it outside ECS processing.
    /// @throws IllegalStateException if this installation scopes its identities to a Store
    public synchronized void reconcile(ID id) {
        Objects.requireNonNull(id, "id");
        requireUnscopedIdentities();
        resolveLinks(id);
    }

    /// Reconnects the links `id` has in `store`, for an installation scoped to one Store.
    /// Call it outside ECS processing.
    public synchronized void reconcile(Store<ECS_TYPE> store, ID id) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(store, "store");
        resolveLinks(Objects.requireNonNull(keyOf(id, store)));
    }

    /// @throws IllegalStateException if this installation scopes its identities to a Store
    @Nullable
    public synchronized Ref<ECS_TYPE> getRef(ID id) {
        Objects.requireNonNull(id, "id");
        requireUnscopedIdentities();
        return index.getRef(id);
    }

    /// The linked entity held under `id` in `store`, for an installation scoped to one Store.
    @Nullable
    public synchronized Ref<ECS_TYPE> getRef(ID id, Store<ECS_TYPE> store) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(store, "store");
        return index.getRef(Objects.requireNonNull(keyOf(id, store)));
    }

    public synchronized void onEntityDeleted(ID id, Ref<ECS_TYPE> ref) {
        onEntityDeleted(id, ref, null);
    }

    /// Confirms the linked entity was deleted. A persistent source in another Store stays unresolved
    /// until that Store reconciles.
    public synchronized void onEntityDeleted(ID id, Ref<ECS_TYPE> ref, @Nullable Holder<ECS_TYPE> holder) {
        var key = keyFor(id, ref);
        if (!index.isCurrent(key, ref)) {
            return;
        }
        discoverLinks(key, ref, holder);
        index.remove(key, ref);
        var discarded = cleanupPending.remove(key);
        if (discarded != null) {
            for (var link : links(discarded)) {
                setSourceHolder(link, null);
            }
        }
        pending.values().removeIf(transition -> transition.id.equals(key));
        var affected = getAffected(key);
        for (var link : affected) {
            if (!isSameStore(link)) {
                continue;
            }
            if (holder != null && link.sourceRef == ref) {
                setSourceHolder(link, holder);
            }
            capture(link, ref, holder);
        }
        if (holder != null) {
            clearHolderReferences(holder);
        }
        for (var link : affected) {
            if (!isCurrent(link) || !isSameStore(link)) {
                continue;
            }
            detach(link);
            if (link.targetId.equals(key) && link.sourceStore != ref.getStore()
                && link.type.getDescriptor().isPersistent()) {
                link.targetRef = null;
                continue;
            }
            if (link.targetId.equals(key) && !link.sourceId.equals(key)) {
                link.cascade = link.type.getDescriptor().getOnDeleteTarget()
                    == RelationshipTraits.OnDeleteTarget.DELETE;
            }
            removeByPolicy(link, ref);
        }
    }

    synchronized boolean hasPendingDeletion(Object id, Store<ECS_TYPE> store) {
        return hasCascadePending(keyOf(id, store));
    }

    private boolean hasCascadePending(@Nullable Object key) {
        for (var link : getPendingCleanup(key)) {
            if (link.cascade) {
                return true;
            }
        }
        return false;
    }

    /// @throws IllegalStateException if this installation scopes its identities to a Store
    public synchronized boolean contains(GenericRelationshipType<ECS_TYPE, ?, ?> type, ID sourceId, ID targetId) {
        requireUnscopedIdentities();
        return find(type, sourceId, targetId) != null || hasLoadedLink(type, sourceId, targetId);
    }

    /// @throws IllegalStateException if this installation scopes its identities to a Store
    public synchronized boolean isResolved(GenericRelationshipType<ECS_TYPE, ?, ?> type, ID sourceId, ID targetId) {
        requireUnscopedIdentities();
        var link = find(type, sourceId, targetId);
        return link == null ? hasLoadedLink(type, sourceId, targetId) : link.resolved;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean hasLoadedLink(GenericRelationshipType type, @Nullable Object sourceId, @Nullable Object targetId) {
        if (!type.getRelationshipTypeRegistry().isRegistered(type)) return false;
        var targetTracker = type.getTargetRelationshipTypeRegistry().getTracker();
        var source = getLoadedRef(sourceId);
        var target = targetTracker == null ? null : targetTracker.getLoadedRef(targetId);
        if (source == null || target == null) return false;
        var outgoing = (OutgoingLink) source.getStore().getComponent(source, type.getSourceType());
        return outgoing != null && outgoing.contains(target);
    }

    synchronized boolean hasRecordedLinks() {
        return !sources.isEmpty() || !incoming.isEmpty() || !cleanupPending.isEmpty();
    }

    // Hytale clears the outgoing component on removal
    synchronized void readHolderLinks(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type,
        Holder<ECS_TYPE> holder,
        Store<ECS_TYPE> context,
        java.util.function.BiConsumer<Ref<ECS_TYPE>, Object> link
    ) {
        type.validate(context);
        type.getSourceType().validate();
        context.assertThread();
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(link, "link");
        var retained = getOutgoing(getHolderSource(holder));
        for (Link association : retained) {
            if (association.type != type || association.sourceHolder != holder) continue;
            var linkedEntity = association.pending == 0 ? getLoadedRef(association.targetId) : null;
            link.accept(linkedEntity != null && linkedEntity.getStore() == context ? linkedEntity : null, association.data);
        }
        var persistence = type.getRelationshipTypeRegistry().getPersistence();
        if (persistence != null) persistence.readHolderLinks(type, holder, context, link);
    }

    @Nullable
    synchronized Object getHolderSource(Holder<ECS_TYPE> holder) {
        var source = holderSources.get(holder);
        return source == null ? null : source.id;
    }

    private void setSourceHolder(Link link, @Nullable Holder<ECS_TYPE> holder) {
        if (link.sourceHolder == holder) {
            return;
        }
        var next = holderSources.get(holder);
        if (next != null && !next.id.equals(link.sourceId)) {
            throw new IllegalArgumentException("Holder represents another identity");
        }
        var previous = link.sourceHolder;
        if (previous != null) {
            var source = holderSources.get(previous);
            assert source != null;
            if (--source.links == 0) {
                holderSources.remove(previous);
            }
        }
        link.sourceHolder = holder;
        if (holder != null) {
            if (next == null) {
                next = new HeldSource(link.sourceId);
                holderSources.put(holder, next);
            }
            next.links++;
        }
    }

    /// The target key uses `context`, because a same-Store link keeps both linked entities in one Store.
    synchronized boolean hasHolderLink(
        GenericRelationshipType<ECS_TYPE, ?, ?> type,
        Holder<ECS_TYPE> holder,
        @Nullable Object source,
        Object target,
        Store<ECS_TYPE> context
    ) {
        var targetKey = keyOf(target, context);
        var retained = getOutgoing(source);
        for (Link link : retained) {
            if (link.type == type && link.sourceHolder == holder && link.targetId.equals(targetKey)) return true;
        }
        var cleanups = getPendingCleanup(source);
        for (Link link : cleanups) {
            if (link.type == type && link.sourceHolder == holder && link.targetId.equals(targetKey)) return true;
        }
        return false;
    }

    synchronized boolean hasUnresolvedOutgoing(GenericRelationshipType<ECS_TYPE, ?, ?> type, Ref<ECS_TYPE> source) {
        Objects.requireNonNull(source, "source");
        return hasUnresolvedOutgoing(type, source, getIdentity(source));
    }

    synchronized boolean hasUnresolvedOutgoing(GenericRelationshipType<ECS_TYPE, ?, ?> type, Ref<ECS_TYPE> source,
        @Nullable Object sourceIdentity) {
        for (var link : getOutgoing(keyOf(sourceIdentity, source.getStore()))) {
            if (link.type == type && !link.resolved) return true;
        }
        return false;
    }

    synchronized boolean hasUnresolvedIncoming(GenericRelationshipType<?, ECS_TYPE, ?> type, Ref<ECS_TYPE> target) {
        var links = incoming.get(keyOfRef(target));
        if (links == null) return false;
        // ReferenceOpenHashSet.forEach avoids an iterator per hop, and the tracker monitor makes
        // reusing one scanner safe
        unresolvedIncoming.type = type;
        unresolvedIncoming.found = false;
        try {
            links(links).forEach(unresolvedIncoming);
            return unresolvedIncoming.found;
        } finally {
            unresolvedIncoming.type = null;
        }
    }

    private static final class UnresolvedIncoming implements java.util.function.Consumer<Link> {
        @Nullable
        private GenericRelationshipType<?, ?, ?> type;
        private boolean found;

        @Override
        public void accept(Link link) {
            if (link.type == type && !link.resolved) found = true;
        }
    }

    /// The registry calls this on the tracker of each side.
    synchronized void onTypeUnregistered(GenericRelationshipType<?, ?, ?> type) {
        var removed = new ArrayList<Link>();
        for (var outgoing : sources.values()) {
            for (var link : links(outgoing)) {
                if (link.type == type) {
                    removed.add(link);
                }
            }
        }
        for (var targets : incoming.values()) {
            for (var link : links(targets)) {
                if (link.type == type && !removed.contains(link)) {
                    removed.add(link);
                }
            }
        }
        removed.forEach(RelationshipTracker::unfile);
        for (var records : new ArrayList<>(cleanupPending.values())) {
            for (var link : new ArrayList<>(links(records))) {
                if (link.type == type && !link.cascade) {
                    removeRecord(cleanupPending, link.sourceId, link);
                    setSourceHolder(link, null);
                }
            }
        }
    }

    synchronized void validateLink(
        GenericRelationshipType<ECS_TYPE, ?, ?> type,
        Ref<ECS_TYPE> source,
        Ref<?> target,
        @Nullable Object sourceIdentity,
        @Nullable Object targetIdentity,
        boolean replacesExclusiveTarget
    ) {
        if (sources.isEmpty() && !retains(type.getDescriptor())) return;
        var targetTracker = type.getTargetRelationshipTypeRegistry().getTracker();
        var sourceId = keyOf(sourceIdentity, source.getStore());
        var targetId = targetTracker == null ? null : targetTracker.keyOf(targetIdentity, target.getStore());
        if (retains(type.getDescriptor()) && (targetTracker == null || sourceId == null || targetId == null)) {
            throw new IllegalStateException("Relationship type '" + type.getDescriptor().id()
                + "' requires an installed tracker and stable identities on both sides");
        }
        if (type.getDescriptor().isExclusive() && !replacesExclusiveTarget) {
            for (var link : getOutgoing(sourceId)) {
                if (link.type == type && !link.resolved && !link.targetId.equals(targetId)) {
                    throw new IllegalStateException(
                        "Source " + source + " already has a target for relationship type '" + type.getDescriptor().id() + "'"
                    );
                }
            }
        }
    }

    synchronized boolean hasUnresolvedLink(GenericRelationshipType<ECS_TYPE, ?, ?> type, Ref<ECS_TYPE> source, Ref<?> target,
        @Nullable Object sourceIdentity, @Nullable Object targetIdentity) {
        if (sources.isEmpty()) return false;
        var targetTracker = type.getTargetRelationshipTypeRegistry().getTracker();
        if (targetTracker == null) {
            return false;
        }
        var link = find(type, keyOf(sourceIdentity, source.getStore()), targetTracker.keyOf(targetIdentity, target.getStore()));
        return link != null && !link.resolved;
    }

    @Nullable
    synchronized Object getIdentity(Ref<ECS_TYPE> ref) {
        return identity.getIdentity(ref.getStore(), ref);
    }

    @Nullable
    synchronized <LINK_DATA> LINK_DATA getUnresolvedLinkData(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
        Ref<ECS_TYPE> source,
        Ref<ECS_TYPE> target
    ) {
        var sourceIdentity = getIdentity(source);
        return getUnresolvedLinkData(type, source, target, sourceIdentity,
            source == target ? sourceIdentity : getIdentity(target));
    }

    @Nullable
    synchronized <LINK_DATA> LINK_DATA getUnresolvedLinkData(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
        Ref<ECS_TYPE> source,
        Ref<ECS_TYPE> target,
        @Nullable Object sourceIdentity,
        @Nullable Object targetIdentity
    ) {
        var link = find(type, keyOf(sourceIdentity, source.getStore()), keyOf(targetIdentity, target.getStore()));
        assert link != null && !link.resolved;
        return type.getDescriptor().linkDataClass().cast(link.data);
    }

    synchronized void updateUnresolvedLink(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, ?> type,
        Ref<ECS_TYPE> source,
        Ref<ECS_TYPE> target,
        @Nullable Object sourceIdentity,
        @Nullable Object targetIdentity,
        @Nullable Object data
    ) {
        var link = find(type, keyOf(sourceIdentity, source.getStore()), keyOf(targetIdentity, target.getStore()));
        if (link == null || link.resolved) {
            return;
        }
        link.data = data;
    }

    synchronized void dropUnresolvedLink(GenericRelationshipType<ECS_TYPE, ?, ?> type, Ref<ECS_TYPE> source, Ref<?> target,
        @Nullable Object sourceIdentity, @Nullable Object targetIdentity) {
        var targetTracker = type.getTargetRelationshipTypeRegistry().getTracker();
        if (targetTracker == null) return;
        var link = find(type, keyOf(sourceIdentity, source.getStore()), targetTracker.keyOf(targetIdentity, target.getStore()));
        if (link != null && !link.resolved) unfile(link);
    }

    synchronized void onLinkDeleted(GenericRelationshipType<ECS_TYPE, ?, ?> type, Ref<ECS_TYPE> source,
        Ref<?> target, @Nullable Object sourceIdentity) {
        for (var link : getOutgoing(keyOf(sourceIdentity, source.getStore()))) {
            if (link.type == type && link.sourceRef == source && link.targetRef == target) {
                unfile(link);
                return;
            }
        }
    }

    /// A target of this installation is looked for in the source's Store. A bridge target is looked
    /// for in the Store its own installation holds beside the source's.
    synchronized void restorePersistent(
        GenericRelationshipType<ECS_TYPE, ?, ?> type,
        Object sourceIdentity,
        Object targetIdentity,
        Ref<ECS_TYPE> source,
        @Nullable Object data
    ) {
        var targetTracker = type.getTargetRelationshipTypeRegistry().getTracker();
        if (targetTracker == null) {
            return;
        }
        var sourceStore = source.getStore();
        var sourceId = keyOf(sourceIdentity, sourceStore);
        var targetId = targetTracker == this
            ? keyOf(targetIdentity, sourceStore)
            : targetTracker.keyBeside(targetIdentity, sourceStore);
        if (sourceId == null || targetId == null) {
            return;
        }
        if (hasLoadedLink(type, sourceId, targetId)) return;
        for (var cleanup : getPendingCleanup(sourceId)) {
            if (cleanup.type == type && cleanup.targetId.equals(targetId)) {
                return;
            }
        }
        var link = find(type, sourceId, targetId);
        if (link == null) {
            if (type.getDescriptor().isExclusive()) {
                for (var outgoing : getOutgoing(sourceId)) {
                    if (outgoing.type == type) {
                        return;
                    }
                }
            }
            link = new Link(type, sourceId, targetId, source, targetTracker.getLoadedRef(targetId));
            link.data = data;
            link.resolved = false;
            file(link);
        } else if (!link.resolved) {
            link.sourceRef = source;
        }
        resolveLinks(sourceId);
    }

    synchronized void removePersistent(Ref<ECS_TYPE> source, @Nullable String typeId, Object targetIdentity) {
        var targetId = keyOf(targetIdentity, source.getStore());
        for (var link : getOutgoing(keyOfRef(source))) {
            if (Objects.equals(link.type.getDescriptor().id(), typeId) && link.targetId.equals(targetId)) {
                unfile(link);
                return;
            }
        }
    }

    synchronized <LINK_DATA> List<DroppedTarget<LINK_DATA>> dropUnresolvedTargets(
        GenericRelationshipType<ECS_TYPE, ?, LINK_DATA> type,
        Ref<ECS_TYPE> source,
        @Nullable Object sourceIdentity
    ) {
        var dropped = new ArrayList<DroppedTarget<LINK_DATA>>();
        for (var link : new ArrayList<>(getOutgoing(keyOf(sourceIdentity, source.getStore())))) {
            if (link.type == type && !link.resolved) {
                dropped.add(new DroppedTarget<>(identityOf(link.targetId),
                    type.getDescriptor().linkDataClass().cast(link.data)));
                unfile(link);
            }
        }
        return dropped;
    }

    record DroppedTarget<LINK_DATA>(Object identity, @Nullable LINK_DATA data) { }

    @Nullable
    synchronized <LINK_DATA> DroppedTarget<LINK_DATA> dropUnresolvedTwin(
        GenericRelationshipType<ECS_TYPE, ECS_TYPE, LINK_DATA> type,
        Ref<ECS_TYPE> source,
        @Nullable Object sourceIdentity,
        Object awayIdentity
    ) {
        var twin = find(type, keyOf(awayIdentity, source.getStore()), keyOf(sourceIdentity, source.getStore()));
        if (twin == null || twin.resolved) return null;
        var data = type.getDescriptor().linkDataClass().cast(twin.data);
        if (type.getDescriptor().isPersistent() && type.getRelationshipTypeRegistry().getPersistence() != null) {
            addRecord(cleanupPending, twin.sourceId, twin, false);
            unfile(twin);
            applyCleanup(twin);
        } else {
            unfile(twin);
        }
        return new DroppedTarget<>(awayIdentity, data);
    }

    /// Copies the link data out before the component detaches. Resolution puts it back.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void capture(Link link, Ref<ECS_TYPE> unloading, @Nullable Holder<ECS_TYPE> holder) {
        if (!link.resolved || !isCurrent(link)) {
            return;
        }
        assert link.sourceRef != null && link.targetRef != null;
        Holder<?> sourceHolder = null;
        if (!link.sourceRef.isValid()) {
            if (link.sourceRef != unloading || holder == null) {
                return;
            }
            sourceHolder = holder;
        }
        OutgoingLink outgoing = getOutgoingOf(link, sourceHolder);
        if (outgoing == null || !outgoing.contains(link.targetRef)) {
            unfile(link);
            return;
        }
        link.data = outgoing.getData(link.targetRef, Object.class);
    }

    /// Read from the holder while the source is leaving, and from its Store otherwise.
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static OutgoingLink getOutgoingOf(Link link, @Nullable Holder<?> sourceHolder) {
        ComponentType sourceType = link.type.getSourceType();
        if (sourceHolder != null) {
            return (OutgoingLink) sourceHolder.getComponent(sourceType);
        }
        assert link.sourceRef != null;
        Ref source = link.sourceRef;
        return (OutgoingLink) source.getStore().getComponent(source, sourceType);
    }

    /// One installation repairs both sides with a single command. Two installations repair each
    /// side on the thread of its own Store.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void detach(Link link) {
        if (!link.resolved || link.sourceRef == null || link.targetRef == null) {
            return;
        }
        detach(link, link.sourceRef, link.targetRef);
        link.resolved = false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void detach(Link link, Ref<?> sourceRef, Ref<?> targetRef) {
        GenericRelationshipType type = link.type;
        Ref source = sourceRef;
        Ref target = targetRef;
        Store sourceStore = source.getStore();
        Store targetStore = target.getStore();
        var sourceTracker = link.type.getRelationshipTypeRegistry().getTracker();
        var targetTracker = link.type.getTargetRelationshipTypeRegistry().getTracker();
        if (sourceTracker == targetTracker) {
            if (sourceStore == targetStore) {
                if (source.isValid() && target.isValid()) {
                    RelationshipCommands.remove(sourceStore, null, type, source, target, true, null, false);
                } else if (sourceStore.isInThread()) {
                    RelationshipLifecycle.detachLinkedEntity(sourceStore, type, source, target,
                        source.isValid() ? target : source);
                }
            }
            return;
        }
        if (target.isValid()) {
            executeOn(targetStore, targetTracker,
                () -> RelationshipLifecycle.releaseDeletedSource(type, source, target));
        }
        if (source.isValid()) {
            executeOn(sourceStore, sourceTracker,
                () -> RelationshipLifecycle.releaseDeletedTarget(type, source, target));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void detachAfterUnload(Store<ECS_TYPE> store, Link link, Ref<ECS_TYPE> unloaded) {
        assert link.sourceRef != null && link.targetRef != null;
        RelationshipLifecycle.detachLinkedEntity((Store) store, (GenericRelationshipType) link.type,
            (Ref) link.sourceRef, (Ref) link.targetRef, (Ref) unloaded);
    }

    /// Runs the action on that Store's thread, outside ECS processing.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void executeOn(Store<?> store, @Nullable RelationshipTracker<?, ?> tracker, Runnable action) {
        if (tracker == null) {
            action.run();
            return;
        }
        ((BiConsumer) tracker.execute).accept(store, action);
    }

    private void resolveLinks(Object loaded) {
        for (var cleanup : new ArrayList<>(getPendingCleanup(loaded))) {
            applyCleanup(cleanup);
        }
        for (var link : getAffected(loaded)) {
            resolve(link);
        }
    }

    /// Both linked entities in one Store reattach here, on that Store's thread. Linked entities in two Stores
    /// reattach on the thread of the source's Store.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void resolve(Link link) {
        if (link.resolved || link.pending != 0 || !isCurrent(link)) {
            return;
        }
        var sourceTracker = link.type.getRelationshipTypeRegistry().getTracker();
        var targetTracker = link.type.getTargetRelationshipTypeRegistry().getTracker();
        if (sourceTracker == null || targetTracker == null) {
            return;
        }
        Ref source = sourceTracker.getLoadedRef(link.sourceId);
        Ref target = targetTracker.getLoadedRef(link.targetId);
        if (source == null || target == null) {
            return;
        }
        Store sourceStore = source.getStore();
        if (sourceTracker == targetTracker) {
            if (sourceStore != target.getStore() || !sourceStore.isInThread()) {
                return;
            }
            sourceStore.assertWriteProcessing();
            link.sourceRef = source;
            setSourceHolder(link, null);
            link.targetRef = target;
            restore(sourceStore, link);
            return;
        }
        if (!source.isValid() || !target.isValid()) {
            return;
        }
        GenericRelationshipType type = link.type;
        var data = link.data;
        executeOn(sourceStore, sourceTracker, () -> {
            if (link.resolved || !source.isValid() || !target.isValid() || !isCurrent(link)) {
                return;
            }
            link.sourceRef = source;
            link.targetRef = target;
            boolean completed = false;
            try {
                RelationshipLifecycle.restoreLink(type, source, target, data);
                completed = true;
            } finally {
                finishRestore(link, source, target, completed);
            }
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void restore(Store<ECS_TYPE> store, Link link) {
        assert link.sourceRef != null && link.targetRef != null;
        var type = (GenericRelationshipType<ECS_TYPE, ECS_TYPE, Object>) link.type;
        Ref source = link.sourceRef;
        Ref target = link.targetRef;
        boolean completed = false;
        try {
            RelationshipCommands.add(store, null, (GenericRelationshipType) type, source, target, link.data, null, false);
            completed = true;
        } finally {
            finishRestore(link, source, target, completed);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void finishRestore(Link link, Ref<?> source, Ref<?> target, boolean completed) {
        if (!isCurrent(link)) return;
        var sourceTracker = link.type.getRelationshipTypeRegistry().getTracker();
        var targetTracker = link.type.getTargetRelationshipTypeRegistry().getTracker();
        if (link.pending != 0 || sourceTracker.getLoadedRef(link.sourceId) != source
            || targetTracker == null || targetTracker.getLoadedRef(link.targetId) != target) {
            detach(link, source, target);
            return;
        }
        GenericRelationshipType type = link.type;
        Ref sourceRef = source;
        var outgoing = (OutgoingLink) sourceRef.getStore().getComponent(sourceRef, type.getSourceType());
        if (completed || outgoing != null && outgoing.contains(target)) unfile(link);
    }

    @Nonnull
    private Collection<Link> getOutgoing(@Nullable Object sourceId) {
        return links(sources.get(sourceId));
    }

    @Nonnull
    private ArrayList<Link> getAffected(Object id) {
        var links = new ArrayList<>(getOutgoing(id));
        var targets = incoming.get(id);
        if (targets != null) {
            for (var link : links(targets)) {
                if (link.type.getRelationshipTypeRegistry().getTracker() != this || !link.sourceId.equals(id)) {
                    links.add(link);
                }
            }
        }
        return links;
    }

    @Nullable
    private Link find(GenericRelationshipType<?, ?, ?> type, @Nullable Object sourceId, @Nullable Object targetId) {
        if (targetId == null) {
            return null;
        }
        for (var link : getOutgoing(sourceId)) {
            if (link.type == type && link.targetId.equals(targetId)) {
                return link;
            }
        }
        return null;
    }

    /// Ask the source side's tracker. It files every link in its outgoing index.
    private static boolean isCurrent(Link link) {
        var sourceTracker = link.type.getRelationshipTypeRegistry().getTracker();
        return sourceTracker != null
            && sourceTracker.find(link.type, link.sourceId, link.targetId) == link;
    }

    /// A bridge link is skipped by the callers, because the other Store cleans up its own side.
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean isSameStore(Link link) {
        return link.type.getTargetRelationshipTypeRegistry() == link.type.getRelationshipTypeRegistry();
    }

    private static void file(Link link) {
        var sourceTracker = link.type.getRelationshipTypeRegistry().getTracker();
        if (sourceTracker != null) {
            addRecord(sourceTracker.sources, link.sourceId, link, false);
        }
        var targetTracker = link.type.getTargetRelationshipTypeRegistry().getTracker();
        if (targetTracker != null) {
            addRecord(targetTracker.incoming, link.targetId, link, true);
        }
    }

    private static void unfile(Link link) {
        var sourceTracker = link.type.getRelationshipTypeRegistry().getTracker();
        if (sourceTracker != null) {
            removeRecord(sourceTracker.sources, link.sourceId, link);
        }
        var targetTracker = link.type.getTargetRelationshipTypeRegistry().getTracker();
        if (targetTracker != null) {
            removeRecord(targetTracker.incoming, link.targetId, link);
        }
        if (sourceTracker != null && !sourceTracker.getPendingCleanup(link.sourceId).contains(link)) {
            link.sourceRef = null;
            sourceTracker.setSourceHolder(link, null);
        }
        link.targetRef = null;
        link.data = null;
        link.resolved = false;
    }

    @SuppressWarnings("unchecked")
    private static Collection<Link> links(@Nullable Object value) {
        if (value == null) return List.of();
        if (value instanceof Link link) return List.of(link);
        return (Collection<Link>) value;
    }

    private static void addRecord(Map<Object, Object> records, Object id, Link link, boolean incoming) {
        var previous = records.get(id);
        if (previous == null) {
            records.put(id, link);
        } else if (previous instanceof Link first) {
            if (first == link) return;
            Collection<Link> multiple = incoming ? new ReferenceOpenHashSet<>(2) : new ArrayList<>(2);
            multiple.add(first);
            multiple.add(link);
            records.put(id, multiple);
        } else {
            var multiple = links(previous);
            if (!multiple.contains(link)) multiple.add(link);
        }
    }

    private static void removeRecord(Map<Object, Object> records, Object id, Link link) {
        var previous = records.get(id);
        if (previous == link) {
            records.remove(id);
        } else if (previous != null && !(previous instanceof Link)) {
            var multiple = links(previous);
            if (!multiple.remove(link)) return;
            if (multiple.size() == 1) records.put(id, multiple.iterator().next());
            else if (multiple.isEmpty()) records.remove(id);
        }
    }

    @Nonnull
    private Collection<Link> getPendingCleanup(@Nullable Object sourceId) {
        return links(cleanupPending.get(sourceId));
    }

    /// Runs on the source side. It owns the records, the holder and the Store.
    private void removeByPolicy(Link link, Ref<ECS_TYPE> unloaded) {
        var sourceTracker = link.type.getRelationshipTypeRegistry().getTracker();
        if (sourceTracker == null) {
            unfile(link);
            return;
        }
        if (sourceTracker != this) {
            sourceTracker.removeByPolicyHere(link, unloaded);
            return;
        }
        removeByPolicyHere(link, unloaded);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void removeByPolicyHere(Link link, Ref<?> unloaded) {
        var notification = link.sourceHolder != null || (link.sourceRef != null && link.sourceRef.isValid())
            ? RelationshipChangeSystem.newRemoval((GenericRelationshipType) link.type,
                (Ref) (link.sourceRef == unloaded ? null : link.sourceRef), identityOf(link.sourceId),
                (Ref) (link.targetRef == unloaded ? null : link.targetRef), identityOf(link.targetId), link.data)
            : null;
        var persistence = link.type.getRelationshipTypeRegistry().getPersistence();
        if (!link.cascade && (persistence == null
            || !link.type.getDescriptor().isPersistent())) {
            var sourceStore = link.sourceStore;
            unfile(link);
            if (notification != null) {
                executeOnSource(sourceStore, () -> notifyRemoval(sourceStore, notification));
            }
            return;
        }
        addRecord(cleanupPending, link.sourceId, link, false);
        unfile(link);
        if (link.sourceHolder != null && (link.sourceRef == null || !link.sourceRef.isValid())
            && link.sourceStore.isInThread()) {
            applyCleanup(link, notification);
        } else {
            executeOnSource(link.sourceStore, () -> applyCleanup(link, notification));
        }
    }

    /// Every link filed here has its source in this installation.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void executeOnSource(Store<?> store, Runnable action) {
        ((BiConsumer) execute).accept(store, action);
    }

    private synchronized void applyCleanup(Link link) {
        applyCleanup(link, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private synchronized void applyCleanup(Link link, @Nullable RelationshipChangeSystem.ChangeEvent<?, ?> notification) {
        if (!getPendingCleanup(link.sourceId).contains(link)) {
            return;
        }
        var replacement = find(link.type, link.sourceId, link.targetId);
        boolean changedAssociation = !link.type.getRelationshipTypeRegistry().isRegistered((GenericRelationshipType) link.type)
            || (replacement != null && replacement != link);
        if (types.getTracker() != this || (!link.cascade && changedAssociation)) {
            removeRecord(cleanupPending, link.sourceId, link);
        } else {
            // a load may have moved the source since this action was queued
            if (!link.sourceStore.isInThread()) {
                executeOnSource(link.sourceStore, () -> applyCleanup(link, notification));
                return;
            }
            var persistence = link.type.getRelationshipTypeRegistry().getPersistence();
            var linkedEntity = getLoadedRef(link.sourceId);
            Ref source = link.sourceRef;
            if (source != null && source.isValid()) {
                // the linked entity may have unloaded since
                if (source.getStore() != link.sourceStore || (linkedEntity != null && linkedEntity != source)
                    || !link.sourceId.equals(keyOfRef(source))) {
                    return;
                }
                if (link.cascade) {
                    source.getStore().removeEntity(source, RemoveReason.REMOVE);
                } else {
                    assert persistence != null;
                    ((RelationshipPersistence) persistence).remove(link.type, source, identityOf(link.targetId));
                }
            } else if (link.sourceHolder != null && linkedEntity == null) {
                if (link.cascade) {
                    return;
                }
                assert persistence != null;
                ((RelationshipPersistence) persistence).removeFromHolder(
                    link.type, link.sourceHolder, identityOf(link.targetId));
            } else {
                // no entity and no holder to clean
                return;
            }
            removeRecord(cleanupPending, link.sourceId, link);
        }
        setSourceHolder(link, null);
        if (notification != null && !changedAssociation) {
            // a holder removal callback runs while the Store is processing
            // TODO: Remove this check when removal callbacks can supply a deferred command context.
            // Shared-source Store has no replacement state query and reserves isProcessing for legacy bridges.
            @SuppressWarnings("deprecation")
            boolean processing = link.sourceStore.isProcessing();
            if (processing) {
                executeOnSource(link.sourceStore, () -> notifyRemoval(link.sourceStore, notification));
            } else {
                notifyRemoval(link.sourceStore, notification);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void notifyRemoval(Store<?> store, RelationshipChangeSystem.ChangeEvent<?, ?> notification) {
        if (types.getTracker() != this
            || !notification.type.getRelationshipTypeRegistry().isRegistered((GenericRelationshipType) notification.type)) {
            return;
        }
        RelationshipChangeSystem.dispatch((Store) store, (RelationshipChangeSystem.ChangeEvent) notification);
    }

    private static boolean retains(RelationshipDescriptor<?, ?> descriptor) {
        return descriptor.isPersistent()
            || descriptor.getTransfer() == RelationshipTraits.Survival.RETAIN
            || descriptor.getTemporaryDeactivation() == RelationshipTraits.Survival.RETAIN;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean isRetainedOn(RelationshipDescriptor<?, ?> descriptor, UnloadReason reason) {
        return switch (reason) {
            // the links wait for onUnloadResolved
            case PENDING -> true;
            case TRANSFER -> descriptor.getTransfer() == RelationshipTraits.Survival.RETAIN;
            case DEACTIVATION ->
                descriptor.getTemporaryDeactivation() == RelationshipTraits.Survival.RETAIN;
        };
    }

    void restoreRegisteredTypes(RelationshipPersistence<ECS_TYPE> persistence) {
        for (var ref : index.getLoadedRefs()) {
            execute.accept(ref.getStore(), () -> {
                if (types.getTracker() == this && ref.isValid()) {
                    persistence.restore(ref);
                }
            });
        }
    }

    public void close() {
        types.closeTracker(this);
        synchronized (this) {
            index.clear();
            sources.clear();
            incoming.clear();
            pending.clear();
            cleanupPending.clear();
            holderSources.clear();
        }
    }

    @Nullable
    private synchronized Ref<ECS_TYPE> getLoadedRef(@Nullable Object key) {
        return key == null ? null : index.getRef(key);
    }

    // removing one link must keep the holder's other links reachable
    private static final class HeldSource {
        private final Object id;
        private int links;

        private HeldSource(Object id) {
            this.id = id;
        }
    }

    private record Pending(Object id, List<Link> links) { }

    /// The key of a linked entity whose identity names it only inside one Store. Two Stores never
    /// share an entry, because Stores compare by reference.
    private record ScopedIdentity(Store<?> store, Object identity) { }

    /// One link, filed in the source side's outgoing index and the target side's incoming index.
    private static final class Link {
        private final GenericRelationshipType<?, ?, ?> type;
        private final Object sourceId;
        private final Object targetId;
        private Store<?> sourceStore;
        @Nullable
        private Ref<?> sourceRef;
        @Nullable
        private Holder<?> sourceHolder;
        @Nullable
        private Ref<?> targetRef;
        @Nullable
        private Object data;
        private boolean resolved = true;
        private int pending;
        private boolean cascade;

        private Link(
            GenericRelationshipType<?, ?, ?> type,
            Object sourceId,
            Object targetId,
            Ref<?> sourceRef,
            @Nullable Ref<?> targetRef
        ) {
            this.type = type;
            this.sourceId = sourceId;
            this.targetId = targetId;
            this.sourceRef = sourceRef;
            this.sourceStore = sourceRef.getStore();
            this.targetRef = targetRef;
        }
    }
}
