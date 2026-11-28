package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.forge.ThemePack;
import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.Config;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

/** On/off switches for everything Better Battle adds, two per row. Saved to config.json right away. */
public final class SettingsScreen extends GuiScreen {
    private static final int DONE = 1000;
    private final GuiScreen parent;

    public SettingsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        Config config = BetterBattle.get().config();
        int cx = width / 2;
        int rows = (Config.TOGGLES.size() + 1) / 2;
        // Centred in the space above the notes at the bottom, so all rows fit at large GUI scales too
        int top = Math.max(28, (height - 60 - rows * 22) / 2 + 4);
        for (int i = 0; i < Config.TOGGLES.size(); i++) {
            int x = i % 2 == 0 ? cx - 174 : cx + 4;
            int y = top + (i / 2) * 22;
            buttonList.add(new GuiButton(i, x, y, 170, 20, label(Config.TOGGLES.get(i), config)));
        }
        buttonList.add(new GuiButton(DONE, cx - 100, height - 28, 200, 20, parent == null ? "Done" : "Back"));
    }

    private static String label(Config.Toggle t, Config c) {
        return t.label + ": " + (t.get(c) ? "§aON" : "§cOFF");
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == DONE) {
            mc.displayGuiScreen(parent);
            return;
        }
        Config config = BetterBattle.get().config();
        Config.Toggle t = Config.TOGGLES.get(button.id);
        t.flip(config);
        button.displayString = label(t, config);
        if (t.is("uiTheme")) ThemePack.apply();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "Better Battle Settings", width / 2, 15, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        for (GuiButton b : buttonList) {
            if (b.id != DONE && b.isMouseOver()) {
                drawCenteredString(fontRendererObj, "§7" + Config.TOGGLES.get(b.id).help, width / 2, height - 44, 0xFFFFFF);
            }
        }
        drawCenteredString(fontRendererObj, "§8Turn anything off to play Build Battle normally. Your games are still saved.", width / 2, height - 56, 0xFFFFFF);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
