package net.aspir3.betterbattle.forge.ui;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.heads.HeadLibrary;
import net.aspir3.betterbattle.heads.MyHeads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTUtil;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The player's custom heads, painted at battle.aspir3.net/heads. Forge has no full Head Database, so this
 * shows only "My heads". Click one to put it in your hotbar during the build phase (creative).
 */
public final class MyHeadsScreen extends GuiScreen {
    private static final int CELL = 22, TOP = 44;
    private static final String BUILD_PHASE_ONLY = "§cHeads only work during the build phase (creative mode).";
    private static final Map<String, ItemStack> ITEMS = new HashMap<String, ItemStack>();

    private final GuiScreen parent;
    private List<HeadLibrary.Head> shown = new ArrayList<HeadLibrary.Head>();
    private int lastVersion = -1, scrollRow;
    private String status = "";

    public MyHeadsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        MyHeads.init(BetterBattle.get().dir());
        BetterBattle.get().refreshMyHeads();
        buttonList.clear();
        buttonList.add(new GuiButton(0, width - 108, height - 26, 100, 20, parent == null ? "Done" : "Back"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) mc.displayGuiScreen(parent);
    }

    @Override
    public void updateScreen() {
        if (MyHeads.version() != lastVersion) {
            lastVersion = MyHeads.version();
            shown = MyHeads.list();
            scrollRow = Math.min(scrollRow, maxRow());
        }
    }

    private int columns() { return Math.max(1, (width - 20) / CELL); }
    private int visibleRows() { return Math.max(1, (height - TOP - 50) / CELL); }
    private int maxRow() { return Math.max(0, (shown.size() + columns() - 1) / columns() - visibleRows()); }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) scrollRow = Math.max(0, Math.min(maxRow(), scrollRow - Integer.signum(wheel)));
    }

    private int x0() { return (width - columns() * CELL) / 2; }

    private HeadLibrary.Head headAt(int mx, int my) {
        if (mx < x0() || my < TOP) return null;
        int col = (mx - x0()) / CELL, row = (my - TOP) / CELL;
        if (col >= columns() || row >= visibleRows()) return null;
        int i = (scrollRow + row) * columns() + col;
        return i < shown.size() ? shown.get(i) : null;
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        super.mouseClicked(mx, my, button);
        HeadLibrary.Head h = headAt(mx, my);
        if (h == null || button != 0) return;
        status = giveCreative(item(h)) ? "Put " + h.name + " in your hotbar." : BUILD_PHASE_ONLY;
    }

    /** A player head showing this texture. Unsigned is enough for a head item. */
    static ItemStack headItem(HeadLibrary.Head h) {
        ItemStack stack = new ItemStack(Items.skull, 1, 3);
        UUID id = UUID.nameUUIDFromBytes(h.hash.getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(id, "BBHead");
        profile.getProperties().put("textures", new Property("textures", h.textureProperty()));
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagCompound owner = new NBTTagCompound();
        NBTUtil.writeGameProfile(owner, profile);
        tag.setTag("SkullOwner", owner);
        NBTTagCompound display = new NBTTagCompound();
        display.setString("Name", "§r" + h.name);
        tag.setTag("display", display);
        stack.setTagCompound(tag);
        return stack;
    }

    private static ItemStack item(HeadLibrary.Head h) {
        ItemStack s = ITEMS.get(h.hash);
        if (s == null) {
            if (ITEMS.size() > 200) ITEMS.clear();
            s = headItem(h);
            ITEMS.put(h.hash, s);
        }
        return s;
    }

    /** Puts the item in the hotbar (the held slot if empty, else the first empty one). Creative only. */
    static boolean giveCreative(ItemStack stack) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.playerController == null || !mc.playerController.isInCreativeMode()) return false;
        int slot = mc.thePlayer.inventory.currentItem;
        if (mc.thePlayer.inventory.getStackInSlot(slot) != null) {
            for (int i = 0; i < 9; i++) {
                if (mc.thePlayer.inventory.getStackInSlot(i) == null) { slot = i; break; }
            }
        }
        ItemStack copy = stack.copy();
        mc.thePlayer.inventory.setInventorySlotContents(slot, copy);
        mc.playerController.sendSlotPacket(copy, 36 + slot);
        mc.thePlayer.inventory.currentItem = slot;
        return true;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "My heads", width / 2, 10, 0xFFFFFF);
        String info = shown.isEmpty()
            ? (MyHeads.synced() ? "No custom heads yet. Paint your own at " + MyHeads.PAINT_URL + "." : "Looking for your heads...")
            : shown.size() + (shown.size() == 1 ? " head" : " heads") + " you painted · click to put one in your hotbar";
        drawCenteredString(fontRendererObj, "§7" + info, width / 2, 26, 0xFFFFFF);

        int x0 = x0(), cols = columns(), rows = visibleRows();
        if (!shown.isEmpty()) drawRect(x0 - 2, TOP - 2, x0 + cols * CELL + 2, TOP + Math.min(rows, (shown.size() + cols - 1) / cols) * CELL + 2, 0x66000000);
        HeadLibrary.Head hover = headAt(mouseX, mouseY);
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableDepth();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = (scrollRow + r) * cols + c;
                if (i >= shown.size()) break;
                HeadLibrary.Head h = shown.get(i);
                int x = x0 + c * CELL, y = TOP + r * CELL;
                if (h == hover) drawRect(x, y, x + CELL, y + CELL, 0x40FFFFFF);
                itemRender.renderItemAndEffectIntoGUI(item(h), x + 3, y + 3);
            }
        }
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableDepth();

        drawString(fontRendererObj, status, 8, height - 20, 0xFFFFFF);
        drawString(fontRendererObj, "§8" + MyHeads.NOTICE, 8, height - 36, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (hover != null) drawHoveringText(java.util.Arrays.asList(hover.name, "§7Click: put in hotbar"), mouseX, mouseY);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
