package net.aspir3.betterbattle.forge;

import com.mojang.authlib.GameProfile;
import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.Platform;
import net.aspir3.betterbattle.achieve.Catalog;
import net.aspir3.betterbattle.forge.ui.AchievementToasts;
import net.aspir3.betterbattle.forge.ui.MenuScreen;
import net.aspir3.betterbattle.game.GameStateMachine;
import net.aspir3.betterbattle.legacy.LegacyIds;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mod(modid = BetterBattle.MOD_ID, name = "Better Battle", version = BetterBattleForge.VERSION,
    clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class BetterBattleForge implements Platform {
    public static final String VERSION = "0.1.0";
    public static final String MC_VERSION = "1.8.9";
    static final String PREFIX = "§d§lBetter Battle §8» §7";

    private static BetterBattleForge instance;

    private File configDir;
    private BetterBattle core;
    private KeyBinding menuKey;
    private final ConcurrentLinkedQueue<PacketWatcher.BlockChange> blockChanges = new ConcurrentLinkedQueue<PacketWatcher.BlockChange>();
    private GuiScreen pendingScreen;
    private boolean connected, joined, plotFound;
    private int plotTries, ticks;

    public static BetterBattleForge get() {
        return instance;
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        instance = this;
        configDir = event.getModConfigurationDirectory();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        core = BetterBattle.init(this, configDir, VERSION, MC_VERSION);
        menuKey = new KeyBinding("key.betterbattle.menu", Keyboard.KEY_B, "key.category.betterbattle");
        ClientRegistry.registerKeyBinding(menuKey);
        ClientCommandHandler.instance.registerCommand(new BbCommand(this));
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new AchievementToasts());
    }

    BetterBattle core() {
        return core;
    }

    /** Opens on the next tick, after the chat box has closed. */
    public void openLater(GuiScreen screen) {
        pendingScreen = screen;
    }

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        // Network thread: just hook the pipeline. Joining happens on the first tick with a player.
        PacketWatcher.install(event.manager, blockChanges);
        connected = true;
        joined = false;
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            public void run() {
                connected = false;
                joined = false;
                core.onLeave();
            }
        });
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event.type == 2) return;   // action bar
        core.onChat(strip(event.message.getUnformattedText()));
    }

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent event) {
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK || !event.world.isRemote || !core.active()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (event.entityPlayer != mc.thePlayer) return;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null) return;

        String id = null;
        String tab = null;
        if (held.getItem() == Items.water_bucket) id = "minecraft:water";
        else if (held.getItem() == Items.lava_bucket) id = "minecraft:lava";
        else if (held.getItem() instanceof ItemBlock) {
            Block block = ((ItemBlock) held.getItem()).getBlock();
            id = LegacyIds.modern(Block.blockRegistry.getNameForObject(block).toString(), held.getItem().getMetadata(held.getMetadata()));
            CreativeTabs creative = block.getCreativeTabToDisplayOn();
            if (creative == CreativeTabs.tabBlock) tab = "building";
            else if (creative == CreativeTabs.tabRedstone) tab = "redstone";
        }
        if (id == null) return;
        BlockPos target = event.pos;
        if (!event.world.getBlockState(target).getBlock().isReplaceable(event.world, target)) target = target.offset(event.face);
        core.game().onOwnBlockPlaced(id, tab, target.getY());
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();

        while (menuKey.isPressed()) pendingScreen = new MenuScreen(null);
        if (pendingScreen != null) {
            mc.displayGuiScreen(pendingScreen);
            pendingScreen = null;
        }

        if (connected && !joined && mc.thePlayer != null) {
            joined = true;
            GameProfile profile = mc.getSession().getProfile();
            String address = mc.getCurrentServerData() == null ? "singleplayer" : mc.getCurrentServerData().serverIP;
            core.onJoin(address, profile.getId(), profile.getName());
            plotFound = false;
        }

        if (!core.active() || mc.thePlayer == null || mc.theWorld == null) {
            blockChanges.clear();
            return;
        }
        GameStateMachine game = core.game();
        ticks++;

        PacketWatcher.BlockChange change;
        while ((change = blockChanges.poll()) != null) game.onBlockUpdate(change.x, change.y, change.z, change.block);

        if (ticks % 10 == 0) readSidebar(mc);
        if (ticks % 100 == 0) readTabList(mc);

        if (game.state() == GameStateMachine.State.BUILDING) {
            if (mc.thePlayer.capabilities.isFlying) game.onFlying();
            if (!plotFound && plotTries++ < 200 && ticks % 5 == 0) findPlot(mc);
        } else {
            plotFound = false;
            plotTries = 0;
        }
    }

    private void readSidebar(Minecraft mc) {
        Scoreboard board = mc.theWorld.getScoreboard();
        ScoreObjective objective = board.getObjectiveInDisplaySlot(1);
        if (objective == null) {
            core.onSidebar("", new ArrayList<String>());
            return;
        }
        List<Score> scores = new ArrayList<Score>(board.getSortedScores(objective));
        List<String> lines = new ArrayList<String>();
        for (int i = scores.size() - 1; i >= 0; i--) {
            String owner = scores.get(i).getPlayerName();
            if (owner == null || owner.startsWith("#")) continue;
            ScorePlayerTeam team = board.getPlayersTeam(owner);
            lines.add(strip(ScorePlayerTeam.formatPlayerName(team, owner)));
        }
        core.onSidebar(strip(objective.getDisplayName()), lines);
    }

    private void readTabList(Minecraft mc) {
        if (mc.getNetHandler() == null) return;
        Collection<NetworkPlayerInfo> infos = mc.getNetHandler().getPlayerInfoMap();
        List<UUID> players = new ArrayList<UUID>();
        for (NetworkPlayerInfo info : infos) {
            UUID id = info.getGameProfile().getId();
            if (id != null && id.version() == 4) players.add(id);   // NPCs use other UUID versions
        }
        core.onTabList(players);
    }

    private void findPlot(Minecraft mc) {
        BlockPos feet = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        int floorY = Integer.MIN_VALUE;
        for (int y = feet.getY() - 1; y > feet.getY() - 40; y--) {
            if (isFloor(mc, feet.getX(), y, feet.getZ())) {
                floorY = y;
                break;
            }
        }
        if (floorY == Integer.MIN_VALUE) return;
        int x = feet.getX(), z = feet.getZ();
        int minX = x, maxX = x, minZ = z, maxZ = z;
        while (minX > x - 45 && isFloor(mc, minX - 1, floorY, z)) minX--;
        while (maxX < x + 45 && isFloor(mc, maxX + 1, floorY, z)) maxX++;
        while (minZ > z - 45 && isFloor(mc, x, floorY, minZ - 1)) minZ--;
        while (maxZ < z + 45 && isFloor(mc, x, floorY, maxZ + 1)) maxZ++;
        if (maxX - minX < 10 || maxZ - minZ < 10) return;
        core.game().onPlotFound(minX, minZ, maxX, maxZ, floorY);
        core.debug("plot: x " + minX + ".." + maxX + ", z " + minZ + ".." + maxZ + ", floor y=" + floorY);
        plotFound = true;
    }

    /** White terracotta: Hypixel's default floor. */
    private static boolean isFloor(Minecraft mc, int x, int y, int z) {
        IBlockState state = mc.theWorld.getBlockState(new BlockPos(x, y, z));
        return state.getBlock() == Blocks.stained_hardened_clay && state.getBlock().getMetaFromState(state) == 0;
    }

    static String strip(String s) {
        String out = EnumChatFormatting.getTextWithoutFormattingCodes(s);
        return out == null ? "" : out.trim();
    }

    // ---- Platform

    @Override
    public void joinServer(String serverId) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        mc.getSessionService().joinServer(mc.getSession().getProfile(), mc.getSession().getToken(), serverId);
    }

    @Override
    public void runOnMainThread(Runnable task) {
        Minecraft.getMinecraft().addScheduledTask(task);
    }

    @Override
    public void showAchievement(Catalog.Entry achievement) {
        AchievementToasts.add(achievement);
    }

    @Override
    public void chat(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI != null) mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(message));
    }
}
