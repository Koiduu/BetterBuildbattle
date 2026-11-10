package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.achieve.Catalog;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Looks like a vanilla advancement toast, with Hypixel's gold "Achievement" wording. */
public final class AchievementToast implements Toast {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("toast/advancement");
    private static final long SHOW_MS = 5000L;

    private final Catalog.Entry achievement;
    private final ItemStack icon;
    private Toast.Visibility visibility = Toast.Visibility.SHOW;

    public AchievementToast(Catalog.Entry achievement) {
        this.achievement = achievement;
        this.icon = Icons.of(achievement);
    }

    @Override
    public Toast.Visibility getWantedVisibility() {
        return visibility;
    }

    @Override
    public void update(ToastManager manager, long visibleTime) {
        visibility = visibleTime >= SHOW_MS * manager.getNotificationDisplayTimeMultiplier()
            ? Toast.Visibility.HIDE
            : Toast.Visibility.SHOW;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long visibleTime) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, width(), height());
        graphics.text(font, Component.literal("Achievement Unlocked!"), 30, 7, 0xFFFFAA00, false);
        graphics.text(font, Component.literal(achievement.name), 30, 18, 0xFFFFFFFF, false);
        graphics.fakeItem(icon, 8, 8);
    }
}
