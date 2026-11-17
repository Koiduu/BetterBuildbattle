package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.achieve.AchievementBrowser;
import net.aspir3.betterbattle.achieve.Catalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.List;

/** Goals stack below the Speed Builders stopwatch in the top-left corner. */
public final class PinnedAchievementsHud {
    private AchievementBrowser view;
    private Object database;
    private long refreshed;

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;
        Minecraft mc = Minecraft.getMinecraft();
        BetterBattle core = BetterBattle.get();
        if (mc.thePlayer == null || mc.currentScreen != null || mc.gameSettings.hideGUI
                || mc.gameSettings.showDebugInfo || !core.config().pinnedAchievementsHud) return;
        List<String> pins = core.config().pinnedAchievements();
        if (pins.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (view == null || database != core.db() || now - refreshed > 2000) {
            try { view = AchievementBrowser.load(core.db(), core.config(), mc.getSession().getUsername()); }
            catch (Exception ignored) { return; }
            database = core.db();
            refreshed = now;
        }
        List<String> timer = core.hudLines();
        int x = 4, y = timer.isEmpty() ? 4 : timer.size() * 10 + 14, width = 180;
        FontRenderer font = mc.fontRendererObj;
        for (String id : pins) {
            Catalog.Entry e = Catalog.get(id);
            if (e == null || view.hidden(e) || view.unlocked.containsKey(id)) continue;
            List<String> name = font.listFormattedStringToWidth(e.name, width - 54);
            List<String> description = font.listFormattedStringToWidth(e.description, width - 14);
            String progress = view.progress(e);
            List<String> progressLines = font.listFormattedStringToWidth(progress, width - 14);
            int names = Math.min(2, name.size()), lines = Math.min(3, description.size());
            int h = 12 + names * 10 + lines * 10 + (progress.isEmpty() ? 0 : progressLines.size() * 10 + 2);
            Gui.drawRect(x, y, x + width, y + h, 0xB0000000);
            Gui.drawRect(x, y, x + 2, y + h, 0xFFFFAA00);
            for (int i = 0; i < names; i++) font.drawString(name.get(i), x + 7, y + 6 + i * 10, 0xFFDD88);
            String ap = "+" + e.points + " AP";
            font.drawString(ap, x + width - font.getStringWidth(ap) - 7, y + 6, 0xFFAA00);
            int textY = y + 8 + names * 10;
            for (int i = 0; i < lines; i++) {
                String line = description.get(i);
                if (i == lines - 1 && description.size() > lines) line = font.trimStringToWidth(line, width - 14 - font.getStringWidth("...")) + "...";
                font.drawString(line, x + 7, textY + i * 10, 0xCCCCCC);
            }
            if (!progress.isEmpty()) for (int i = 0; i < progressLines.size(); i++) font.drawString(progressLines.get(i), x + 7, textY + lines * 10 + 2 + i * 10, 0xAAAAAA);
            y += h + 4;
        }
    }
}
