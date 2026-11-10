package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.forge.BetterBattleForge;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

public final class CreditsScreen extends GuiScreen {
    private final GuiScreen parent;

    public CreditsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        buttonList.add(new GuiButton(0, width / 2 - 100, height - 28, 200, 20, "Done"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        mc.displayGuiScreen(parent);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int cx = width / 2;
        drawCenteredString(fontRendererObj, "Credits", cx, 15, 0xFFFFFF);
        String[] lines = {
            "§d§lBetter Battle",
            "§8Version " + BetterBattleForge.VERSION,
            "",
            "§7Created by §6Koidu",
            "§7for the Aspiring guild and every Build Battle player",
            "",
            "§8Stats from the Hypixel API",
            "§8Not affiliated with Hypixel or Mojang",
            "§bbattle.aspir3.net",
        };
        int y = Math.max(45, height / 2 - 60);
        for (String line : lines) {
            drawCenteredString(fontRendererObj, line, cx, y, 0xFFFFFF);
            y += 12;
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
