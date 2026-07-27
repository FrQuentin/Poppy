package fr.quentin.poppy.commands;

import fr.quentin.poppy.listeners.SleepPercentageListener;
import fr.quentin.poppy.listeners.TabHealthListener;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles /poppy: with no argument, a small easter egg (places or drops a
 * poppy — see {@link #handleEasterEgg}). With {@code reload}, reloads
 * config.yml and messages.yml live and re-applies the two settings that
 * can't just be read live via {@link PoppyConfig} on their own — the
 * sleep gamerule and the tab-health refresh interval (see
 * {@link #handleReload}).
 *
 * <p>{@link #handleEasterEgg} guards its placement with a simulated
 * {@link BlockPlaceEvent} (see {@link #isProtected}) — same pattern as
 * {@code DeathChestManager#isProtected} — before calling
 * {@code setBlockData(...)} directly, which otherwise bypasses protection
 * plugins (WorldGuard, GriefPrevention...) entirely since it mutates the
 * world at the engine level with no event involved.
 * {@link Block#canPlace(BlockData)} alone only checks physical
 * placement validity (soil type, replaceability), never claims/regions.
 */
public class PoppyCommand extends SafeCommand implements TabCompleter {

    private final PoppyConfig config;
    private final PoppyLogger logger;
    private final SleepPercentageListener sleepPercentageListener;
    private final TabHealthListener tabHealthListener;
    private final Map<UUID, Long> lastEasterEgg = new HashMap<>();

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

        String actorName = sender instanceof Player player ? player.getName() : "CONSOLE";
        logger.log(PoppyLogger.Category.ADMIN, actorName, "reloaded config.yml and messages.yml");

        sender.sendMessage(messages.get("poppy.reload-success"));
    }

    private void handleEasterEgg(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }

        long remaining = cooldownRemaining(player.getUniqueId());
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

        lastEasterEgg.put(player.getUniqueId(), System.currentTimeMillis());

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

    private long cooldownRemaining(UUID uuid) {
        long cooldownMillis = config.poppyEasterEggCooldownMillis();
        if (cooldownMillis <= 0) {
            return 0;
        }
        Long last = lastEasterEgg.get(uuid);
        if (last == null) {
            return 0;
        }
        long remainingMillis = cooldownMillis - (System.currentTimeMillis() - last);
        return remainingMillis <= 0 ? 0 : (remainingMillis / 1000) + 1;
    }

    /**
     * Fires a simulated {@link BlockPlaceEvent} before any world mutation
     * happens, so protection plugins (WorldGuard, GriefPrevention,
     * Lands...) that listen on that event get a chance to cancel it —
     * exactly as if the player had physically placed a poppy there. Same
     * approach as {@code DeathChestManager#isProtected}.
     *
     * <p>The {@link BlockPlaceEvent} constructor used here is marked
     * {@code @ApiStatus.Internal} by Paper — there's no stable public
     * alternative for simulating a place event from plugin code. Accepted
     * since verifying protection before this placement matters more than
     * the (small) risk of Paper changing the signature in a future version.
     */
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