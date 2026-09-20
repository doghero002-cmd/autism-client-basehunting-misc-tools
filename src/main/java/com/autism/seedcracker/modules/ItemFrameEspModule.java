package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.render.BlockEspRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.StringListSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Item Frame ESP (MeteorPlus ItemFrameEsp port).
 *
 * Highlights item frames containing whitelisted items. Default whitelist is elytra (players
 * display trophy elytras at bases) plus totems/netherite - "someone hung something valuable
 * here" is a base sign. PathSeeker note baked in: elytra frames in END CITIES are natural ship
 * loot, so end-dimension elytra frames can be ignored with a toggle.
 */
public final class ItemFrameEspModule extends Module {

    private final StringListSetting items = add(new StringListSetting("items", "Item whitelist",
            "minecraft:elytra|minecraft:totem_of_undying|minecraft:netherite_ingot|minecraft:enchanted_golden_apple")
        .description("Item ids (| separated); frames holding any of these are highlighted.").group("Filter"));
    private final BoolSetting anyItem = add(new BoolSetting("any-item", "Any non-empty frame", false)
        .description("Highlight EVERY frame with an item (frames themselves are player-placed).").group("Filter"));
    private final BoolSetting skipEndShips = add(new BoolSetting("skip-end-ships", "Skip End elytra frames", true)
        .description("Ignore elytra frames in the End (natural end-ship loot, not a player sign).").group("Filter"));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0xC0FFD700)
        .description("Highlight colour.").group("Render"));
    private final BoolSetting tracers = add(new BoolSetting("tracer", "Tracer", true)
        .description("Tracer to each matched frame.").group("Render"));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notification", true)
        .description("Chat ping the first time a matched frame is seen.").group("General"));

    private final Set<BlockPos> notified = new HashSet<>();

    public ItemFrameEspModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":item-frame-esp", "ItemFrame ESP", category,
            "Highlights item frames holding valuable items (trophy walls = bases).");
    }

    @Override
    public void onDisable() {
        BlockEspRenderer.clear(id());
        notified.clear();
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Set<Item> wanted = parseItems();
        boolean inEnd = mc.level.dimension() == net.minecraft.world.level.Level.END;

        Set<BlockPos> matches = new HashSet<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ItemFrame frame)) continue;
            ItemStack stack = frame.getItem();
            if (stack.isEmpty()) continue;

            boolean match = anyItem.get() || wanted.contains(stack.getItem());
            if (!match) continue;
            if (skipEndShips.get() && inEnd && stack.getItem() == net.minecraft.world.item.Items.ELYTRA) continue;

            BlockPos pos = frame.blockPosition();
            matches.add(pos);
            if (notify.get() && notified.add(pos)) {
                AutismClientMessaging.sendPrefixed("§6[FrameESP] §f"
                    + stack.getHoverName().getString() + " frame at "
                    + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            }
        }

        BlockEspRenderer.feed(id(), matches, color.get(), tracers.get(), true);
    }

    private Set<Item> parseItems() {
        Set<Item> out = new HashSet<>();
        java.util.List<String> raw = items.get();
        if (raw == null) return out;
        for (String id : raw) {
            String t = id.trim();
            if (t.isEmpty()) continue;
            Identifier ident = Identifier.tryParse(t);
            if (ident != null) BuiltInRegistries.ITEM.getOptional(ident).ifPresent(out::add);
        }
        return out;
    }
}
