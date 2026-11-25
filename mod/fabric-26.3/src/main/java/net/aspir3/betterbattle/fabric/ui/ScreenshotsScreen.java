package net.aspir3.betterbattle.fabric.ui;

import com.mojang.blaze3d.platform.NativeImage;
import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.fabric.compat.Compat;
import net.aspir3.betterbattle.shots.RoundShots;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

/** Your round screenshots, newest first, one at a time. They are deleted after 15 days. */
public final class ScreenshotsScreen extends Screen {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(BetterBattle.MOD_ID, "round_screenshot");

    private final Screen parent;
    private List<File> files;
    private int index;
    private boolean loaded;
    private int imgW, imgH;
    private String problem;
    private Button newer, older, delete;

    public ScreenshotsScreen(Screen parent) {
        super(Component.literal("Round Screenshots"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        files = RoundShots.list(RoundShots.dir(BetterBattle.get().dir()));
        index = Math.max(0, Math.min(index, files.size() - 1));
        int y = height - 26;
        newer = addRenderableWidget(Button.builder(Component.literal("< Newer"), b -> go(-1)).bounds(width / 2 - 156, y, 70, 20).build());
        older = addRenderableWidget(Button.builder(Component.literal("Older >"), b -> go(1)).bounds(width / 2 - 82, y, 70, 20).build());
        delete = addRenderableWidget(Button.builder(Component.literal("Delete"), b -> deleteCurrent()).bounds(width / 2 - 8, y, 70, 20).build());
        addRenderableWidget(Button.builder(parent == null ? CommonComponents.GUI_DONE : CommonComponents.GUI_BACK, b -> onClose())
            .bounds(width / 2 + 66, y, 90, 20).build());
        load();
    }

    private void go(int dir) {
        index = Math.max(0, Math.min(index + dir, files.size() - 1));
        load();
    }

    private void deleteCurrent() {
        if (files.isEmpty()) return;
        files.get(index).delete();
        files.remove(index);
        index = Math.max(0, Math.min(index, files.size() - 1));
        load();
    }

    private void release() {
        if (loaded) minecraft.getTextureManager().release(TEXTURE);
        loaded = false;
    }

    private void load() {
        release();
        problem = null;
        boolean any = !files.isEmpty();
        newer.active = any && index > 0;
        older.active = any && index < files.size() - 1;
        delete.active = any;
        if (!any) return;
        try (InputStream in = new FileInputStream(files.get(index))) {
            NativeImage image = NativeImage.read(in);
            imgW = image.getWidth();
            imgH = image.getHeight();
            minecraft.getTextureManager().register(TEXTURE, new DynamicTexture(() -> "Better Battle round screenshot", image));
            loaded = true;
        } catch (Exception e) {
            problem = "Couldn't open this picture.";
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
        if (files.isEmpty()) {
            graphics.centeredText(font, Component.literal("No round screenshots yet.").withStyle(ChatFormatting.GRAY), width / 2, height / 2 - 12, 0xFFFFFFFF);
            graphics.centeredText(font, Component.literal("One is saved when the building time ends. They are kept for 15 days.")
                .withStyle(ChatFormatting.DARK_GRAY), width / 2, height / 2, 0xFFFFFFFF);
            return;
        }
        graphics.centeredText(font, Component.literal(RoundShots.label(files.get(index)) + "   (" + (index + 1) + " of " + files.size() + ")")
            .withStyle(ChatFormatting.GRAY), width / 2, 24, 0xFFFFFFFF);
        if (problem != null) {
            graphics.centeredText(font, Component.literal(problem).withStyle(ChatFormatting.RED), width / 2, height / 2, 0xFFFFFFFF);
        } else if (loaded) {
            int maxW = width - 40, maxH = height - 80;
            double scale = Math.min(maxW / (double) imgW, maxH / (double) imgH);
            int w = Math.max(1, (int) (imgW * scale)), h = Math.max(1, (int) (imgH * scale));
            graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, (width - w) / 2, 38, 0f, 0f, w, h, imgW, imgH, imgW, imgH);
        }
    }

    @Override
    public void removed() {
        release();
    }

    @Override
    public void onClose() {
        Compat.setScreen(minecraft, parent);
    }
}
