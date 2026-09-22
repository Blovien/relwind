/*
 * Copyright (C) 2026 Relwind contributors
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License, version 3.0.
 */
package dev.hytalemodding.blovien.relwind;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.IComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.ISystem;
import com.hypixel.hytale.component.system.QuerySystem;
import com.hypixel.hytale.component.system.RefSystem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/// Holds the relationship types registered on one ComponentRegistry, and installs the tracker
/// and the persistence they use.
/// Class-based registration accepts any data class. Per-link values must be immutable, including
/// reachable state, because storage copies share them; see {@link GenericRelationshipType}.
/// Data component registrations retain the native Component.clone contract.
public final class RelationshipTypeRegistry<ECS_TYPE> {
    private static final ThreadLocal<Boolean> RELATIONSHIP_SYSTEM_CALLBACK_ACTIVE = ThreadLocal.withInitial(() -> false);
    private static final Object REGISTRY_ORDERING = new Object();

    private final ComponentRegistry<ECS_TYPE> componentRegistry;
    private final IComponentRegistry<ECS_TYPE> registrar;
    private final RelationshipAccessSystem<ECS_TYPE> access;
    private final RelationshipDeletionSystem<ECS_TYPE> removal;
    /// Named types whose sources live here, keyed by id, wherever their targets live.
    private final Map<String, GenericRelationshipType<ECS_TYPE, ?, ?>> types = new HashMap<>();
    /// Runtime types whose sources live here. They have no id to key them by.
    private final Set<GenericRelationshipType<ECS_TYPE, ?, ?>> runtimeTypes =
        Collections.newSetFromMap(new IdentityHashMap<>());
    /// Types whose targets live here, including those another registry registered against this one.
    private final List<GenericRelationshipType<?, ECS_TYPE, ?>> incomingTypes = new CopyOnWriteArrayList<>();
    @Nullable
    private RelationshipPersistence<ECS_TYPE> persistence;
    /// Declare it before the first registration.
    private boolean withoutPersistence;
    @Nullable
    private RefSystem<ECS_TYPE> transitions;
    @Nullable
    private RelationshipTracker<ECS_TYPE, ?> transitionsTracker;
    private boolean registryChangeActive;
    private boolean closed;

    /// For an application that owns the ComponentRegistry and registers on it directly.
    public RelationshipTypeRegistry(ComponentRegistry<ECS_TYPE> componentRegistry) {
        this(componentRegistry, componentRegistry);
    }

    /// A plugin passes its ComponentRegistryProxy as `registrar`. Reads and unregistration still go
    /// to `componentRegistry`, because IComponentRegistry carries only the register methods.
    public RelationshipTypeRegistry(ComponentRegistry<ECS_TYPE> componentRegistry, IComponentRegistry<ECS_TYPE> registrar) {
        this.componentRegistry = Objects.requireNonNull(componentRegistry, "componentRegistry");
        this.registrar = Objects.requireNonNull(registrar, "registrar");
        access = RelationshipAccessSystem.install(componentRegistry, registrar);
        access.hold(this);
        removal = RelationshipDeletionSystem.install(componentRegistry, registrar, this);
    }

    @Nonnull
    ComponentRegistry<ECS_TYPE> getComponentRegistry() {
        return componentRegistry;
    }

    @Nonnull
    IComponentRegistry<ECS_TYPE> getRegistrar() {
        return registrar;
    }

    @Nonnull
    PersistenceIdentity<ECS_TYPE, ?> getPersistenceIdentity() {
        return Objects.requireNonNull(getTracker(), "Relationship tracker is not installed").getPersistenceIdentity();
    }

    /// Installs the tracker for this Store kind. `runtime.execute` must run its action on that
    /// Store's thread, outside ECS processing.
    @Nonnull
    public <ID> RelationshipTracker<ECS_TYPE, ID> installTracker(
        PersistenceIdentity<ECS_TYPE, ID> identity,
        StoreRuntime<ECS_TYPE> runtime
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(runtime, "runtime");
        // getRegisteredTypes locks each sibling registry
        access.validateBridgeSources(runtime);
        synchronized (this) {
            if (closed) throw new IllegalStateException("Relationship types are closed");
            var tracker = new RelationshipTracker<>(this, identity, runtime);
            access.installTracker(tracker);
            registerTransitions(runtime, tracker);
            return tracker;
        }
    }

