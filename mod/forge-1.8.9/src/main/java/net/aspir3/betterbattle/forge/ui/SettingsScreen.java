package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.forge.ThemePack;
import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.Config;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

/** On/off switches for everything Better Battle adds, two per row. Saved to config.json right away. */
public final class SettingsScreen extends GuiScreen {
    private static final int DONE = 1000, PREV = 1001, NEXT = 1002;
    private final GuiScreen parent;
    /** When the toggles don't all fit at this GUI scale they are split into pages. */
    private int page, pages = 1;

    public SettingsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        Config config = BetterBattle.get().config();
        int cx = width / 2;
        int total = Config.TOGGLES.size();
        // Whole rows between the title and the notes and Done button at the bottom
        int rowsFit = Math.max(2, (height - 28 - 64) / 22);
        int perPage = Math.min(total, rowsFit * 2);
        pages = (total + perPage - 1) / perPage;
        page = Math.max(0, Math.min(page, pages - 1));
        int from = page * perPage, to = Math.min(total, from + perPage);
        int rows = (to - from + 1) / 2;
        int top = Math.max(28, (height - 60 - rows * 22) / 2 + 4);
        for (int i = from; i < to; i++) {
            int x = (i - from) % 2 == 0 ? cx - 174 : cx + 4;
            int y = top + ((i - from) / 2) * 22;
            buttonList.add(new GuiButton(i, x, y, 170, 20, label(Config.TOGGLES.get(i), config)));
        }
        if (pages > 1) {
            GuiButton prev = new GuiButton(PREV, cx - 108, height - 28, 36, 20, "<");
            GuiButton next = new GuiButton(NEXT, cx + 72, height - 28, 36, 20, ">");
            prev.enabled = page > 0;
            next.enabled = page < pages - 1;
            buttonList.add(prev);
            buttonList.add(next);
            buttonList.add(new GuiButton(DONE, cx - 64, height - 28, 128, 20, parent == null ? "Done" : "Back"));
            return;
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
        if (button.id == PREV || button.id == NEXT) {
            page += button.id == NEXT ? 1 : -1;
            initGui();
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
        drawCenteredString(fontRendererObj, "Better Battle Settings" + (pages > 1 ? " (" + (page + 1) + "/" + pages + ")" : ""), width / 2, 15, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        for (GuiButton b : buttonList) {
            if (b.id < DONE && b.isMouseOver()) {
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
