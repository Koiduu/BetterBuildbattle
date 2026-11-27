package net.aspir3.betterbattle.forge.ui;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.heads.BannerLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.Items;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntityBanner;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Banner Database for 1.8.9: the same catalog as the modern builds, minus designs that need newer
 * patterns. Categories on the left, a search box and a grid of designs, and a small designer underneath.
 */
public final class BannersScreen extends GuiScreen {
    private static final int CELL = 20, SIDE = 92, TOP = 30, GRID = TOP + 34, DESIGNER = 64;
    private static final int MAX_SHOWN = 150, MAX_SHOWN_LOW_END = 40;
    private static final int BASE = 0, PATTERN = 1, COLOUR = 2, ADD = 3, REMOVE = 4, SAVE = 5, GET = 6, DONE = 7;
    private static final Map<BannerLibrary.Design, ItemStack> ITEMS = new IdentityHashMap<BannerLibrary.Design, ItemStack>();
    private static BannerLibrary library;

    private final GuiScreen parent;
    private final List<String> categoryIds = new ArrayList<String>();
    private String category = BannerLibrary.ALL;
    private GuiTextField search, name;
    private GuiButton base, pattern, colour, addLayer, removeLayer;
    private List<BannerLibrary.Design> shown = new ArrayList<BannerLibrary.Design>();
    private String lastQuery = "", lastCategory;
    private boolean dirty = true;
    private int scrollRow, categoryRow;
    private BannerLibrary.Design design = new BannerLibrary.Design("", "white");
    private String status = "";

    public BannersScreen(GuiScreen parent) {
        this.parent = parent;
    }

    /** One library for the session, made the first time the screen opens. */
    public static BannerLibrary library() {
        if (library == null) library = new BannerLibrary(BetterBattle.get().dir(), true);
        return library;
    }