    private <ID> void registerTransitions(StoreRuntime<ECS_TYPE> runtime, RelationshipTracker<ECS_TYPE, ID> tracker) {
        // a second tracker would leave the previous transition system running behind it
        unregisterTransitions();
        var system = runtime.getTransitionSystem(tracker);
        registrar.registerSystem(system);
        this.transitions = system;
        this.transitionsTracker = tracker;
    }

    private void unregisterTransitions() {
        var system = transitions;
        if (system == null) {
            return;
        }
        transitions = null;
        transitionsTracker = null;
        componentRegistry.unregisterSystem(getSystemClass(system));
    }

    void declareWithoutPersistence() {
        withoutPersistence = true;
    }

    /// Installs persistence on the tracker this registry installed, whose identity type and codec
    /// its saved records use.
    @Nonnull
    public synchronized <ID> RelationshipPersistence<ECS_TYPE> installPersistence(RelationshipTracker<ECS_TYPE, ID> tracker) {
        if (withoutPersistence) {
            throw new IllegalStateException(
                "This relationship type registry is declared without persistence and saves no links");
        }
        var installed = checkPersistenceTracker(tracker);
        persistence = new RelationshipPersistence<>(this, installed);
        return persistence;
    }

    @Nonnull
    private RelationshipTracker<ECS_TYPE, Object> checkPersistenceTracker(RelationshipTracker<ECS_TYPE, ?> tracker) {
        if (closed) throw new IllegalStateException("Relationship types are closed");
        if (persistence != null) {
            throw new IllegalStateException("Relationship persistence is already installed");
        }
        Objects.requireNonNull(tracker, "tracker");
        if (getTracker() == null) {
            throw new IllegalStateException("Relationship persistence requires an installed relationship tracker");
        }
        if (tracker != getTracker()) {
            throw new IllegalStateException("Relationship persistence requires the tracker this registry installed");
        }
        @SuppressWarnings("unchecked")
        var installed = (RelationshipTracker<ECS_TYPE, Object>) tracker;
        return installed;
    }

    @Nullable
    public RelationshipTracker<ECS_TYPE, ?> getTracker() {
        return access.getCurrentTracker();
    }

    @Nullable
    public synchronized RelationshipPersistence<ECS_TYPE> getPersistence() {
        return persistence;
    }

    /// The system `StoreRuntime.getTransitionSystem` built.
    @Nullable
    synchronized RefSystem<ECS_TYPE> getTransitionSystem() {
        return transitions;
    }

    /// The named type registered under this id, or null. A null id matches nothing.
    @Nullable
    synchronized GenericRelationshipType<ECS_TYPE, ?, ?> getRegisteredType(@Nullable String id) {
        if (id == null) {
            return null;
        }
        return types.get(id);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    synchronized boolean isRegistered(GenericRelationshipType<ECS_TYPE, ?, ?> type) {
        var id = type.getDescriptor().id();
        if (id != null) {
            return types.get(id) == type;
        }
        return runtimeTypes.contains(type);
    }

    void closeTracker(RelationshipTracker<ECS_TYPE, ?> tracker) {
        access.closeTracker(componentRegistry, tracker);
        if (transitionsTracker == tracker) unregisterTransitions();
    }

    /// A type registered with an id saves its links. A type without an id keeps them in memory.
    /// An id must be namespaced, in the `Group:Name` form.
    @Nonnull
    public RelationshipType<ECS_TYPE, Void> registerRelationship(RelationshipRules rules) {
        return registerSameStore(null, Void.class, null, null, null, rules);
    }

    @Nonnull
    public <LINK_DATA> RelationshipType<ECS_TYPE, LINK_DATA> registerRelationship(
        Class<LINK_DATA> linkDataClass,
        RelationshipRules rules
    ) {
        Objects.requireNonNull(linkDataClass, "linkDataClass");
        return registerSameStore(null, linkDataClass, null, null, null, rules);
    }

    /// The rules must declare a single target, because Hytale keeps one component of a type per entity.
    @Nonnull
    public <LINK_DATA extends Component<ECS_TYPE>> RelationshipType<ECS_TYPE, LINK_DATA>
        registerRelationship(
        ComponentType<ECS_TYPE, LINK_DATA> dataComponentType,
        RelationshipDataObserver<ECS_TYPE, LINK_DATA> observer,
        RelationshipRules rules
    ) {
        Objects.requireNonNull(dataComponentType, "dataComponentType");
        Objects.requireNonNull(observer, "observer");
        return registerSameStore(null, getDataClass(dataComponentType), dataComponentType, observer, null, rules);
    }

    @Nonnull
    public RelationshipType<ECS_TYPE, Void> registerRelationship(String id, RelationshipRules rules) {
        Objects.requireNonNull(id, "id");
        return registerSameStore(id, Void.class, null, null, null, rules);
    }

    /// A null codec leaves the saved links alone. They are not restored and a link change throws.
    @Nonnull
    public <LINK_DATA> RelationshipType<ECS_TYPE, LINK_DATA> registerRelationship(
        String id,
        Class<LINK_DATA> linkDataClass,
        @Nullable Codec<LINK_DATA> codec,
        RelationshipRules rules
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(linkDataClass, "linkDataClass");
        return registerSameStore(id, linkDataClass, null, null, codec, rules);
    }

    /// Register `dataComponentType` with a codec first. Without one this call fails.
    @Nonnull
    public <LINK_DATA extends Component<ECS_TYPE>> RelationshipType<ECS_TYPE, LINK_DATA>
        registerRelationship(
        String id,
        ComponentType<ECS_TYPE, LINK_DATA> dataComponentType,
        RelationshipDataObserver<ECS_TYPE, LINK_DATA> observer,
        RelationshipRules rules
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dataComponentType, "dataComponentType");
        Objects.requireNonNull(observer, "observer");
        return registerSameStore(id, getDataClass(dataComponentType), dataComponentType, observer, null, rules);
    }

