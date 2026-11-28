package net.aspir3.betterbattle.fabric.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Lobby news in the corner: a join request, an answer, a free slot. The chat line under it has the buttons. */
public final class LobbyToast implements Toast {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("toast/advancement");
    private static final long SHOW_MS = 6000L;

    private final String title, line;
    private Toast.Visibility visibility = Toast.Visibility.SHOW;

    public LobbyToast(String title, String line) {
        this.title = title;
        this.line = line;
    }

    @Override
    public Toast.Visibility getWantedVisibility() {
        return visibility;
    }

    @Override
    public void update(ToastManager manager, long visibleTime) {
        visibility = visibleTime >= SHOW_MS * manager.getNotificationDisplayTimeMultiplier() ? Toast.Visibility.HIDE : Toast.Visibility.SHOW;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long visibleTime) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, width(), height());
        graphics.text(font, title, 8, 7, 0xFFFF55FF, false);
        graphics.text(font, font.plainSubstrByWidth(line, width() - 16), 8, 18, 0xFFFFFFFF, false);
    }
}
