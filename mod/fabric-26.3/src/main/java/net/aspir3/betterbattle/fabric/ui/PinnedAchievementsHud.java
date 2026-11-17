package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.achieve.AchievementBrowser;
import net.aspir3.betterbattle.achieve.Catalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Goals stay left; the Speed Builders stopwatch uses the toast corner on the right. */
public final class PinnedAchievementsHud {
    private static AchievementBrowser view;
    private static long refreshed;
    private static Object database;

    private PinnedAchievementsHud() {}

    public static void extract(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        BetterBattle core = BetterBattle.get();
        if (client.player == null || client.gui.screen() != null || client.gui.hud.isHidden()
                || client.getDebugOverlay().showDebugScreen() || !core.config().pinnedAchievementsHud) return;
        List<String> pins = core.config().pinnedAchievements();
        if (pins.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (view == null || database != core.db() || now - refreshed > 2000) {
            try { view = AchievementBrowser.load(core.db(), core.config(), client.getGameProfile().id().toString()); }
            catch (Exception ignored) { return; }
            refreshed = now;
            database = core.db();
        }
        int x = 6, y = 6, width = 180;
        if (SpbTimerToast.showing() && graphics.guiWidth() < width + 162) y = 60;
        for (String id : pins) {
            Catalog.Entry entry = Catalog.get(id);
            if (entry == null || view.hidden(entry) || view.unlocked.containsKey(id)) continue;
            List<FormattedCharSequence> description = client.font.split(Component.literal(entry.description), width - 14);
            List<FormattedCharSequence> name = client.font.split(Component.literal(entry.name), width - 54);
            String progress = view.progress(entry);
            List<FormattedCharSequence> progressLines = client.font.split(Component.literal(progress), width - 14);
            int nameLines = Math.min(2, name.size()), lines = Math.min(3, description.size());
            int h = 12 + nameLines * 10 + lines * 10 + (progress.isEmpty() ? 0 : progressLines.size() * 10 + 2);
            graphics.fill(x, y, x + width, y + h, 0xB0000000);
            graphics.fill(x, y, x + 2, y + h, 0xFFFFAA00);
            for (int i = 0; i < nameLines; i++) graphics.text(client.font, name.get(i), x + 7, y + 6 + i * 10, 0xFFFFDD88, false);
            String ap = "+" + entry.points + " AP";
            graphics.text(client.font, ap, x + width - client.font.width(ap) - 7, y + 6, 0xFFFFAA00, false);
            int textY = y + 8 + nameLines * 10;
            for (int i = 0; i < lines; i++) {
                if (i == lines - 1 && description.size() > lines) {
                    StringBuilder last = new StringBuilder();
                    description.get(i).accept((index, style, codePoint) -> { last.appendCodePoint(codePoint); return true; });
                    String clipped = client.font.plainSubstrByWidth(last.toString(), width - 14 - client.font.width("..."));
                    graphics.text(client.font, clipped + "...", x + 7, textY + i * 10, 0xFFCCCCCC, false);
                } else graphics.text(client.font, description.get(i), x + 7, textY + i * 10, 0xFFCCCCCC, false);
            }
            if (!progress.isEmpty()) for (int i = 0; i < progressLines.size(); i++) {
                graphics.text(client.font, progressLines.get(i), x + 7, textY + lines * 10 + 2 + i * 10, 0xFFAAAAAA, false);
            }
            y += h + 4;
        }
    }
}
