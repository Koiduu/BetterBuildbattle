package net.aspir3.betterbattle.fabric.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A teammate ping, in the same frame as the achievement toast: who it's from on top, the callout below. */
public final class PingToast implements Toast {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("toast/advancement");
    private static final long SHOW_MS = 3500L;
    private static final ItemStack ICON = new ItemStack(Items.BELL);

    private final String title, text;
    private Toast.Visibility visibility = Toast.Visibility.SHOW;

    public PingToast(String title, String text) {
        this.title = title;
        this.text = text;
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
        graphics.text(font, Component.literal(title), 30, 7, 0xFFFF55FF, false);
        graphics.text(font, Component.literal(text), 30, 18, 0xFFFFFFFF, false);
        graphics.fakeItem(ICON, 8, 8);
    }
}