    /// `targetRegistry` must sit on a different ComponentRegistry than this one.
    @Nonnull
    public <TARGET> GenericRelationshipType<ECS_TYPE, TARGET, Void> registerRelationship(
        RelationshipTypeRegistry<TARGET> targetRegistry,
        RelationshipRules rules
    ) {
        return registerTargeting(null, targetRegistry, Void.class, null, rules);
    }

    @Nonnull
    public <TARGET, LINK_DATA> GenericRelationshipType<ECS_TYPE, TARGET, LINK_DATA> registerRelationship(
        RelationshipTypeRegistry<TARGET> targetRegistry,
        Class<LINK_DATA> linkDataClass,
        RelationshipRules rules
    ) {
        Objects.requireNonNull(linkDataClass, "linkDataClass");
        return registerTargeting(null, targetRegistry, linkDataClass, null, rules);
    }

    @Nonnull
    public <TARGET> GenericRelationshipType<ECS_TYPE, TARGET, Void> registerRelationship(
        String id,
        RelationshipTypeRegistry<TARGET> targetRegistry,
        RelationshipRules rules
    ) {
        Objects.requireNonNull(id, "id");
        return registerTargeting(id, targetRegistry, Void.class, null, rules);
    }

    @Nonnull
    public <TARGET, LINK_DATA> GenericRelationshipType<ECS_TYPE, TARGET, LINK_DATA> registerRelationship(
        String id,
        RelationshipTypeRegistry<TARGET> targetRegistry,
        Class<LINK_DATA> linkDataClass,
        @Nullable Codec<LINK_DATA> codec,
        RelationshipRules rules
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(linkDataClass, "linkDataClass");
        return registerTargeting(id, targetRegistry, linkDataClass, codec, rules);
    }

    @Nonnull
    private <LINK_DATA> RelationshipType<ECS_TYPE, LINK_DATA> registerSameStore(
        @Nullable String id,
        Class<LINK_DATA> linkDataClass,
        @Nullable ComponentType<ECS_TYPE, ? extends Component<ECS_TYPE>> dataComponentType,
        @Nullable RelationshipDataObserver<ECS_TYPE, ? extends Component<ECS_TYPE>> dataObserver,
        @Nullable Codec<LINK_DATA> codec,
        RelationshipRules rules
    ) {
        var descriptor = new RelationshipDescriptor<ECS_TYPE, LINK_DATA>(
            id, null, linkDataClass, dataComponentType, dataObserver, codec, Objects.requireNonNull(rules, "rules"));
        return (RelationshipType<ECS_TYPE, LINK_DATA>) registerDescriptor(descriptor);
    }

