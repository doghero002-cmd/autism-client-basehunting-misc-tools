package com.autism.seedcracker.commands;

import com.autism.seedcracker.flip.FlipScreen;
import com.autism.seedcracker.modules.AHFlipperModule;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import autismclient.commands.AutismCommandSource;
import autismclient.commands.Command;
import net.minecraft.client.Minecraft;

/** .flip opens the AH Flipper panel (ranked flips + paper ledger). */
public final class FlipCommand extends Command {
    public FlipCommand() {
        super("flip", "Open the AH Flipper panel", "flips", "flipper");
    }

    @Override
    public void build(LiteralArgumentBuilder<AutismCommandSource> root) {
        root.executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.gui.setScreen(new FlipScreen(mc.gui.screen())));
            return SUCCESS;
        });
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("status").executes(ctx -> {
            Minecraft.getInstance().execute(() -> {
                AHFlipperModule module = AHFlipperModule.INSTANCE;
                if (module == null) autismclient.util.AutismClientMessaging.sendPrefixed("[AH Flipper] Enable the module in Trading first.");
                else for (String line : module.diagnostics()) autismclient.util.AutismClientMessaging.sendPrefixed("[AH Flipper] " + line);
            });
            return SUCCESS;
        }));
    }
}
