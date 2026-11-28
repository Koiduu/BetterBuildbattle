package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.fabric.compat.Compat;
import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.fabric.BetterBattleFabric;
import net.aspir3.betterbattle.heads.HeadLibrary;
import net.aspir3.betterbattle.heads.MyHeads;
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
 * in your hotbar during the build phase (creative); right-click to favourite. "My heads" holds the
 * player's own heads, painted at battle.aspir3.net/heads.
 */
public final class HeadsScreen extends Screen {
    private static final int CELL = 20, SIDE = 92, TOP = 58;
    private static final String BUILD_PHASE_ONLY = "Heads only work during the build phase (creative mode). Open this again once building starts.";
    private static final java.util.regex.Pattern USERNAME = java.util.regex.Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Map<String, ItemStack> ITEMS = new HashMap<>();

    private final Screen parent;
    private final String startQuery;
    private EditBox search;
    private Button playerHead;
    private final List<Button> categoryButtons = new ArrayList<>();
    private final List<String> categoryIds = new ArrayList<>();
    private String category = "all";
    private List<HeadLibrary.Head> shown = new ArrayList<>();
    private int scrollRow, lastLoaded = -1, lastMine = -1;
    private String lastQuery = null, lastCategory = null;
    private String given;

    public HeadsScreen(Screen parent, String query) {
        super(Component.literal("Head Database"));
        this.parent = parent;
        this.startQuery = query == null ? "" : query;
    }

    private static HeadLibrary library() { return BetterBattleFabric.heads(); }

    @Override
    protected void init() {
        library().loadAll();
        search = new EditBox(font, SIDE + 10, 30, Math.max(120, width - SIDE - 150), 20, Component.literal("Search"));
        search.setHint(Component.literal("Search heads, or type a player name").withStyle(ChatFormatting.DARK_GRAY));
        search.setMaxLength(48);
        if (lastQuery == null) search.setValue(startQuery);
        else search.setValue(lastQuery);
        search.setResponder(q -> scrollRow = 0);
        addRenderableWidget(search);
        setInitialFocus(search);

        playerHead = addRenderableWidget(Button.builder(Component.literal("Player head"), b -> {
            // A real player's head: Hypixel's /skull works with any username
            String name = search.getValue().trim();
            if (minecraft.getConnection() != null) minecraft.getConnection().sendCommand("skull " + name);
            given = "Asked for " + name + "'s head";
        }).bounds(width - 132, 30, 122, 20).tooltip(Tooltip.create(Component.literal("Runs /skull with the name you typed"))).build());

        categoryButtons.clear();
        categoryIds.clear();
        int y = 30;
        y = categoryButton("all", "All heads", y);
        y = categoryButton(MyHeads.CATEGORY, "My heads", y);
        y = categoryButton("favourites", "★ Favourites", y);
        y = categoryButton("recent", "Recent", y);
        y += 4;
        for (HeadLibrary.Category c : HeadLibrary.CATEGORIES) y = categoryButton(c.id, c.label, y);

        addRenderableWidget(Button.builder(parent == null ? CommonComponents.GUI_DONE : CommonComponents.GUI_BACK, b -> onClose()).bounds(width - 108, height - 26, 100, 20).build());
    }

    private int categoryButton(String id, String label, int y) {
        Button b = addRenderableWidget(Button.builder(Component.literal(label), btn -> {
            category = id;
            scrollRow = 0;
            if (MyHeads.CATEGORY.equals(id)) BetterBattle.get().refreshMyHeads();
        }).bounds(6, y, SIDE - 6, 16).build());
        b.setMessage(Component.literal(label));
        categoryButtons.add(b);
        categoryIds.add(id);
        return y + 17;
    }

    /** Heads drawn at once: every one is a full item model, so a small GUI scale on a big window gets very heavy. */
    private static final int MAX_HEADS = 150, MAX_HEADS_LOW_END = 40;

    private int columns() {
        int fit = Math.max(1, (width - SIDE - 20) / CELL);
        return Math.min(fit, maxHeads());
    }

