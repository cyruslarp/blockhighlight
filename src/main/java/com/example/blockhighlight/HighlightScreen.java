package com.example.blockhighlight;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

public class HighlightScreen extends Screen {
    private static final int PER_PAGE = 8;
    private final HighlightConfig cfg = HighlightConfig.INSTANCE;
    private final List<Button> resultButtons = new ArrayList<>();
    private EditBox search;
    private int page = 0;
    private int leftX, rightX;

    public HighlightScreen() { super(Component.literal("Block Highlight")); }

    @Override
    protected void init() {
        leftX = width / 2 - 205;
        rightX = width / 2 + 5;
        int y = 40, w = 200;

        addRenderableWidget(Button.builder(label("Highlight", cfg.enabled), b -> {
            cfg.enabled = !cfg.enabled; b.setMessage(label("Highlight", cfg.enabled)); changed();
        }).bounds(leftX, y, w, 20).build());

        addRenderableWidget(new Slider(leftX, y + 24, w, 20, "Radius", 4, 48, cfg.radius, v -> { cfg.radius = v; changed(); }));
        addRenderableWidget(new Slider(leftX, y + 48, w, 20, "Fill opacity %", 5, 100, cfg.opacity, v -> cfg.opacity = v));

        addRenderableWidget(Button.builder(label("Fill", cfg.fill), b -> {
            cfg.fill = !cfg.fill; b.setMessage(label("Fill", cfg.fill)); changed();
        }).bounds(leftX, y + 72, 98, 20).build());
        addRenderableWidget(Button.builder(label("Outline", cfg.outline), b -> {
            cfg.outline = !cfg.outline; b.setMessage(label("Outline", cfg.outline)); changed();
        }).bounds(leftX + 102, y + 72, 98, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Add all ores"), b -> {
            cfg.addAllOres(); changed(); refreshResults();
        }).bounds(leftX, y + 100, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear all"), b -> {
            cfg.blocks.clear(); changed(); refreshResults();
        }).bounds(leftX + 102, y + 100, 98, 20).build());

        addRenderableWidget(new Slider(leftX, y + 124, w, 20, "Freecam speed", 2, 40, cfg.freecamSpeed, v -> cfg.freecamSpeed = v));

        addRenderableWidget(Button.builder(label("Stash/base alerts", cfg.stashAlerts), b -> {
            cfg.stashAlerts = !cfg.stashAlerts; b.setMessage(label("Stash/base alerts", cfg.stashAlerts));
        }).bounds(leftX, y + 148, w, 20).build());
        addRenderableWidget(new Slider(leftX, y + 172, w, 20, "Stash min containers", 3, 30, cfg.stashMin, v -> cfg.stashMin = v));

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(leftX, y + 200, w, 20).build());

        search = new EditBox(font, rightX, y, w, 20, Component.literal("Search"));
        search.setHint(Component.literal("Search blocks (e.g. ore, chest)"));
        search.setResponder(s -> { page = 0; refreshResults(); });
        addRenderableWidget(search);

        int pagerY = y + 24 + PER_PAGE * 22;
        addRenderableWidget(Button.builder(Component.literal("< Prev"), b -> { if (page > 0) { page--; refreshResults(); } })
                .bounds(rightX, pagerY, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Next >"), b -> { page++; refreshResults(); })
                .bounds(rightX + 102, pagerY, 98, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Staff watch settings..."),
                b -> minecraft.gui.setScreen(new StaffScreen(this))).bounds(rightX, pagerY + 24, 200, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Sus chunks & light settings..."),
                b -> minecraft.gui.setScreen(new SusScreen(this))).bounds(rightX, pagerY + 48, 200, 20).build());

        refreshResults();
    }

    private void refreshResults() {
        for (Button b : resultButtons) removeWidget(b);
        resultButtons.clear();

        String q = search == null ? "" : search.getValue().toLowerCase().trim();
        List<Block> matches = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            String full = BuiltInRegistries.BLOCK.getKey(b).toString();
            String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (path.equals("air") || path.endsWith("_air")) continue;
            boolean match = q.isEmpty()
                    ? cfg.blocks.contains(full)
                    : (path.contains(q.replace(' ', '_')) || b.getName().getString().toLowerCase().contains(q));
            if (match) matches.add(b);
        }
        int maxPage = Math.max(0, (matches.size() - 1) / PER_PAGE);
        if (page > maxPage) page = maxPage;

        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= matches.size()) break;
            Block block = matches.get(idx);
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            Button btn = Button.builder(rowText(block, id), b -> {
                if (!cfg.blocks.remove(id)) cfg.blocks.add(id);
                changed();
                b.setMessage(rowText(block, id));
            }).bounds(rightX, 64 + i * 22, 200, 20).build();
            resultButtons.add(btn);
            addRenderableWidget(btn);
        }
    }

    private Component rowText(Block block, String id) {
        return Component.literal(cfg.blocks.contains(id) ? "[x] " : "[  ] ").append(block.getName());
    }

    private static Component label(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "ON" : "OFF"));
    }

    private void changed() { BlockHighlightClient.rescan = true; }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        String t = "Block Highlight";
        graphics.text(this.font, t, width / 2 - font.width(t) / 2, 14, 0xFFFFFFFF, true);
        String hint = search != null && search.getValue().isEmpty()
                ? "Your selected blocks (type to search all)" : "Click a block to toggle it";
        graphics.text(this.font, hint, rightX, 28, 0xFFAAAAAA, true);
    }

    @Override
    public void removed() { cfg.save(); }

    static class Slider extends AbstractSliderButton {
        private final String label; private final int min, max; private final IntConsumer cb;
        Slider(int x, int y, int w, int h, String label, int min, int max, int val, IntConsumer cb) {
            super(x, y, w, h, Component.empty(), (val - min) / (double) (max - min));
            this.label = label; this.min = min; this.max = max; this.cb = cb;
            updateMessage();
        }
        private int get() { return min + (int) Math.round(value * (max - min)); }
        @Override protected void updateMessage() { setMessage(Component.literal(label + ": " + get())); }
        @Override protected void applyValue() { cb.accept(get()); }
    }
}
