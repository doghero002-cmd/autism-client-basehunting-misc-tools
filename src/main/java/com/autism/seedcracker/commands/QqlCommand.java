package com.autism.seedcracker.commands;

import com.autism.seedcracker.setup.Loadouts;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import autismclient.commands.AutismCommandSource;
import autismclient.commands.Command;
import autismclient.util.AutismClientMessaging;

/**
 * {@code .qql} - the "just tell me what to turn on" command. Lists the one-click loadouts and
 * applies them, so new users get a direct answer instead of scrolling 90-odd modules.
 *
 * Usage:
 *   .qql                 -> list every loadout
 *   .qql <name>          -> apply a loadout (turns off other addon modules first)
 *   .qql <name> keep     -> apply a loadout on top of whatever is already enabled
 *   .qql off             -> turn every addon module off
 */
public final class QqlCommand extends Command {
    public QqlCommand() {
        super("qql", "One-click loadouts: .qql [list|off|<name> [keep]]", "loadout", "setup");
    }

    @Override
    public void build(LiteralArgumentBuilder<AutismCommandSource> root) {
        // Bare ".qql" lists the loadouts.
        root.executes(ctx -> { Loadouts.printOverview(); return SUCCESS; });

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("list")
            .executes(ctx -> { Loadouts.printOverview(); return SUCCESS; }));

        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("off")
            .executes(ctx -> { AutismClientMessaging.sendPrefixed(Loadouts.disableAll()); return SUCCESS; }));

        // ".qql <name>" and ".qql <name> keep".
        root.then(RequiredArgumentBuilder.<AutismCommandSource, String>argument("name", StringArgumentType.word())
            .executes(ctx -> apply(StringArgumentType.getString(ctx, "name"), true))
            .then(RequiredArgumentBuilder.<AutismCommandSource, String>argument("mode", StringArgumentType.word())
                .executes(ctx -> {
                    String mode = StringArgumentType.getString(ctx, "mode");
                    boolean exclusive = !mode.equalsIgnoreCase("keep") && !mode.equalsIgnoreCase("add");
                    return apply(StringArgumentType.getString(ctx, "name"), exclusive);
                })));
    }

    private int apply(String name, boolean exclusive) {
        Loadouts.Loadout loadout = Loadouts.byKey(name);
        if (loadout == null) {
            AutismClientMessaging.sendPrefixed("§cUnknown loadout '" + name + "'. Use §e.qql§c to list them.");
            return SUCCESS;
        }
        AutismClientMessaging.sendPrefixed(Loadouts.apply(loadout, exclusive));
        return SUCCESS;
    }
}
