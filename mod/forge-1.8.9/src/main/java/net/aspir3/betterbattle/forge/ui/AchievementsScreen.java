package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.Config;
import net.aspir3.betterbattle.achieve.AchievementBrowser;
import net.aspir3.betterbattle.achieve.Catalog;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** A scrolling ledger and a separate page for the selected goal. */
public final class AchievementsScreen extends GuiScreen {
    private final GuiScreen parent;
    private AchievementBrowser view;
    private AchievementBrowser.Show show = AchievementBrowser.Show.ALL;
    private int modeIndex, listWidth, panelX, panelWidth, listTop, totalCount, recommendationCount;
    private int listScroll, detailScroll, dragging;
    private boolean narrow, detailOpen;
    private Catalog.Entry selected;
    private List<Catalog.Entry> rows = new ArrayList<Catalog.Entry>();
    private List<Catalog.Entry> recommendations = new ArrayList<Catalog.Entry>();
    private final List<String> detailLines = new ArrayList<String>();
    private GuiButton pin, back, showButton, modeButton;

    public AchievementsScreen(GuiScreen parent) { this.parent = parent; }
    private Config config() { return BetterBattle.get().config(); }
    private String mode() { return AchievementBrowser.MODES.get(modeIndex); }

    @Override
    public void initGui() {
        try { view = AchievementBrowser.load(BetterBattle.get().db(), config(), mc.getSession().getUsername()); }
        catch (Exception ignored) { view = new AchievementBrowser(mc.getSession().getUsername(), java.time.LocalDate.now().toEpochDay()); }
        narrow = width < 420;
        listWidth = narrow ? width - 16 : (width - 24) / 2;
        panelX = narrow ? 8 : listWidth + 16;
        panelWidth = width - panelX - 8;
        buttonList.clear();
        int bw = Math.min(150, (width - 28) / 3), start = (width - bw * 3 - 12) / 2;
        buttonList.add(showButton = new GuiButton(0, start, height - 28, bw, 20, ""));
        buttonList.add(modeButton = new GuiButton(1, start + bw + 6, height - 28, bw, 20, ""));
        buttonList.add(new GuiButton(2, start + (bw + 6) * 2, height - 28, bw, 20, "Done"));
        buttonList.add(pin = new GuiButton(3, panelX + 8, height - 62, narrow ? (panelWidth - 24) / 2 : panelWidth - 16, 20, ""));
        buttonList.add(back = new GuiButton(4, panelX + panelWidth / 2 + 4, height - 62, (panelWidth - 24) / 2, 20, "Back to list"));
        fill();
    }

    private void fill() {
        String previous = selected == null ? null : selected.id;
        rows = view.entries(show, mode(), config());
        recommendations = view.recommended(config(), mode());
        recommendationCount = show == AchievementBrowser.Show.ALL || show == AchievementBrowser.Show.RECOMMENDED ? recommendations.size() : 0;
        listTop = recommendationCount > 0 ? 56 : 40;
        totalCount = 0;
        for (Catalog.Entry e : Catalog.list()) if (AchievementBrowser.inMode(e, mode())) totalCount++;
        selected = rows.isEmpty() ? null : rows.get(0);
        for (Catalog.Entry e : rows) if (e.id.equals(previous)) selected = e;
        listScroll = 0;
        showButton.displayString = "Show: " + AchievementBrowser.modeName(show.name().toLowerCase(Locale.ROOT));
        modeButton.displayString = "Mode: " + AchievementBrowser.modeName(mode());
        updateDetail();
    }

    private void addDetail(String text) {
        if (text.isEmpty()) detailLines.add("");
        else detailLines.addAll(fontRendererObj.listFormattedStringToWidth(text, panelWidth - 24));
    }

