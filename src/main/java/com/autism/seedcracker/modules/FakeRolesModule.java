package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Fake Roles.
 *
 * Rewrites your own nametag in incoming chat so it shows a fake DonutSMP role (SRMOD, MEDIA,
 * SRADMIN, DEV) and tag (+, ++, +++) with the matching color/icon. Other players' tags are
 * untouched. Purely cosmetic/local.
 *
 * Clean-room port of the obfuscated Zelith "FakeRoles" module; the chat rewrite is applied by
 * the FakeRolesChatMixin on incoming system-chat packets.
 */
public final class FakeRolesModule extends Module {

    public enum Role { NONE, SRMOD, MEDIA, SRADMIN, DEV, CUSTOM }
    public enum Tag { NONE, PLUS, PLUS_PLUS, PLUS_PLUS_PLUS }

    private static FakeRolesModule instance;

    private final EnumSetting<Role> role = add(new EnumSetting<>(
            "role", "Role", Role.NONE, Role.values())
        .description("The fake role shown on your nametag.")
        .group("General"));
    private final EnumSetting<Tag> tag = add(new EnumSetting<>(
            "tag", "Tag", Tag.NONE, Tag.values())
        .description("The fake + tag shown on your nametag.")
        .group("General"));
    private final StringSetting customRank = add(new StringSetting(
            "custom-rank", "Custom rank text", "VIP")
        .description("CUSTOM role: the rank text shown in brackets (e.g. VIP, OWNER, YT).")
        .group("Custom")
        .visibleWhen(() -> role.get() == Role.CUSTOM));
    private final ColorSetting customColor = add(new ColorSetting(
            "custom-color", "Custom rank colour", 0xFF55FFFF)
        .description("CUSTOM role: colour of the rank text and your name.")
        .group("Custom")
        .visibleWhen(() -> role.get() == Role.CUSTOM));
    private final BoolSetting customBold = add(new BoolSetting(
            "custom-bold", "Custom rank bold", true)
        .description("CUSTOM role: make the rank text bold.")
        .group("Custom")
        .visibleWhen(() -> role.get() == Role.CUSTOM));

    public FakeRolesModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-fake-roles", "Fake Roles", category,
            "Shows a fake role/tag on your nametag in chat.");
        instance = this;
    }

    @Override
    public void onDisable() {
        if (instance == this) instance = null;
    }

    /** Active role/tag, or nulls when disabled. Called by the chat mixin. */
    private static Role activeRole() {
        FakeRolesModule m = instance;
        return (m != null && m.isEnabled()) ? m.role.get() : Role.NONE;
    }

    private static Tag activeTag() {
        FakeRolesModule m = instance;
        return (m != null && m.isEnabled()) ? m.tag.get() : Tag.NONE;
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
        FakeRolesModule m = instance;

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
            // Donator tag (blue +/++/+++).
            String cTag = switch (t) {
                case PLUS -> "+";
                case PLUS_PLUS -> "++";
                case PLUS_PLUS_PLUS -> "+++";
                default -> "";
            };
            if (!cTag.isEmpty()) {
                out.append(Component.literal(cTag + " ")
                    .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.BLUE).withBold(true)));
            }
            out.append(Component.literal(name).withStyle(style -> style.withColor(argb)));
            return out;
        }

        net.minecraft.ChatFormatting color = switch (r) {
            case SRMOD -> net.minecraft.ChatFormatting.GREEN;
            case MEDIA -> net.minecraft.ChatFormatting.LIGHT_PURPLE;
            case SRADMIN -> net.minecraft.ChatFormatting.RED;
            case DEV -> net.minecraft.ChatFormatting.AQUA;
            default -> net.minecraft.ChatFormatting.WHITE;
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
        // DonutSMP donator ranks: a blue "+", "++" or "+++" before the name.
        if (!tagStr.isEmpty()) {
            out.append(Component.literal(tagStr + " ")
                .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.BLUE).withBold(true)));
        }
        out.append(Component.literal(name).withStyle(color));
        return out;
    }
}
