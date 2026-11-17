package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.fabric.BetterBattleFabric;
import net.aspir3.betterbattle.heads.HeadLibrary;
import net.aspir3.betterbattle.heads.SkinSwap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The head database. Categories on the left, a grid of heads on the right. Clicking a head gets it
 * with /skull (your skin wears it for a moment); right-click to favourite.
 */
public final class HeadsScreen extends Screen {
    private static final int CELL = 20, SIDE = 92, TOP = 58;
    private static final java.util.regex.Pattern USERNAME = java.util.regex.Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Map<String, ItemStack> ITEMS = new HashMap<>();

    private final Screen parent;
    private final String startQuery;
    private EditBox search;
    private Button playerHead, cancel;
    private final List<Button> categoryButtons = new ArrayList<>();
    private String category = "all";
    private List<HeadLibrary.Head> shown = new ArrayList<>();
    private int scrollRow, lastLoaded = -1;
    private String lastQuery = null, lastCategory = null;
    private HeadLibrary.Head confirming;

    public HeadsScreen(Screen parent, String query) {
        super(Component.literal("Head Database"));
        this.parent = parent;
        this.startQuery = query == null ? "" : query;
    }

    private static HeadLibrary library() { return BetterBattleFabric.heads(); }
    private static SkinSwap swap() { return BetterBattleFabric.swap(); }

