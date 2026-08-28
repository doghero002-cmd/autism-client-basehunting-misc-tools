package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.fake.FakeBalance;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

import java.util.Random;

/**
 * Fake Payments.
 *
 * Periodically shows fake "<name> paid you $X." messages in chat (with a random name and
 * amount), so it looks like other players are paying you. Purely local - no packets are sent.
 * Adds to the shared fake balance.
 *
 * Clean-room port of the obfuscated Zelith "FakePayments" module.
 */
public final class FakePaymentsModule extends Module {

    private static final String[] NAMES = {
        "SchneeSchuhHase", "g0Onboy312", "ArgonEnjoyer", "DrDiddy934", "esternomastoicle",
        "HERHAKE", "YoxyC", "freaddyFAS", "itzmedr", "laps_", "Salem6542", "ZEN_2213",
        "Hulpic54", "kostbar_troll", "YukonCharlie", "GSMusie", "ImdarealFox", "ItzTerax",
        "MadGhast2", "Kloputzer337", "lejonrasmus", "Munkerlich", "LazerminerCivan",
        "GioRobit", "dih23", "mrpatao", "Nottis1", "ItsZanthrax", "TheRealOnixy",
        "isqpzzz", "itzdursum", "SKskellyfarm01", "Ethereums656", "loosal", "yourmom_6",
        "LrexTTV", "VeriKuula", "Justsyncc", "cpvpGard", "ArchivePebroo", "Im_joe1",
        "Test_Of_Fate", "PhoenixX626", "Crimsonfarmmaker", "soonmedia", "nolimitd0sh",
        "BearHug", "neeisnee", "HawkVision", "itziran", "walksyv1", "Xinox_", "Popelesser34"
    };

    private final Random rng = new Random();
    private int ticks = 0;
    private int nextAt = 0;

    private final IntSetting delay = add(new IntSetting("delay", "Delay (s)", 5, 1, 60, 1)
        .description("Seconds between fake payments (when Random Delay is off).")
        .group("Timing"));
    private final BoolSetting randomDelay = add(new BoolSetting("random-delay", "Random delay", false)
        .description("Randomize the interval between fake payments.")
        .group("Timing"));
    private final IntSetting minDelay = add(new IntSetting("min-delay", "Min delay (s)", 3, 1, 60, 1)
        .description("Minimum random interval.").group("Timing").visibleWhen(() -> randomDelay.get()));
    private final IntSetting maxDelay = add(new IntSetting("max-delay", "Max delay (s)", 7, 1, 120, 1)
        .description("Maximum random interval.").group("Timing").visibleWhen(() -> randomDelay.get()));
    private final StringSetting minAmount = add(new StringSetting("min-amount", "Min amount", "1m")
        .description("Smallest fake payment (supports k/m/b/t suffixes).")
        .group("Amount"));
    private final StringSetting maxAmount = add(new StringSetting("max-amount", "Max amount", "25m")
        .description("Largest fake payment (supports k/m/b/t suffixes).")
        .group("Amount"));
    private final BoolSetting sound = add(new BoolSetting("sound", "Sound", true)
        .description("Play a ding on each fake payment.")
        .group("General"));

    public FakePaymentsModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-fake-payments", "Fake Payments", category,
            "Shows fake incoming payments in chat on a timer.");
    }

    @Override
    public void onEnable() {
        ticks = 0;
        nextAt = nextInterval();
    }

    private int nextInterval() {
        int secs;
        if (randomDelay.get()) {
            int lo = Math.min(minDelay.get(), maxDelay.get());
            int hi = Math.max(minDelay.get(), maxDelay.get());
            secs = lo + rng.nextInt(hi - lo + 1);
        } else {
            secs = delay.get();
        }
        return secs * 20;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (++ticks < nextAt) return;
        ticks = 0;
        nextAt = nextInterval();
        fire(mc);
    }

    private void fire(Minecraft mc) {
        String name = NAMES[rng.nextInt(NAMES.length)];
        long amount = randomAmount();
        MutableComponent msg = Component.literal(name).withStyle(ChatFormatting.GREEN)
            .append(Component.literal(" paid you ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("$" + FakeBalance.format(amount)).withStyle(ChatFormatting.GOLD))
            .append(Component.literal(".").withStyle(ChatFormatting.GRAY));
        if (mc.gui != null) {
            mc.gui.chatListener().handleSystemMessage(msg, false);
        }
        if (sound.get() && mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
        FakeBalance.add(amount);
    }

    private long randomAmount() {
        long lo = parseAmount(minAmount.get());
        long hi = parseAmount(maxAmount.get());
        if (lo > hi) { long t = lo; lo = hi; hi = t; }
        if (hi <= lo) return lo;
        long v = lo + (long) (rng.nextDouble() * (hi - lo));
        return v;
    }

    /** Parses amounts like 1000, 5k, 1.5m, 2b, 1t. */
    static long parseAmount(String raw) {
        if (raw == null) return 1000L;
        String s = raw.toLowerCase(java.util.Locale.ROOT).trim();
        try {
            if (s.endsWith("k")) return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1_000.0);
            if (s.endsWith("m")) return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1_000_000.0);
            if (s.endsWith("b")) return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1_000_000_000.0);
            if (s.endsWith("t")) return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1_000_000_000_000.0);
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 1000L;
        }
    }
}