    private String categoryLabel(String id) {
        if (BannerLibrary.ALL.equals(id)) return "All banners";
        if (BannerLibrary.SAVED.equals(id)) return "★ Saved";
        for (BannerLibrary.Category c : library().categories()) if (c.id.equals(id)) return c.label;
        return id;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        categoryIds.clear();
        categoryIds.add(BannerLibrary.ALL);
        categoryIds.add(BannerLibrary.SAVED);
        for (BannerLibrary.Category c : library().categories()) categoryIds.add(c.id);
        categoryRow = Math.max(9, Math.min(12, (height - DESIGNER - 12 - TOP) / categoryIds.size()));

        search = new GuiTextField(100, fontRendererObj, SIDE + 10, TOP, Math.max(100, width - SIDE - 20), 18);
        search.setMaxStringLength(48);
        search.setText(lastQuery);
        search.setFocused(true);

        buttonList.clear();
        int y1 = height - DESIGNER + 4, y2 = y1 + 22, x = 8;
        int total = Math.min(470, width - 16 - 16);
        int bw = total * 20 / 100, pw = total * 30 / 100, lw = total * 15 / 100;
        buttonList.add(base = new GuiButton(BASE, x, y1, bw, 20, ""));
        buttonList.add(pattern = new GuiButton(PATTERN, x + bw + 4, y1, pw, 20, ""));
        buttonList.add(colour = new GuiButton(COLOUR, x + bw + pw + 8, y1, bw, 20, ""));
        buttonList.add(addLayer = new GuiButton(ADD, x + bw * 2 + pw + 12, y1, lw, 20, "+ Layer"));
        buttonList.add(removeLayer = new GuiButton(REMOVE, x + bw * 2 + pw + lw + 16, y1, lw, 20, "- Layer"));
        name = new GuiTextField(101, fontRendererObj, x + 1, y2 + 1, 118, 18);
        name.setMaxStringLength(32);
        name.setText(design.name == null ? "" : design.name);
        buttonList.add(new GuiButton(SAVE, x + 124, y2, 44, 20, "Save"));
        buttonList.add(new GuiButton(GET, x + 172, y2, 44, 20, "Get it"));
        buttonList.add(new GuiButton(DONE, width - 68, y2, 60, 20, parent == null ? "Done" : "Back"));
        dirty = true;
        refreshDesigner();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    // ---- Designer

    private String[] last() { return design.layers.get(design.layers.size() - 1); }

    private static String next(String[] list, String current, boolean skipModern) {
        int start = 0;
        for (int i = 0; i < list.length; i++) if (list[i].equals(current)) start = i + 1;
        for (int k = 0; k < list.length; k++) {
            String s = list[(start + k) % list.length];
            if (!skipModern || !BannerLibrary.MODERN_ONLY.contains(s)) return s;
        }
        return list[0];
    }

    private static String label(String id) {
        String s = id.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void refreshDesigner() {
        boolean any = !design.layers.isEmpty();
        base.displayString = "Base: " + label(design.base);
        pattern.enabled = colour.enabled = removeLayer.enabled = any;
        pattern.displayString = any ? label(last()[0]) : "No layers";
        colour.displayString = any ? label(last()[1]) : "-";
        addLayer.enabled = design.layers.size() < BannerLibrary.MAX_LAYERS;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case BASE: design.base = next(BannerLibrary.COLORS, design.base, false); break;
            case PATTERN: last()[0] = next(BannerLibrary.PATTERNS, last()[0], true); break;
            case COLOUR: last()[1] = next(BannerLibrary.COLORS, last()[1], false); break;
            case ADD: design.layers.add(new String[]{"stripe_bottom", "black"}); break;
            case REMOVE: design.layers.remove(design.layers.size() - 1); break;
            case SAVE:
                design.name = name.getText();
                library().save(design);
                dirty = true;
                status = "Saved.";
                break;
            case GET: give(design); break;
            default: mc.displayGuiScreen(parent); return;
        }
        refreshDesigner();
    }

    // ---- Items

    private static int dye(String colour) {
        String n = "light_gray".equals(colour) ? "SILVER" : colour.toUpperCase(java.util.Locale.ROOT);
        try {
            return EnumDyeColor.valueOf(n).getDyeDamage();
        } catch (IllegalArgumentException e) {
            return EnumDyeColor.BLACK.getDyeDamage();
        }
    }

    private static String patternCode(String id) {
        for (TileEntityBanner.EnumBannerPattern p : TileEntityBanner.EnumBannerPattern.values()) {
            if (p.getPatternName().equals(id)) return p.getPatternID();
        }
        return null;
    }

    /** The banner as an item, with its patterns in the same NBT a crafted banner carries. */
    public static ItemStack bannerItem(BannerLibrary.Design d) {
        ItemStack stack = new ItemStack(Items.banner, 1, dye(d.base));
        NBTTagList patterns = new NBTTagList();
        for (String[] l : d.layers) {
            String code = patternCode(l[0]);
            if (code == null) continue;
            NBTTagCompound p = new NBTTagCompound();
            p.setString("Pattern", code);
            p.setInteger("Color", dye(l[1]));
            patterns.appendTag(p);
        }
        NBTTagCompound blockEntity = new NBTTagCompound();
        blockEntity.setInteger("Base", dye(d.base));
        blockEntity.setTag("Patterns", patterns);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("BlockEntityTag", blockEntity);
        stack.setTagCompound(tag);
        if (d.name != null && !d.name.isEmpty()) stack.setStackDisplayName("§r" + d.name);
        return stack;
    }

    private static ItemStack item(BannerLibrary.Design d) {
        ItemStack s = ITEMS.get(d);
        if (s == null) {
            if (ITEMS.size() > 800) ITEMS.clear();
            s = bannerItem(d);
            ITEMS.put(d, s);
        }
        return s;
    }

    /** Puts the banner in your hotbar the way creative mode does (Hypixel's build phase). */
    private void give(BannerLibrary.Design d) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.playerController == null || !mc.playerController.isInCreativeMode()) {
            status = "§cBanners can only be given in creative mode, e.g. while building.";
            return;
        }
        ItemStack stack = bannerItem(d);
        int slot = player.inventory.currentItem;
        if (player.inventory.mainInventory[slot] != null) {
            for (int i = 0; i < 9; i++) {
                if (player.inventory.mainInventory[i] == null) { slot = i; break; }
            }
        }
        player.inventory.mainInventory[slot] = stack.copy();
        mc.playerController.sendSlotPacket(stack.copy(), 36 + slot);
        player.inventory.currentItem = slot;
        status = "Put " + (d.name == null || d.name.isEmpty() ? "the banner" : d.name) + " in your hotbar.";
    }

    // ---- Grid

    private static int maxShown() {
        return BetterBattle.get().config().lowEndMode ? MAX_SHOWN_LOW_END : MAX_SHOWN;
    }

    private int columns() { return Math.max(1, Math.min((width - SIDE - 20) / CELL, maxShown())); }

    private int visibleRows() {
        int fit = Math.max(1, (height - GRID - 8 - DESIGNER) / CELL);
        return Math.max(1, Math.min(fit, maxShown() / columns()));
    }

    private int maxRow() { return Math.max(0, (shown.size() + columns() - 1) / columns() - visibleRows()); }

    private BannerLibrary.Design designAt(int mx, int my) {
        int x0 = SIDE + 10;
        if (mx < x0 || my < GRID) return null;
        int col = (mx - x0) / CELL, row = (my - GRID) / CELL;
        if (col >= columns() || row >= visibleRows()) return null;
        int i = (scrollRow + row) * columns() + col;
        return i >= 0 && i < shown.size() ? shown.get(i) : null;
    }

    private String categoryAt(int mx, int my) {
        if (mx < 6 || mx >= SIDE || my < TOP) return null;
        int i = (my - TOP) / categoryRow;
        return i < categoryIds.size() ? categoryIds.get(i) : null;
    }

    @Override
    public void updateScreen() {
        search.updateCursorCounter();
        name.updateCursorCounter();
        String q = search.getText();
        if (dirty || !q.equals(lastQuery) || !category.equals(lastCategory)) {
            shown = library().search(category, q);
            lastQuery = q;
            lastCategory = category;
            dirty = false;
            scrollRow = Math.min(scrollRow, maxRow());
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) scrollRow = Math.max(0, Math.min(maxRow(), scrollRow - Integer.signum(wheel) * 2));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        super.mouseClicked(mouseX, mouseY, button);
        search.mouseClicked(mouseX, mouseY, button);
        name.mouseClicked(mouseX, mouseY, button);
        String cat = categoryAt(mouseX, mouseY);
        if (cat != null && button == 0) {
            category = cat;
            scrollRow = 0;
            return;
        }
        BannerLibrary.Design d = designAt(mouseX, mouseY);
        if (d == null) return;
        boolean mine = library().saved().contains(d);
        if (button == 0) {
            give(d);
        } else if (button == 1) {
            if (mine && isShiftKeyDown()) {
                library().remove(d);
                dirty = true;
                status = "Deleted " + d.name + ".";
            } else {
                design = d.copy();
                name.setText(design.name == null ? "" : design.name);
                status = "Editing " + d.name + ".";
                refreshDesigner();
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (name.isFocused()) {
            name.textboxKeyTyped(typedChar, keyCode);
            design.name = name.getText();
        } else if (search.textboxKeyTyped(typedChar, keyCode)) {
            scrollRow = 0;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "Banner Database", width / 2, 10, 0xFFFFFF);

        String hoverCat = categoryAt(mouseX, mouseY);
        for (int i = 0; i < categoryIds.size(); i++) {
            String id = categoryIds.get(i);
            int y = TOP + i * categoryRow;
            boolean on = id.equals(category);
            if (on) drawRect(6, y, SIDE, y + categoryRow - 1, 0x80000000);
            else if (id.equals(hoverCat)) drawRect(6, y, SIDE, y + categoryRow - 1, 0x40FFFFFF);
            fontRendererObj.drawString(categoryLabel(id), 10, y + (categoryRow - 8) / 2, on ? 0xFFFF55 : 0xDDDDDD);
        }

        search.drawTextBox();
        if (search.getText().isEmpty() && !search.isFocused()) fontRendererObj.drawString("Search: flag, letter a, heart...", SIDE + 14, TOP + 5, 0x555555);

        int x0 = SIDE + 10, cols = columns(), rows = visibleRows();
        String info;
        if (!shown.isEmpty()) info = shown.size() + (shown.size() == 1 ? " banner" : " banners") + " · click: put in hotbar · right-click: edit";
        else if (BannerLibrary.SAVED.equals(category) && search.getText().trim().isEmpty()) info = "Nothing saved yet: design one below and press Save.";
        else info = "No banners match.";
        fontRendererObj.drawString(info, x0, GRID - 12, 0xAAAAAA);
        drawRect(x0 - 2, GRID - 2, x0 + cols * CELL + 2, GRID + rows * CELL + 2, 0x66000000);
        BannerLibrary.Design hover = designAt(mouseX, mouseY);
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableRescaleNormal();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = (scrollRow + r) * cols + c;
                if (i >= shown.size()) break;
                BannerLibrary.Design d = shown.get(i);
                int x = x0 + c * CELL, y = GRID + r * CELL;
                if (d == hover) {
                    RenderHelper.disableStandardItemLighting();
                    drawRect(x, y, x + CELL, y + CELL, 0x40FFFFFF);
                    RenderHelper.enableGUIStandardItemLighting();
                }
                mc.getRenderItem().renderItemAndEffectIntoGUI(item(d), x + 2, y + 2);
            }
        }
        mc.getRenderItem().renderItemAndEffectIntoGUI(bannerItem(design), 230, height - DESIGNER + 28);
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.disableRescaleNormal();

        fontRendererObj.drawString("Designer (layer " + design.layers.size() + "/" + BannerLibrary.MAX_LAYERS + "):", 8, height - DESIGNER - 8, 0xAAAAAA);
        name.drawTextBox();
        if (name.getText().isEmpty() && !name.isFocused()) fontRendererObj.drawString("Name", 13, height - DESIGNER + 32, 0x555555);
        int statusX = 252, statusW = width - 76 - statusX;
        if (statusW > 20) fontRendererObj.drawString(fontRendererObj.trimStringToWidth(status, statusW), statusX, height - DESIGNER + 32, 0xFFFF55);

        super.drawScreen(mouseX, mouseY, partialTicks);
        if (hover != null) {
            boolean mine = library().saved().contains(hover);
            List<String> lines = new ArrayList<String>();
            lines.add(hover.name);
            lines.add("§8" + (mine ? "Saved by you" : categoryLabel(hover.category)));
            lines.add("§7" + (mine ? "Click: hotbar · Right-click: edit · Shift+right-click: delete" : "Click: hotbar · Right-click: edit a copy"));
            drawHoveringText(lines, mouseX, mouseY);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
