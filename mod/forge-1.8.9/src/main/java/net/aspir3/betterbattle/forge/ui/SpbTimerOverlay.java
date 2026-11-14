package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.BetterBattle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.List;

/**
 * The Speed Builders timer in the top-left corner while a round is on. A stopwatch and your
 * best time; it never shows anything about where blocks go.
 */
public final class SpbTimerOverlay {
    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings.showDebugInfo) return;
        List<String> lines = BetterBattle.get().hudLines();
        if (lines.isEmpty()) return;
        FontRenderer font = mc.fontRendererObj;
        int w = 0;
        for (String line : lines) w = Math.max(w, font.getStringWidth(line));
        int x = 4, y = 4;
        Gui.drawRect(x, y, x + w + 10, y + lines.size() * 10 + 6, 0xB0000000);
        Gui.drawRect(x, y, x + 2, y + lines.size() * 10 + 6, 0xFFFFAA00);
        for (String line : lines) {
            font.drawStringWithShadow(line, x + 6, y + 4, 0xFFFFFF);
            y += 10;
        }
    }
}