    private void updateDetail() {
        detailLines.clear();
        if (selected == null) addDetail("\u00a77Try another Show or Mode filter, then select an achievement to see its details.");
        else {
            addDetail("\u00a7e" + view.name(selected));
            addDetail("");
            addDetail(view.description(selected));
            addDetail("");
            addDetail("\u00a76+" + selected.points + " Aspiring Points");
            if (!view.hidden(selected)) addDetail("\u00a77" + AchievementBrowser.modes(selected));
            addDetail("");
            Long time = view.unlocked.get(selected.id);
            addDetail(time == null ? "\u00a77Locked" : "\u00a7aUnlocked " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(time)));
            String progress = view.progress(selected);
            if (!progress.isEmpty()) addDetail(progress);
        }
        detailScroll = 0;
        boolean pinned = selected != null && config().pinnedAchievements().contains(selected.id);
        boolean full = config().pinnedAchievements().size() >= 3;
        pin.displayString = pinned ? "Unpin" : full ? "Pins full (3/3)" : "Pin achievement";
        pin.enabled = selected != null && !view.hidden(selected) && !view.unlocked.containsKey(selected.id) && (pinned || !full);
        pin.visible = selected != null && (!narrow || detailOpen);
        back.visible = narrow && detailOpen;
    }

    private void cycle(int id, int direction) {
        if (id == 0) show = AchievementBrowser.Show.values()[Math.floorMod(show.ordinal() + direction, AchievementBrowser.Show.values().length)];
        else modeIndex = Math.floorMod(modeIndex + direction, AchievementBrowser.MODES.size());
        fill();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id < 2) cycle(button.id, 1);
        else if (button.id == 2) mc.displayGuiScreen(parent);
        else if (button.id == 3 && selected != null) { view.togglePin(selected, config()); fill(); }
        else if (button.id == 4) { detailOpen = false; updateDetail(); }
    }

    private boolean listVisible() { return !narrow || !detailOpen; }
    private int listMax() { return Math.max(0, rows.size() * 38 - (height - 40 - listTop)); }
    private int detailMax() { return Math.max(0, detailLines.size() * 12 + 4 - (height - 134)); }

    @Override
    protected void mouseClicked(int x, int y, int button) throws IOException {
        if (button == 1) {
            if (showButton.mousePressed(mc, x, y)) { cycle(0, -1); return; }
            if (modeButton.mousePressed(mc, x, y)) { cycle(1, -1); return; }
        }
        if (button == 0) {
            if (listVisible() && x >= 8 && x < 8 + listWidth && y >= listTop && y < height - 40) {
                if (x >= listWidth + 2 && listMax() > 0) { dragging = 1; dragScroll(y); return; }
                int index = (y - listTop + listScroll) / 38;
                if (index < rows.size()) {
                    selected = rows.get(index);
                    if (narrow) detailOpen = true;
                    updateDetail();
                }
                return;
            }
            if ((!narrow || detailOpen) && x >= panelX + panelWidth - 6 && x < panelX + panelWidth && y >= 70 && y < height - 64 && detailMax() > 0) {
                dragging = 2; dragScroll(y); return;
            }
        }
        super.mouseClicked(x, y, button);
    }

    private void dragScroll(int y) {
        if (dragging == 1) listScroll = Math.max(0, Math.min(listMax(), (y - listTop) * listMax() / Math.max(1, height - 40 - listTop)));
        if (dragging == 2) detailScroll = Math.max(0, Math.min(detailMax(), (y - 70) * detailMax() / Math.max(1, height - 134)));
    }

    @Override protected void mouseClickMove(int x, int y, int button, long elapsed) { if (dragging != 0) dragScroll(y); }
    @Override protected void mouseReleased(int x, int y, int button) { dragging = 0; super.mouseReleased(x, y, button); }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        int x = Mouse.getEventX() * width / mc.displayWidth;
        int y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (listVisible() && x >= 8 && x < 8 + listWidth && y >= listTop && y < height - 40) listScroll = Math.max(0, Math.min(listMax(), listScroll - Integer.signum(wheel) * 38));
        else if ((!narrow || detailOpen) && x >= panelX && x < panelX + panelWidth && y >= 70 && y < height - 64) detailScroll = Math.max(0, Math.min(detailMax(), detailScroll - Integer.signum(wheel) * 24));
    }

    private void clip(int x, int y, int w, int h) {
        int scale = new ScaledResolution(mc).getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * scale, mc.displayHeight - (y + h) * scale, w * scale, Math.max(0, h) * scale);
    }

    private void icon(Catalog.Entry e, int x, int y) {
        ItemStack stack = view.hidden(e) ? new ItemStack(Items.paper) : Icons.of(e);
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableRescaleNormal();
        mc.getRenderItem().renderItemAndEffectIntoGUI(stack, x, y);
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.disableRescaleNormal();
    }

    private String fit(String text, int w) {
        return fontRendererObj.getStringWidth(text) <= w ? text : fontRendererObj.trimStringToWidth(text, Math.max(0, w - fontRendererObj.getStringWidth("..."))) + "...";
    }

    private void scrollbar(int x, int top, int bottom, int scroll, int max) {
        if (max <= 0) return;
        int h = bottom - top, thumb = Math.max(12, h * h / (h + max));
        int y = top + (h - thumb) * scroll / max;
        drawRect(x, top, x + 3, bottom, 0x60000000);
        drawRect(x, y, x + 3, y + thumb, 0xFF888888);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "Achievements", width / 2, 8, 0xFFFFFF);
        drawCenteredString(fontRendererObj, rows.size() + "/" + totalCount + " \u00b7 " + AchievementBrowser.modeName(mode()), width / 2, 23, 0xAAAAAA);
        Catalog.Entry hovered = null;
        if (listVisible()) {
            if (recommendationCount > 0) fontRendererObj.drawString(listScroll < recommendationCount * 38 ? "Recommended for you" : "More achievements", 18, 44, 0x88BBAA);
            drawRect(8, listTop, 8 + listWidth, height - 40, 0xB0000000);
            clip(8, listTop, listWidth, height - 40 - listTop);
            for (int i = 0; i < rows.size(); i++) {
                int y = listTop + i * 38 - listScroll;
                if (y + 38 <= listTop || y >= height - 40) continue;
                Catalog.Entry e = rows.get(i);
                if (e == selected) {
                    drawRect(10, y, listWidth + 4, y + 36, 0x554D3C20);
                    drawRect(10, y, 12, y + 36, 0xFFFFAA00);
                }
                icon(e, 16, y + 9);
                String ap = "+" + e.points + " AP";
                fontRendererObj.drawString(fit(view.name(e), listWidth - 42 - fontRendererObj.getStringWidth(ap)), 38, y + 4, view.unlocked.containsKey(e.id) ? 0xFFFF55 : 0xDDDDDD);
                fontRendererObj.drawString(ap, listWidth + 1 - fontRendererObj.getStringWidth(ap), y + 4, 0xFFAA00);
                fontRendererObj.drawString(fit(view.description(e), listWidth - 42), 38, y + 15, 0x999999);
                int rank = recommendationCount > 0 ? recommendations.indexOf(e) + 1 : 0;
                String label = rank > 0 ? rank + ". Daily pick" : config().pinnedAchievements().contains(e.id) ? "Pinned" : view.unlocked.containsKey(e.id) ? "Unlocked" : "Locked";
                fontRendererObj.drawString(label, 38, y + 26, rank > 0 ? 0x88BBAA : 0x777777);
                if (mouseX >= 8 && mouseX < listWidth + 2 && mouseY >= Math.max(y, listTop) && mouseY < Math.min(y + 38, height - 40)) hovered = e;
            }
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            scrollbar(listWidth + 2, listTop, height - 40, listScroll, listMax());
            if (rows.isEmpty()) drawCenteredString(fontRendererObj, "No achievements match.", 8 + listWidth / 2, height / 2, 0xAAAAAA);
        }
        if (!narrow || detailOpen) {
            drawRect(panelX, 40, panelX + panelWidth, 68, 0xB0000000);
            drawRect(panelX, 40, panelX + 2, 68, 0xFFFFAA00);
            if (selected != null) icon(selected, panelX + 9, 46);
            fontRendererObj.drawString(selected == null ? "No achievements here" : "Achievement details", panelX + (selected == null ? 9 : 31), 50, 0xAAAAAA);
            drawRect(panelX, 70, panelX + panelWidth, height - 64, 0xB0000000);
            clip(panelX, 70, panelWidth, height - 134);
            for (int i = 0; i < detailLines.size(); i++) fontRendererObj.drawString(detailLines.get(i), panelX + 10, 74 + i * 12 - detailScroll, 0xE0E0E0);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            scrollbar(panelX + panelWidth - 5, 70, height - 64, detailScroll, detailMax());
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (hovered != null) drawHoveringText(fontRendererObj.listFormattedStringToWidth(view.description(hovered), Math.min(300, width - 30)), mouseX, mouseY);
    }

    @Override public boolean doesGuiPauseGame() { return false; }
    @Override protected void keyTyped(char typedChar, int keyCode) throws IOException { if (keyCode == 1) mc.displayGuiScreen(parent); else super.keyTyped(typedChar, keyCode); }
}
