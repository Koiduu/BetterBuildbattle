package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.BetterBattle;
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
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Banner Database: categories on the left, a search box and a grid of designs on the right, and a small
 * designer underneath. Click a design to put it in your hotbar, right-click to edit a copy.
 */
public final class BannersScreen extends Screen {
    private static final int CELL = 20, SIDE = 92, TOP = 30, GRID = TOP + 34, DESIGNER = 64;
    /** Banners drawn at once: each is a full item model, so cap it like the head database does. */
    private static final int MAX_SHOWN = 150, MAX_SHOWN_LOW_END = 40;
    /** Built item stacks, so scrolling a big category doesn't rebuild every banner every frame. */
    private static final Map<BannerLibrary.Design, ItemStack> ITEMS = new IdentityHashMap<>();

    private final Screen parent;
    private String category = BannerLibrary.ALL;
    private final List<String> categoryIds = new ArrayList<>();
    private final List<Button> categoryButtons = new ArrayList<>();
    private Button categoryCycle;
    private EditBox search;
    private List<BannerLibrary.Design> shown = new ArrayList<>();
    private String lastQuery, lastCategory;
    private boolean dirty = true;
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

    private String categoryLabel(String id) {
        if (BannerLibrary.ALL.equals(id)) return "All banners";
        if (BannerLibrary.SAVED.equals(id)) return "★ Saved";
        for (BannerLibrary.Category c : library().categories()) if (c.id.equals(id)) return c.label;
        return id;
    }