    @Nonnull
    private <TARGET, LINK_DATA> GenericRelationshipType<ECS_TYPE, TARGET, LINK_DATA> registerTargeting(
        @Nullable String id,
        RelationshipTypeRegistry<TARGET> targetRegistry,
        Class<LINK_DATA> linkDataClass,
        @Nullable Codec<LINK_DATA> codec,
        RelationshipRules rules
    ) {
        Objects.requireNonNull(targetRegistry, "targetRegistry");
        var descriptor = new RelationshipDescriptor<>(
            id, targetRegistry, linkDataClass, null, null, codec, Objects.requireNonNull(rules, "rules"));
        return registerDescriptor(descriptor);
    }

    @Nonnull @SuppressWarnings("unchecked")
    private static <ECS_TYPE, LINK_DATA extends Component<ECS_TYPE>> Class<LINK_DATA> getDataClass(
        ComponentType<ECS_TYPE, LINK_DATA> dataComponentType
    ) {
        return (Class<LINK_DATA>) dataComponentType.getTypeClass();
    }

    @Nonnull
    private <TARGET, LINK_DATA> GenericRelationshipType<ECS_TYPE, TARGET, LINK_DATA> registerDescriptor(
        RelationshipDescriptor<TARGET, LINK_DATA> descriptor
    ) {
        var targetTypes = getTargetRegistry(descriptor);
        ComponentType<ECS_TYPE, Component<ECS_TYPE>> dataComponentType = descriptor.getDataComponentType();
        var codec = descriptor.codec();
        if (dataComponentType != null) {
            if (descriptor.getCardinality() != RelationshipRules.Cardinality.SINGLE_TARGET) {
                var label = descriptor.id() == null
                    ? "runtime relationship type" : "Relationship type '" + descriptor.id() + "'";
                throw new IllegalArgumentException(
                    label + " must declare a single target to carry a data component");
            }
            dataComponentType.validateRegistry(componentRegistry);
            dataComponentType.validate();
            codec = getComponentCodec(dataComponentType);
        }
        validatePersistence(descriptor, codec, dataComponentType != null);
        if (targetTypes != this) {
            var tracker = getTracker();
            if (tracker != null) validateCascadingSource(descriptor, tracker.getStoreRuntime());
        }
        rejectRelationshipSystemCallback();
        var registeredCodec = codec;
        return executeWithBothHeld(targetTypes,
            () -> registerHeld(descriptor, targetTypes, dataComponentType, registeredCodec));
    }

    @Nonnull @SuppressWarnings("unchecked")
    private <TARGET> RelationshipTypeRegistry<TARGET> getTargetRegistry(RelationshipDescriptor<TARGET, ?> descriptor) {
        var targetTypes = descriptor.targetTypes();
        if (targetTypes == null) {
            return (RelationshipTypeRegistry<TARGET>) this;
        }
        if (targetTypes.getComponentRegistry() == componentRegistry) {
            throw new IllegalArgumentException("Relationship type '" + descriptor.id()
                + "' must target a different registry; register a same store type instead");
        }
        return targetTypes;
    }

    static void validateCascadingSource(RelationshipDescriptor<?, ?> descriptor, StoreRuntime<?> runtime) {
        if (descriptor.getTargetDeletion() == RelationshipRules.TargetDeletion.CASCADE_SOURCE
            && !runtime.isDeletionSupported()) {
            // TODO: allow this once a block entity source can delete its entity targets
            throw new IllegalArgumentException("Relationship type '" + descriptor.id()
                + "' cannot cascade source deletion because its source store never deletes a linked entity");
        }
    }

    /// A registration running the other way waits here.
    private <R> R executeWithBothHeld(RelationshipTypeRegistry<?> targetTypes, Supplier<R> change) {
        if (targetTypes == this) {
            synchronized (this) {
                return change.get();
            }
        }
        int sourceOrder = System.identityHashCode(this);
        int targetOrder = System.identityHashCode(targetTypes);
        if (sourceOrder == targetOrder) {
            synchronized (REGISTRY_ORDERING) {
                return executeWithHeldInOrder(this, targetTypes, change);
            }
        }
        return sourceOrder < targetOrder
            ? executeWithHeldInOrder(this, targetTypes, change)
            : executeWithHeldInOrder(targetTypes, this, change);
    }

    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    private static <R> R executeWithHeldInOrder(
        RelationshipTypeRegistry<?> first,
        RelationshipTypeRegistry<?> second,
        Supplier<R> change
    ) {
        synchronized (first) {
            synchronized (second) {
                return change.get();
            }
        }
    }

