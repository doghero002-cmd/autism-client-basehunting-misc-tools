package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.fake.FakeBalance;

import autismclient.api.module.BoolSetting;
import autismclient.modules.Module;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.sounds.SoundEvents;

/**
 * Fake Pay.
 *
 * Intercepts your outgoing "/pay <player> <amount>" command, cancels the real payment, and
 * instead shows a fake "You paid <player> $X." message locally — so it looks like you paid
 * without the server ever seeing the transaction. Also bumps the shared fake balance.
 *
 * Clean-room port of the obfuscated Zelith "FakePay" module.
 */
public final class FakePayModule extends Module {

    private final BoolSetting sound = add(new BoolSetting("sound", "Sound", true)
        .description("Play a ding when faking a payment.")
        .group("General"));
    private final BoolSetting addToFakeBalance = add(new BoolSetting("add-to-balance", "Update balance", true)
        .description("Deduct the faked amount from the Fake Scoreboard balance (you paid it out).")
        .group("General"));

    public FakePayModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-fake-pay", "Fake Pay", category,
            "Fakes /pay commands locally so it looks like you paid without paying.");
    }

    @Override
    public boolean onPacketSend(Packet<?> packet) {
        if (packet instanceof ServerboundChatPacket chat) {
            return handle(chat.message()) ? true : false;
        }
        if (packet instanceof ServerboundChatCommandPacket cmd) {
            return handle(cmd.command()) ? true : false;
        }
        return false;
    }

    /** Returns true if the message was a /pay command we faked (and should be cancelled). */
    private boolean handle(String raw) {
        if (raw == null) return false;
        String text = raw.trim();
        if (text.startsWith("/")) text = text.substring(1);
        if (!text.startsWith("pay ")) return false;

        String[] parts = text.substring(4).trim().split("\\s+");
        if (parts.length < 2) return false;
        String player = parts[0];
        // DonutSMP accepts k/m shorthand on /pay ("166.3k", "1.8m"); expand it to a long.
        long amount = parseAmount(parts[1]);
        if (amount < 1) return false;

        Minecraft mc = Minecraft.getInstance();

        // DonutSMP style: white text, green "$", space after "$", k/m shorthand.
        MutableComponent msg = Component.literal("You paid ").withStyle(ChatFormatting.WHITE)
            .append(Component.literal(player).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal("$").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" " + FakeBalance.formatShort(amount)).withStyle(ChatFormatting.WHITE));
        if (mc.gui != null) {
            mc.gui.chatListener().handleSystemMessage(msg, false);
        }

        if (sound.get() && mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
        if (addToFakeBalance.get()) {
            // Paying money out: deduct it from the fake balance.
            FakeBalance.add(-amount);
        }
        return true; // cancel the real payment
    }

    /** Parses a DonutSMP amount that may use k/m/b shorthand or commas ("166.3k", "1.8m", "30"). */
    private static long parseAmount(String raw) {
        String s = raw.replace(",", "").trim().toLowerCase(java.util.Locale.ROOT);
        double mult = 1.0;
        if (s.endsWith("b")) { mult = 1_000_000_000.0; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mult = 1_000_000.0; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("k")) { mult = 1_000.0; s = s.substring(0, s.length() - 1); }
        try {
            double v = Double.parseDouble(s) * mult;
            return (long) v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
