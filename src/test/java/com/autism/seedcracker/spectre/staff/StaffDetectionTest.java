// SPDX-License-Identifier: GPL-3.0-only
// Regression checks for the Spectre staff port (upstream d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da).
package com.autism.seedcracker.spectre.staff;

import java.lang.reflect.InvocationTargetException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StaffDetectionTest {
    private static StaffMatcher matcher(String names, String ignored) {
        return new StaffMatcher(StaffMatcher.MODE_ALL, StaffMatcher.DEFAULT_RANK_WORDS, StaffMatcher.DEFAULT_MARKERS,
            false, StaffMatcher.DEFAULT_TEAM_RANK_MAX, names, ignored);
    }

    @Test
    void accountNamesAreNotStaffPrefixes() {
        var matcher = matcher("", "");
        assertNull(matcher.match("Admin_Steve", "50-Admin_Steve", "[VIP] Admin_Steve"));
        assertNull(matcher.match("Mod", null, "Mod"));
        assertEquals("Mod", matcher.match("Mod", null, "[Moderator] Mod").rank());
        assertEquals("Sr. Mod", matcher.match("Alex", null, "[Sr. Mod] Alex").rank());
    }

    @Test
    void ranksMarkersAndTeamOrderingSurviveFormatting() {
        var matcher = matcher("", "");
        assertEquals("Admin", matcher.match("Alex", null, "§c[ᴀᴅᴍɪɴ] §fAlex").rank());
        assertEquals("03", matcher.match("Alex", "03-Alex", "Alex").rank());
        assertEquals("Staff", matcher.match("Alex", null, "★ Alex").rank());
        assertNull(matcher.match("Alex", "09-Alex", "Alex"));
        assertEquals(-1, StaffMatcher.teamRank("3-Alex"));
        assertEquals(-1, StaffMatcher.teamRank(null));
    }

    @Test
    void explicitIgnoreOverridesRosterAndDecorations() {
        var matcher = matcher("Alex", "alex");
        assertNull(matcher.match("ALEX", "00-ALEX", "[Owner] ALEX"));
        assertEquals("Staff", matcher("Alex", "").match("aLeX", null, "aLeX").rank());
    }

    @Test
    void leaveMessagesRequireWholeAccountNamesAndARealDeparturePhrase() {
        assertTrue(AntiVanishText.looksLikeLeaveMessage("§e[Server] FluffyMaster07 left the game", "FluffyMaster07"));
        assertTrue(AntiVanishText.looksLikeLeaveMessage("FluffyMaster07 has quit", "FluffyMaster07"));
        assertFalse(AntiVanishText.looksLikeLeaveMessage("FakeFluffyMaster07 left the game", "FluffyMaster07"));
        assertFalse(AntiVanishText.looksLikeLeaveMessage("FluffyMaster07: I left the mine", "FluffyMaster07"));
        assertFalse(AntiVanishText.looksLikeLeaveMessage("FluffyMaster07 joined the game", "FluffyMaster07"));
        assertFalse(AntiVanishText.containsPlayerName("_Alex_", "Alex"));
        assertFalse(AntiVanishText.containsPlayerName(null, "Alex"));
        assertEquals("admin", AntiVanishText.normalize("§cＡᴅᴍɪɴ\u200b"));
    }

    @Test
    void joinBundlesAndBulkUnlistingAreNotVanishEvents() {
        assertFalse(AntiVanishHeuristics.inspectTabHide(true, 1));
        assertFalse(AntiVanishHeuristics.inspectTabHide(false, 0));
        assertFalse(AntiVanishHeuristics.inspectTabHide(false, 4));
        assertTrue(AntiVanishHeuristics.inspectTabHide(false, 1));
        assertTrue(AntiVanishHeuristics.inspectTabHide(false, 3));
    }

    @Test
    void correlationNeedsIndependentTypesAndAnIdentifiedNearbySubject() {
        assertFalse(AntiVanishHeuristics.corroborated(1, 100, true));
        assertFalse(AntiVanishHeuristics.corroborated(2, 34, true));
        assertFalse(AntiVanishHeuristics.corroborated(2, 100, false));
        assertTrue(AntiVanishHeuristics.corroborated(2, 35, true));
        assertEquals(30, AntiVanishHeuristics.silentTabRemovalScore(false));
        assertEquals(100, AntiVanishHeuristics.silentTabRemovalScore(true));
        assertFalse(AntiVanishHeuristics.matureListing(1000, 2499));
        assertTrue(AntiVanishHeuristics.matureListing(1000, 2500));
        assertFalse(AntiVanishHeuristics.matureListing(2500, 1000));
    }

    @Test
    void departureRetractionOnlyTargetsTabDepartureEvidence() {
        assertTrue(AntiVanishHeuristics.tabDepartureReason("TAB visibility: silent TAB disappearance"));
        assertTrue(AntiVanishHeuristics.tabDepartureReason("TAB visibility: entity remained"));
        assertFalse(AntiVanishHeuristics.tabDepartureReason("TAB visibility: switched to spectator"));
        assertFalse(AntiVanishHeuristics.tabDepartureReason("Invisible Entity: metadata flag"));
        assertFalse(AntiVanishHeuristics.tabDepartureReason(null));
    }

    @Test
    void sensorFiltersExcludeOrdinaryWeatherAndFootsteps() {
        assertTrue(AntiVanishHeuristics.suspiciousSound("minecraft:block.chest.open"));
        assertTrue(AntiVanishHeuristics.suspiciousSound("minecraft:block.wooden_door.close"));
        assertFalse(AntiVanishHeuristics.suspiciousSound("minecraft:block.grass.step"));
        assertFalse(AntiVanishHeuristics.suspiciousSound(null));
        assertTrue(AntiVanishHeuristics.suspiciousParticle("minecraft:damage_indicator"));
        assertFalse(AntiVanishHeuristics.suspiciousParticle("minecraft:rain"));
    }

    @Test
    void whitelistAndArrivalDiffPreserveFreecameraAndDoNotReactToLeaves() {
        var whitelist = PlayerDetectionLogic.whitelist(" friend, ALT, ,friend ");
        assertTrue(PlayerDetectionLogic.ignored("FRIEND", "Self", whitelist));
        assertTrue(PlayerDetectionLogic.ignored("FREECAMERA", "Self", whitelist));
        assertTrue(PlayerDetectionLogic.ignored("SELF", "Self", whitelist));
        assertTrue(PlayerDetectionLogic.ignored(null, "Self", whitelist));
        assertFalse(PlayerDetectionLogic.ignored("NewPlayer", "Self", whitelist));
        assertEquals(Set.of("New"), PlayerDetectionLogic.entered(Set.of("Old"), Set.of("Old", "New")));
        assertTrue(PlayerDetectionLogic.entered(Set.of("Old", "New"), Set.of("Old")).isEmpty());
        assertEquals(Set.of("freecamera"), PlayerDetectionLogic.whitelist(null));
    }

    @Test
    void optInPayCannotInjectAnotherCommandOrInvalidAmount() {
        assertEquals("pay Friend 1.5m", PlayerDetectionLogic.paymentCommand(" Friend ", " 1.5M "));
        assertEquals("pay .Bedrock 100", PlayerDetectionLogic.paymentCommand(".Bedrock", "100"));
        for (String invalid : new String[] {"0", "0.0000", "-1", "NaN", "Infinity", "all", "1\npay Somebody 100", "9".repeat(40)}) {
            assertNull(PlayerDetectionLogic.paymentCommand("Friend", invalid), invalid);
        }
        for (String invalid : new String[] {"", "bad name", "Friend\npay", "@a", "a".repeat(17)}) {
            assertNull(PlayerDetectionLogic.paymentCommand(invalid, "100"), invalid);
        }
        assertNull(PlayerDetectionLogic.paymentCommand(null, null));
    }

    @Test
    void nameFilteringHasNullFallbackAndCanBeReplacedWithoutStateLeak() {
        try {
            NameFilter.set(name -> "Hidden");
            assertEquals("Hidden", NameFilter.apply("FluffyMaster07"));
            assertNull(NameFilter.apply(null));
            NameFilter.set(name -> null);
            assertEquals("FluffyMaster07", NameFilter.apply("FluffyMaster07"));
        } finally {
            NameFilter.set(null);
        }
    }

    @Test
    void goliathLookupRespectsRectanglesAndExactMapEdges() {
        assertEquals("Oceania 1", DonutGoliathMapData.name(DonutGoliathMapData.goliathAt(-225000, -225000)));
        assertEquals("NA West 1", DonutGoliathMapData.name(DonutGoliathMapData.goliathAt(-175000, -225000)));
        assertNotEquals(DonutGoliathMapData.goliathAt(-175000.001, -225000), DonutGoliathMapData.goliathAt(-175000, -225000));
        assertEquals(DonutGoliathMapData.goliathAt(-175000, -225000), DonutGoliathMapData.goliathAt(-150000.001, -175000.001));
        assertNotEquals(DonutGoliathMapData.goliathAt(-175000, -225000), DonutGoliathMapData.goliathAt(-150000, -225000));
        assertEquals(-1, DonutGoliathMapData.goliathAt(-225000.001, 0));
        assertEquals(-1, DonutGoliathMapData.goliathAt(225000, 0));
        assertEquals(-1, DonutGoliathMapData.goliathAt(Double.NaN, 0));
        assertEquals(-1, DonutGoliathMapData.goliathAt(0, Double.POSITIVE_INFINITY));
    }

    @Test
    void shardTrackerHandlesRespawnBrandGapsAndConnectionReplacement() {
        var tracker = new ShardTracker();
        Object world = new Object(), player = new Object(), connection = new Object();
        var first = new ShardTracker.Snapshot(world, player, connection, "A", 1);
        assertFalse(tracker.update(first, false));
        assertFalse(tracker.update(first, false));
        Object respawn = new Object();
        assertFalse(tracker.update(new ShardTracker.Snapshot(world, respawn, connection, "A", 1), true));
        assertFalse(tracker.update(new ShardTracker.Snapshot(world, respawn, connection, null, 1), false));
        assertTrue(tracker.update(new ShardTracker.Snapshot(world, respawn, connection, "B", 1), false));
        assertTrue(tracker.update(new ShardTracker.Snapshot(world, respawn, connection, "B", 2), false));
        assertTrue(tracker.update(new ShardTracker.Snapshot(world, respawn, new Object(), "B", 2), false));
        tracker.clear();
        assertFalse(tracker.update(first, false));
        assertTrue(tracker.update(new ShardTracker.Snapshot(new Object(), player, connection, "A", 1), false));
    }

    /** Standalone entry point uses the existing JUnit assertions without starting Gradle or Minecraft. */
    public static void main(String[] args) throws Exception {
        StaffDetectionTest checks = new StaffDetectionTest();
        int count = 0;
        for (var method : StaffDetectionTest.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Test.class)) continue;
            try {
                method.invoke(checks);
            } catch (InvocationTargetException failure) {
                throw new AssertionError(method.getName(), failure.getCause());
            }
            count++;
        }
        System.out.println("StaffDetectionTest: " + count + " checks passed");
    }
}
