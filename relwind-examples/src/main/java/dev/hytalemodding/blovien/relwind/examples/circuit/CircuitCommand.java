/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.hytalemodding.blovien.relwind.examples.circuit;

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

/// `/relwind circuit`, the playable side of the example. Each subcommand works on the block entity
/// the player is looking at, or on the two block entities the wire tool last used.
public final class CircuitCommand extends AbstractPlayerCommand {
    private static final double REACH = 8;

    private final CircuitExample circuit;

    public CircuitCommand(CircuitExample circuit) {
        super("circuit", "Build a block signal network");
        this.circuit = circuit;
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        addSubCommand(new MarkSource());
        addSubCommand(new Wire());
        addSubCommand(new Unwire());
        addSubCommand(new Probe());
    }

    @Override
    protected void execute(
        CommandContext context,
        Store<EntityStore> store,
        Ref<EntityStore> player,
        PlayerRef playerRef,
        World world
    ) {
        context.sendMessage(Message.raw("Use /relwind circuit source, wire, unwire or probe."));
    }

    /// The block entity the player is looking at, or null when there is no block or the block has
    /// no block entity. Only a block entity can be a linked entity of a chunk relationship.
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
            context.sendMessage(Message.raw("That block has no block entity, so it cannot join a circuit."));
        }
        return block;
    }

    private final class MarkSource extends AbstractPlayerCommand {
        MarkSource() {
            super("source", "Make the block you are looking at feed the network");
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
            var block = getLookedAtBlock(context, store, player, world);
            if (block == null) {
                return;
            }
            circuit.markSource(world.getChunkStore().getStore(), block);
            context.sendMessage(Message.raw("That block now feeds the network with strength "
                + PowerTickingSystem.SOURCE_STRENGTH + "."));
        }
    }

    private final class Wire extends AbstractPlayerCommand {
        Wire() {
            super("wire", "Arm the wire tool, then use two blocks to wire them together");
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
            circuit.ensureWireTool(store, player).arm();
            context.sendMessage(Message.raw("Wire tool armed. Use the block the signal leaves, then the block it reaches."));
        }
    }

    private final class Unwire extends AbstractPlayerCommand {
        Unwire() {
            super("unwire", "Remove the wire between the last two blocks you used");
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
            var tool = store.getComponent(player, WireTool.getComponentType());
            var from = tool == null ? null : tool.getPreviousUsed();
            var to = tool == null ? null : tool.getLastUsed();
            if (from == null || to == null) {
                context.sendMessage(Message.raw("Use two block entities first, then /relwind circuit unwire."));
                return;
            }
            context.sendMessage(Message.raw(circuit.unwire(world.getChunkStore().getStore(), from, to)
                ? "The wire between the last two blocks you used is gone."
                : "The last two blocks you used are not wired together."));
        }
    }

    private final class Probe extends AbstractPlayerCommand {
        Probe() {
            super("probe", "Report the signal at the block you are looking at");
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
            var block = getLookedAtBlock(context, store, player, world);
            if (block == null) {
                return;
            }
            var chunks = world.getChunkStore().getStore();
            context.sendMessage(Message.raw("Strength " + circuit.getStrength(block, chunks)
                + (circuit.hasReachableSource(block) ? ", wired to a source." : ", wired to no source.")));
            for (var strength : circuit.getIncomingStrengths(block)) {
                context.sendMessage(Message.raw("A wire reaching this block carried strength " + strength + "."));
            }
        }
    }
}
