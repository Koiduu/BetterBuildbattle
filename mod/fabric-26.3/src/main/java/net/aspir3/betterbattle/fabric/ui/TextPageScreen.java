package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.pages.Pages;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** One of the core's text pages (What's New, Last Game, Weekly Challenges), with page buttons if it's long. */
public final class TextPageScreen extends Screen {
    private static final int LINE = 11;

    private final Screen parent;
    private final Pages.Page page;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private final List<String> wrapped = new ArrayList<>();
    private int textWidth, perPage = 1, first;
    private Button prev, next;

    public TextPageScreen(Screen parent, Pages.Page page) {
        super(Component.literal(page.title));
        this.parent = parent;
        this.page = page;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);
        textWidth = Math.min(360, width - 40);
        wrapped.clear();
        for (String line : page.lines) wrapped.addAll(Pages.wrap(line, textWidth, font::width));

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        prev = footer.addChild(Button.builder(Component.literal("<"), b -> turn(-1)).width(20).build());
        footer.addChild(Button.builder(parent == null ? CommonComponents.GUI_DONE : CommonComponents.GUI_BACK, b -> onClose()).width(150).build());
        next = footer.addChild(Button.builder(Component.literal(">"), b -> turn(1)).width(20).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        perPage = Math.max(1, (layout.getContentHeight() - 16) / LINE);
        first = Math.max(0, Math.min(first, (wrapped.size() - 1) / perPage * perPage));
        updateButtons();
    }

    private void turn(int dir) {
        first = Math.max(0, Math.min(first + dir * perPage, (wrapped.size() - 1) / perPage * perPage));
        updateButtons();
    }

    private void updateButtons() {
        if (prev == null) return;
        prev.active = first > 0;
        next.active = first + perPage < wrapped.size();
        prev.visible = next.visible = wrapped.size() > perPage;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int x = width / 2 - textWidth / 2;
        int y = layout.getHeaderHeight() + 8;
        for (int i = first; i < wrapped.size() && i < first + perPage; i++) {
            graphics.text(font, wrapped.get(i), x, y, 0xFFFFFFFF, true);
            y += LINE;
        }
        if (wrapped.size() > perPage) {
            int pages = (wrapped.size() + perPage - 1) / perPage;
            String label = (first / perPage + 1) + " / " + pages;
            graphics.text(font, Component.literal(label).withStyle(ChatFormatting.DARK_GRAY),
                width - font.width(label) - 8, layout.getHeaderHeight() + layout.getContentHeight() - 12, 0xFFFFFFFF, false);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
