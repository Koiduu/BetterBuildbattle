package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.fabric.compat.Compat;
import com.mojang.blaze3d.platform.InputConstants;
import net.aspir3.betterbattle.fabric.BetterBattleFabric;
import net.aspir3.betterbattle.heads.BannerLibrary;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Banner designs: presets and your saved ones in a grid, and a small designer underneath. Click to put one in your hotbar. */
public final class BannersScreen extends Screen {
    private static final int CELL = 20, SIDE = 92, TOP = 30, DESIGNER = 64;
    private final Screen parent;
    private boolean showSaved;
    private int scrollRow;
    private BannerLibrary.Design design = new BannerLibrary.Design("", "white");
    private EditBox name;
    private Button base, pattern, colour, addLayer, removeLayer;
    private String status = "";

    public BannersScreen(Screen parent) {
        super(Component.literal("Banner Database"));
        this.parent = parent;
    }

    private static BannerLibrary library() { return BetterBattleFabric.banners(); }
    private List<BannerLibrary.Design> shown() { return showSaved ? library().saved() : BannerLibrary.PRESETS; }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Presets"), b -> { showSaved = false; scrollRow = 0; }).bounds(6, TOP, SIDE - 6, 16).build());
        addRenderableWidget(Button.builder(Component.literal("★ Saved"), b -> { showSaved = true; scrollRow = 0; }).bounds(6, TOP + 17, SIDE - 6, 16).build());

        int y1 = height - DESIGNER + 4, y2 = y1 + 22, x = 8;
        base = addRenderableWidget(Button.builder(Component.empty(), b -> design.base = next(BannerLibrary.COLORS, design.base)).bounds(x, y1, 96, 20).build());
        pattern = addRenderableWidget(Button.builder(Component.empty(), b -> last()[0] = next(BannerLibrary.PATTERNS, last()[0])).bounds(x + 100, y1, 150, 20).build());
        colour = addRenderableWidget(Button.builder(Component.empty(), b -> last()[1] = next(BannerLibrary.COLORS, last()[1])).bounds(x + 254, y1, 96, 20).build());
        addLayer = addRenderableWidget(Button.builder(Component.literal("+ Layer"), b -> design.layers.add(new String[]{"stripe_bottom", "black"})).bounds(x + 354, y1, 56, 20).build());
        removeLayer = addRenderableWidget(Button.builder(Component.literal("- Layer"), b -> design.layers.remove(design.layers.size() - 1)).bounds(x + 414, y1, 56, 20).build());

        name = new EditBox(font, x, y2, 150, 20, Component.literal("Name"));
        name.setHint(Component.literal("Name").withStyle(ChatFormatting.DARK_GRAY));
        name.setMaxLength(32);
        name.setValue(design.name == null ? "" : design.name);
        name.setResponder(v -> design.name = v);
        addRenderableWidget(name);
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> { library().save(design); status = "Saved."; }).bounds(x + 154, y2, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Get it"), b -> give(design)).bounds(x + 218, y2, 60, 20).build());
        addRenderableWidget(Button.builder(parent == null ? CommonComponents.GUI_DONE : CommonComponents.GUI_BACK, b -> onClose()).bounds(width - 108, y2, 100, 20).build());
    }

    private String[] last() { return design.layers.get(design.layers.size() - 1); }

    private static String next(String[] list, String current) {
        for (int i = 0; i < list.length; i++) if (list[i].equals(current)) return list[(i + 1) % list.length];
        return list[0];
    }

    private static String label(String id) {
        String s = id.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void give(BannerLibrary.Design d) {
        if (BetterBattleFabric.giveCreative(BetterBattleFabric.bannerItem(d))) status = "Put " + (d.name == null || d.name.isEmpty() ? "the banner" : d.name) + " in your hotbar.";
        else status = "Banners can only be given in creative mode, e.g. while building in Build Battle.";
    }

    private int columns() { return Math.max(1, (width - SIDE - 20) / CELL); }
    private int visibleRows() { return Math.max(1, (height - TOP - 14 - DESIGNER) / CELL); }

    private BannerLibrary.Design designAt(double mx, double my) {
        int x0 = SIDE + 10, y0 = TOP + 12;
        int col = (int) Math.floor((mx - x0) / CELL), row = (int) Math.floor((my - y0) / CELL);
        if (mx < x0 || my < y0 || col >= columns() || row >= visibleRows()) return null;
        int i = (scrollRow + row) * columns() + col;
        return i >= 0 && i < shown().size() ? shown().get(i) : null;
    }

    @Override
    public void tick() {
        boolean any = !design.layers.isEmpty();
        base.setMessage(Component.literal("Base: " + label(design.base)));
        pattern.active = colour.active = removeLayer.active = any;
        pattern.setMessage(Component.literal(any ? label(last()[0]) : "No layers"));
        colour.setMessage(Component.literal(any ? label(last()[1]) : "-"));
        addLayer.active = design.layers.size() < BannerLibrary.MAX_LAYERS;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int maxRow = Math.max(0, (shown().size() + columns() - 1) / columns() - visibleRows());
        scrollRow = Math.max(0, Math.min(maxRow, scrollRow - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        BannerLibrary.Design d = designAt(event.x(), event.y());
        if (d == null) return false;
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            give(d);
        } else if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (showSaved && event.hasShiftDown()) {
                library().remove(d);
                status = "Deleted " + d.name + ".";
            } else {
                design = d.copy();
                name.setValue(design.name == null ? "" : design.name);
                status = "Editing " + d.name + ".";
            }
        }
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
        int x0 = SIDE + 10, y0 = TOP + 12, cols = columns(), rows = visibleRows();
        List<BannerLibrary.Design> list = shown();
        String info = list.isEmpty() ? "Nothing saved yet: design one below and press Save."
            : "Click: put in hotbar (creative) · Right-click: edit" + (showSaved ? " · Shift+right-click: delete" : "");
        graphics.text(font, Component.literal(info).withStyle(ChatFormatting.GRAY), x0, TOP, 0xFFFFFFFF, false);
        graphics.fill(x0 - 2, y0 - 2, x0 + cols * CELL + 2, y0 + rows * CELL + 2, 0x66000000);
        BannerLibrary.Design hover = designAt(mouseX, mouseY);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = (scrollRow + r) * cols + c;
                if (i >= list.size()) break;
                BannerLibrary.Design d = list.get(i);
                int x = x0 + c * CELL, y = y0 + r * CELL;
                if (d == hover) graphics.fill(x, y, x + CELL, y + CELL, 0x40FFFFFF);
                graphics.item(BetterBattleFabric.bannerItem(d), x + 2, y + 2);
            }
        }
        if (hover != null) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(hover.name));
            graphics.setTooltipForNextFrame(font, lines, java.util.Optional.empty(), mouseX, mouseY);
        }
        int py = height - DESIGNER - 8;
        graphics.text(font, Component.literal("Designer (layer " + design.layers.size() + "/" + BannerLibrary.MAX_LAYERS + "):").withStyle(ChatFormatting.GRAY), 8, py, 0xFFFFFFFF, false);
        graphics.item(BetterBattleFabric.bannerItem(design), 300, height - DESIGNER + 26);
        graphics.text(font, Component.literal(status).withStyle(ChatFormatting.YELLOW), 324, height - DESIGNER + 30, 0xFFFFFFFF, false);
    }

    @Override
    public void onClose() {
        Compat.setScreen(minecraft, parent);
    }
}
