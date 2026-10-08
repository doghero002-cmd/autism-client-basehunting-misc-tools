package com.autism.seedcracker.modules;

import java.util.Random;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.fake.FakeBalance;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.sounds.SoundEvents;

/**
 * Fake Identity - the three "look richer / higher-ranked than you are" tools merged into one
 * module so they stop cluttering the menu as three near-identical entries:
 *
 *  - Fake /pay      : cancel your own /pay and show a local "You paid X $Y." instead.
 *  - Fake payments  : on a timer, show fake "<name> paid you $Z." messages.
 *  - Fake rank      : rewrite your own nametag in chat to show a fake DonutSMP role/tag.
 *
 * Each is an independent sub-toggle; the module is one on/off entry and each feature only runs
 * when both the module and its toggle are on. (Clean-room port/merge of the Zelith FakePay,
 * FakePayments and FakeRoles modules.)
 */
public final class FakeIdentityModule extends Module {

    public enum Role { NONE, SRMOD, MEDIA, SRADMIN, DEV, CUSTOM }
    public enum Tag { NONE, PLUS, PLUS_PLUS, PLUS_PLUS_PLUS }

    private static final String[] PAYER_NAMES = {
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

    private static FakeIdentityModule instance;

    private final Random rng = new Random();
    private int ticks = 0;
    private int nextAt = 0;

    // --- Feature toggles -----------------------------------------------------------------------
    private final BoolSetting payEnabled = add(new BoolSetting("pay", "Fake /pay", true)
        .description("Cancel your real /pay and show a fake 'You paid ...' message locally instead.")
        .group("Features"));
    private final BoolSetting paymentsEnabled = add(new BoolSetting("payments", "Fake incoming payments", false)
        .description("Periodically show fake 'someone paid you $X' messages in chat.")
        .group("Features"));
    private final BoolSetting rolesEnabled = add(new BoolSetting("roles", "Fake rank", false)
        .description("Rewrite your own nametag in chat to show a fake DonutSMP role/tag.")
        .group("Features"));

    // --- Fake /pay -----------------------------------------------------------------------------
    private final BoolSetting paySound = add(new BoolSetting("pay-sound", "Pay sound", true)
        .description("Play a ding when faking a payment.")
        .group("Fake /pay").visibleWhen(payEnabled::get));
    private final BoolSetting payUpdatesBalance = add(new BoolSetting("pay-balance", "Pay updates balance", true)
        .description("Deduct the faked amount from the Fake Scoreboard balance (you paid it out).")
        .group("Fake /pay").visibleWhen(payEnabled::get));

    // --- Fake incoming payments ----------------------------------------------------------------
    private final IntSetting delay = add(new IntSetting("delay", "Delay (s)", 5, 1, 60, 1)
        .description("Seconds between fake payments (when Random Delay is off).")
        .group("Fake payments").visibleWhen(paymentsEnabled::get));
    private final BoolSetting randomDelay = add(new BoolSetting("random-delay", "Random delay", false)
        .description("Randomize the interval between fake payments.")
        .group("Fake payments").visibleWhen(paymentsEnabled::get));
    private final IntSetting minDelay = add(new IntSetting("min-delay", "Min delay (s)", 3, 1, 60, 1)
        .description("Minimum random interval.")
        .group("Fake payments").visibleWhen(() -> paymentsEnabled.get() && randomDelay.get()));
    private final IntSetting maxDelay = add(new IntSetting("max-delay", "Max delay (s)", 7, 1, 120, 1)
        .description("Maximum random interval.")
        .group("Fake payments").visibleWhen(() -> paymentsEnabled.get() && randomDelay.get()));
    private final StringSetting minAmount = add(new StringSetting("min-amount", "Min amount", "1m")
        .description("Smallest fake payment (supports k/m/b/t suffixes).")
        .group("Fake payments").visibleWhen(paymentsEnabled::get));
    private final StringSetting maxAmount = add(new StringSetting("max-amount", "Max amount", "25m")
        .description("Largest fake payment (supports k/m/b/t suffixes).")
        .group("Fake payments").visibleWhen(paymentsEnabled::get));
    private final BoolSetting paymentsSound = add(new BoolSetting("payments-sound", "Payment sound", true)
        .description("Play a ding on each fake payment.")
        .group("Fake payments").visibleWhen(paymentsEnabled::get));

    // --- Fake rank -----------------------------------------------------------------------------
    private final EnumSetting<Role> role = add(new EnumSetting<>("role", "Role", Role.NONE, Role.values())
        .description("The fake role shown on your nametag.")
        .group("Fake rank").visibleWhen(rolesEnabled::get));
    private final EnumSetting<Tag> tag = add(new EnumSetting<>("tag", "Tag", Tag.NONE, Tag.values())
        .description("The fake + tag shown on your nametag.")
        .group("Fake rank").visibleWhen(rolesEnabled::get));
    private final StringSetting customRank = add(new StringSetting("custom-rank", "Custom rank text", "VIP")
        .description("CUSTOM role: the rank text shown in brackets (e.g. VIP, OWNER, YT).")
        .group("Fake rank").visibleWhen(() -> rolesEnabled.get() && role.get() == Role.CUSTOM));
    private final ColorSetting customColor = add(new ColorSetting("custom-color", "Custom rank colour", 0xFF55FFFF)
        .description("CUSTOM role: colour of the rank text and your name.")
        .group("Fake rank").visibleWhen(() -> rolesEnabled.get() && role.get() == Role.CUSTOM));
    private final BoolSetting customBold = add(new BoolSetting("custom-bold", "Custom rank bold", true)
        .description("CUSTOM role: make the rank text bold.")
        .group("Fake rank").visibleWhen(() -> rolesEnabled.get() && role.get() == Role.CUSTOM));

    public FakeIdentityModule() {
        super(SeedcrackerAddon.ID + ":fake-identity", "Fake Identity",
            "Fake /pay, fake incoming payments and a fake rank - all in one. Toggle the parts you want.");
        instance = this;
    }

    @Override
    public void onEnable() {
        // Restore the mixin hook (onDisable nulls it) and reset the payments timer.
        instance = this;
        ticks = 0;
        nextAt = nextInterval();
    }

    @Override
    public void onDisable() {
        if (instance == this) instance = null;
    }

    // --- Fake /pay: cancel outgoing /pay -------------------------------------------------------
    @Override
    public boolean onPacketSend(Packet<?> packet) {
        if (!payEnabled.get()) return false;
        // Commands only: plain chat packets never carry commands.
        if (packet instanceof ServerboundChatCommandPacket cmd) {
            return handlePay(cmd.command());
        }
        return false;
    }

    /** Returns true if the message was a /pay command we faked (and should be cancelled). */
    private boolean handlePay(String raw) {
        if (raw == null) return false;
        String text = raw.trim();
        if (text.startsWith("/")) text = text.substring(1);
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (!lower.equals("pay") && !lower.startsWith("pay ")) return false;

        String[] parts = text.substring(Math.min(4, text.length())).trim().split("\\s+");
        if (lower.equals("pay")) return false; // bare /pay with no args
        if (parts.length < 2) return false;
        String player = parts[0];
        long amount = parseAmount(parts[1]);
        if (amount < 1) return false;

        Minecraft mc = Minecraft.getInstance();
        MutableComponent msg = Component.literal("You paid ").withStyle(ChatFormatting.WHITE)
            .append(Component.literal(player).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal("$").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" " + FakeBalance.formatShort(amount)).withStyle(ChatFormatting.WHITE));
        if (mc.gui != null) {
            mc.gui.chatListener().handleSystemMessage(msg, false);
        }
        if (paySound.get() && mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
        if (payUpdatesBalance.get()) {
            FakeBalance.add(-amount);
        }
        return true; // cancel the real payment
    }

    // --- Fake incoming payments: timer ---------------------------------------------------------
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
        if (!paymentsEnabled.get()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (++ticks < nextAt) return;
        ticks = 0;
        nextAt = nextInterval();
        firePayment(mc);
    }

    private void firePayment(Minecraft mc) {
        String name = PAYER_NAMES[rng.nextInt(PAYER_NAMES.length)];
        long amount = randomAmount();
        MutableComponent msg = Component.literal(name).withStyle(ChatFormatting.GREEN)
            .append(Component.literal(" paid you ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("$" + FakeBalance.format(amount)).withStyle(ChatFormatting.GOLD))
            .append(Component.literal(".").withStyle(ChatFormatting.GRAY));
        if (mc.gui != null) {
            mc.gui.chatListener().handleSystemMessage(msg, false);
        }
        if (paymentsSound.get() && mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
        FakeBalance.add(amount);
    }

    private long randomAmount() {
        long lo = parsePaymentAmount(minAmount.get());
        long hi = parsePaymentAmount(maxAmount.get());
        if (lo > hi) { long t = lo; lo = hi; hi = t; }
        if (hi <= lo) return lo;
        return lo + (long) (rng.nextDouble() * (hi - lo));
    }

    private static long parseAmount(String raw) {
        return com.autism.seedcracker.util.pure.PriceMath.parseAmount(raw);
    }

    /** Payments parser: 1000 fallback keeps the old behaviour on blanks/garbage. */
    private static long parsePaymentAmount(String raw) {
        long v = com.autism.seedcracker.util.pure.PriceMath.parseAmount(raw);
        return v < 0 ? 1000L : v;
    }

    // --- Fake rank: chat nametag rewrite (called by FakeRolesChatMixin) -------------------------
    private static Role activeRole() {
        FakeIdentityModule m = instance;
        return (m != null && m.isEnabled() && m.rolesEnabled.get()) ? m.role.get() : Role.NONE;
    }

    private static Tag activeTag() {
        FakeIdentityModule m = instance;
        return (m != null && m.isEnabled() && m.rolesEnabled.get()) ? m.tag.get() : Tag.NONE;
    }

    private static String localName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? mc.player.getGameProfile().name() : null;
    }

    /** Rewrites a chat component, replacing the local player's nametag with the fake one. */
    public static Component transform(Component original) {
        Role r = activeRole();
        Tag t = activeTag();
        if (original == null || (r == Role.NONE && t == Tag.NONE)) return original;
        String name = localName();
        if (name == null || name.isBlank()) return original;

        String text = original.getString();
        if (!text.contains(name)) return original;

        MutableComponent fakeTag = buildTag(name, r, t);
        int idx = text.indexOf(name);
        MutableComponent out = Component.empty();
        if (idx > 0) {
            out.append(Component.literal(text.substring(0, idx)));
        }
        out.append(fakeTag);
        int end = idx + name.length();
        if (end < text.length()) {
            out.append(Component.literal(text.substring(end)));
        }
        return out;
    }

    private static MutableComponent buildTag(String name, Role r, Tag t) {
        FakeIdentityModule m = instance;

        // CUSTOM role: player-typed rank text + chosen colour/bold.
        if (r == Role.CUSTOM && m != null) {
            String rank = m.customRank.get().trim();
            int argb = m.customColor.get();
            boolean bold = m.customBold.get();
            MutableComponent out = Component.empty();
            if (!rank.isEmpty()) {
                out.append(Component.literal("[" + rank + "] ")
                    .withStyle(style -> style.withColor(argb).withBold(bold)));
            }
            String cTag = switch (t) {
                case PLUS -> "+";
                case PLUS_PLUS -> "++";
                case PLUS_PLUS_PLUS -> "+++";
                default -> "";
            };
            if (!cTag.isEmpty()) {
                out.append(Component.literal(cTag + " ")
                    .withStyle(style -> style.withColor(ChatFormatting.BLUE).withBold(true)));
            }
            out.append(Component.literal(name).withStyle(style -> style.withColor(argb)));
            return out;
        }

        ChatFormatting color = switch (r) {
            case SRMOD -> ChatFormatting.GREEN;
            case MEDIA -> ChatFormatting.LIGHT_PURPLE;
            case SRADMIN -> ChatFormatting.RED;
            case DEV -> ChatFormatting.AQUA;
            default -> ChatFormatting.WHITE;
        };
        String roleLabel = switch (r) {
            case SRMOD -> "SRMOD";
            case SRADMIN -> "SRADMIN";
            case DEV -> "DEV";
            default -> "";
        };
        String tagStr = switch (t) {
            case PLUS -> "+";
            case PLUS_PLUS -> "++";
            case PLUS_PLUS_PLUS -> "+++";
            default -> "";
        };
        MutableComponent out = Component.empty();
        if (r == Role.MEDIA) {
            out.append(Component.literal("📹 ").withStyle(color));
        } else if (!roleLabel.isEmpty()) {
            out.append(Component.literal("[" + roleLabel + "] ")
                .withStyle(style -> style.withColor(color).withBold(true)));
        }
        if (!tagStr.isEmpty()) {
            out.append(Component.literal(tagStr + " ")
                .withStyle(style -> style.withColor(ChatFormatting.BLUE).withBold(true)));
        }
        out.append(Component.literal(name).withStyle(color));
        return out;
    }
}
