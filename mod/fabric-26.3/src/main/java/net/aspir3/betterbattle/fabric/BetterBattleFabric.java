package net.aspir3.betterbattle.fabric;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.Platform;
import net.aspir3.betterbattle.achieve.Catalog;
import net.aspir3.betterbattle.fabric.ui.AchievementToast;
import net.aspir3.betterbattle.fabric.ui.AchievementsScreen;
import net.aspir3.betterbattle.fabric.ui.CreditsScreen;
import net.aspir3.betterbattle.fabric.ui.MenuScreen;
import net.aspir3.betterbattle.fabric.ui.SettingsScreen;
import net.aspir3.betterbattle.fabric.ui.SpbTimerToast;
import net.aspir3.betterbattle.fabric.ui.TextPageScreen;
import net.aspir3.betterbattle.pages.Pages;
import net.aspir3.betterbattle.game.GameStateMachine;
import net.aspir3.betterbattle.palette.Palettes;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.lwjgl.sdl.SDLScancode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

public final class BetterBattleFabric implements ClientModInitializer, Platform {
    public static final String MC_VERSION = "26.3";
    private static final String PREFIX = "§d§lBetter Battle §8» §7";

    private BetterBattle core;
    private KeyMapping menuKey;
    private final ConcurrentLinkedQueue<PacketWatcher.BlockChange> blockChanges = new ConcurrentLinkedQueue<>();
    private Supplier<Screen> pendingScreen;
    private boolean plotFound;
    private int plotTries;
    private int ticks;