    @Nonnull
    private <TARGET, LINK_DATA> GenericRelationshipType<ECS_TYPE, TARGET, LINK_DATA> registerHeld(
        RelationshipDescriptor<TARGET, LINK_DATA> descriptor,
        RelationshipTypeRegistry<TARGET> targetTypes,
        @Nullable ComponentType<ECS_TYPE, Component<ECS_TYPE>> dataComponentType,
        @Nullable Codec<LINK_DATA> codec
    ) {
        if (closed) throw new IllegalStateException("Relationship types are closed");
        if (targetTypes.closed) throw new IllegalStateException("Target relationship types are closed");
        if (descriptor.id() != null && types.containsKey(descriptor.id())) {
            throw new IllegalArgumentException(
                "Relationship type '" + descriptor.id() + "' is already registered"
            );
        }

        beginRegistryChange();
        try {
            // a refusal here leaves nothing behind
            if (dataComponentType != null) {
                rejectSharedDataComponent(descriptor, dataComponentType);
            }
            @SuppressWarnings({"unchecked", "rawtypes"})
            ComponentType<ECS_TYPE, OutgoingLink<ECS_TYPE, TARGET>> sourceType =
                registrar.registerComponent((Class) OutgoingLink.class, OutgoingLink::new);
            @SuppressWarnings({"unchecked", "rawtypes"})
            ComponentType<TARGET, IncomingLinks<ECS_TYPE, TARGET>> incomingType =
                targetTypes.registrar.registerComponent((Class) IncomingLinks.class, IncomingLinks::new);
            var type = newType(descriptor, sourceType, incomingType, codec);
            // a refused observer leaves the registry as it was
            if (dataComponentType != null) {
                try {
                    observeLinkData(type, dataComponentType, Objects.requireNonNull(
                        descriptor.getDataObserver(), "A data component type is described with its observer"));
                } catch (RuntimeException failure) {
                    componentRegistry.unregisterComponent(sourceType);
                    targetTypes.componentRegistry.unregisterComponent(incomingType);
                    throw failure;
                }
            }
            if (descriptor.id() != null) {
                types.put(descriptor.id(), type);
            } else {
                runtimeTypes.add(type);
            }
            targetTypes.incomingTypes.add(type);
            var tracker = getTracker();
            if (persistence != null && tracker != null && descriptor.isPersistent()) {
                tracker.restoreRegisteredTypes(persistence);
            }
            return type;
        } finally {
            registryChangeActive = false;
        }
    }

    @Nonnull @SuppressWarnings("unchecked")
    private <TARGET, LINK_DATA> GenericRelationshipType<ECS_TYPE, TARGET, LINK_DATA> newType(
        RelationshipDescriptor<TARGET, LINK_DATA> descriptor,
        ComponentType<ECS_TYPE, OutgoingLink<ECS_TYPE, TARGET>> sourceType,
        ComponentType<TARGET, IncomingLinks<ECS_TYPE, TARGET>> incomingType,
        @Nullable Codec<LINK_DATA> codec
    ) {
        if (descriptor.targetTypes() == null) {
            return (GenericRelationshipType<ECS_TYPE, TARGET, LINK_DATA>) new RelationshipType<>(
                this, componentRegistry, (RelationshipDescriptor<ECS_TYPE, LINK_DATA>) descriptor,
                (ComponentType<ECS_TYPE, OutgoingLink<ECS_TYPE, ECS_TYPE>>) (ComponentType<?, ?>) sourceType,
                (ComponentType<ECS_TYPE, IncomingLinks<ECS_TYPE, ECS_TYPE>>) (ComponentType<?, ?>) incomingType,
                codec);
        }
        return new GenericRelationshipType<>(
            this, componentRegistry, descriptor, sourceType, incomingType, codec);
    }

