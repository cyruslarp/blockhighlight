package com.example.blockhighlight;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class StaffScreen extends Screen {
    private final Screen parent;
    private final HighlightConfig cfg = HighlightConfig.INSTANCE;

    public StaffScreen(Screen parent) {
        super(Component.literal("Staff Watch"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int w = 240, x = width / 2 - w / 2, y = 36;

        addRenderableWidget(Button.builder(label(), b -> {
            cfg.staffWatch = !cfg.staffWatch; b.setMessage(label());
        }).bounds(x, y, w, 20).build());

        addRenderableWidget(new HighlightScreen.Slider(x, y + 24, w, 20, "Box X position %", 0, 100, cfg.staffX, v -> cfg.staffX = v));
        addRenderableWidget(new HighlightScreen.Slider(x, y + 48, w, 20, "Box Y position %", 0, 100, cfg.staffY, v -> cfg.staffY = v));

        EditBox names = new EditBox(font, x, y + 92, w, 20, Component.literal("Names"));
        names.setMaxLength(512);
        names.setValue(cfg.staffNames);
        names.setHint(Component.literal("Staff names, comma separated"));
        names.setResponder(s -> cfg.staffNames = s);
        addRenderableWidget(names);

        EditBox keys = new EditBox(font, x, y + 132, w, 20, Component.literal("Keywords"));
        keys.setMaxLength(512);
        keys.setValue(cfg.staffKeywords);
        keys.setHint(Component.literal("Rank keywords, comma separated"));
        keys.setResponder(s -> cfg.staffKeywords = s);
        addRenderableWidget(keys);

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(x, y + 164, w, 20).build());
    }

    private Component label() {
        return Component.literal("Staff watch: " + (cfg.staffWatch ? "ON" : "OFF"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int x = width / 2 - 120;
        g.text(this.font, "Staff Watch", width / 2 - font.width("Staff Watch") / 2, 14, 0xFFFFFFFF, true);
        g.text(this.font, "Names to always treat as staff:", x, 36 + 80, 0xFFAAAAAA, true);
        g.text(this.font, "Rank words in tab list/prefix (e.g. admin, mod):", x, 36 + 120, 0xFFAAAAAA, true);
        StaffWatch.draw(g, minecraft, true); // live preview of the box position
    }

    @Override
    public void onClose() { this.minecraft.gui.setScreen(parent); }

    @Override
    public void removed() { cfg.save(); }
}
