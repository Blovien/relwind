/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.examples.alliance;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import javax.annotation.Nullable;

import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.INTEGER;

/// The player-facing half of [AllianceExample], reached as `/relwind ally`. Each subcommand acts
/// on the entity the player is looking at.
public final class AllianceCommand extends AbstractPlayerCommand {
    private final AllianceExample alliance;

    public AllianceCommand(AllianceExample alliance) {
        super("ally", "Form alliances and see how far they reach");
        this.alliance = alliance;
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        addSubCommand(new Add());
        addSubCommand(new Remove());
        addSubCommand(new ListAllies());
        addSubCommand(new Reach());
    }

    /// `/relwind ally` on its own shows the same listing as `/relwind ally list`.
    @Override
    protected void execute(
        CommandContext context,
        Store<EntityStore> store,
        Ref<EntityStore> source,
        PlayerRef player,
        World world
    ) {
        sendAllies(context, store, source);
    }

    private void sendAllies(CommandContext context, Store<EntityStore> store, Ref<EntityStore> entity) {
        var allies = alliance.getAllies(entity);
        if (allies.isEmpty()) {
            context.sendMessage(Message.raw("You have no allies. Look at one and use /relwind ally add."));
            return;
        }
        context.sendMessage(Message.raw("You are allied with:"));
        for (var ally : allies) {
            context.sendMessage(Message.raw("  ").insert(getEntityName(store, ally)));
        }
        if (alliance.isWaitingForAnAlly(entity)) {
            context.sendMessage(Message.raw("One more ally is leaving the world and is not listed."));
        }
    }

    /// The entity the player is looking at, or null after reporting why there is none.
    @Nullable
    private Ref<EntityStore> getLookedAtEntity(CommandContext context, Store<EntityStore> store, Ref<EntityStore> source) {
        var target = TargetUtil.getTargetEntity(source, store);
        if (target == null || !target.isValid() || target.getStore() != store) {
            context.sendMessage(Message.raw("Look at a nearby creature or player, then use this command again."));
            return null;
        }
        return target;
    }

    private static Message getEntityName(Store<EntityStore> store, Ref<EntityStore> entity) {
        var displayName = store.getComponent(entity, DisplayNameComponent.getComponentType());
        var name = displayName == null ? null : displayName.getDisplayName();
        if (name != null) {
            return name;
        }
        var player = store.getComponent(entity, PlayerRef.getComponentType());
        return Message.raw(player == null ? "that entity" : player.getUsername());
    }

    private final class Add extends AbstractPlayerCommand {
        private Add() {
            super("add", "Ally with the creature or player you are looking at");
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
            var target = getLookedAtEntity(context, store, source);
            if (target == null) {
                return;
            }
            // an alliance with an entity that is leaving the world is left alone until that unload settles
            if (alliance.isWaitingForAnAlly(source) || alliance.isWaitingForAnAlly(target)) {
                context.sendMessage(Message.raw("One of your alliances is leaving the world. Try again shortly."));
                return;
            }
            context.sendMessage(alliance.ally(store, source, target)
                ? Message.raw("You are now allied with ").insert(getEntityName(store, target)).insert(".")
                : Message.raw("You are already allied with ").insert(getEntityName(store, target)).insert("."));
        }
    }

    private final class Remove extends AbstractPlayerCommand {
        private Remove() {
            super("remove", "End the alliance with the creature or player you are looking at");
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
            var target = getLookedAtEntity(context, store, source);
            if (target == null) {
                return;
            }
            context.sendMessage(alliance.unally(store, source, target)
                ? Message.raw("Your alliance with ").insert(getEntityName(store, target)).insert(" has ended.")
                : Message.raw("You are not allied with ").insert(getEntityName(store, target)).insert("."));
        }
    }

    private final class ListAllies extends AbstractPlayerCommand {
        private ListAllies() {
            super("list", "Show the allies you are allied with directly");
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
            sendAllies(context, store, source);
        }
    }

    private final class Reach extends AbstractPlayerCommand {
        private final DefaultArg<Integer> hopsArg = withDefaultArg("hops",
            "How many alliances to follow, up to " + AllianceExample.MAX_HOPS, INTEGER,
            AllianceExample.ALERT_HOPS, String.valueOf(AllianceExample.ALERT_HOPS));

        private Reach() {
            super("reach", "Show every ally within a number of alliances, with its distance");
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
            int hops = hopsArg.get(context);
            if (hops < 1 || hops > AllianceExample.MAX_HOPS) {
                context.sendMessage(Message.raw("Follow between 1 and " + AllianceExample.MAX_HOPS + " alliances."));
                return;
            }
            var reach = alliance.reach(source, hops);
            if (reach.allies().isEmpty()) {
                context.sendMessage(Message.raw("No ally is within " + getHops(hops) + " of you."));
            } else {
                context.sendMessage(Message.raw("Within " + getHops(hops) + " of you:"));
                for (var ally : reach.allies()) {
                    context.sendMessage(Message.raw("  " + getHops(ally.depth()) + ": ").insert(getEntityName(store, ally.entity())));
                }
            }
            context.sendMessage(Message.raw(reach.truncated()
                ? "The search was cut: your allies reach further than this."
                : "That is the whole group."));
        }
    }

    private static String getHops(int hops) {
        return hops + (hops == 1 ? " alliance" : " alliances");
    }
}