    @Override
    protected void init() {
        library().loadAll();
        swap().restoreIfLeftOver();
        search = new EditBox(font, SIDE + 10, 30, Math.max(120, width - SIDE - 150), 20, Component.literal("Search"));
        search.setHint(Component.literal("Search heads, or type a player name").withStyle(ChatFormatting.DARK_GRAY));
        search.setMaxLength(48);
        if (lastQuery == null) search.setValue(startQuery);
        else search.setValue(lastQuery);
        search.setResponder(q -> scrollRow = 0);
        addRenderableWidget(search);
        setInitialFocus(search);

        playerHead = addRenderableWidget(Button.builder(Component.literal("Player head"), b -> {
            swap().playerHead(search.getValue().trim());
        }).bounds(width - 132, 30, 122, 20).tooltip(Tooltip.create(Component.literal("Runs /skull with the name you typed"))).build());

        categoryButtons.clear();
        int y = 30;
        y = categoryButton("all", "All heads", y);
        y = categoryButton("favourites", "★ Favourites", y);
        y = categoryButton("recent", "Recent", y);
        y += 4;
        for (HeadLibrary.Category c : HeadLibrary.CATEGORIES) y = categoryButton(c.id, c.label, y);

        cancel = addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> swap().cancel()).bounds(width - 214, height - 26, 100, 20).build());
        addRenderableWidget(Button.builder(parent == null ? CommonComponents.GUI_DONE : CommonComponents.GUI_BACK, b -> onClose()).bounds(width - 108, height - 26, 100, 20).build());
    }

    private int categoryButton(String id, String label, int y) {
        Button b = addRenderableWidget(Button.builder(Component.literal(label), btn -> {
            category = id;
            scrollRow = 0;
        }).bounds(6, y, SIDE - 6, 16).build());
        b.setMessage(Component.literal(label));
        categoryButtons.add(b);
        return y + 17;
    }

    private int columns() { return Math.max(1, (width - SIDE - 20) / CELL); }
    private int visibleRows() { return Math.max(1, (height - TOP - 34) / CELL); }

    @Override
    public void tick() {
        String q = search.getValue();
        int loaded = library().loadedCount();
        if (!q.equals(lastQuery) || !category.equals(lastCategory) || loaded != lastLoaded) {
            shown = library().search(category, q);
            lastQuery = q;
            lastCategory = category;
            lastLoaded = loaded;
            int maxRow = Math.max(0, (shown.size() + columns() - 1) / columns() - visibleRows());
            scrollRow = Math.min(scrollRow, maxRow);
        }
        playerHead.active = !swap().busy() && USERNAME.matcher(q.trim()).matches();
        SkinSwap.Step step = swap().step();
        cancel.active = step == SkinSwap.Step.RECONNECTING || step == SkinSwap.Step.WAITING || step == SkinSwap.Step.ASKED;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int maxRow = Math.max(0, (shown.size() + columns() - 1) / columns() - visibleRows());
        scrollRow = Math.max(0, Math.min(maxRow, scrollRow - (int) Math.signum(scrollY) * 2));
        return true;
    }

    private HeadLibrary.Head headAt(double mx, double my) {
        int col = (int) Math.floor((mx - SIDE - 10) / CELL), row = (int) Math.floor((my - TOP) / CELL);
        if (mx < SIDE + 10 || my < TOP || col >= columns() || row >= visibleRows()) return null;
        int i = (scrollRow + row) * columns() + col;
        return i >= 0 && i < shown.size() ? shown.get(i) : null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        HeadLibrary.Head h = headAt(event.x(), event.y());
        if (h == null) return false;
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            library().toggleFavourite(h);
            lastLoaded = -1;
            return true;
        }
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || swap().busy()) return true;
        if (confirming != null && confirming.hash.equals(h.hash)) {
            confirming = null;
            library().addRecent(h);
            swap().start(h);
        } else {
            confirming = h;
        }
        return true;
    }

    private static ItemStack item(HeadLibrary.Head h) {
        ItemStack s = ITEMS.get(h.hash);
        if (s == null) {
            if (ITEMS.size() > 4000) ITEMS.clear();
            s = BetterBattleFabric.headItem(h);
            ITEMS.put(h.hash, s);
        }
        return s;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
        for (Button b : categoryButtons) {
            int i = categoryButtons.indexOf(b);
            String id = i == 0 ? "all" : i == 1 ? "favourites" : i == 2 ? "recent" : HeadLibrary.CATEGORIES.get(i - 3).id;
            if (id.equals(category)) graphics.outline(b.getX() - 1, b.getY() - 1, b.getWidth() + 2, b.getHeight() + 2, 0xFFFFFF55);
        }

        int x0 = SIDE + 10, cols = columns(), rows = visibleRows();
        String info;
        boolean anyLoading = false;
        for (HeadLibrary.Category c : HeadLibrary.CATEGORIES) anyLoading |= library().isLoading(c.id);
        String err = HeadLibrary.CATEGORIES.stream().map(c -> library().error(c.id)).filter(e -> e != null).findFirst().orElse(null);
        if (shown.isEmpty() && anyLoading) info = "Loading heads...";
        else if (shown.isEmpty() && err != null) info = "Couldn't load heads: " + err;
        else info = String.format("%,d heads", shown.size()) + (anyLoading ? " (still loading)" : "") + "  ·  click twice to get one, right-click to favourite";
        graphics.text(font, Component.literal(info).withStyle(ChatFormatting.GRAY), x0, TOP - 12, 0xFFFFFFFF, false);

        graphics.fill(x0 - 2, TOP - 2, x0 + cols * CELL + 2, TOP + rows * CELL + 2, 0x66000000);
        HeadLibrary.Head hover = headAt(mouseX, mouseY);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = (scrollRow + r) * cols + c;
                if (i >= shown.size()) break;
                HeadLibrary.Head h = shown.get(i);
                int x = x0 + c * CELL, y = TOP + r * CELL;
                if (h == hover) graphics.fill(x, y, x + CELL, y + CELL, 0x40FFFFFF);
                if (confirming != null && confirming.hash.equals(h.hash)) graphics.outline(x, y, CELL, CELL, 0xFF55FF55);
                else if (library().isFavourite(h)) graphics.outline(x, y, CELL, CELL, 0xAAFFD700);
                graphics.item(item(h), x + 2, y + 2);
            }
        }

        if (hover != null) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(hover.name));
            if (hover.tags != null && !hover.tags.isEmpty()) lines.add(Component.literal(hover.tags).withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.literal(confirming == hover ? "Click again to get it with /skull" : "Click to get · Right-click to favourite").withStyle(ChatFormatting.GRAY));
            graphics.setTooltipForNextFrame(font, lines, java.util.Optional.empty(), mouseX, mouseY);
        }

        String status = swap().status();
        if (confirming != null && !swap().busy())
            status = "Get " + confirming.name + "? You'll briefly rejoin Hypixel wearing it as your skin, then it changes back. Click it again.";
        graphics.text(font, Component.literal(status).withStyle(swap().busy() ? ChatFormatting.YELLOW : ChatFormatting.GRAY), 8, height - 20, 0xFFFFFFFF, false);
        graphics.text(font, Component.literal(HeadLibrary.CREDIT).withStyle(ChatFormatting.DARK_GRAY), 8, height - 36, 0xFFFFFFFF, false);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
