package net.aspir3.betterbattle.forge;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.forge.ui.AchievementsScreen;
import net.aspir3.betterbattle.forge.ui.CreditsScreen;
import net.aspir3.betterbattle.forge.ui.MenuScreen;
import net.aspir3.betterbattle.palette.Palettes;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** /bb, /bb achievements, /bb sync, /bb palette [size], /bb debug, /bb credits */
final class BbCommand extends CommandBase {
    private static final String P = BetterBattleForge.PREFIX;
    private final BetterBattleForge mod;

    BbCommand(BetterBattleForge mod) {
        this.mod = mod;
    }

    @Override
    public String getCommandName() {
        return "bb";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/bb [achievements|sync|palette|debug|credits]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "achievements", "sync", "palette", "debug", "credits");
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        BetterBattle core = mod.core();
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        if (sub.isEmpty()) {
            mod.openLater(new MenuScreen(null));
        } else if (sub.equals("achievements")) {
            mod.openLater(new AchievementsScreen(null));
        } else if (sub.equals("credits")) {
            mod.openLater(new CreditsScreen(null));
        } else if (sub.equals("sync")) {
            if (!core.active()) {
                mod.chat(P + "Better Battle only runs on Hypixel.");
                return;
            }
            core.syncNow();
            mod.chat(P + "Syncing your games...");
        } else if (sub.equals("palette")) {
            int size = 6;
            if (args.length > 1) {
                try {
                    size = Math.max(3, Math.min(12, Integer.parseInt(args[1])));
                } catch (NumberFormatException ignored) {
                }
            }
            Palettes.Palette p = Palettes.roll(size, true, new Random());
            StringBuilder line = new StringBuilder(P + "§6" + p.name + " palette§7: ");
            for (int i = 0; i < p.blocks.size(); i++) {
                if (i > 0) line.append("§8, ");
                line.append("§f").append(pretty(p.blocks.get(i)));
            }
            mod.chat(line.toString());
        } else if (sub.equals("debug")) {
            boolean on = !core.config().debug;
            core.setDebug(on);
            mod.chat(P + (on ? "Debug log §aon§7. Chat and scoreboard lines go to §f" + core.debugFile().getName()
                : "Debug log §coff§7."));
        } else {
            mod.chat(P + "Usage: " + getCommandUsage(sender));
        }
    }

    private static String pretty(String id) {
        StringBuilder out = new StringBuilder();
        for (String w : Arrays.asList(id.split("_"))) {
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }
}
