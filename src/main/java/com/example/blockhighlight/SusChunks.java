package com.example.blockhighlight;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scores loaded chunks by how many "player-made looking" blocks they contain
 * (redstone parts, chests, hoppers, concrete, glass, signs, storage blocks...).
 * Chunks over the threshold are marked red. It is a guess, not proof: villages,
 * strongholds, bastions etc. also contain some of these blocks.
 */
public final class SusChunks {
    record Sus(int cx, int cz, int score, BlockPos pos) {}

    private static final int RADIUS_CHUNKS = 12;
    private static final int CHUNKS_PER_TICK = 3;
    private static final int MAX_DRAWN = 40;

    private static Map<Block, Double> weights;
    private static final Map<Long, Sus> results = new HashMap<>();
    private static final Set<Long> alerted = new HashSet<>();
    private static final List<Long> queue = new ArrayList<>();
    private static List<Sus> drawList = List.of();
    private static Object lastLevel;
    private static int refillTimer;

    private SusChunks() {}

    public static List<Sus> drawList() { return drawList; }

    private static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }
    private static int keyX(long k) { return (int) (k >> 32); }
    private static int keyZ(long k) { return (int) k; }

    // ---------- block weights ----------

    private static void buildWeights() {
        weights = new HashMap<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            String p = BuiltInRegistries.BLOCK.getKey(b).getPath();
            double w = weightFor(p);
            if (w > 0) weights.put(b, w);
        }
    }

    private static double weightFor(String p) {
        switch (p) {
            case "redstone_wire": case "repeater": case "comparator": case "observer": case "piston":
            case "sticky_piston": case "dispenser": case "dropper": case "hopper": case "redstone_lamp":
            case "note_block": case "daylight_detector": case "target": case "tnt": case "redstone_block":
            case "slime_block": case "honey_block": case "scaffolding": case "crafter": case "ender_chest":
            case "brewing_stand": case "enchanting_table":
                return 2;
            case "chest": case "trapped_chest": case "barrel": case "furnace": case "blast_furnace":
            case "smoker": case "anvil": case "jukebox": case "iron_block": case "gold_block":
            case "lapis_block": case "coal_block":
                return 1;
            case "crafting_table": case "lectern":
                return 0.5;
            case "diamond_block": case "emerald_block": case "netherite_block": case "conduit":
            case "lodestone": case "respawn_anchor":
                return 4;
            case "beacon":
                return 5;
            case "nether_portal":
                return 5;
            case "amethyst_cluster":
                return 0.4; // only counted when the geode option is on
            case "glass": case "glass_pane":
                return 0.5;
            default:
        }
        if (p.endsWith("shulker_box")) return 1.5;
        if (p.endsWith("_bed")) return 1;
        if (p.endsWith("_concrete") || p.endsWith("_concrete_powder") || p.endsWith("glazed_terracotta")) return 1.5;
        if (p.endsWith("stained_glass") || p.endsWith("stained_glass_pane")) return 0.5;
        if (p.endsWith("_sign") || p.endsWith("_wall_sign") || p.endsWith("_hanging_sign") || p.endsWith("_wall_hanging_sign")) return 1;
        if (p.endsWith("_banner") || p.endsWith("_wall_banner")) return 1;
        return 0;
    }

    // ---------- scanning ----------

    public static void tick(Minecraft mc) {
        HighlightConfig cfg = HighlightConfig.INSTANCE;
        if (mc.level == null || mc.player == null || !cfg.susChunks) {
            results.clear(); queue.clear(); drawList = List.of();
            if (mc.level == null) { alerted.clear(); lastLevel = null; }
            return;
        }
        if (weights == null) buildWeights();
        if (mc.level != lastLevel) { lastLevel = mc.level; results.clear(); alerted.clear(); queue.clear(); }

        BlockPos c = Freecam.center(mc);
        int ccx = c.getX() >> 4, ccz = c.getZ() >> 4;

        if (queue.isEmpty() && ++refillTimer >= 60) {
            refillTimer = 0;
            List<long[]> tmp = new ArrayList<>();
            for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++)
                for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++)
                    tmp.add(new long[]{key(ccx + dx, ccz + dz), (long) dx * dx + (long) dz * dz});
            tmp.sort((a, b) -> Long.compare(a[1], b[1]));
            Set<Long> keep = new HashSet<>();
            for (long[] t : tmp) { queue.add(t[0]); keep.add(t[0]); }
            results.keySet().removeIf(k -> !keep.contains(k));
        }

        for (int i = 0; i < CHUNKS_PER_TICK && !queue.isEmpty(); i++) {
            long k = queue.remove(0);
            int cx = keyX(k), cz = keyZ(k);
            LevelChunk chunk = mc.level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) { results.remove(k); continue; }
            Sus s = scanChunk(chunk, cx, cz, cfg);
            if (s == null) { results.remove(k); continue; }
            results.put(k, s);
            if (alerted.add(k)) StashFinder.push(mc, StashFinder.Kind.SUS, s.pos(), s.score());
        }

        List<Sus> list = new ArrayList<>(results.values());
        list.sort((a, b) -> Long.compare(dist(a, ccx, ccz), dist(b, ccx, ccz)));
        drawList = list.size() > MAX_DRAWN ? new ArrayList<>(list.subList(0, MAX_DRAWN)) : list;
    }

    private static long dist(Sus s, int ccx, int ccz) {
        long dx = s.cx() - ccx, dz = s.cz() - ccz;
        return dx * dx + dz * dz;
    }

    private static Sus scanChunk(LevelChunk chunk, int cx, int cz, HighlightConfig cfg) {
        LevelChunkSection[] sections = chunk.getSections();
        double score = 0, sx = 0, sy = 0, sz = 0;
        int n = 0;
        List<BlockPos> pts = new ArrayList<>();
        int minX = cx << 4, minZ = cz << 4;

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec == null || sec.hasOnlyAir()) continue;
            if (!sec.maybeHas(st -> weights.containsKey(st.getBlock()))) continue;
            int baseY = (chunk.getMinSectionY() + i) << 4;
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++) {
                        Block b = sec.getBlockState(x, y, z).getBlock();
                        Double w = weights.get(b);
                        if (w == null) continue;
                        if (b == Blocks.AMETHYST_CLUSTER && !cfg.susGeodes) continue;
                        score += w;
                        sx += minX + x; sy += baseY + y; sz += minZ + z; n++;
                        if (pts.size() < 500) pts.add(new BlockPos(minX + x, baseY + y, minZ + z));
                    }
        }
        if (n == 0 || score < cfg.susThreshold) return null;

        double mx = sx / n, my = sy / n, mz = sz / n;
        BlockPos best = pts.get(0);
        double bd = Double.MAX_VALUE;
        for (BlockPos p : pts) {
            double d = (p.getX() - mx) * (p.getX() - mx) + (p.getY() - my) * (p.getY() - my) + (p.getZ() - mz) * (p.getZ() - mz);
            if (d < bd) { bd = d; best = p; }
        }
        return new Sus(cx, cz, (int) score, best);
    }
}
