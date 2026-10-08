package com.example.blockhighlight;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Looks at the block entities (chests, hoppers, furnaces...) in chunks your client has loaded.
 * If one chunk has a lot of them, it pings you in the top right with the coordinates.
 */
public final class StashFinder {
    enum Kind {
        STASH("STASH FOUND", 0xFFFF5555, "containers"),
        BASE("BASE FOUND", 0xFFFFAA00, "base blocks"),
        FARM("FARM FOUND", 0xFF55FF55, "hoppers");
        final String title; final int color; final String unit;
        Kind(String t, int c, String u) { title = t; color = c; unit = u; }
    }

    record Alert(Kind kind, int x, int y, int z, int count, long createdMs) {}

    private static final long SHOW_MS = 12_000;
    private static final int MAX_SHOWN = 5;
    private static final List<Alert> alerts = new ArrayList<>();
    private static final Set<String> seen = new HashSet<>();
    private static Object lastLevel;
    private static int tick;

    private StashFinder() {}

    // ---------- scanning (client tick) ----------

    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) { lastLevel = null; return; }
        if (mc.level != lastLevel) { lastLevel = mc.level; seen.clear(); alerts.clear(); }
        HighlightConfig cfg = HighlightConfig.INSTANCE;
        if (!cfg.stashAlerts || ++tick % 40 != 0) return;

        BlockPos c = Freecam.center(mc);
        int ccx = c.getX() >> 4, ccz = c.getZ() >> 4;
        int r = 32;
        for (int cx = ccx - r; cx <= ccx + r; cx++)
            for (int cz = ccz - r; cz <= ccz + r; cz++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunkNow(cx, cz);
                if (chunk != null) scanChunk(mc, chunk, cx, cz, cfg);
            }
    }

    private static void scanChunk(Minecraft mc, LevelChunk chunk, int cx, int cz, HighlightConfig cfg) {
        List<BlockPos> containers = new ArrayList<>(), hoppers = new ArrayList<>(), living = new ArrayList<>();
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            String id = BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).getPath();
            if (id.equals("chest") || id.equals("trapped_chest") || id.equals("barrel") || id.endsWith("shulker_box")) {
                containers.add(be.getBlockPos());
            } else if (id.equals("hopper")) {
                hoppers.add(be.getBlockPos());
            } else if (id.endsWith("_bed") || id.equals("furnace") || id.equals("blast_furnace") || id.equals("smoker")
                    || id.equals("brewing_stand") || id.equals("enchanting_table") || id.equals("beacon")
                    || id.equals("ender_chest")) {
                living.add(be.getBlockPos());
            }
        }
        check(mc, Kind.STASH, containers, cfg.stashMin, cx, cz);
        check(mc, Kind.FARM, hoppers, cfg.stashMin + 4, cx, cz);
        // a base = several "living" blocks plus at least a couple of containers
        if (containers.size() >= 2) {
            List<BlockPos> all = new ArrayList<>(living);
            check(mc, Kind.BASE, all, Math.max(3, cfg.stashMin / 2), cx, cz);
        }
    }

    private static void check(Minecraft mc, Kind kind, List<BlockPos> list, int min, int cx, int cz) {
        if (list.size() < min) return;
        String key = cx + "," + cz + "," + kind;
        if (!seen.add(key)) return;

        // pick the real block closest to the group's center so the coords are an actual block
        double mx = 0, my = 0, mz = 0;
        for (BlockPos p : list) { mx += p.getX(); my += p.getY(); mz += p.getZ(); }
        mx /= list.size(); my /= list.size(); mz /= list.size();
        BlockPos best = list.get(0);
        double bd = Double.MAX_VALUE;
        for (BlockPos p : list) {
            double d = (p.getX() - mx) * (p.getX() - mx) + (p.getY() - my) * (p.getY() - my) + (p.getZ() - mz) * (p.getZ() - mz);
            if (d < bd) { bd = d; best = p; }
        }

        Alert a = new Alert(kind, best.getX(), best.getY(), best.getZ(), list.size(), System.currentTimeMillis());
        alerts.add(0, a);
        mc.player.sendSystemMessage(Component.literal(kind.title + " at " + a.x() + " " + a.y() + " " + a.z()
                + " (" + a.count() + " " + kind.unit + ")"));
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f));
    }

    // ---------- HUD (top right) ----------

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (alerts.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        alerts.removeIf(a -> now - a.createdMs() > SHOW_MS);

        int screenW = mc.getWindow().getGuiScaledWidth();
        int y = 6;
        int shown = 0;
        for (Alert a : alerts) {
            if (shown++ >= MAX_SHOWN) break;
            String line1 = a.kind().title;
            String line2 = a.x() + ", " + a.y() + ", " + a.z() + "  (" + a.count() + " " + a.kind().unit + ")";
            int w = Math.max(mc.font.width(line1), mc.font.width(line2)) + 10;
            int x = screenW - w - 6;
            g.fill(x, y, x + w, y + 26, 0xAA000000);
            g.fill(x, y, x + 2, y + 26, a.kind().color);
            g.text(mc.font, line1, x + 6, y + 3, a.kind().color, true);
            g.text(mc.font, line2, x + 6, y + 14, 0xFFFFFFFF, true);
            y += 30;
        }
    }
}
