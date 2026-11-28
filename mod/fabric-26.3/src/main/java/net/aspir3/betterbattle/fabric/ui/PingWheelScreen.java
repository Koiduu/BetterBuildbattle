package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.fabric.compat.Compat;
import net.aspir3.betterbattle.pings.TeammatePings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Teams: the wheel of callouts for your teammate. Pick one and it closes. Never pauses the game. */
public final class PingWheelScreen extends Screen {
    private static final int RADIUS = 72, BUTTON_W = 108, BUTTON_H = 20;

    public PingWheelScreen() {
        super(Component.literal("Ping your teammate"));
    }

    @Override
    protected void init() {
        int cx = width / 2, cy = height / 2;
        int n = TeammatePings.WHEEL.size();
        for (int i = 0; i < n; i++) {
            TeammatePings.Kind kind = TeammatePings.WHEEL.get(i);
            double a = -Math.PI / 2 + 2 * Math.PI * i / n;   // clockwise from the top
            int x = cx + (int) Math.round(Math.cos(a) * RADIUS * 1.5) - BUTTON_W / 2;
            int y = cy + (int) Math.round(Math.sin(a) * RADIUS) - BUTTON_H / 2;
            addRenderableWidget(Button.builder(Component.literal(kind.label), b -> pick(kind))
                .bounds(x, y, BUTTON_W, BUTTON_H).build());
        }
    }

    private void pick(TeammatePings.Kind kind) {
        Compat.setScreen(minecraft, null);
        BetterBattle.get().ping(kind, null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        String mate = BetterBattle.get().pingTeammate();
        graphics.centeredText(font, Component.literal(mate == null ? "Ping" : "Ping " + mate)
            .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD), width / 2, height / 2 - 4, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal("Esc to cancel").withStyle(ChatFormatting.DARK_GRAY),
            width / 2, height / 2 + RADIUS + 20, 0xFFFFFFFF);
    }

    @Override
    public void tick() {
        if (!BetterBattle.get().pingsActive()) onClose();   // the round ended while the wheel was open
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Compat.setScreen(minecraft, null);
    }
}
