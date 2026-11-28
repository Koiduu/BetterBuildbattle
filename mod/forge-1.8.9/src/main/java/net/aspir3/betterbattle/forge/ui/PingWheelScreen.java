package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.pings.TeammatePings;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

/** Teams: the wheel of callouts for your teammate. Pick one and it closes. Never pauses the game. */
public final class PingWheelScreen extends GuiScreen {
    private static final int RADIUS = 72, BUTTON_W = 108, BUTTON_H = 20;

    @Override
    public void initGui() {
        buttonList.clear();
        int cx = width / 2, cy = height / 2;
        int n = TeammatePings.WHEEL.size();
        for (int i = 0; i < n; i++) {
            double a = -Math.PI / 2 + 2 * Math.PI * i / n;   // clockwise from the top
            int x = cx + (int) Math.round(Math.cos(a) * RADIUS * 1.5) - BUTTON_W / 2;
            int y = cy + (int) Math.round(Math.sin(a) * RADIUS) - BUTTON_H / 2;
            buttonList.add(new GuiButton(i, x, y, BUTTON_W, BUTTON_H, TeammatePings.WHEEL.get(i).label));
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id < 0 || button.id >= TeammatePings.WHEEL.size()) return;
        mc.displayGuiScreen(null);
        BetterBattle.get().ping(TeammatePings.WHEEL.get(button.id), null);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        String mate = BetterBattle.get().pingTeammate();
        drawCenteredString(fontRendererObj, "§d§l" + (mate == null ? "Ping" : "Ping " + mate), width / 2, height / 2 - 4, 0xFFFFFF);
        drawCenteredString(fontRendererObj, "§8Esc to cancel", width / 2, height / 2 + RADIUS + 20, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void updateScreen() {
        if (!BetterBattle.get().pingsActive()) mc.displayGuiScreen(null);   // the round ended while it was open
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
