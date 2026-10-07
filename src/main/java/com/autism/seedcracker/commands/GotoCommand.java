package com.autism.seedcracker.commands;

import com.autism.seedcracker.motion.Motion;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import autismclient.commands.AutismCommandSource;
import autismclient.commands.Command;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Built-in pathing without Baritone. ".goto x z", ".goto x y z", ".goto stop", ".goto mine x y z"
 * (allowed to break blocks). The GoTo module drives the engine each tick.
 */
public final class GotoCommand extends Command {
    public GotoCommand() {
        super("goto", "Built-in pathfinder: .goto [mine] x [y] z | y <level> | away <blocks> | explore <radius> | ore <block,...> [count] | structure <type> | follow <player> | base | death | debug | stop", "path");
    }

    @Override
    public void build(LiteralArgumentBuilder<AutismCommandSource> root) {
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("ore")
            .then(RequiredArgumentBuilder.<AutismCommandSource, String>argument("blocks", com.mojang.brigadier.arguments.StringArgumentType.word())
                .executes(ctx -> mineOre(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "blocks"), 64))
                .then(RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("count", IntegerArgumentType.integer(1, 2304))
                    .executes(ctx -> mineOre(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "blocks"),
                        IntegerArgumentType.getInteger(ctx, "count"))))));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("stop").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                com.autism.seedcracker.motion.MineTask.stop(mc);
                Motion.stop(mc);
            });
            AutismClientMessaging.sendPrefixed("§7[GoTo] stopped.");
            return SUCCESS;
        }));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("debug").executes(ctx -> {
            Minecraft.getInstance().execute(() -> {
                boolean on = com.autism.seedcracker.motion.GoToModule.toggleDebug();
                var log = com.autism.seedcracker.motion.MotionDebug.logPath();
                AutismClientMessaging.sendPrefixed(on ? "\u00a7a[GoTo] debug ON" + (log != null ? "\u00a77 - motion-errors.log + motion-trace.log in " + log : "")
                    : "\u00a77[GoTo] debug off.");
            });
            return SUCCESS;
        }));
        root.then(coords(false));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("mine").then(coords(true)));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("y")
            .then(RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("level", IntegerArgumentType.integer(-64, 320))
                .executes(ctx -> {
                    int y = IntegerArgumentType.getInteger(ctx, "level");
                    run(mc -> Motion.goToY(mc, y, true), "Y " + y, true);
                    return SUCCESS;
                })));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("structure")
            .then(RequiredArgumentBuilder.<AutismCommandSource, String>argument("type", com.mojang.brigadier.arguments.StringArgumentType.word())
                .executes(ctx -> {
                    String type = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "type");
                    var want = com.autism.seedcracker.seedmap.SeedStructures.parse(type);
                    var hit = com.autism.seedcracker.modules.SeedMapModule.current().stream()
                        .filter(h -> want.contains(h.type())).findFirst().orElse(null);
                    if (hit == null) {
                        AutismClientMessaging.sendPrefixed("\u00a7e[GoTo] No " + type + " in the Seed Map list (enable Seed Map, add it to Structures).");
                        return SUCCESS;
                    }
                    run(mc -> Motion.goToXZ(mc, hit.blockX(), hit.blockZ(), Motion.Backend.BUILT_IN, false),
                        hit.type().label + " at " + hit.blockX() + " " + hit.blockZ(), false);
                    return SUCCESS;
                })));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("explore")
            .then(RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("radius", IntegerArgumentType.integer(64, 20000))
                .executes(ctx -> {
                    int r = IntegerArgumentType.getInteger(ctx, "radius");
                    run(mc -> Motion.explore(mc, r), "explore " + r + " blocks around you", false);
                    return SUCCESS;
                })));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("elytra")
            .then(RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("x", IntegerArgumentType.integer())
                .then(RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("z", IntegerArgumentType.integer())
                    .executes(ctx -> {
                        int x = IntegerArgumentType.getInteger(ctx, "x");
                        int z = IntegerArgumentType.getInteger(ctx, "z");
                        run(mc -> com.autism.seedcracker.modules.ElytraTravelModule.start(x, z),
                            "elytra to " + x + " " + z, false);
                        return SUCCESS;
                    }))));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("away")
            .then(RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("blocks", IntegerArgumentType.integer(4, 500))
                .executes(ctx -> {
                    int d = IntegerArgumentType.getInteger(ctx, "blocks");
                    run(mc -> Motion.runAway(mc, d), d + " blocks away", false);
                    return SUCCESS;
                })));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("follow")
            .then(RequiredArgumentBuilder.<AutismCommandSource, String>argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                .executes(ctx -> {
                    String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player");
                    run(mc -> Motion.follow(mc, name), name, false);
                    return SUCCESS;
                })));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("death").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            String dim = mc.level == null ? "" : mc.level.dimension().identifier().toString();
            BlockPos d = com.autism.seedcracker.modules.DeathWaypointModule.lastDeath(dim);
            if (d == null) {
                AutismClientMessaging.sendPrefixed("\u00a7e[GoTo] No death recorded in this dimension (enable Death Waypoint).");
                return SUCCESS;
            }
            run(m -> Motion.goNear(m, d, 2, Motion.Backend.BUILT_IN, false), "your death spot " + d.toShortString(), false);
            return SUCCESS;
        }));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("base").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return SUCCESS;
            var bases = com.autism.seedcracker.finder.BaseTracker.fusedNearest(mc.player.getX(), mc.player.getZ(), 1);
            if (bases.isEmpty()) {
                AutismClientMessaging.sendPrefixed("\u00a7e[GoTo] No suspected base tracked yet (run a finder).");
                return SUCCESS;
            }
            var b = bases.get(0);
            run(m -> Motion.goToXZ(m, b.blockX(), b.blockZ(), Motion.Backend.BUILT_IN, false),
                "best base lead " + b.blockX() + " " + b.blockZ() + " (" + b.confidence() + "%)", false);
            return SUCCESS;
        }));
    }

    /** {@code diamond_ore,deepslate_diamond_ore} (comma list; "diamond" expands to both ore variants). */
    private int mineOre(String list, int count) {
        java.util.Set<net.minecraft.world.level.block.Block> blocks = new java.util.HashSet<>();
        for (String raw : list.split(",")) {
            String name = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (name.isEmpty()) continue;
            // The bare name too: ancient_debris, gilded_blackstone, amethyst_cluster aren't "*_ore".
            for (String id : name.contains("ore") || name.contains(":") ? java.util.List.of(name)
                : java.util.List.of(name, name + "_ore", "deepslate_" + name + "_ore", "nether_" + name + "_ore")) {
                var key = net.minecraft.resources.Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
                if (key == null) continue;
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(key).ifPresent(blocks::add);
            }
        }
        blocks.remove(net.minecraft.world.level.block.Blocks.AIR);
        if (blocks.isEmpty()) {
            AutismClientMessaging.sendPrefixed("\u00a7c[GoTo] Unknown block(s): " + list);
            return SUCCESS;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (!com.autism.seedcracker.motion.GoToModule.ensureEnabled()) return;
            boolean ok = com.autism.seedcracker.motion.MineTask.start(mc, blocks, count);
            AutismClientMessaging.sendPrefixed(ok ? "\u00a7a[GoTo] mining up to " + count + " visible " + list
                : "\u00a7e[GoTo] no exposed " + list + " in the loaded chunks nearby.");
        });
        return SUCCESS;
    }

    private RequiredArgumentBuilder<AutismCommandSource, Integer> coords(boolean mine) {
        return RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("x", IntegerArgumentType.integer())
            .then(RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("z", IntegerArgumentType.integer())
                .executes(ctx -> {
                    int x = IntegerArgumentType.getInteger(ctx, "x");
                    int z = IntegerArgumentType.getInteger(ctx, "z");
                    run(mc -> Motion.goToXZ(mc, x, z, Motion.Backend.BUILT_IN, mine), x + " " + z, mine);
                    return SUCCESS;
                })
                .then(RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("z2", IntegerArgumentType.integer())
                    .executes(ctx -> {
                        int x = IntegerArgumentType.getInteger(ctx, "x");
                        int y = IntegerArgumentType.getInteger(ctx, "z");
                        int z = IntegerArgumentType.getInteger(ctx, "z2");
                        run(mc -> Motion.goTo(mc, new BlockPos(x, y, z), Motion.Backend.BUILT_IN, mine), x + " " + y + " " + z, mine);
                        return SUCCESS;
                    })));
    }

    private static void run(java.util.function.Predicate<Minecraft> start, String where, boolean mine) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (!com.autism.seedcracker.motion.GoToModule.ensureEnabled()) {
                AutismClientMessaging.sendPrefixed("§c[GoTo] couldn't enable the GoTo module.");
                return;
            }
            boolean ok = start.test(mc);
            AutismClientMessaging.sendPrefixed(ok ? "§a[GoTo] heading to " + where + (mine ? " (mining allowed)" : "")
                : "§c[GoTo] couldn't start.");
            if (ok) Motion.onFinish(() -> {
                String s = Motion.status();
                AutismClientMessaging.sendPrefixed((s.equals("arrived") ? "§a" : "§e") + "[GoTo] " + s + ".");
            });
        });
    }
}
