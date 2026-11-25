package net.aspir3.betterbattle.fabric.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** The calls that differ between Minecraft versions. The 26.1 build uses these older names; 26.2 and 26.3 share another copy. */
public final class Compat {
    private Compat() {}

    public static void setScreen(Minecraft client, Screen screen) { client.setScreen(screen); }

    public static Screen screen(Minecraft client) { return client.screen; }

    public static boolean hudHidden(Minecraft client) { return client.options.hideGui; }

    public static ToastManager toasts(Minecraft client) { return client.getToastManager(); }

    public static net.minecraft.client.multiplayer.chat.ChatListener chatListener(Minecraft client) { return client.getChatListener(); }

    public static com.mojang.blaze3d.pipeline.RenderTarget mainTarget(Minecraft client) { return client.getMainRenderTarget(); }

    public static Item wool(DyeColor c) { return switch (c) {
        case WHITE -> Items.WHITE_WOOL; case RED -> Items.RED_WOOL; case CYAN -> Items.CYAN_WOOL;
        default -> throw new IllegalArgumentException(c.toString()); }; }

    public static Item terracottaItem(DyeColor c) { return Items.WHITE_TERRACOTTA; }

    public static Item bed(DyeColor c) { return Items.BLACK_BED; }

    public static Block terracotta(DyeColor c) { return Blocks.WHITE_TERRACOTTA; }
}