    /// A shut down ComponentRegistry has already released its components.
    private static <TARGET> void unregisterIncoming(GenericRelationshipType<?, TARGET, ?> type) {
        var targetTypes = type.getTargetRelationshipTypeRegistry();
        targetTypes.incomingTypes.remove(type);
        var targetRegistry = targetTypes.getComponentRegistry();
        if (targetRegistry.isShutdown()) {
            return;
        }
        var incomingType = type.getIncomingType();
        incomingType.validateRegistry(targetRegistry);
        incomingType.validate();
        targetRegistry.unregisterComponent(incomingType);
    }

    /// Each saved payload of multi-target link data carries its own version.
    private void validatePersistence(RelationshipDescriptor<?, ?> descriptor, @Nullable Codec<?> codec, boolean dataComponent) {
        if (!descriptor.isPersistent()) {
            return;
        }
        if (withoutPersistence) {
            throw new IllegalArgumentException("Relationship type '" + descriptor.id()
                + "' is persistent, but this relationship type registry is declared without"
                + " persistence and saves no links; register a runtime type instead");
        }
        if (descriptor.id() == null || !isNamespaced(descriptor.id())) {
            throw new IllegalArgumentException("Persistent relationship type id must be namespaced");
        }
        if (descriptor.linkDataClass() == Void.class) {
            if (codec != null) throw new IllegalArgumentException("A payload codec requires a persistent type with link data");
        } else if (dataComponent) {
            if (codec == null) {
                throw new IllegalArgumentException("Persistent relationship type '" + descriptor.id()
                    + "' requires a codec on its data component type");
            }
        } else if (descriptor.getCardinality() == RelationshipRules.Cardinality.MULTIPLE_TARGETS
            && codec != null && !isVersionedBuilderCodec(codec)) {
            throw new IllegalArgumentException(
                "Persistent relationship type '" + descriptor.id() + "' requires a versioned BuilderCodec");
        }
    }

    private static boolean isNamespaced(@Nullable String id) {
        if (id == null) {
            return false;
        }
        int separator = id.indexOf(':');
        return separator > 0 && separator < id.length() - 1;
    }

    private static boolean isVersionedBuilderCodec(Codec<?> codec) {
        if (!(codec instanceof BuilderCodec<?> builder)) return false;
        try {
            var field = BuilderCodec.class.getDeclaredField("versioned");
            field.setAccessible(true);
            return field.getBoolean(builder);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not inspect BuilderCodec versioning", failure);
        }
    }

    @Nullable @SuppressWarnings("unchecked")
    private <LINK_DATA> Codec<LINK_DATA> getComponentCodec(ComponentType<ECS_TYPE, Component<ECS_TYPE>> dataComponentType) {
        var lock = componentRegistry.getDataUpdateLock().readLock();
        lock.lock();
        try {
            return (Codec<LINK_DATA>) componentRegistry._internal_getData().getComponentCodec(dataComponentType);
        } finally {
            lock.unlock();
        }
    }

    /// A data component type belongs to one relationship type, whatever class observes it. See ADR 0001.
    private void rejectSharedDataComponent(
        RelationshipDescriptor<?, ?> descriptor,
        ComponentType<ECS_TYPE, Component<ECS_TYPE>> dataComponentType
    ) {
        var observed = getTypeCarryingDataIn(dataComponentType);
        if (observed == null) return;
        throw new IllegalArgumentException("Relationship type '" + observed.getDescriptor().id()
            + "' already carries its link data in that component, so '" + descriptor.id() + "' cannot");
    }

    /// Hytale keeps one system per class. Two relationship types cannot share an observer class.
    private void observeLinkData(
        GenericRelationshipType<ECS_TYPE, ?, ?> type,
        ComponentType<ECS_TYPE, Component<ECS_TYPE>> dataComponentType,
        RelationshipDataObserver<ECS_TYPE, Component<ECS_TYPE>> observer
    ) {
        // registerSystem reads the component type off the observer
        observer.observe(type, dataComponentType);
        try {
            registrar.registerSystem(observer);
        } catch (RuntimeException failure) {
            observer.release();
            throw failure;
        }
    }

    /// A registry holds few types and a registration is rare.
    @Nullable
    private GenericRelationshipType<ECS_TYPE, ?, ?> getTypeCarryingDataIn(
        ComponentType<ECS_TYPE, Component<ECS_TYPE>> dataComponentType
    ) {
        for (var registered : getRegisteredTypes()) {
            if (registered.getDescriptor().rawDataComponentType() == dataComponentType) return registered;
        }
        return null;
    }

