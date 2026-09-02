package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * Home Setter.
 *
 * A one-shot module: enabling it deletes the configured home slot, waits a beat, then sets it to
 * your current position by running the server's home commands, then auto-disables. Works with
 * both /delhome+sethome style plugins and /home+sethome style via a custom command template.
 *
 * Clean-room port of the obfuscated Zelith "HomeSetter" module.
 */
public final class HomeSetterModule extends Module {

    private final IntSetting slot = add(new IntSetting("slot", "Home slot", 1, 1, 10, 1)
        .description("Which home slot number to set.")
        .group("General"));
    private final BoolSetting deleteFirst = add(new BoolSetting("delete-first", "Delete old home first", true)
        .description("Run the delete command before setting, to overwrite an existing home.")
        .group("General"));
    private final StringSetting setCommand = add(new StringSetting("set-command", "Set command", "sethome %slot%")
        .description("Command used to set the home. %slot% is replaced with the slot number.")
        .group("General"));
    private final StringSetting deleteCommand = add(new StringSetting("delete-command", "Delete command", "delhome %slot%")
        .description("Command used to delete the home. %slot% is replaced with the slot number.")
        .group("General"));
    private final IntSetting delayMs = add(new IntSetting("delay-ms", "Delay (ms)", 750, 100, 5000, 50)
        .description("Milliseconds to wait between the delete and set commands.")
        .group("General"));

    private volatile boolean running = false;

    public HomeSetterModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-home-setter", "Home Setter", category,
            "One-shot: sets a home at your current position by running the server home commands.");
    }

    @Override
    public void onEnable() {
        if (running) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) {
            AutismClientMessaging.sendPrefixed("§c[Home Setter] Join a world first.");
            setEnabledSilently(false);
            return;
        }
        running = true;
        int homeSlot = slot.get();
        int wait = delayMs.get();
        boolean doDelete = deleteFirst.get();
        String setCmd = buildCommand(setCommand.get(), homeSlot);
        String delCmd = buildCommand(deleteCommand.get(), homeSlot);

        AutismClientMessaging.sendPrefixed("§7[Home Setter] Setting home §f" + homeSlot + " §7at your position...");

        // Run the delete on the client thread, then set after a delay off-thread.
        if (doDelete) {
            sendCommand(mc, delCmd);
        }
        Thread delayThread = new Thread(() -> {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            mc.execute(() -> {
                sendCommand(mc, setCmd);
                AutismClientMessaging.sendPrefixed("§a[Home Setter] Home §f" + homeSlot + " §aset.");
                running = false;
                setEnabledSilently(false);
            });
        }, "HomeSetter-Delay");
        delayThread.setDaemon(true);
        delayThread.start();
    }

    @Override
    public void onDisable() {
        running = false;
    }

    private static String buildCommand(String template, int slotNumber) {
        String cmd = template == null || template.isBlank() ? "sethome %slot%" : template.trim();
        cmd = cmd.replace("%slot%", Integer.toString(slotNumber));
        return cmd.startsWith("/") ? cmd.substring(1) : cmd;
    }

    private static void sendCommand(Minecraft mc, String command) {
        if (command == null || command.isBlank()) return;
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return;
        try {
            connection.sendCommand(command);
        } catch (Throwable t) {
            try {
                connection.sendChat("/" + command);
            } catch (Throwable ignored) {
            }
        }
    }
}
