package com.autism.seedcracker.modules;

import java.util.List;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.util.translate.TranslationEngine;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.modules.Module;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

/**
 * Translate.
 *
 * Auto-translates chat messages and item tooltip text into your chosen language while active.
 * Translation is asynchronous and cached: the first time a line appears it shows in the original
 * language (the fetch runs in the background), and the NEXT time the same text appears - a
 * repeated chat line, or re-opening the tooltip - the translated line is shown beneath it.
 * Optionally re-prints chat lines locally once their translation lands.
 *
 * Uses Google Translate's free client endpoint; nothing is sent until a line actually needs
 * translating, and identical text is only ever fetched once per session.
 */
public final class TranslateModule extends Module {

    public enum Lang {
        EN("English", "en"), ES("Español", "es"), FR("Français", "fr"), DE("Deutsch", "de"),
        PT("Português", "pt"), RU("Русский", "ru"), ZH("中文", "zh-CN"), JA("日本語", "ja"),
        KO("한국어", "ko"), IT("Italiano", "it"), NL("Nederlands", "nl"), PL("Polski", "pl"),
        TR("Türkçe", "tr"), UK("Українська", "uk"), VI("Tiếng Việt", "vi"), ID("Indonesia", "id");

        public final String label;
        public final String code;
        Lang(String label, String code) { this.label = label; this.code = code; }
    }

    private static TranslateModule instance;

    private final EnumSetting<Lang> targetLang = add(new EnumSetting<>("target-lang", "Translate to",
            Lang.EN, Lang.values())
        .description("Language to translate chat and item text into.")
        .group("General"));
    private final BoolSetting chat = add(new BoolSetting("chat", "Translate chat", true)
        .description("Append a translated line under incoming chat messages.")
        .group("Scope"));
    private final BoolSetting chatReprint = add(new BoolSetting("chat-reprint", "Re-print chat", true)
        .description("When a chat line's translation finishes, print the translated line locally so you see it even for one-off messages.")
        .group("Scope").visibleWhen(() -> chat.get()));
    private final BoolSetting items = add(new BoolSetting("items", "Translate item tooltips", true)
        .description("Append translated lines to item tooltips (name + lore).")
        .group("Scope"));
    private final BoolSetting itemName = add(new BoolSetting("item-name", "Also item name", true)
        .description("Translate the item's display name line as well as the lore lines.")
        .group("Scope").visibleWhen(() -> items.get()));

    public TranslateModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":translate", "Translate", category,
            "Auto-translates chat and item tooltip text into your language (async + cached).");
        instance = this;
    }

    @Override
    public void onEnable() {
        TranslationEngine.setEnabled(true);
    }

    @Override
    public void onDisable() {
        if (instance == this) instance = null;
    }

    private String langCode() {
        return targetLang.get().code;
    }

    // ---- chat (called by TranslateChatMixin) ----

    /**
     * Rewrites an incoming chat component to append a cached translation. On a cache miss the
     * fetch is queued and the component is returned unchanged; with re-print enabled the
     * translation is printed locally as soon as it arrives.
     */
    public static Component transformChat(Component original) {
        TranslateModule m = instance;
        if (m == null || !m.isEnabled() || !m.chat.get() || original == null) return original;
        String text = original.getString();
        if (text == null || text.isBlank()) return original;
        String lang = m.langCode();
        boolean reprint = m.chatReprint.get();

        String cached = TranslationEngine.lookup(text, lang, reprint
            ? translated -> Minecraft.getInstance().execute(() -> printTranslated(translated))
            : null);

        if (cached == null) return original;
        MutableComponent out = original.copy();
        out.append(Component.literal("\n" + cached).withStyle(ChatFormatting.GRAY));
        return out;
    }

    /** Locally prints a finished chat translation (grey, prefixed to read as a translation). */
    private static void printTranslated(String translated) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.sendSystemMessage(Component.literal("  " + translated)
            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    // ---- item tooltips (called by ModuleRegistry.appendTooltip) ----

    @Override
    public void appendTooltip(ItemStack stack, List<?> lines) {
        if (!isEnabled() || !items.get() || stack == null || stack.isEmpty() || lines == null) return;
        String lang = langCode();
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<Component> raw = (List) lines;
        int limit = itemName.get() ? raw.size() : Math.max(0, raw.size() - 1); // skip name when off
        int appended = 0;
        for (int i = 0; i < raw.size() && appended < limit; i++) {
            if (!itemName.get() && i == 0) continue; // first line is the item name
            Component line = raw.get(i);
            if (line == null) continue;
            String text = line.getString();
            if (text == null || text.isBlank()) continue;
            String cached = TranslationEngine.lookup(text, lang, null);
            if (cached != null && appended < 12) {
                raw.add(Component.literal("  " + cached).withStyle(ChatFormatting.DARK_GRAY));
                appended++;
            }
        }
    }

    @Override
    public String info() {
        return targetLang.get().code;
    }
}