    /// A later registration can use this observer class again.
    private void releaseLinkData(GenericRelationshipType<ECS_TYPE, ?, ?> type) {
        RelationshipDataObserver<ECS_TYPE, Component<ECS_TYPE>> observer = type.getDescriptor().getDataObserver();
        if (observer == null) return;
        componentRegistry.unregisterSystem(getSystemClass(observer));
        observer.release();
    }

    /// Releases the type's storage on both registries. A type that is not registered is rejected.
    public void unregisterRelationship(GenericRelationshipType<ECS_TYPE, ?, ?> type) {
        Objects.requireNonNull(type, "type");
        rejectRelationshipSystemCallback();
        executeWithBothHeld(type.getTargetRelationshipTypeRegistry(), () -> {
            unregisterHeld(type);
            return null;
        });
    }

    private void unregisterHeld(GenericRelationshipType<ECS_TYPE, ?, ?> type) {
        if (type.getDescriptor().id() != null) {
            if (types.get(type.getDescriptor().id()) != type) {
                throw new IllegalArgumentException(
                    "Relationship type '" + type.getDescriptor().id() + "' is not registered"
                );
            }
        } else if (!runtimeTypes.contains(type)) {
            throw new IllegalArgumentException("Runtime relationship type is not registered");
        }

        beginRegistryChange();
        try {
            type.getSourceType().validateRegistry(componentRegistry);
            type.getSourceType().validate();
            releaseLinkData(type);
            if (persistence != null) persistence.onTypeUnregistering(type);
            unregisterDependentSystems(type);
            componentRegistry.unregisterComponent(type.getSourceType());
            unregisterIncoming(type);
            var tracker = getTracker();
            if (tracker != null) {
                tracker.onTypeUnregistered(type);
            }
            var targetTracker = type.getTargetRelationshipTypeRegistry().getTracker();
            if (targetTracker != null && targetTracker != tracker) {
                targetTracker.onTypeUnregistered(type);
            }
            if (type.getDescriptor().id() != null) {
                types.remove(type.getDescriptor().id());
            } else {
                runtimeTypes.remove(type);
            }
        } finally {
            registryChangeActive = false;
        }
    }

    /// Releases the persistence, the tracker, the types and the systems this registry installed.
    public void close() {
        rejectRelationshipSystemCallback();
        // unregistering one of these locks its source registry
        for (var incoming : List.copyOf(incomingTypes)) {
            releaseFromSourceRegistry(incoming);
        }
        synchronized (this) {
            closeHeld();
        }
    }

