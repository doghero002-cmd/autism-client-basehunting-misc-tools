// SPDX-License-Identifier: GPL-3.0-only
// Adapted from https://github.com/cody-raves/Spectre, commit d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
// Upstream: src/main/java/net/spectre/client/util/antivanish/AntiVanishHeuristics.java
package com.autism.seedcracker.spectre.staff;

import java.util.Locale;
public final class AntiVanishHeuristics {
   private AntiVanishHeuristics() {
   }

   public static boolean suspiciousSound(String id) {
      String path = path(id);
      return path.contains("chest.open")
         || path.contains("chest.close")
         || path.contains("chest.locked")
         || path.contains("barrel.open")
         || path.contains("barrel.close")
         || path.contains("shulker_box.open")
         || path.contains("shulker_box.close")
         || path.contains("door.open")
         || path.contains("door.close")
         || path.contains("fence_gate.open")
         || path.contains("fence_gate.close")
         || path.contains("button.click")
         || path.contains("lever.click");
   }

   public static boolean suspiciousParticle(String id) {
      String path = path(id);
      return path.contains("crit") || path.contains("enchanted_hit") || path.contains("damage_indicator") || path.contains("smoke") || path.equals("block");
   }

   public static boolean inspectTabHide(boolean addingPlayer, int unlistedCount) {
      return !addingPlayer && unlistedCount > 0 && unlistedCount < 4;
   }

   public static boolean matureListing(long listedSinceMs, long nowMs) {
      return nowMs - listedSinceMs >= 1500L;
   }

   public static int silentTabRemovalScore(boolean serverSendsLeaveMessages) {
      return serverSendsLeaveMessages ? 100 : 30;
   }

   public static boolean corroborated(int independentKinds, int score, boolean locatedPlayer) {
      return locatedPlayer && independentKinds >= 2 && score >= 35;
   }

   public static boolean tabDepartureReason(String reason) {
      if (reason == null) return false;
      String lower = reason.toLowerCase(Locale.ROOT);
      if (!lower.startsWith("tab visibility:")) return false;
      String detail = lower.substring("tab visibility:".length());
      return detail.contains("tab") || detail.contains("entity remained");
   }

   public static String path(String id) {
      if (id == null) {
         return "unknown";
      } else {
         int split = id.indexOf(58);
         return (split >= 0 && split + 1 < id.length() ? id.substring(split + 1) : id).toLowerCase(Locale.ROOT);
      }
   }
}
