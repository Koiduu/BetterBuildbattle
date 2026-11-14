package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.BetterBattle;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

import java.util.List;

/**
 * The Speed Builders timer, shown in the toast corner while a round is on. It's a stopwatch and
 * your best time; it never shows anything about where blocks go.
 */
public final class SpbTimerToast implements Toast {
    private static final int LINE = 10;
    private static volatile boolean showing;

    private Toast.Visibility visibility = Toast.Visibility.SHOW;

    /** True while one is on screen, so the adapter adds at most one. */
    public static boolean showing() {
        return showing;
    }

    public SpbTimerToast() {
        showing = true;
    }

    @Override
    public Toast.Visibility getWantedVisibility() {
        return visibility;
    }

    @Override
    public void update(ToastManager manager, long visibleTime) {
        if (BetterBattle.get().hudLines().isEmpty()) {
            visibility = Toast.Visibility.HIDE;
            showing = false;
        }
    }

    @Override
    public int width() {
        return 150;
    }

    @Override
    public int height() {
        return 4 * LINE + 8;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long visibleTime) {
        graphics.fill(0, 0, width(), height(), 0xB0000000);
        graphics.fill(0, 0, 2, height(), 0xFFFFAA00);
        List<String> lines = BetterBattle.get().hudLines();
        int y = 5;
        for (int i = 0; i < lines.size() && i < 4; i++) {
            graphics.text(font, lines.get(i), 7, y, 0xFFFFFFFF, true);
            y += LINE;
        }
    }
}
