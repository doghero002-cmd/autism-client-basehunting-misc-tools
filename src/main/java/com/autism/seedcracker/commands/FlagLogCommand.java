package com.autism.seedcracker.commands;

import java.util.List;

import com.autism.seedcracker.util.FlagLog;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import autismclient.commands.AutismCommandSource;
import autismclient.commands.Command;
import autismclient.util.AutismClientMessaging;

/**
 * In-game flag-log viewer: shows the latest FlagLog entries in chat so you can see WHY a module
 * tripped a failsafe or disabled itself without digging up flag-log.txt.
 * Usage: .flaglog [count]
 */
public final class FlagLogCommand extends Command {
    public FlagLogCommand() {
        super("flaglog", "Show recent flag-log entries: .flaglog [count]", "flags", "fl");
    }

    @Override
    public void build(LiteralArgumentBuilder<AutismCommandSource> root) {
        root.executes(ctx -> {
            show(10);
            return SUCCESS;
        });
        root.then(RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("count", IntegerArgumentType.integer(1, 50))
            .executes(ctx -> {
                show(IntegerArgumentType.getInteger(ctx, "count"));
                return SUCCESS;
            }));
    }

    private static void show(int n) {
        List<String> entries = FlagLog.recent(n);
        if (entries.isEmpty()) {
            AutismClientMessaging.sendPrefixed("§7[FlagLog] No entries this session. Full log: " + FlagLog.path());
            return;
        }
        AutismClientMessaging.sendPrefixed("§b[FlagLog] §flast " + entries.size() + " entries:");
        for (String e : entries) {
            // Trim the date (keep time) so rows fit in chat.
            String line = e.length() > 11 ? e.substring(11) : e;
            String color = line.contains("| FLAG |") ? "§c" : line.contains("| WARN |") ? "§e" : "§7";
            AutismClientMessaging.sendPrefixed(color + line);
        }
    }
}
