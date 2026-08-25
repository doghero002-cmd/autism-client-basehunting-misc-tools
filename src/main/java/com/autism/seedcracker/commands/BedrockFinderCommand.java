package com.autism.seedcracker.commands;

import autismclient.commands.AutismCommandSource;
import autismclient.commands.Command;
import com.autism.seedcracker.bedrock.BedrockFinderScreen;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;

/** Opens the Bedrock Finder pattern-grid GUI. Usage: .bfinder */
public final class BedrockFinderCommand extends Command {
    public BedrockFinderCommand() {
        super("bfinder", "Open the Bedrock Finder grid GUI.", "bedrockfinder", "bf");
    }

    @Override
    public void build(LiteralArgumentBuilder<AutismCommandSource> root) {
        root.executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreenAndShow(new BedrockFinderScreen(null)));
            return SUCCESS;
        });
    }
}
