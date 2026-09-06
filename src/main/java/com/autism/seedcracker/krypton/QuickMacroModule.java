package com.autism.seedcracker.krypton;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.StringListSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Quick Macro.
 *
 * Runs one (or all) of a list of commands the moment the module is enabled, then turns itself
 * back off. Bind the toggle to a key for a one-press command macro.
 *
 * Port of the Krypton "QuickMacro" module to the AUTISM API (Mojang 26.2).
 */
public final class QuickMacroModule extends Module {

    private final StringListSetting commands = add(new StringListSetting(
            "commands", "Commands", "spawn")
        .description("Commands to run (| separated, or one per entry). Leading / optional.")
        .group("General"));
    private final BoolSetting executeAll = add(new BoolSetting("execute-all", "Execute all", false)
        .description("Run every command in the list; off = only the first.")
        .group("General"));
    private final BoolSetting notify = add(new BoolSetting("notify", "Notifications", true)
        .description("Chat notification when commands run.")
        .group("General"));

    public QuickMacroModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":quick-macro", "Quick Macro", category,
            "Run command(s) on enable, then disable. Bind the toggle to a key.");
    }

    @Override
    public void onEnable() {
        run();
        setEnabledSilently(false);
    }

    private void run() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        List<String> list = commands.get();
        if (list == null || list.isEmpty()) {
            if (notify.get()) AutismClientMessaging.sendPrefixed("§cQuick Macro: no commands configured.");
            return;
        }
        if (executeAll.get()) {
            for (String cmd : list) send(mc, cmd);
            if (notify.get()) AutismClientMessaging.sendPrefixed("§aQuick Macro: ran " + list.size() + " command(s).");
        } else {
            send(mc, list.get(0));
            if (notify.get()) AutismClientMessaging.sendPrefixed("§aQuick Macro: ran command.");
        }
    }

    private void send(Minecraft mc, String command) {
        if (command == null) return;
        String c = command.trim();
        if (c.isEmpty()) return;
        if (c.startsWith("/")) mc.getConnection().sendCommand(c.substring(1));
        else mc.getConnection().sendChat(c);
    }
}
