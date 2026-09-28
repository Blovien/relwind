/*
 * Copyright (C) 2026 Relwind contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package io.github.blovien.relwind.examples;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import io.github.blovien.relwind.examples.alliance.AllianceCommand;
import io.github.blovien.relwind.examples.alliance.AllianceExample;
import io.github.blovien.relwind.examples.anchor.AnchorCommand;
import io.github.blovien.relwind.examples.anchor.AnchorExample;
import io.github.blovien.relwind.examples.circuit.CircuitCommand;
import io.github.blovien.relwind.examples.circuit.CircuitExample;
import io.github.blovien.relwind.examples.hierarchy.OwnershipCommand;
import io.github.blovien.relwind.examples.hierarchy.OwnershipExample;

final class RelwindExamplesCommand extends AbstractCommandCollection {
    RelwindExamplesCommand(
        OwnershipExample ownership,
        AllianceExample alliance,
        CircuitExample circuit,
        AnchorExample anchor
    ) {
        super("relwindexamples", "relwind-examples commands collection");
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        addSubCommand(new OwnershipCommand(ownership));
        addSubCommand(new AllianceCommand(alliance));
        addSubCommand(new CircuitCommand(circuit));
        addSubCommand(new AnchorCommand(anchor));
    }
}
