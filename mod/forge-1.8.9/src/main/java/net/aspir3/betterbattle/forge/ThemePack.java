package net.aspir3.betterbattle.forge;

import net.aspir3.betterbattle.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.client.resources.data.IMetadataSerializer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The "Better Battle look" for 1.8.9: the textures in betterbattle_ui/ inside the mod jar (made by
 * tools/ui-pack/build.py), served as a pack that sits above vanilla and below the player's own packs.
 * 1.8.9 has no built-in pack screen, so the Settings screen switches it; while it is off the pack serves nothing.
 */
public final class ThemePack implements IResourcePack {
    private static final String ROOT = "/betterbattle_ui/";

    private ThemePack() {}

    /** Adds the pack to Minecraft's default packs. Called from init, before the game's final resource load. */
    @SuppressWarnings("unchecked")
    public static void install() {
        try {
            List<IResourcePack> packs = (List<IResourcePack>) ReflectionHelper.getPrivateValue(
                Minecraft.class, Minecraft.getMinecraft(), "defaultResourcePacks", "field_110449_ao");
            packs.add(new ThemePack());
        } catch (Exception e) {
            System.err.println("[Better Battle] Couldn't add the UI pack: " + e);
        }
    }

    /** Called when the setting is flipped: reload so the textures switch now. */
    public static void apply() {
        Minecraft.getMinecraft().refreshResources();
    }

    private static String path(ResourceLocation location) {
        return ROOT + "assets/" + location.getResourceDomain() + "/" + location.getResourcePath();
    }

    @Override
    public InputStream getInputStream(ResourceLocation location) throws IOException {
        InputStream in = Theme.on() ? ThemePack.class.getResourceAsStream(path(location)) : null;
        if (in == null) throw new FileNotFoundException(location.toString());
        return in;
    }

    @Override
    public boolean resourceExists(ResourceLocation location) {
        return Theme.on() && ThemePack.class.getResource(path(location)) != null;
    }

    @Override
    public Set<String> getResourceDomains() {
        return Collections.singleton("minecraft");
    }

    @Override
    public <T extends IMetadataSection> T getPackMetadata(IMetadataSerializer serializer, String section) throws IOException {
        return null;
    }

    @Override
    public BufferedImage getPackImage() throws IOException {
        InputStream in = ThemePack.class.getResourceAsStream(ROOT + "pack.png");
        if (in == null) throw new FileNotFoundException("pack.png");
        try {
            return ImageIO.read(in);
        } finally {
            in.close();
        }
    }

    @Override
    public String getPackName() {
        return "Better Battle UI";
    }
}
