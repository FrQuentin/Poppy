package fr.quentin.poppy.commands;

import fr.quentin.poppy.listeners.SleepPercentageListener;
import fr.quentin.poppy.listeners.TabHealthListener;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Handles /poppy: with no argument, a small easter egg (places or drops a
 * poppy — see {@link #handleEasterEgg}). With {@code reload}, reloads
 * config.yml and messages.yml live and re-applies the two settings that
 * can't just be read live via {@link PoppyConfig} on their own — the
 * sleep gamerule and the tab-health refresh interval (see
 * {@link #handleReload}).
 */
public class PoppyCommand extends SafeCommand implements TabCompleter {

    private final PoppyConfig config;
    private final PoppyLogger logger;
    private final SleepPercentageListener sleepPercentageListener;
    private final TabHealthListener tabHealthListener;

    public PoppyCommand(JavaPlugin plugin, Messages messages, PoppyConfig config, PoppyLogger logger,
                        SleepPercentageListener sleepPercentageListener, TabHealthListener tabHealthListener) {
        super(plugin, messages);
        this.config = config;
        this.logger = logger;
        this.sleepPercentageListener = sleepPercentageListener;
        this.tabHealthListener = tabHealthListener;
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            return handleReload(sender);
        }

        return handleEasterEgg(sender);
    }

    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command, @NonNull String alias, String @NonNull [] args) {
        if (args.length != 1) {
            return List.of();
        }
        if (!sender.hasPermission("poppy.reload")) {
            return List.of();
        }
        if (!"reload".startsWith(args[0].toLowerCase())) {
            return List.of();
        }
        return List.of("reload");
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("poppy.reload")) {
            sender.sendMessage(messages.get("general.no-permission"));
            return true;
        }

        config.reload();
        messages.reload();
        sleepPercentageListener.reapply();
        tabHealthListener.reapply();

        String actorName = sender instanceof Player player ? player.getName() : "CONSOLE";
        logger.log(PoppyLogger.Category.ADMIN, actorName, "reloaded config.yml and messages.yml");

        sender.sendMessage(messages.get("poppy.reload-success"));
        return true;
    }

    private boolean handleEasterEgg(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        GameMode gameMode = player.getGameMode();
        if (gameMode != GameMode.SURVIVAL && gameMode != GameMode.CREATIVE) {
            player.sendMessage(messages.get("poppy.wrong-gamemode"));
            return true;
        }

        boolean creative = gameMode == GameMode.CREATIVE;
        ItemStack poppy = new ItemStack(Material.POPPY, 1);

        if (!creative && !player.getInventory().containsAtLeast(poppy, 1)) {
            player.sendMessage(messages.get("poppy.no-poppy"));
            return true;
        }

        if (!creative) {
            player.getInventory().removeItem(poppy);
        }

        Block feetBlock = player.getLocation().getBlock();
        BlockData poppyData = Material.POPPY.createBlockData();
        boolean placedAsBlock = feetBlock.canPlace(poppyData);

        if (placedAsBlock) {
            feetBlock.setBlockData(poppyData);
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_CROP_PLANT, 1.0f, 1.0f);
        } else {
            player.getWorld().dropItemNaturally(player.getLocation(), poppy);
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_GRASS_BREAK, 1.0f, 1.0f);
        }

        logger.log(PoppyLogger.Category.EASTER_EGG, player, "used /poppy (" + (placedAsBlock ? "placed as block" : "dropped as item") + ")");

        player.sendMessage(messages.get("poppy.success"));
        return true;
    }
}