    @Override
    public void onInitializeClient() {
        String version = FabricLoader.getInstance().getModContainer(BetterBattle.MOD_ID)
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
            .orElse("dev");
        core = BetterBattle.init(this, FabricLoader.getInstance().getConfigDir().toFile(), version, MC_VERSION);

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(BetterBattle.MOD_ID, "main"));
        menuKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.betterbattle.menu", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_B, category));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerData server = client.getCurrentServer();
            GameProfile profile = client.getGameProfile();
            core.onJoin(server == null ? "singleplayer" : server.ip, profile.id(), profile.name());
            plotFound = false;
            if (core.active()) PacketWatcher.install(handler.getConnection(), blockChanges::add);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> core.onLeave());

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) core.onChat(message.getString());
        });

        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() && core.active()) onUseBlock(player.getItemInHand(hand), level, hit);
            return InteractionResult.PASS;
        });

        // Left-clicking a block breaks it. Measure Twice and Speed Builders precision count these.
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (level.isClientSide() && core.active() && !level.getBlockState(pos).isAir()) core.game().onOwnBlockBroken();
            return InteractionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        registerCommands();
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
            ClientCommands.literal("bb")
                .executes(c -> open(this::menu))
                .then(ClientCommands.literal("achievements").executes(c -> open(() -> new AchievementsScreen(null))))
                .then(ClientCommands.literal("recap").executes(c -> open(() -> new TextPageScreen(null, core.lastGamePage()))))
                .then(ClientCommands.literal("weekly").executes(c -> open(() -> new TextPageScreen(null, core.weeklyPage()))))
                .then(ClientCommands.literal("news").executes(c -> open(() -> new TextPageScreen(null, Pages.whatsNew()))))
                .then(ClientCommands.literal("settings").executes(c -> open(() -> new SettingsScreen(null))))
                .then(ClientCommands.literal("credits").executes(c -> open(() -> new CreditsScreen(null))))
                .then(ClientCommands.literal("sync").executes(c -> {
                    if (!core.active()) {
                        chat(PREFIX + "Better Battle only runs on Hypixel.");
                        return 0;
                    }
                    core.syncNow();
                    chat(PREFIX + "Syncing your games...");
                    return 1;
                }))
                .then(ClientCommands.literal("link")
                    .then(ClientCommands.argument("code", StringArgumentType.word()).executes(c -> {
                        if (!core.active()) {
                            chat(PREFIX + "Join Hypixel first, then run this again.");
                            return 0;
                        }
                        core.link(StringArgumentType.getString(c, "code"));
                        return 1;
                    })))
                .then(ClientCommands.literal("palette")
                    .executes(c -> palette(6))
                    .then(ClientCommands.argument("size", IntegerArgumentType.integer(3, 12))
                        .executes(c -> palette(IntegerArgumentType.getInteger(c, "size")))))
                .then(ClientCommands.literal("debug").executes(c -> {
                    boolean on = !core.config().debug;
                    core.setDebug(on);
                    chat(PREFIX + (on
                        ? "Debug log §aon§7. Chat and scoreboard lines go to §f" + core.debugFile().getName()
                        : "Debug log §coff§7."));
                    return 1;
                }))));
    }

    /** The B menu, or What's New on the first press after an update (Back goes to the menu). */
    private Screen menu() {
        MenuScreen menu = new MenuScreen(null);
        return core.takeWhatsNew() ? new TextPageScreen(menu, Pages.whatsNew()) : menu;
    }

    private int open(Supplier<Screen> screen) {
        // Opened on the next tick, after the chat box has closed.
        pendingScreen = screen;
        return 1;
    }

    private int palette(int size) {
        Palettes.Palette p = Palettes.roll(size, false, new Random());
        StringBuilder line = new StringBuilder(PREFIX + "§6" + p.name + " palette§7: ");
        for (int i = 0; i < p.blocks.size(); i++) {
            if (i > 0) line.append("§8, ");
            line.append("§f").append(pretty(p.blocks.get(i)));
        }
        chat(line.toString());
        return 1;
    }

    private static String pretty(String id) {
        String[] words = id.replace('_', ' ').split(" ");
        StringBuilder out = new StringBuilder();
        for (String w : words) {
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    private void tick(Minecraft client) {
        while (menuKey.consumeClick()) pendingScreen = this::menu;
        if (pendingScreen != null) {
            client.gui.setScreen(pendingScreen.get());
            pendingScreen = null;
        }
        if (!core.active() || client.player == null || client.level == null) {
            blockChanges.clear();
            return;
        }
        GameStateMachine game = core.game();
        ticks++;

        PacketWatcher.BlockChange change;
        while ((change = blockChanges.poll()) != null) game.onBlockUpdate(change.x(), change.y(), change.z(), change.block());

        if (ticks % 10 == 0) readSidebar(client);
        if (!SpbTimerToast.showing() && !core.hudLines().isEmpty()) client.gui.toastManager().addToast(new SpbTimerToast());
        if (ticks % 100 == 0) readTabList(client);

        if (game.state() == GameStateMachine.State.BUILDING) {
            if (client.player.getAbilities().mayfly) game.onCanFly();
            if (client.player.getAbilities().flying) game.onFlying();
            if (!plotFound && plotTries++ < 200 && ticks % 5 == 0) findPlot(client);
        } else {
            plotFound = false;
            plotTries = 0;
        }
    }

    private void readSidebar(Minecraft client) {
        Scoreboard board = client.level.getScoreboard();
        Objective objective = board.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) {
            core.onSidebar("", List.of());
            return;
        }
        List<PlayerScoreEntry> entries = new ArrayList<>(board.listPlayerScores(objective));
        entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());
        List<String> lines = new ArrayList<>();
        for (PlayerScoreEntry entry : entries) {
            if (entry.isHidden()) continue;
            PlayerTeam team = board.getPlayersTeam(entry.owner());
            lines.add(strip(PlayerTeam.formatNameForTeam(team, Component.literal(entry.owner())).getString()));
        }
        core.onSidebar(strip(objective.getDisplayName().getString()), lines);
    }

    private void readTabList(Minecraft client) {
        ClientPacketListener connection = client.getConnection();
        if (connection == null) return;
        List<UUID> players = new ArrayList<>();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            UUID id = info.getProfile().id();
            if (id.version() == 4) players.add(id);   // NPCs in the tab list use other UUID versions
        }
        core.onTabList(players);
    }

    /** The block under the player is the floor; the white terracotta around it marks out the plot. */
    private void findPlot(Minecraft client) {
        BlockPos feet = client.player.blockPosition();
        int floorY = Integer.MIN_VALUE;
        for (int y = feet.getY() - 1; y > feet.getY() - 40; y--) {
            if (isFloor(client, feet.getX(), y, feet.getZ())) {
                floorY = y;
                break;
            }
        }
        if (floorY == Integer.MIN_VALUE) return;
        int x = feet.getX(), z = feet.getZ();
        int minX = x, maxX = x, minZ = z, maxZ = z;
        while (minX > x - 45 && isFloor(client, minX - 1, floorY, z)) minX--;
        while (maxX < x + 45 && isFloor(client, maxX + 1, floorY, z)) maxX++;
        while (minZ > z - 45 && isFloor(client, x, floorY, minZ - 1)) minZ--;
        while (maxZ < z + 45 && isFloor(client, x, floorY, maxZ + 1)) maxZ++;
        if (maxX - minX < 10 || maxZ - minZ < 10) return;   // not a plot yet
        core.game().onPlotFound(minX, minZ, maxX, maxZ, floorY);
        core.debug("plot: x " + minX + ".." + maxX + ", z " + minZ + ".." + maxZ + ", floor y=" + floorY);
        plotFound = true;
    }

    private static boolean isFloor(Minecraft client, int x, int y, int z) {
        return client.level.getBlockState(new BlockPos(x, y, z)).is(Blocks.DYED_TERRACOTTA.pick(DyeColor.WHITE));
    }

    private void onUseBlock(ItemStack stack, Level level, BlockHitResult hit) {
        String id = null;
        if (stack.is(Items.WATER_BUCKET)) id = "minecraft:water";
        else if (stack.is(Items.LAVA_BUCKET)) id = "minecraft:lava";
        else if (stack.getItem() instanceof BlockItem blockItem) id = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
        if (id == null) return;
        BlockPos target = hit.getBlockPos();
        if (!level.getBlockState(target).canBeReplaced()) target = target.relative(hit.getDirection());
        core.game().onOwnBlockPlaced(id, creativeTab(stack), target.getY());
    }

    private static String creativeTab(ItemStack stack) {
        for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            Identifier key = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            if (key == null) continue;
            String name = switch (key.getPath()) {
                case "building_blocks" -> "building";
                case "colored_blocks" -> "colored";
                case "natural_blocks" -> "natural";
                case "functional_blocks" -> "functional";
                case "redstone_blocks" -> "redstone";
                default -> null;
            };
            if (name != null && tab.contains(stack)) return name;
        }
        return null;
    }

    private static String strip(String s) {
        String out = ChatFormatting.stripFormatting(s);
        return out == null ? "" : out.trim();
    }

    // ---- Platform

    @Override
    public void joinServer(String serverId) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        mc.services().sessionService().joinServer(mc.getUser().getProfileId(), mc.getUser().getAccessToken(), serverId);
    }

    @Override
    public void runOnMainThread(Runnable task) {
        Minecraft.getInstance().execute(task);
    }

    @Override
    public void showAchievement(Catalog.Entry achievement) {
        Minecraft.getInstance().gui.toastManager().addToast(new AchievementToast(achievement));
    }

    @Override
    public void openPage(Pages.Page page) {
        pendingScreen = () -> new TextPageScreen(null, page);
    }

    @Override
    public void chat(String message) {
        Minecraft.getInstance().gui.chatListener().handleSystemMessage(Component.literal(message), false);
    }
}
