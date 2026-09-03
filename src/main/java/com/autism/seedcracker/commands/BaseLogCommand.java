package com.autism.seedcracker.commands;

import com.autism.seedcracker.baselog.BaseLogScreen;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import autismclient.commands.AutismCommandSource;
import autismclient.commands.Command;
import net.minecraft.client.Minecraft;

/** Opens the Base Log Browser GUI. Usage: .bases */
public final class BaseLogCommand extends Command {
    public BaseLogCommand() {
        super("bases", "Open the Base Log Browser GUI.", "baselog", "bl");
    }

    @Override
    public void build(LiteralArgumentBuilder<AutismCommandSource> root) {
        root.executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.gui.setScreen(new BaseLogScreen(mc.gui.screen())));
            return SUCCESS;
        });
    }
}
