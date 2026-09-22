/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.examples.anchor;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import javax.annotation.Nullable;

/// The player-facing half of [AnchorExample], reached as `/relwind anchor`. Each subcommand acts
/// on the player running it.
public final class AnchorCommand extends AbstractPlayerCommand {
    private static final double REACH = 8;

    private static final String NO_ANCHOR =
        "You have no anchor. Look at a block entity and use /relwind anchor set.";
    private static final String UNRESOLVED = "Your anchor is in an unloaded area.";

    private final AnchorExample anchor;

    public AnchorCommand(AnchorExample anchor) {
        super("anchor", "Remember a block and travel back to it");
        this.anchor = anchor;
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        addSubCommand(new SetAnchor());
        addSubCommand(new Go());
        addSubCommand(new Clear());
        addSubCommand(new ShowStatus());
    }

    /// `/relwind anchor` on its own reports the same status as `/relwind anchor status`.
    @Override
    protected void execute(
        CommandContext context,
        Store<EntityStore> store,
        Ref<EntityStore> player,
        PlayerRef playerRef,
        World world
    ) {
        sendStatus(context, player, world);
    }

    private void sendStatus(CommandContext context, Ref<EntityStore> player, World world) {
        context.sendMessage(Message.raw(
            getStatusMessage(anchor.getStatus(player, world.getChunkStore().getStore()))));
    }

    /// What each status reads as to the player, including when `go` and `clear` have nothing to act on.
    static String getStatusMessage(AnchorExample.Status status) {
        return switch (status) {
            case AnchorExample.Status.Unset ignored -> NO_ANCHOR;
            case AnchorExample.Status.Away ignored -> UNRESOLVED;
            case AnchorExample.Status.At at -> "Your anchor is at " + getPosition(at) + ".";
        };
    }

    private static String getPosition(AnchorExample.Status.At at) {
        return at.x() + ", " + at.y() + ", " + at.z();
    }

    /// The block entity the player is looking at, or null after reporting why there is none.
    /// Only a block entity can be the linked entity of a chunk relationship.
    @Nullable
    private static Ref<ChunkStore> getLookedAtBlock(
        CommandContext context,
        Store<EntityStore> store,
        Ref<EntityStore> player,
        World world
    ) {
        var position = TargetUtil.getTargetBlock(player, REACH, store);
        if (position == null) {
            context.sendMessage(Message.raw("Look at a block within reach first."));
            return null;
        }
        var block = BlockModule.getBlockEntity(world, position.x, position.y, position.z);
        if (block == null) {
            context.sendMessage(Message.raw("That block has no block entity, so it cannot be an anchor."));
        }
        return block;
    }

    private final class SetAnchor extends AbstractPlayerCommand {
        private SetAnchor() {
            super("set", "Anchor yourself to the block you are looking at");
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        }

        @Override
        protected void execute(
            CommandContext context,
            Store<EntityStore> store,
            Ref<EntityStore> player,
            PlayerRef playerRef,
            World world
        ) {
            var chunks = world.getChunkStore().getStore();
            // an anchor that is away cannot be replaced, because the type keeps its one target until it settles
            if (anchor.getStatus(player, chunks) instanceof AnchorExample.Status.Away) {
                context.sendMessage(Message.raw(
                    UNRESOLVED + " It stays yours until that area loads again."));
                return;
            }
            var block = getLookedAtBlock(context, store, player, world);
            if (block == null) {
                return;
            }
            anchor.setAnchor(store, player, block);
            sendStatus(context, player, world);
        }
    }

    private final class Go extends AbstractPlayerCommand {
        private Go() {
            super("go", "Travel back to your anchor");
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        }

        @Override
        protected void execute(
            CommandContext context,
            Store<EntityStore> store,
            Ref<EntityStore> player,
            PlayerRef playerRef,
            World world
        ) {
            if (!(anchor.getStatus(player, world.getChunkStore().getStore())
                instanceof AnchorExample.Status.At at)) {
                sendStatus(context, player, world);
                return;
            }
            anchor.travelTo(store, player, at);
            context.sendMessage(Message.raw("Travelling to your anchor at " + getPosition(at) + "."));
        }
    }

    private final class Clear extends AbstractPlayerCommand {
        private Clear() {
            super("clear", "Forget your anchor");
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        }

        @Override
        protected void execute(
            CommandContext context,
            Store<EntityStore> store,
            Ref<EntityStore> player,
            PlayerRef playerRef,
            World world
        ) {
            if (anchor.clearAnchor(store, player)) {
                context.sendMessage(Message.raw("Your anchor was removed."));
                return;
            }
            sendStatus(context, player, world);
        }
    }

    private final class ShowStatus extends AbstractPlayerCommand {
        private ShowStatus() {
            super("status", "Report where your anchor is");
            setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        }

        @Override
        protected void execute(
            CommandContext context,
            Store<EntityStore> store,
            Ref<EntityStore> player,
            PlayerRef playerRef,
            World world
        ) {
            sendStatus(context, player, world);
        }
    }
}
