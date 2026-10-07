package com.autism.seedcracker.commands;

import com.autism.seedcracker.market.PriceChartScreen;
import com.autism.seedcracker.market.PriceTracker;
import com.autism.seedcracker.modules.PriceCheckModule;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import autismclient.commands.AutismCommandSource;
import autismclient.commands.Command;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;

/**
 * Price-history lookups backed by the Price Check module's personal market database.
 * Usage: .price            -> open the chart screen
 *        .price <item>     -> chat stats for one item (also opens the chart on it)
 */
public final class PriceCommand extends Command {
    public PriceCommand() {
        super("price", "AH price history: .price [item]", "prices", "pricecheck");
    }

    @Override
    public void build(LiteralArgumentBuilder<AutismCommandSource> root) {
        root.executes(ctx -> {
            open(null);
            return SUCCESS;
        });
        root.then(RequiredArgumentBuilder.<AutismCommandSource, String>argument("item", StringArgumentType.greedyString())
            .executes(ctx -> {
                String item = PriceTracker.normalize(StringArgumentType.getString(ctx, "item"));
                PriceTracker.Stats s = PriceTracker.stats(item);
                if (s == null) {
                    AutismClientMessaging.sendPrefixed("§e[Price] No data for '" + item
                        + "' yet - browse /ah with Price Check enabled to learn it.");
                } else {
                    AutismClientMessaging.sendPrefixed("§f" + item
                        + " §7min §f" + PriceCheckModule.compact(s.min())
                        + " §7median §f" + PriceCheckModule.compact(s.median())
                        + " §7(24h §f" + PriceCheckModule.compact(s.recentMedian())
                        + "§7) max §f" + PriceCheckModule.compact(s.max())
                        + " §7from " + s.samples() + " samples");
                }
                open(item);
                return SUCCESS;
            }));
    }

    private static void open(String item) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new PriceChartScreen(mc.gui.screen(), item)));
    }
}
