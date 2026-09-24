package com.autism.seedcracker.commands;

import java.util.ArrayList;
import java.util.List;

import com.autism.seedcracker.bedrock.BedrockFinderEngine;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import autismclient.commands.AutismCommandSource;
import autismclient.commands.Command;
import autismclient.util.AutismClientMessaging;

/**
 * Intersects the texture cracker's and bedrock finder's most recent match lists: coordinates
 * that BOTH methods agree on (within a pairing distance, since the two screenshot regions are
 * near each other rather than identical) are almost certainly the real position - each method's
 * false positives die in the intersection. Usage: .crosscheck [maxDist]
 */
public final class CrossCheckCommand extends Command {

    private static final int MAX_REPORTED = 10;

    public CrossCheckCommand() {
        super("crosscheck", "Intersect texture-crack and bedrock-finder matches.", "xc");
    }

    @Override
    public void build(LiteralArgumentBuilder<AutismCommandSource> root) {
        root.executes(ctx -> {
            run(16);
            return SUCCESS;
        });
        root.then(RequiredArgumentBuilder.<AutismCommandSource, Integer>argument("maxDist", IntegerArgumentType.integer(1, 10000))
            .executes(ctx -> {
                run(IntegerArgumentType.getInteger(ctx, "maxDist"));
                return SUCCESS;
            }));
    }

    private static void run(int maxDist) {
        List<long[]> tex = TextureCrackCommand.lastMatches;
        List<long[]> bed = BedrockFinderEngine.lastMatches;
        if (tex.isEmpty() || bed.isEmpty()) {
            msg("§cNeed results from BOTH crackers first: .texcrack solve ("
                + tex.size() + " match(es)) + bedrock finder search (" + bed.size() + " match(es)).");
            return;
        }

        record Pair(long tx, long tz, long bx, long bz, double dist, double texCost, double score) {}
        List<Pair> pairs = new ArrayList<>();
        List<Double> costs = TextureCrackCommand.lastCosts;
        long maxDistSq = (long) maxDist * maxDist;
        for (int ti = 0; ti < tex.size(); ti++) {
            long[] t = tex.get(ti);
            double cost = ti < costs.size() ? costs.get(ti) : 0;
            for (long[] b : bed) {
                long dx = t[0] - b[0], dz = t[1] - b[1];
                long d2 = dx * dx + dz * dz;
                if (d2 > maxDistSq) continue;
                double dist = Math.sqrt(d2);
                // Rank by credibility: an exact texture read close by beats a fuzzy read far away.
                double score = (cost + 1.0) * (dist + 1.0);
                pairs.add(new Pair(t[0], t[1], b[0], b[1], dist, cost, score));
            }
        }
        pairs.sort(java.util.Comparator.comparingDouble(Pair::score));

        if (pairs.isEmpty()) {
            msg("§eNo agreement within " + maxDist + " blocks (" + tex.size() + " texture x "
                + bed.size() + " bedrock). Try .crosscheck " + (maxDist * 4) + ".");
            return;
        }
        msg("§a" + pairs.size() + " agreeing pair(s) within " + maxDist + " blocks (best first):");
        for (int i = 0; i < pairs.size() && i < MAX_REPORTED; i++) {
            Pair p = pairs.get(i);
            msg(String.format("  §aX %d Z %d §7(texture%s) <-> §aX %d Z %d §7(bedrock, %.1f apart)",
                p.tx(), p.tz(), p.texCost() <= 0 ? ", exact" : String.format(", cost %.1f", p.texCost()),
                p.bx(), p.bz(), p.dist()));
        }
        if (pairs.size() > MAX_REPORTED) msg("§7... and " + (pairs.size() - MAX_REPORTED) + " more.");
        Pair best = pairs.get(0);
        msg(String.format("§a§lMost likely: X %d Z %d §7(score %.2f)", best.tx(), best.tz(), best.score()));
    }

    private static void msg(String s) {
        AutismClientMessaging.sendPrefixed(s);
    }
}
