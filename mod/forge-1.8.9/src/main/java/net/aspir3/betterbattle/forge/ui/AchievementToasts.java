package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.achieve.Catalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Draws the same slide-in box 1.8.9 uses for its own achievements, one toast at a time.
 */
public final class AchievementToasts {
    private static final ResourceLocation TEXTURE = new ResourceLocation("textures/gui/achievement/achievement_background.png");
    private static final long SHOW_MS = 4000L;
    private static final Deque<Catalog.Entry> QUEUE = new ArrayDeque<Catalog.Entry>();

    private Catalog.Entry current;
    private ItemStack icon;
    private long shownAt;

    public static void add(Catalog.Entry achievement) {
        QUEUE.add(achievement);
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        long now = Minecraft.getSystemTime();
        if (current == null || now - shownAt > SHOW_MS) {
            current = QUEUE.poll();
            if (current == null) return;
            icon = Icons.of(current);
            shownAt = now;
        }
        draw(Minecraft.getMinecraft(), (now - shownAt) / (double) SHOW_MS);
    }

    private void draw(Minecraft mc, double t) {
        // Same easing as vanilla GuiAchievement: slide in, hold, slide out
        double slide = t * 2.0;
        if (slide > 1.0) slide = 2.0 - slide;
        slide *= 4.0;
        slide = 1.0 - slide;
        if (slide < 0.0) slide = 0.0;
        slide *= slide;
        slide *= slide;

        ScaledResolution res = new ScaledResolution(mc);
        int x = res.getScaledWidth() - 160;
        int y = -(int) (slide * 36.0);

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableLighting();
        mc.getTextureManager().bindTexture(TEXTURE);
        new Gui().drawTexturedModalRect(x, y, 96, 202, 160, 32);

        mc.fontRendererObj.drawString("Achievement Unlocked!", x + 30, y + 7, 0xFFAA00);
        mc.fontRendererObj.drawString(current.name, x + 30, y + 18, 0xFFFFFF);

        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableColorMaterial();
        GlStateManager.enableLighting();
        mc.getRenderItem().renderItemAndEffectIntoGUI(icon, x + 8, y + 8);
        GlStateManager.disableLighting();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
    }
}
