package net.aspir3.betterbattle.forge.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.Deque;

/** Lobby news in the same slide-in box as the achievement toasts. The chat line under it has the buttons. */
public final class LobbyToasts {
    private static final ResourceLocation TEXTURE = new ResourceLocation("textures/gui/achievement/achievement_background.png");
    private static final long SHOW_MS = 5000L;
    private static final Deque<String[]> QUEUE = new ArrayDeque<String[]>();

    private String[] current;
    private long shownAt;

    public static void add(String title, String line) {
        QUEUE.add(new String[] {title, line});
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        long now = Minecraft.getSystemTime();
        if (current == null || now - shownAt > SHOW_MS) {
            current = QUEUE.poll();
            if (current == null) return;
            shownAt = now;
        }
        double t = (now - shownAt) / (double) SHOW_MS;
        double slide = t * 2.0;
        if (slide > 1.0) slide = 2.0 - slide;
        slide = Math.max(0.0, 1.0 - slide * 4.0);
        slide = slide * slide * slide * slide;

        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution res = new ScaledResolution(mc);
        int x = res.getScaledWidth() - 160;
        int y = -(int) (slide * 36.0);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableLighting();
        mc.getTextureManager().bindTexture(TEXTURE);
        new Gui().drawTexturedModalRect(x, y, 96, 202, 160, 32);
        mc.fontRendererObj.drawString(current[0], x + 8, y + 7, 0xFF55FF);
        mc.fontRendererObj.drawString(mc.fontRendererObj.trimStringToWidth(current[1], 144), x + 8, y + 18, 0xFFFFFF);
    }
}
