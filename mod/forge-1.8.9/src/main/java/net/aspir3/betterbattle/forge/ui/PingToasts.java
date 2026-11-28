package net.aspir3.betterbattle.forge.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Teammate pings in the same box as the achievement toasts, one at a time. Drawn one box lower, so a ping
 * and an achievement can show together.
 */
public final class PingToasts {
    private static final ResourceLocation TEXTURE = new ResourceLocation("textures/gui/achievement/achievement_background.png");
    private static final long SHOW_MS = 3000L;
    private static final int MAX_WAITING = 4;
    private static final Deque<String[]> QUEUE = new ArrayDeque<String[]>();
    private static final ItemStack ICON = new ItemStack(Items.compass);

    private String[] current;
    private long shownAt;

    /** Main thread. Old ones are dropped if pings pile up. */
    public static void add(String title, String text) {
        while (QUEUE.size() >= MAX_WAITING) QUEUE.poll();
        QUEUE.add(new String[] {title, text});
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
        draw(Minecraft.getMinecraft(), (now - shownAt) / (double) SHOW_MS);
    }

    private void draw(Minecraft mc, double t) {
        // The achievement toast's easing: slide in, hold, slide out
        double slide = t * 2.0;
        if (slide > 1.0) slide = 2.0 - slide;
        slide *= 4.0;
        slide = 1.0 - slide;
        if (slide < 0.0) slide = 0.0;
        slide *= slide;
        slide *= slide;

        ScaledResolution res = new ScaledResolution(mc);
        int x = res.getScaledWidth() - 160;
        int y = 34 - (int) (slide * 70.0);

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableLighting();
        mc.getTextureManager().bindTexture(TEXTURE);
        new Gui().drawTexturedModalRect(x, y, 96, 202, 160, 32);

        mc.fontRendererObj.drawString(current[0], x + 30, y + 7, 0xFF55FF);
        mc.fontRendererObj.drawString(current[1], x + 30, y + 18, 0xFFFFFF);

        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableColorMaterial();
        GlStateManager.enableLighting();
        mc.getRenderItem().renderItemAndEffectIntoGUI(ICON, x + 8, y + 8);
        GlStateManager.disableLighting();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
    }
}