    private int visibleRows() {
        int fit = Math.max(1, (height - TOP - 34) / CELL);
        return Math.max(1, Math.min(fit, maxHeads() / columns()));
    }

    private static int maxHeads() {
        return BetterBattle.get().config().lowEndMode ? MAX_HEADS_LOW_END : MAX_HEADS;
    }

    @Override
    public void tick() {
        String q = search.getValue();
        int loaded = library().loadedCount(), mine = MyHeads.version();
        if (!q.equals(lastQuery) || !category.equals(lastCategory) || loaded != lastLoaded || mine != lastMine) {
            lastMine = mine;
            shown = library().search(category, q);
            lastQuery = q;
            lastCategory = category;
            lastLoaded = loaded;
            int maxRow = Math.max(0, (shown.size() + columns() - 1) / columns() - visibleRows());
            scrollRow = Math.min(scrollRow, maxRow);
        }
        playerHead.active = USERNAME.matcher(q.trim()).matches();
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
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return true;
        if (BetterBattleFabric.giveCreative(BetterBattleFabric.headItem(h))) {
            library().addRecent(h);
            given = "Put " + h.name + " in your hotbar.";
        } else {
            given = BUILD_PHASE_ONLY;
        }
        return true;
    }

    private static ItemStack item(HeadLibrary.Head h) {
        ItemStack s = ITEMS.get(h.hash);
        if (s == null) {
            if (ITEMS.size() > 600) ITEMS.clear();
            s = BetterBattleFabric.headItem(h);
            ITEMS.put(h.hash, s);
        }
        return s;
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
        boolean anyLoading = false;
        for (HeadLibrary.Category c : HeadLibrary.CATEGORIES) anyLoading |= library().isLoading(c.id);
        String err = HeadLibrary.CATEGORIES.stream().map(c -> library().error(c.id)).filter(e -> e != null).findFirst().orElse(null);
        boolean mineView = MyHeads.CATEGORY.equals(category);
        if (mineView && shown.isEmpty() && search.getValue().trim().isEmpty()) info = "No custom heads yet. Paint your own at " + MyHeads.PAINT_URL + ", then link with /bb link.";
        else if (mineView) info = shown.size() + (shown.size() == 1 ? " head" : " heads") + " you painted  ·  click to put one in your hotbar  ·  more at " + MyHeads.PAINT_URL;
        else if (shown.isEmpty() && anyLoading) info = "Loading heads...";
        else if (shown.isEmpty() && err != null) info = "Couldn't load heads: " + err;
        else info = String.format("%,d heads", shown.size()) + (anyLoading ? " (still loading)" : "") + "  ·  click to put one in your hotbar, right-click to favourite";
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
                if (library().isFavourite(h)) graphics.outline(x, y, CELL, CELL, 0xAAFFD700);
                graphics.item(item(h), x + 2, y + 2);
            }
        }

        if (hover != null) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(hover.name));
            if (hover.tags != null && !hover.tags.isEmpty()) lines.add(Component.literal(hover.tags).withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.literal("Click: put in hotbar · Right-click: favourite").withStyle(ChatFormatting.GRAY));
            graphics.setTooltipForNextFrame(font, lines, java.util.Optional.empty(), mouseX, mouseY);
        }

        String status = given == null ? "" : given;
        ChatFormatting statusColour = BUILD_PHASE_ONLY.equals(given) ? ChatFormatting.RED : ChatFormatting.GRAY;
        graphics.text(font, Component.literal(status).withStyle(statusColour), 8, height - 20, 0xFFFFFFFF, false);
        String credit = MyHeads.CATEGORY.equals(category) ? MyHeads.NOTICE : HeadLibrary.CREDIT;
        graphics.text(font, Component.literal(credit).withStyle(ChatFormatting.DARK_GRAY), 8, height - 36, 0xFFFFFFFF, false);
    }

    @Override
    public void onClose() {
        Compat.setScreen(minecraft, parent);
    }
}