    @Override
    protected void init() {
        categoryIds.clear();
        categoryIds.add(BannerLibrary.ALL);
        categoryIds.add(BannerLibrary.SAVED);
        for (BannerLibrary.Category c : library().categories()) categoryIds.add(c.id);

        // One button per category when they fit above the designer, else one button that cycles through them
        categoryButtons.clear();
        categoryCycle = null;
        int room = height - DESIGNER - 12 - TOP, step = Math.min(17, room / categoryIds.size());
        if (step >= 12) {
            int y = TOP;
            for (String id : categoryIds) {
                categoryButtons.add(addRenderableWidget(Button.builder(Component.literal(categoryLabel(id)), b -> pick(id))
                    .bounds(6, y, SIDE - 6, step - 1).build()));
                y += step;
            }
        } else {
            categoryCycle = addRenderableWidget(Button.builder(Component.empty(), b -> {
                int i = categoryIds.indexOf(category);
                pick(categoryIds.get((i + 1) % categoryIds.size()));
            }).bounds(6, TOP, SIDE - 6, 18).build());
        }

        search = new EditBox(font, SIDE + 10, TOP, Math.max(100, width - SIDE - 20), 18, Component.literal("Search"));
        search.setHint(Component.literal("Search: flag, letter a, heart, red...").withStyle(ChatFormatting.DARK_GRAY));
        search.setMaxLength(48);
        search.setValue(lastQuery == null ? "" : lastQuery);
        search.setResponder(q -> scrollRow = 0);
        addRenderableWidget(search);
        setInitialFocus(search);

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
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
            library().save(design);
            dirty = true;
            status = "Saved.";
        }).bounds(x + 154, y2, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Get it"), b -> give(design)).bounds(x + 218, y2, 60, 20).build());
        addRenderableWidget(Button.builder(parent == null ? CommonComponents.GUI_DONE : CommonComponents.GUI_BACK, b -> onClose()).bounds(width - 108, y2, 100, 20).build());
        dirty = true;
    }

    private void pick(String id) {
        category = id;
        scrollRow = 0;
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

    private static int maxShown() {
        return BetterBattle.get().config().lowEndMode ? MAX_SHOWN_LOW_END : MAX_SHOWN;
    }

    private int columns() { return Math.max(1, Math.min((width - SIDE - 20) / CELL, maxShown())); }

    private int visibleRows() {
        int fit = Math.max(1, (height - GRID - 8 - DESIGNER) / CELL);
        return Math.max(1, Math.min(fit, maxShown() / columns()));
    }

    private int maxRow() { return Math.max(0, (shown.size() + columns() - 1) / columns() - visibleRows()); }

    private BannerLibrary.Design designAt(double mx, double my) {
        int x0 = SIDE + 10;
        int col = (int) Math.floor((mx - x0) / CELL), row = (int) Math.floor((my - GRID) / CELL);
        if (mx < x0 || my < GRID || col >= columns() || row >= visibleRows()) return null;
        int i = (scrollRow + row) * columns() + col;
        return i >= 0 && i < shown.size() ? shown.get(i) : null;
    }

    private static ItemStack item(BannerLibrary.Design d) {
        ItemStack s = ITEMS.get(d);
        if (s == null) {
            s = BetterBattleFabric.bannerItem(d);
            // Without a world the patterns can't be looked up yet: don't keep the plain banner
            if (net.minecraft.client.Minecraft.getInstance().level == null) return s;
            if (ITEMS.size() > 800) ITEMS.clear();
            ITEMS.put(d, s);
        }
        return s;
    }

    @Override
    public void tick() {
        String q = search.getValue();
        if (dirty || !q.equals(lastQuery) || !category.equals(lastCategory)) {
            shown = library().search(category, q);
            lastQuery = q;
            lastCategory = category;
            dirty = false;
            scrollRow = Math.min(scrollRow, maxRow());
        }
        if (categoryCycle != null) categoryCycle.setMessage(Component.literal("▸ " + categoryLabel(category)));
        boolean any = !design.layers.isEmpty();
        base.setMessage(Component.literal("Base: " + label(design.base)));
        pattern.active = colour.active = removeLayer.active = any;
        pattern.setMessage(Component.literal(any ? label(last()[0]) : "No layers"));
        colour.setMessage(Component.literal(any ? label(last()[1]) : "-"));
        addLayer.active = design.layers.size() < BannerLibrary.MAX_LAYERS;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scrollRow = Math.max(0, Math.min(maxRow(), scrollRow - (int) Math.signum(scrollY) * 2));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        BannerLibrary.Design d = designAt(event.x(), event.y());
        if (d == null) return false;
        boolean mine = library().saved().contains(d);
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            give(d);
        } else if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (mine && event.hasShiftDown()) {
                library().remove(d);
                dirty = true;
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
        for (int i = 0; i < categoryButtons.size(); i++) {
            Button b = categoryButtons.get(i);
            if (categoryIds.get(i).equals(category)) graphics.outline(b.getX() - 1, b.getY() - 1, b.getWidth() + 2, b.getHeight() + 2, 0xFFFFFF55);
        }

        int x0 = SIDE + 10, cols = columns(), rows = visibleRows();
        String info;
        if (!shown.isEmpty()) info = shown.size() + (shown.size() == 1 ? " banner" : " banners") + "  ·  click: put in hotbar  ·  right-click: edit";
        else if (BannerLibrary.SAVED.equals(category) && search.getValue().trim().isEmpty()) info = "Nothing saved yet: design one below and press Save.";
        else info = "No banners match.";
        graphics.text(font, Component.literal(info).withStyle(ChatFormatting.GRAY), x0, GRID - 12, 0xFFFFFFFF, false);
        graphics.fill(x0 - 2, GRID - 2, x0 + cols * CELL + 2, GRID + rows * CELL + 2, 0x66000000);
        BannerLibrary.Design hover = designAt(mouseX, mouseY);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = (scrollRow + r) * cols + c;
                if (i >= shown.size()) break;
                BannerLibrary.Design d = shown.get(i);
                int x = x0 + c * CELL, y = GRID + r * CELL;
                if (d == hover) graphics.fill(x, y, x + CELL, y + CELL, 0x40FFFFFF);
                graphics.item(item(d), x + 2, y + 2);
            }
        }
        if (hover != null) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(hover.name));
            boolean mine = library().saved().contains(hover);
            lines.add(Component.literal(mine ? "Saved by you" : categoryLabel(hover.category)).withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.literal(mine ? "Click: hotbar · Right-click: edit · Shift+right-click: delete" : "Click: hotbar · Right-click: edit a copy").withStyle(ChatFormatting.GRAY));
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
