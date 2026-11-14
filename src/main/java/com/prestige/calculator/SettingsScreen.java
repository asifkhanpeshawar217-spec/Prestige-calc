package com.prestige.calculator;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class SettingsScreen extends Screen {
    private final Screen parent;

    public SettingsScreen(Screen parent) {
        super(Text.literal("Prestige Calculator Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = (this.width - 260) / 2;
        int top = (this.height - 120) / 2;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Back"), button ->
                this.client.setScreen(parent)
        ).dimensions(left + 80, top + 75, 100, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        int left = (this.width - 260) / 2;
        int top = (this.height - 120) / 2;
        context.fill(left, top, left + 260, top + 120, 0xEE171B25);
        context.drawBorder(left, top, 260, 120, 0xFF65718A);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, top + 12, 0xFFFFFFFF);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal("Settings will be added in the next stage."),
                left + 18, top + 42, 0xFFD8DEE9);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
