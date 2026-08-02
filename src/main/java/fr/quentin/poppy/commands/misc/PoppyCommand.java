package fr.quentin.poppy.commands.misc;

import fr.quentin.poppy.manager.sleep.SleepPercentageListener;
import fr.quentin.poppy.listeners.tab.TabHealthListener;
import fr.quentin.poppy.util.cooldown.CooldownStore;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Handles /poppy: with no argument, a small easter egg (places or drops a
 * poppy). With {@code reload}, reloads config.yml and messages.yml live,
 * and re-applies every setting that can't be picked up automatically by
 * {@link PoppyConfig}'s own live reads alone — the sleep gamerule
 * ({@link SleepPercentageListener#reapply}), the tab-health refresh
 * interval ({@link TabHealthListener#reapply}), and the Poppy log file's
 * flush interval ({@link PoppyLogger#reapply}).
 *
 * <p>The easter egg is rate-limited via
 * {@code poppy-easteregg-cooldown-seconds} in config.yml, tracked in a
 * shared {@link CooldownStore}.
 */
public class PoppyCommand extends SafeCommand implements TabCompleter {

    private final PoppyConfig config;
    private final PoppyLogger logger;
    private final SleepPercentageListener sleepPercentageListener;
    private final TabHealthListener tabHealthListener;
    private final CooldownStore easterEggCooldown;

    public PoppyCommand(JavaPlugin plugin, Messages messages, PoppyConfig config, PoppyLogger logger,
                        SleepPercentageListener sleepPercentageListener, TabHealthListener tabHealthListener) {
        super(plugin, messages);
        this.config = config;
        this.logger = logger;
        this.sleepPercentageListener = sleepPercentageListener;
        this.tabHealthListener = tabHealthListener;
        this.easterEggCooldown = new CooldownStore(plugin);
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            handleReload(sender);
            return true;
        }

        handleEasterEgg(sender);
        return true;
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

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("poppy.reload")) {
            sender.sendMessage(messages.get("general.no-permission"));
            return;
        }

        config.reload();
        messages.reload();
        sleepPercentageListener.reapply();
        tabHealthListener.reapply();
        logger.reapply();

        String actorName = sender instanceof Player player ? player.getName() : "CONSOLE";
        logger.log(PoppyLogger.Category.ADMIN, actorName, "reloaded config.yml and messages.yml");

        sender.sendMessage(messages.get("poppy.reload-success"));
    }

    private void handleEasterEgg(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }

        long remaining = easterEggCooldown.remainingSeconds(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(messages.get("poppy.cooldown"));
            return;
        }

        GameMode gameMode = player.getGameMode();
        if (gameMode != GameMode.SURVIVAL && gameMode != GameMode.CREATIVE) {
            player.sendMessage(messages.get("poppy.wrong-gamemode"));
            return;
        }

        boolean creative = gameMode == GameMode.CREATIVE;
        ItemStack poppy = new ItemStack(Material.POPPY, 1);

        if (!creative && !player.getInventory().containsAtLeast(poppy, 1)) {
            player.sendMessage(messages.get("poppy.no-poppy"));
            return;
        }

        easterEggCooldown.start(player.getUniqueId(), config.poppyEasterEggCooldownMillis());

        Block feetBlock = player.getLocation().getBlock();
        BlockData poppyData = Material.POPPY.createBlockData();
        boolean canPlaceHere = feetBlock.canPlace(poppyData) && !isProtected(feetBlock, player);

        if (!creative) {
            player.getInventory().removeItem(poppy);
        }

        if (canPlaceHere) {
            feetBlock.setBlockData(poppyData);
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_CROP_PLANT, 1.0f, 1.0f);
        } else {
            player.getWorld().dropItemNaturally(player.getLocation(), poppy);
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_GRASS_BREAK, 1.0f, 1.0f);
        }

        logger.log(PoppyLogger.Category.EASTER_EGG, player, "used /poppy (" + (canPlaceHere ? "placed as block" : "dropped as item") + ")");

        player.sendMessage(messages.get("poppy.success"));
    }

    @SuppressWarnings("UnstableApiUsage")
    private boolean isProtected(Block block, Player player) {
        BlockState replacedState = block.getState();
        Block placedAgainst = block.getRelative(BlockFace.DOWN);
        ItemStack poppyItem = new ItemStack(Material.POPPY);
        BlockPlaceEvent placeEvent = new BlockPlaceEvent(block, replacedState, placedAgainst, poppyItem, player, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(placeEvent);
        return placeEvent.isCancelled() || !placeEvent.canBuild();
    }
}