    /// Closing either registry releases the type from both.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void releaseFromSourceRegistry(GenericRelationshipType<?, ECS_TYPE, ?> type) {
        var sourceTypes = type.getRelationshipTypeRegistry();
        if (sourceTypes == this) {
            return;
        }
        ((RelationshipTypeRegistry) sourceTypes).unregisterRelationship(type);
    }

    private synchronized void closeHeld() {
        if (closed) return;
        // Hytale writes each live payload out as it removes the component
        if (persistence != null) {
            persistence.close();
            persistence = null;
        }
        var tracker = getTracker();
        if (tracker != null) tracker.close();
        for (var type : getRegisteredTypes()) unregisterHeld(type);
        RelationshipChangeSystem.unregisterUnusedEventType(componentRegistry);
        unregisterTransitions();
        removal.release(componentRegistry, this);
        access.release(componentRegistry, this);
        closed = true;
    }

    private static void rejectRelationshipSystemCallback() {
        if (RELATIONSHIP_SYSTEM_CALLBACK_ACTIVE.get()) {
            throw new IllegalStateException(
                "Relationship type registration cannot change from a relationship system callback"
            );
        }
    }

    private void beginRegistryChange() {
        if (registryChangeActive) {
            throw new IllegalStateException("Relationship type registration is already changing");
        }
        registryChangeActive = true;
    }

    static void invokeRelationshipSystemCallback(Runnable callback) {
        boolean previous = RELATIONSHIP_SYSTEM_CALLBACK_ACTIVE.get();
        RELATIONSHIP_SYSTEM_CALLBACK_ACTIVE.set(true);
        try {
            callback.run();
        } finally {
            if (previous) {
                RELATIONSHIP_SYSTEM_CALLBACK_ACTIVE.set(true);
            } else {
                RELATIONSHIP_SYSTEM_CALLBACK_ACTIVE.remove();
            }
        }
    }

    private void unregisterDependentSystems(GenericRelationshipType<ECS_TYPE, ?, ?> type) {
        var dependentSystems = new ArrayList<RegisteredRelationshipSystem<ECS_TYPE>>();
        for (var registeredSystem : access.getRelationshipSystems().values()) {
            var system = registeredSystem.system;
            if (!componentRegistry.hasSystem(system)) {
                continue;
            }
            if (system instanceof RelationshipChangeSystem<?, ?> observer) {
                if (observer.getRelationshipType() == type) dependentSystems.add(registeredSystem);
                continue;
            }
            var query = getSystemQuery(system);
            if (query == null || !query.requiresComponentType(type.getSourceType())
                && !requiresIncoming(query, type)) {
                continue;
            }
            dependentSystems.add(registeredSystem);
        }
        for (var registeredSystem : dependentSystems) {
            registeredSystem.unregister(componentRegistry);
        }
        RelationshipChangeSystem.unregisterUnusedEventType(componentRegistry);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean requiresIncoming(Query<ECS_TYPE> query, GenericRelationshipType<ECS_TYPE, ?, ?> type) {
        return type.getTargetRelationshipTypeRegistry() == this && query.requiresComponentType((ComponentType) type.getIncomingType());
    }

    void onRelationshipSystemRegistered(RegisteredRelationshipSystem<ECS_TYPE> registration) {
        access.getRelationshipSystems().put(registration.system.getClass(), registration);
    }

    void onRelationshipSystemUnregistered(ISystem<ECS_TYPE> system) {
        access.getRelationshipSystems().computeIfPresent(
            system.getClass(),
            (ignored, registered) -> registered.system == system ? null : registered
        );
    }

    @Nullable
    private static <ECS_TYPE> Query<ECS_TYPE> getSystemQuery(ISystem<ECS_TYPE> system) {
        return ((QuerySystem<ECS_TYPE>) system).getQuery();
    }

    @Nonnull @SuppressWarnings("unchecked")
    private static <ECS_TYPE> Class<? extends ISystem<ECS_TYPE>> getSystemClass(ISystem<ECS_TYPE> system) {
        return (Class<? extends ISystem<ECS_TYPE>>) system.getClass();
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) failure;
    }

    @Nonnull
    synchronized ArrayList<GenericRelationshipType<ECS_TYPE, ?, ?>> getRegisteredTypes() {
        var registered = new ArrayList<>(types.values());
        registered.addAll(runtimeTypes);
        return registered;
    }

    /// Leaves out the types sourced here, because getRegisteredTypes already lists those.
    @Nonnull
    List<GenericRelationshipType<?, ECS_TYPE, ?>> getIncomingFromOtherRegistries() {
        var incoming = new ArrayList<GenericRelationshipType<?, ECS_TYPE, ?>>();
        for (var type : incomingTypes) {
            if (type.getRelationshipTypeRegistry() != this) {
                incoming.add(type);
            }
        }
        return incoming;
    }

    /// Finishes native removal before reporting a plugin's unregistration callback failure.
    static final class RegisteredRelationshipSystem<ECS_TYPE> {
        private final ISystem<ECS_TYPE> system;
        private boolean unregistering;
        @Nullable
        private Throwable callbackFailure;

        RegisteredRelationshipSystem(ISystem<ECS_TYPE> system) {
            this.system = system;
        }

        void invokeUnregistrationCallback(Runnable callback) {
            if (!unregistering) {
                callback.run();
                return;
            }
            try {
                callback.run();
            } catch (RuntimeException | Error failure) {
                callbackFailure = failure;
            }
        }

        void unregister(ComponentRegistry<ECS_TYPE> registry) {
            if (unregistering) {
                throw new IllegalStateException("Relationship system unregistration is already active");
            }
            unregistering = true;
            try {
                try {
                    registry.unregisterSystem(getSystemClass(system));
                } catch (RuntimeException | Error nativeFailure) {
                    if (callbackFailure != null) nativeFailure.addSuppressed(callbackFailure);
                    throw nativeFailure;
                }
                if (callbackFailure != null) throwUnchecked(callbackFailure);
            } finally {
                unregistering = false;
                callbackFailure = null;
            }
        }
    }
}
