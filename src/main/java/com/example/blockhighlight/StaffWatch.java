package com.example.blockhighlight;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shows which online players look like staff.
 * NOTE: a client cannot see other players' real permissions. This reads only what the server
 * shows everyone: the tab list / team prefixes (e.g. "[Admin] Steve"), plus a name list you type in.
 */
public final class StaffWatch {
    private static List<String> staffOnline = List.of();
    private static final Set<String> previous = new HashSet<>();
    private static int tick;

    private StaffWatch() {}

    // ---------- detection (client tick) ----------

    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || mc.getConnection() == null) {
            staffOnline = List.of();
            previous.clear();
            return;
        }
        HighlightConfig cfg = HighlightConfig.INSTANCE;
        if (!cfg.staffWatch) { staffOnline = List.of(); return; }
        if (++tick % 20 != 0) return;

        Set<String> names = new HashSet<>();
        for (String n : cfg.staffNames.split(",")) if (!n.isBlank()) names.add(n.trim().toLowerCase(Locale.ROOT));
        List<Pattern> keywords = new ArrayList<>();
        for (String k : cfg.staffKeywords.split(",")) {
            if (k.isBlank()) continue;
            keywords.add(Pattern.compile("(^|[^a-z])" + Pattern.quote(k.trim().toLowerCase(Locale.ROOT)) + "([^a-z]|$)"));
        }

        Scoreboard sb = mc.level.getScoreboard();
        String self = mc.player.getName().getString();
        List<String> found = new ArrayList<>();
        Set<String> foundNames = new HashSet<>();

        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String name = info.getProfile().name();
            if (name.equalsIgnoreCase(self)) continue;

            Component tab = info.getTabListDisplayName();
            String shown = tab == null ? "" : tab.getString().replace(name, " ");
            PlayerTeam team = sb.getPlayersTeam(name);
            String teamText = team == null ? "" : team.getPlayerPrefix().getString() + " " + team.getPlayerSuffix().getString();
            String decoration = (shown + " " + teamText).trim();
            String lower = decoration.toLowerCase(Locale.ROOT);

            boolean staff = names.contains(name.toLowerCase(Locale.ROOT));
            if (!staff) for (Pattern p : keywords) if (p.matcher(lower).find()) { staff = true; break; }
            if (!staff) continue;

            found.add(decoration.isEmpty() ? name : decoration + " " + name);
            foundNames.add(name);
        }

        for (String n : foundNames)
            if (!previous.contains(n)) mc.player.sendSystemMessage(Component.literal("Staff online: " + n));
        previous.clear();
        previous.addAll(foundNames);
        staffOnline = found;
    }

    // ---------- HUD box ----------

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!HighlightConfig.INSTANCE.staffWatch) return;
        if (mc.gui.screen() instanceof StaffScreen) return; // the settings screen draws its own preview
        draw(g, mc, false);
    }

    public static void draw(GuiGraphicsExtractor g, Minecraft mc, boolean preview) {
        HighlightConfig cfg = HighlightConfig.INSTANCE;
        List<String> list = staffOnline;
        if (preview && list.isEmpty()) list = List.of("[Admin] ExampleName", "[Mod] AnotherName");

        int lines = Math.min(list.size(), 8);
        String header = list.isEmpty() ? "Staff online: none" : "Staff online: " + list.size();
        int w = mc.font.width(header);
        for (int i = 0; i < lines; i++) w = Math.max(w, mc.font.width(list.get(i)));
        w += 10;
        int h = 6 + 11 + lines * 11 + 3;

        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int x = (int) ((sw - w) * (cfg.staffX / 100.0));
        int y = (int) ((sh - h) * (cfg.staffY / 100.0));

        int accent = list.isEmpty() ? 0xFF55FF55 : 0xFFFF5555;
        g.fill(x, y, x + w, y + h, 0xAA000000);
        g.fill(x, y, x + 2, y + h, accent);
        g.text(mc.font, header, x + 6, y + 4, accent, true);
        for (int i = 0; i < lines; i++) g.text(mc.font, list.get(i), x + 6, y + 15 + i * 11, 0xFFFFFFFF, true);
    }
}
