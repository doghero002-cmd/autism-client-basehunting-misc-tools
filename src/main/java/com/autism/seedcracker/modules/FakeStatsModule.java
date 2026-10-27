// Adapted from cody-raves/Spectre d5a7dd3; GPL-3.0-only. See SPECTRE-NOTICES.txt.
package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import autismclient.modules.Module;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.fake.FakeBalance;
import com.autism.seedcracker.spectre.stats.FakeStatsText;
import static com.autism.seedcracker.spectre.stats.FakeStatsText.markerAt;
import static com.autism.seedcracker.spectre.stats.FakeStatsText.fit;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.StringSetting;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.util.StringDecomposer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType;

@Environment(EnvType.CLIENT)
public final class FakeStatsModule extends Module {
   private static final String OBJECTIVE = "dogs_fake_stats";
   private static final int LINES = 15;
   private static final Comparator<PlayerScoreEntry> ORDER = Comparator.comparingInt(PlayerScoreEntry::value)
      .reversed()
      .thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER);
   private static final Pattern MONEY = Pattern.compile("\\$\\s?(\\d[\\d.,]*[KkMmBbTtQq]?)");
   private static volatile FakeStatsModule active;
   private final StringSetting playerName = add(new StringSetting("name", "Name", "")
      .description("Local sidebar alias. Blank keeps your real name.").group("Display"));
   private final BoolSetting paymentBalance = add(new BoolSetting("payment-balance", "Use fake payment balance", true)
      .description("When Money is blank, use the local Fake Identity balance instead of scrambling it.").group("Display"));
   private final FakeStatsModule.Stat money = this.stat("Money", "money", "balance", "$");
   private final List<FakeStatsModule.Stat> stats = List.of(
      this.money,
      this.stat("Shards", "shards", "★"),
      this.stat("Kills", "kills", "\ud83d\udde1", "⚔"),
      this.stat("Deaths", "deaths", "☠"),
      this.stat("Playtime", "playtime", "⌚"),
      this.stat("Team", "team")
   );
   private long seed;
   private Scoreboard board;
   private Objective fake;
   private String sidebarMoney;

   public FakeStatsModule() {
      super(SeedcrackerAddon.ID + ":fake-stats", "Fake Stats",
         "Spectre's local sidebar stats and tab balance. Blank stats are scrambled; server data is unchanged.");
   }

   private FakeStatsModule.Stat stat(String name, String... markers) {
      return new FakeStatsModule.Stat(add(new StringSetting(name.toLowerCase(Locale.ROOT), name, "")
         .description("Local display value; blank scrambles the original numbers.").group("Stats")), markers);
   }

   private String configured(Stat stat) {
      String custom = stat.value().get().strip();
      return stat == money && custom.isEmpty() && paymentBalance.get()
         ? FakeBalance.formatShort(FakeBalance.get()) : custom;
   }

   public static Objective sidebarForRender(Objective original) {
      FakeStatsModule module = active;
      Minecraft mc = Minecraft.getInstance();
      if (module == null || original == null || mc.level == null
         || original.getScoreboard() != mc.level.getScoreboard()) return original;
      return module.install(original);
   }

   public static Component tabName(UUID player, Component name) {
      FakeStatsModule module = active;
      return module != null && name != null && isSelf(player) ? module.fakeTabMoney(name) : name;
   }

   public static FormattedText tabText(FormattedText text) {
      FakeStatsModule module = active;
      return (FormattedText)(module != null && text instanceof Component line ? module.fakeTabMoney(line) : text);
   }

   public static ReadOnlyScoreInfo tabScore(ScoreHolder holder, ReadOnlyScoreInfo real) {
      final FakeStatsModule module = active;
      LocalPlayer player = Minecraft.getInstance().player;
      return module != null && real != null && player != null && player.getScoreboardName().equals(holder.getScoreboardName()) ? new ReadOnlyScoreInfo() {
         @Override
         public int value() {
            return real.value();
         }

         @Override
         public boolean isLocked() {
            return real.isLocked();
         }

         @Override
         public NumberFormat numberFormat() {
            return real.numberFormat();
         }

         @Override
         public MutableComponent formatValue(NumberFormat fallback) {
            return Component.empty().append(module.fakeTabMoney(real.formatValue(fallback)));
         }
      } : real;
   }

   @Override
   public void onEnable() {
      this.seed = ThreadLocalRandom.current().nextLong();
      active = this;
      this.refresh();
   }

   @Override
   public void tick() {
      this.refresh();
   }

   @Override
   public void onDisable() {
      if (active == this) active = null;
      this.restore();
   }

   @Override
   public void onGameLeft() {
      this.restore();
      if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) {
         if (active == this) active = null;
         setEnabledSilently(false);
      }
   }

   private void refresh() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.level == null) {
         this.restore();
         return;
      }
      Scoreboard current = mc.level.getScoreboard();
      if (current != this.board) this.restore();
      Objective server = current.getDisplayObjective(DisplaySlot.SIDEBAR);
      if (server == null) this.sidebarMoney = null;
      else this.install(server);
   }

   private Objective install(Objective server) {
      Scoreboard current = server.getScoreboard();
      if (this.fake == null || current != this.board) {
         // A private render board keeps fake balances out of other modules' scoreboard reads.
         Scoreboard display = new Scoreboard();
         this.fake = display.addObjective(OBJECTIVE, ObjectiveCriteria.DUMMY, Component.empty(),
            RenderType.INTEGER, false, server.numberFormat());
         this.board = current;
      }
      this.fake.setNumberFormat(server.numberFormat());
      this.writeRows(current, this.fake, server);
      return this.fake;
   }

   private void writeRows(Scoreboard current, Objective objective, Objective server) {
      Scoreboard display = objective.getScoreboard();
      List<PlayerScoreEntry> rows = new ArrayList<>();

      for (PlayerScoreEntry entry : current.listPlayerScores(server)) {
         if (!entry.isHidden()) {
            rows.add(entry);
         }
      }

      rows.sort(ORDER);
      if (rows.size() > LINES) {
         rows.subList(LINES, rows.size()).clear();
      }

      Component title = visible(server.getDisplayName());
      Component renamedTitle = this.renamed(title);
      objective.setDisplayName(renamedTitle != null ? renamedTitle : this.mask(title, 0));
      NumberFormat format = server.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
      this.sidebarMoney = null;

      for (int i = 0; i < rows.size(); i++) {
         PlayerScoreEntry row = rows.get(i);
         Component name = visible(PlayerTeam.formatNameForTeam(current.getPlayersTeam(row.owner()), row.ownerName()));
         Component number = visible(row.formatValue(format));
         Component renamed = this.renamed(name);
         FakeStatsModule.Hit hit = renamed == null ? this.find(name.getString(), number.getString()) : null;
         String custom = hit != null ? configured(hit.stat()) : "";
         String shown = null;
         if (renamed != null) {
            name = renamed;
         } else if (custom.isEmpty()) {
            name = this.mask(name, i + 1);
            number = this.mask(number, i + 1);
            if (hit != null) {
               shown = (hit.inNumber() ? number : name).getString().substring(hit.start(), hit.end());
            }
         } else {
            Component target = hit.inNumber() ? number : name;
            shown = fit(target.getString().substring(hit.start(), hit.end()), custom);
            Component swapped = replace(target, List.of(new int[]{hit.start(), hit.end()}), shown);
            name = hit.inNumber() ? this.mask(name, i + 1) : swapped;
            number = hit.inNumber() ? swapped : this.mask(number, i + 1);
         }

         if (hit != null && hit.stat() == this.money) {
            this.sidebarMoney = shown;
         }

         ScoreHolder holder = ScoreHolder.forNameOnly(holderName(i));
         ScoreAccess access = display.getOrCreatePlayerScore(holder, objective);
         access.set(row.value());
         access.display(name);
         access.numberFormatOverride(new FixedFormat(number));
      }

      for (int i = rows.size(); i < LINES; i++) {
         ScoreHolder holder = ScoreHolder.forNameOnly(holderName(i));
         if (display.getPlayerScoreInfo(holder, objective) != null) {
            display.resetSinglePlayerScore(holder, objective);
         }
      }

   }

   private void restore() {
      this.board = null;
      this.fake = null;
      this.sidebarMoney = null;
   }

   private FakeStatsModule.Hit find(String name, String number) {
      for (FakeStatsModule.Stat stat : this.stats) {
         int[] value = valueAfter(name, stat);
         if (value != null && value[0] < value[1]) {
            return new FakeStatsModule.Hit(stat, false, value[0], value[1]);
         }

         if (value != null) {
            return new FakeStatsModule.Hit(stat, true, 0, number.length());
         }

         value = valueAfter(number, stat);
         if (value != null) {
            return new FakeStatsModule.Hit(stat, true, value[0], value[1]);
         }
      }

      return null;
   }

   private static int[] valueAfter(String line, FakeStatsModule.Stat stat) {
      for (String marker : stat.markers()) {
         int at = markerAt(line, marker);
         if (at >= 0) {
            int start = at + marker.length();
            int end = line.length();

            while (start < end && (Character.isWhitespace(line.charAt(start)) || line.charAt(start) == ':')) {
               start++;
            }

            while (end > start && Character.isWhitespace(line.charAt(end - 1))) {
               end--;
            }

            return new int[]{start, end};
         }
      }

      return null;
   }

   private static String selfName() {
      LocalPlayer player = Minecraft.getInstance().player;
      return player != null ? player.getGameProfile().name() : null;
   }

   private Component renamed(Component line) {
      String self = selfName();
      int at = self != null ? markerAt(line.getString(), self) : -1;
      if (at < 0) {
         return null;
      } else {
         String custom = this.playerName.get().strip();
         return (Component)(custom.isEmpty() ? line : replace(line, List.of(new int[]{at, at + self.length()}), custom));
      }
   }

   private String tabMoney() {
      String custom = configured(this.money);
      String shown = custom.isEmpty() ? this.sidebarMoney : custom;
      if (shown == null) {
         return null;
      } else {
         shown = shown.startsWith("$") ? shown.substring(1).strip() : shown.strip();
         return shown.isEmpty() ? null : shown;
      }
   }

   private Component fakeTabMoney(Component line) {
      String shown = this.tabMoney();
      if (shown != null && line.getString().indexOf(36) >= 0) {
         Component clean = visible(line);
         Matcher matcher = MONEY.matcher(clean.getString());
         List<int[]> ranges = new ArrayList<>();

         while (matcher.find()) {
            ranges.add(new int[]{matcher.start(1), matcher.end(1)});
         }

         return (Component)(ranges.isEmpty() ? line : replace(clean, ranges, shown));
      } else {
         return line;
      }
   }

   private static boolean isSelf(UUID player) {
      LocalPlayer self = Minecraft.getInstance().player;
      return self != null && self.getUUID().equals(player);
   }

   private static Component visible(Component line) {
      if (line.getString().indexOf(167) < 0) {
         return line;
      } else {
         MutableComponent out = Component.empty();
         StringBuilder run = new StringBuilder();
         Style[] runStyle = new Style[]{null};
         StringDecomposer.iterateFormatted(line, Style.EMPTY, (index, style, codePoint) -> {
            if (runStyle[0] != null && !style.equals(runStyle[0])) {
               out.append(Component.literal(run.toString()).setStyle(runStyle[0]));
               run.setLength(0);
            }

            runStyle[0] = style;
            run.appendCodePoint(codePoint);
            return true;
         });
         if (!run.isEmpty()) {
            out.append(Component.literal(run.toString()).setStyle(runStyle[0]));
         }

         return out;
      }
   }

   private static MutableComponent replace(Component line, List<int[]> ranges, String text) {
      if (line.getString().isEmpty()) {
         return Component.literal(text);
      } else {
         MutableComponent out = Component.empty();
         int[] pos = new int[]{0};
         int[] next = new int[]{0};
         line.visit((style, piece) -> {
            int start = pos[0];
            int end = start + piece.length();
            int cursor = start;

            while (cursor < end) {
               int[] range = next[0] < ranges.size() ? ranges.get(next[0]) : null;
               if (range == null || range[0] >= end) {
                  out.append(Component.literal(piece.substring(cursor - start)).setStyle(style));
                  cursor = end;
               } else if (cursor < range[0]) {
                  out.append(Component.literal(piece.substring(cursor - start, range[0] - start)).setStyle(style));
                  cursor = range[0];
               } else {
                  if (cursor == range[0]) {
                     out.append(Component.literal(text).setStyle(style));
                  }

                  cursor = Math.min(end, range[1]);
                  if (cursor == range[1]) {
                     next[0]++;
                  }
               }
            }

            pos[0] = end;
            return Optional.empty();
         }, Style.EMPTY);
         return out;
      }
   }

   private static String holderName(int row) {
      return new String(new char[]{'\ue000', (char)(57360 + row)});
   }

   private Component mask(Component line, int row) {
      if (line == null) {
         return Component.empty();
      } else {
         MutableComponent out = Component.empty();
         boolean[] changed = new boolean[]{false};
         int[] number = new int[]{0};
         line.visit((style, text) -> {
            String shown = FakeStatsText.maskText(this.seed, text, row, number);
            if (!shown.equals(text)) {
               changed[0] = true;
            }

            out.append(Component.literal(shown).setStyle(style));
            return Optional.empty();
         }, Style.EMPTY);
         return (Component)(changed[0] ? out : line);
      }
   }

   @Environment(EnvType.CLIENT)
   private record Hit(FakeStatsModule.Stat stat, boolean inNumber, int start, int end) {
   }

   @Environment(EnvType.CLIENT)
   private record Stat(StringSetting value, String... markers) {
   }
}
