/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.examples;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.blovien.relwind.RelationshipRules;
import dev.hytalemodding.blovien.relwind.RelationshipType;
import dev.hytalemodding.blovien.relwind.RelationshipTypeRegistry;
import dev.hytalemodding.blovien.relwind.examples.alliance.AllianceAlertSystem;
import dev.hytalemodding.blovien.relwind.examples.alliance.AllianceExample;
import dev.hytalemodding.blovien.relwind.examples.anchor.AnchorExample;
import dev.hytalemodding.blovien.relwind.examples.circuit.CircuitExample;
import dev.hytalemodding.blovien.relwind.examples.circuit.Powered;
import dev.hytalemodding.blovien.relwind.examples.circuit.PowerTickingSystem;
import dev.hytalemodding.blovien.relwind.examples.circuit.Signal;
import dev.hytalemodding.blovien.relwind.examples.circuit.Source;
import dev.hytalemodding.blovien.relwind.examples.circuit.WireTool;
import dev.hytalemodding.blovien.relwind.examples.circuit.WireToolSystem;
import dev.hytalemodding.blovien.relwind.examples.hierarchy.LastClaimed;
import dev.hytalemodding.blovien.relwind.examples.hierarchy.OwnershipExample;
import dev.hytalemodding.blovien.relwind.plugin.Relwind;

import javax.annotation.Nonnull;

public final class RelwindExamplePlugin extends JavaPlugin {
    private static RelwindExamplePlugin instance;

    private ComponentType<EntityStore, LastClaimed> lastClaimedComponentType;
    private ComponentType<ChunkStore, Powered> poweredComponentType;
    private ComponentType<ChunkStore, Source> sourceComponentType;
    private ComponentType<EntityStore, WireTool> wireToolComponentType;

    public static RelationshipType<ChunkStore, Signal> POWERS;

    public RelwindExamplePlugin(JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Nonnull
    public static RelwindExamplePlugin get() {
        return instance;
    }

    public ComponentType<EntityStore, LastClaimed> getLastClaimedComponentType() {
        return lastClaimedComponentType;
    }

    public ComponentType<ChunkStore, Powered> getPoweredComponentType() {
        return poweredComponentType;
    }

    public ComponentType<ChunkStore, Source> getSourceComponentType() {
        return sourceComponentType;
    }

    public ComponentType<EntityStore, WireTool> getWireToolComponentType() {
        return wireToolComponentType;
    }

    @Override
    protected void setup() {
        // Usual Hytale ECS stuff
        {
            var entityComponentRegistry = getEntityStoreRegistry();
            var chunkComponentRegistry = getChunkStoreRegistry();

            lastClaimedComponentType = entityComponentRegistry.registerComponent(LastClaimed.class, LastClaimed::new);
            poweredComponentType = chunkComponentRegistry.registerComponent(Powered.class, Powered::new);
            sourceComponentType = chunkComponentRegistry.registerComponent(Source.class, Source::new);
            wireToolComponentType = entityComponentRegistry.registerComponent(WireTool.class, WireTool::new);
        }

        RelationshipTypeRegistry<EntityStore> entityRelationshipRegistry;
        RelationshipTypeRegistry<ChunkStore> chunkRelationshipRegistry;

        // Relwind stores relationship types inside RelationshipTypeRegistry(s) for each Store type, the Store type
        // is determined by the Store type (`ECS_TYPE`) of the source entity.
        {
            var relwind = Relwind.get();
            entityRelationshipRegistry = relwind.getEntityRelationshipTypeRegistry();
            chunkRelationshipRegistry = relwind.getChunkRelationshipTypeRegistry();
        }

        // This is the simplest type of relationship. `ownedBy` is a relationship that has a source entity of `EntityStore`
        // which doesn't contain data (`Void`)
        RelationshipType<EntityStore, Void> ownedBy;

        ownedBy = entityRelationshipRegistry.registerRelationship(
                // `Void` relationship types can be serialized by giving them a persistent name
                OwnershipExample.OWNED_BY,
                // NOTE: there are various RelationshipRules that you can explore, for convention you could create a common
                // static RULES field for sharing rules to other common relationships
                OwnershipExample.RULES
        );
        var ownership = new OwnershipExample(ownedBy);

        var alliedWith = entityRelationshipRegistry.registerRelationship(AllianceExample.RULES);
        var alliance = new AllianceExample(alliedWith);
        getEntityStoreRegistry().registerSystem(new AllianceAlertSystem(alliance));

        POWERS = chunkRelationshipRegistry.registerRelationship(Signal.class, RelationshipRules.multiple());
        var circuit = new CircuitExample();
        getChunkStoreRegistry().registerSystem(new PowerTickingSystem());
        getEntityStoreRegistry().registerSystem(new WireToolSystem(circuit));

        var anchoredTo = entityRelationshipRegistry.registerRelationship(
            AnchorExample.ANCHORED_TO,
            // An interesting case of Relwind is when relationships are between `ECS_TYPE`(s), by mentioning the other
            // store here (or a custom store if needed), you defined a relationship between two entities in different stores
            // NOTE: for the peculiar case of `ChunkStore` this is mostly for EntityStore entity to ChunkStore BlockEntity
            chunkRelationshipRegistry,
            AnchorExample.RULES
        );
        var anchor = new AnchorExample(anchoredTo);

        getCommandRegistry().registerCommand(new RelwindExamplesCommand(ownership, alliance, circuit, anchor));
    }
}
