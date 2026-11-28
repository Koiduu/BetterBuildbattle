package net.aspir3.betterbattle.fabric;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.fabric.compat.Compat;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.PackRepository;

/**
 * The "Better Battle look": a resource pack inside the mod jar (resourcepacks/betterbattle_ui, made by
 * tools/ui-pack/build.py). It shows up in the Resource Packs screen too, and the "Better Battle look" setting
 * and that screen are kept in step with each other.
 */
public final class ThemePack {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(BetterBattle.MOD_ID, "betterbattle_ui");

    private ThemePack() {}

    public static void register() {
        FabricLoader.getInstance().getModContainer(BetterBattle.MOD_ID).ifPresent(mod ->
            ResourceLoader.registerBuiltinPack(ID, mod, Component.literal("Better Battle UI"), PackActivationType.DEFAULT_ENABLED));
    }

    /** The pack's id in the repository; Fabric builds it from the Identifier, so match on the path. */
    private static String packId(PackRepository repo) {
        for (String id : repo.getAvailableIds()) {
            if (id.endsWith(ID.getPath())) return id;
        }
        return null;
    }

    /** Called when the setting is flipped in the Settings screen: turns the pack on or off and reloads. */
    public static void apply(Minecraft client, boolean on) {
        PackRepository repo = client.getResourcePackRepository();
        String id = packId(repo);
        if (id == null || repo.getSelectedIds().contains(id) == on) return;
        if (on) repo.addPack(id);
        else repo.removePack(id);
        client.options.updateResourcePacks(repo);   // saves options.txt and reloads resources
    }

    /** Every couple of seconds: if the player switched the pack in the Resource Packs screen, follow it. */
    public static void follow(Minecraft client) {
        if (Compat.screen(client) instanceof PackSelectionScreen) return;   // wait until they are done there
        BetterBattle core = BetterBattle.get();
        if (core == null) return;
        String id = packId(client.getResourcePackRepository());
        if (id == null) return;
        boolean selected = client.getResourcePackRepository().getSelectedIds().contains(id);
        if (selected != core.config().uiTheme) {
            core.config().uiTheme = selected;
            core.config().save();
        }
    }
}
