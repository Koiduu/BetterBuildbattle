package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.pages.Pages;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.util.ArrayList;
import java.util.List;

/** One of the core's text pages (What's New, Last Game, Weekly Challenges), with page buttons if it's long. */
public final class TextPageScreen extends GuiScreen {
    private static final int LINE = 11, TOP = 34, PREV = 0, DONE = 1, NEXT = 2;

    private final GuiScreen parent;
    private final Pages.Page page;
    private final List<String> wrapped = new ArrayList<String>();
    private int textWidth, perPage = 1, first;

    public TextPageScreen(GuiScreen parent, Pages.Page page) {
        this.parent = parent;
        this.page = page;
    }

    @Override
    public void initGui() {
        textWidth = Math.min(360, width - 40);
        wrapped.clear();
        for (String line : page.lines) {
            wrapped.addAll(Pages.wrap(line, textWidth, new Pages.Width() {
                public int of(String text) { return fontRendererObj.getStringWidth(text); }
            }));
        }
        perPage = Math.max(1, (height - TOP - 40) / LINE);
        first = Math.max(0, Math.min(first, (wrapped.size() - 1) / perPage * perPage));
        buttonList.clear();
        int cx = width / 2;
        buttonList.add(new GuiButton(PREV, cx - 108, height - 28, 20, 20, "<"));
        buttonList.add(new GuiButton(DONE, cx - 80, height - 28, 160, 20, parent == null ? "Done" : "Back"));
        buttonList.add(new GuiButton(NEXT, cx + 88, height - 28, 20, 20, ">"));
        updateButtons();
    }

    private void updateButtons() {
        GuiButton prev = buttonList.get(0), next = buttonList.get(2);
        prev.enabled = first > 0;
        next.enabled = first + perPage < wrapped.size();
        prev.visible = next.visible = wrapped.size() > perPage;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == DONE) {
            mc.displayGuiScreen(parent);
            return;
        }
        int dir = button.id == PREV ? -1 : 1;
        first = Math.max(0, Math.min(first + dir * perPage, (wrapped.size() - 1) / perPage * perPage));
        updateButtons();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, page.title, width / 2, 15, 0xFFFFFF);
        int x = width / 2 - textWidth / 2;
        int y = TOP;
        for (int i = first; i < wrapped.size() && i < first + perPage; i++) {
            fontRendererObj.drawStringWithShadow(wrapped.get(i), x, y, 0xFFFFFF);
            y += LINE;
        }
        if (wrapped.size() > perPage) {
            int pages = (wrapped.size() + perPage - 1) / perPage;
            String label = (first / perPage + 1) + " / " + pages;
            fontRendererObj.drawString(label, width - fontRendererObj.getStringWidth(label) - 8, height - 22, 0x555555);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
