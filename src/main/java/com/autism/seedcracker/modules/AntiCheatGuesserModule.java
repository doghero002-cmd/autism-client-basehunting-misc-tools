package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.util.FlagLog;

import autismclient.api.module.BoolSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;

/**
 * AntiCheat Guesser (Platinum port).
 *
 * Fingerprints the server's anti-cheat from its transaction/ping ID patterns. Each major AC uses
 * a recognizable ID scheme for its {@link ClientboundPingPacket} (the "transaction" channel):
 *  - Grim: small ids counting DOWN from 0 (-1, -2, -3, ...) in a tight sequence
 *  - Vulcan: ids in the -20000..-25000 range
 *  - Matrix/AAC: ids counting UP from small positives
 *  - Verus: alternating short-range ids
 * After sampling enough pings it reports its best guess, which tells you which bypass profiles
 * to enable.
 */
public final class AntiCheatGuesserModule extends Module {

    private final BoolSetting announce = add(new BoolSetting("announce", "Announce guess", true)
        .description("Chat message when the anti-cheat is identified.").group("General"));

    private final java.util.ArrayDeque<Integer> ids = new java.util.ArrayDeque<>();
    private String guess = "unknown";
    private boolean announced = false;

    public AntiCheatGuesserModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":ac-guesser", "AntiCheat Guesser", category,
            "Fingerprints the server's anti-cheat from transaction/ping ID patterns.");
    }

    @Override
    public void onEnable() {
        ids.clear();
        guess = "unknown";
        announced = false;
    }

    @Override
    public void onGameJoin() {
        ids.clear();
        guess = "unknown";
        announced = false;
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        if (!(packet instanceof ClientboundPingPacket ping)) return false;

        ids.addLast(ping.getId());
        while (ids.size() > 40) ids.removeFirst();
        if (ids.size() >= 10) classify();
        return false;
    }

    private void classify() {
        int[] arr = ids.stream().mapToInt(Integer::intValue).toArray();
        int descending = 0, ascending = 0, vulcanRange = 0, nearZero = 0;
        for (int i = 0; i < arr.length; i++) {
            int id = arr[i];
            if (id <= 0 && id > -1000) nearZero++;
            if (id <= -20000 && id >= -30000) vulcanRange++;
            if (i > 0) {
                if (arr[i] < arr[i - 1]) descending++;
                else if (arr[i] > arr[i - 1]) ascending++;
            }
        }
        int n = arr.length;
        String newGuess;
        if (vulcanRange > n / 2) newGuess = "Vulcan";
        else if (nearZero > n / 2 && descending > (n - 1) * 3 / 4) newGuess = "Grim";
        else if (ascending > (n - 1) * 3 / 4 && arr[0] >= 0) newGuess = "Matrix/AAC-like";
        else if (descending > 0 && ascending > 0 && nearZero > n / 3) newGuess = "Verus-like";
        else newGuess = "unknown (custom?)";

        if (!newGuess.equals(guess)) {
            guess = newGuess;
            announced = false;
        }
        if (!announced && !"unknown".equals(guess)) {
            announced = true;
            FlagLog.info("ACID", "AntiCheatGuesser", "guess=" + guess + " sample=" + java.util.Arrays.toString(
                java.util.Arrays.copyOf(arr, Math.min(10, arr.length))));
            if (announce.get()) {
                AutismClientMessaging.sendPrefixed("§b[AC Guesser] §fAnti-cheat looks like: §e" + guess);
            }
        }
    }

    @Override
    public String info() {
        return guess;
    }
}
