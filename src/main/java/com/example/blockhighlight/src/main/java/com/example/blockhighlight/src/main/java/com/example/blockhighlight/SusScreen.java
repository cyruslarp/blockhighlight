package com.example.blockhighlight;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class SusScreen extends Screen {
    private final Screen parent;
    private final HighlightConfig cfg = HighlightConfig.INSTANCE;

    public SusScreen(Screen parent) {
        super(Component.literal("Sus Chunks & Light"));
        this.parent = parent;
    }

    private static Component label(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "ON" : "OFF"));
    }

    @Override
    protected void init() {
        int w = 240, x = width / 2 - w / 2, y = 40;

        addRenderableWidget(Button.builder(label("Sus chunks (red)", cfg.susChunks), b -> {
            cfg.susChunks = !cfg.susChunks; b.setMessage(label("Sus chunks (red)", cfg.susChunks));
        }).bounds(x, y, w, 20).build());

        addRenderableWidget(new HighlightScreen.Slider(x, y + 24, w, 20, "Sus threshold (lower = more)", 4, 60,
                cfg.susThreshold, v -> { cfg.susThreshold = v; }));

        addRenderableWidget(Button.builder(label("Count amethyst clusters", cfg.susGeodes), b -> {
            cfg.susGeodes = !cfg.susGeodes; b.setMessage(label("Count amethyst clusters", cfg.susGeodes));
        }).bounds(x, y + 52, w, 20).build());

        addRenderableWidget(Button.builder(label("Fullbright in freecam", cfg.fullbrightFreecam), b -> {
            cfg.fullbrightFreecam = !cfg.fullbrightFreecam; b.setMessage(label("Fullbright in freecam", cfg.fullbrightFreecam));
        }).bounds(x, y + 84, w, 20).build());

        addRenderableWidget(Button.builder(label("Fullbright always", cfg.fullbrightAlways), b -> {
            cfg.fullbrightAlways = !cfg.fullbrightAlways; b.setMessage(label("Fullbright always", cfg.fullbrightAlways));
        }).bounds(x, y + 108, w, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(x, y + 140, w, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        String t = "Sus Chunks & Light";
        g.text(this.font, t, width / 2 - font.width(t) / 2, 14, 0xFFFFFFFF, true);
    }

    @Override
    public void onClose() { this.minecraft.gui.setScreen(parent); }

    @Override
    public void removed() { cfg.save(); }
}
