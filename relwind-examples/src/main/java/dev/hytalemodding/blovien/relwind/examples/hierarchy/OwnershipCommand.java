/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.examples.hierarchy;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import javax.annotation.Nullable;

/// The player-facing half of [OwnershipExample], reached as `/relwind own`. Each subcommand acts
/// on the creature the player is looking at.
public final class OwnershipCommand extends AbstractPlayerCommand {
    private final OwnershipExample ownership;

    public OwnershipCommand(OwnershipExample ownership) {
        super("own", "Claim creatures and nest them into a tree you own");
        this.ownership = ownership;
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        addSubCommand(new Claim());
        addSubCommand(new Nest());
        addSubCommand(new Release());
        addSubCommand(new ListOwned());
        addSubCommand(new Dismiss());
    }

    /// `/relwind own` on its own shows the same listing as `/relwind own list`.
    @Override
    protected void execute(
        CommandContext context,
        Store<EntityStore> store,
        Ref<EntityStore> source,
        PlayerRef player,
        World world
    ) {
        sendListing(context, store, source);
    }

    private void sendListing(CommandContext context, Store<EntityStore> store, Ref<EntityStore> owner) {
        var listing = ownership.getListing(owner);
        if (listing.owned().isEmpty()) {
            context.sendMessage(Message.raw("You own no creatures. Look at one and use /relwind own claim."));
            return;
        }
        context.sendMessage(Message.raw("You own " + getCreatures(listing.owned().size()) + ":"));
        for (var owned : listing.owned()) {
            context.sendMessage(Message.raw("  depth " + owned.depth() + ": ").insert(getCreatureName(store, owned.creature())));
        }
        if (listing.truncated()) {
            context.sendMessage(Message.raw(
                "The tree runs deeper than " + OwnershipExample.LISTING_DEPTH + " levels and this listing stops there."));
        }
    }

    /// The creature the player is looking at, or null after reporting why there is none.
    /// Players are refused because an owned player could be dismissed by the cascade.
    @Nullable
    private Ref<EntityStore> getLookedAtCreature(CommandContext context, Store<EntityStore> store, Ref<EntityStore> source) {
        var target = TargetUtil.getTargetEntity(source, store);
        if (target == null || !target.isValid() || target.getStore() != store) {
            context.sendMessage(Message.raw("Look at a nearby creature, then use this command again."));
            return null;
        }
        if (store.getComponent(target, PlayerRef.getComponentType()) != null) {
            context.sendMessage(Message.raw("That is a player. Only creatures can be owned."));
            return null;
        }
        return target;
    }

    private static String getCreatures(int count) {
        return count + (count == 1 ? " creature" : " creatures");
    }

    private static Message getCreatureName(Store<EntityStore> store, Ref<EntityStore> creature) {
        var displayName = store.getComponent(creature, DisplayNameComponent.getComponentType());
        var name = displayName == null ? null : displayName.getDisplayName();
        return name == null ? Message.raw("that creature") : name;
    }

    private final class Claim extends AbstractPlayerCommand {
        private Claim() {
            super("claim", "Own the creature you are looking at");
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        }

        @Override
        protected void execute(
            CommandContext context,
            Store<EntityStore> store,
            Ref<EntityStore> source,
            PlayerRef player,
            World world
        ) {
            var creature = getLookedAtCreature(context, store, source);
            if (creature == null) {
                return;
            }
            ownership.claim(store, source, creature);
            context.sendMessage(Message.raw("You claimed ").insert(getCreatureName(store, creature))
                .insert(". Nest creatures under it with /relwind own nest."));
        }
    }

    private final class Nest extends AbstractPlayerCommand {
        private Nest() {
            super("nest", "Put the creature you are looking at under the one you claimed last");
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        }

        @Override
        protected void execute(
            CommandContext context,
            Store<EntityStore> store,
            Ref<EntityStore> source,
            PlayerRef player,
            World world
        ) {
            var creature = getLookedAtCreature(context, store, source);
            if (creature == null) {
                return;
            }
            var above = ownership.getLastClaimed(source, store);
            if (above == null) {
                context.sendMessage(Message.raw("Claim a creature first with /relwind own claim."));
                return;
            }
            if (!ownership.nest(store, source, creature)) {
                context.sendMessage(Message.raw(
                    "A creature cannot be nested under itself or under one of its own creatures."));
                return;
            }
            context.sendMessage(Message.raw("You nested ").insert(getCreatureName(store, creature))
                .insert(" under ").insert(getCreatureName(store, above)).insert("."));
        }
    }

    private final class Release extends AbstractPlayerCommand {
        private Release() {
            super("release", "Take the creature you are looking at out of its tree");
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        }

        @Override
        protected void execute(
            CommandContext context,
            Store<EntityStore> store,
            Ref<EntityStore> source,
            PlayerRef player,
            World world
        ) {
            var creature = getLookedAtCreature(context, store, source);
            if (creature == null) {
                return;
            }
            context.sendMessage(ownership.release(store, creature)
                ? Message.raw("You released ").insert(getCreatureName(store, creature))
                    .insert(". The creatures it owns stayed with it.")
                : Message.raw("Nothing to release: ").insert(getCreatureName(store, creature)).insert(" has no owner."));
        }
    }

    private final class ListOwned extends AbstractPlayerCommand {
        private ListOwned() {
            super("list", "Show every creature in your tree with its depth");
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        }

        @Override
        protected void execute(
            CommandContext context,
            Store<EntityStore> store,
            Ref<EntityStore> source,
            PlayerRef player,
            World world
        ) {
            sendListing(context, store, source);
        }
    }

    private final class Dismiss extends AbstractPlayerCommand {
        private Dismiss() {
            super("dismiss", "Remove the creature you are looking at and everything below it");
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        }

        @Override
        protected void execute(
            CommandContext context,
            Store<EntityStore> store,
            Ref<EntityStore> source,
            PlayerRef player,
            World world
        ) {
            var creature = getLookedAtCreature(context, store, source);
            if (creature == null) {
                return;
            }
            // the name is read before the removal, because the reference is invalid afterwards
            var name = getCreatureName(store, creature);
            int owned = ownership.dismiss(store, creature);
            context.sendMessage(Message.raw("You dismissed ").insert(name)
                .insert(owned == 0 ? "." : " and the " + getCreatures(owned) + " it owned."));
        }
    }
}
