package com.example.blockhighlight;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

public class HighlightConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("blockhighlight.json");
    public static HighlightConfig INSTANCE = load();

    public boolean enabled = true;
    public int radius = 24;      // blocks
    public int opacity = 35;     // percent, fill opacity
    public boolean fill = true;
    public boolean outline = true;
    public int freecamSpeed = 10; // blocks per second
    public boolean stashAlerts = true;
    public boolean staffWatch = true;
    public int staffX = 0;   // % across the screen
    public int staffY = 30;  // % down the screen
    public String staffNames = "";
    public String staffKeywords = "admin,owner,mod,moderator,helper,staff,op,manager,dev";
    public int stashMin = 8;      // containers in one chunk to count as a stash
    public Set<String> blocks = new LinkedHashSet<>(Set.of(
            "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:ancient_debris"));

    public static HighlightConfig load() {
        try {
            if (Files.exists(FILE)) {
                HighlightConfig c = GSON.fromJson(Files.readString(FILE), HighlightConfig.class);
                if (c != null) return c;
            }
        } catch (Exception ignored) {}
        return new HighlightConfig();
    }

    public void save() {
        try { Files.writeString(FILE, GSON.toJson(this)); } catch (Exception ignored) {}
    }

    public void addAllOres() {
        for (Block b : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(b);
            String s = id.toString();
            if (s.endsWith("_ore") || s.equals("minecraft:ancient_debris")) blocks.add(s);
        }
    }

    public static int colorFor(String id) {
        if (id.contains("diamond")) return 0x00FFFF;
        if (id.contains("emerald")) return 0x00FF40;
        if (id.contains("gold")) return 0xFFD700;
        if (id.contains("iron")) return 0xD8AF93;
        if (id.contains("coal")) return 0x777777;
        if (id.contains("redstone")) return 0xFF2020;
        if (id.contains("lapis")) return 0x2850FF;
        if (id.contains("copper")) return 0xE07830;
        if (id.contains("ancient_debris")) return 0xA0522D;
        if (id.contains("quartz")) return 0xFFFFFF;
        float hue = (id.hashCode() & 0xFFFF) / 65535f;
        return Color.getHSBColor(hue, 0.8f, 1f).getRGB() & 0xFFFFFF;
    }
}
