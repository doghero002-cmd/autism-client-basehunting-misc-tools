package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismNotifications;
import net.minecraft.client.Minecraft;

/**
 * Weather Notifier.
 *
 * Watches the current level's rain / thunder state and notifies you whenever it changes
 * (rain starts/stops, thunderstorm starts/ends).
 *
 * Clean-room port of the obfuscated Zelith "WeatherNotifier" module.
 */
public final class WeatherNotifierModule extends Module {

    private final BoolSetting notifyRain = add(new BoolSetting("notify-rain", "Notify rain", true)
        .description("Notify when rain starts or stops.")
        .group("General"));
    private final BoolSetting notifyThunder = add(new BoolSetting("notify-thunder", "Notify thunder", true)
        .description("Notify when a thunderstorm starts or ends.")
        .group("General"));
    private final BoolSetting toast = add(new BoolSetting("toast", "Toast notification", true)
        .description("Show an on-screen toast.")
        .group("Notifications"));
    private final BoolSetting chat = add(new BoolSetting("chat", "Chat message", true)
        .description("Print a chat message.")
        .group("Notifications"));

    private Boolean raining = null;
    private Boolean thundering = null;

    public WeatherNotifierModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-weather-notifier", "Weather Notifier", category,
            "Notifies you when rain or a thunderstorm starts or stops.");
    }

    @Override
    public void onEnable() {
        raining = null;
        thundering = null;
    }

    @Override
    public void onDisable() {
        raining = null;
        thundering = null;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        boolean nowRaining = mc.level.isRaining();
        boolean nowThundering = mc.level.isThundering();

        if (raining == null) {
            raining = nowRaining;
            thundering = nowThundering;
            return;
        }

        if (notifyRain.get()) {
            if (nowRaining && !raining) notify("The rain started.");
            else if (!nowRaining && raining) notify("The rain stopped.");
        }
        if (notifyThunder.get()) {
            if (nowThundering && !thundering) notify("A thunderstorm started.");
            else if (!nowThundering && thundering) notify("The thunderstorm ended.");
        }

        raining = nowRaining;
        thundering = nowThundering;
    }

    private void notify(String message) {
        if (chat.get()) {
            AutismClientMessaging.sendPrefixed("§b[Weather] §f" + message);
        }
        if (toast.get()) {
            AutismNotifications.warning("Weather: " + message);
        }
    }
}
