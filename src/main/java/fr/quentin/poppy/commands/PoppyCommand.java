package fr.quentin.poppy.commands;

import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Handles /poppy: a small easter egg. If the sender is in Survival mode
 * and has at least one poppy flower in their inventory, removes one and
 * either places it as an actual block at the sender's feet (if that block
 * is air and stands on solid ground), or drops it as an item on the
 * ground otherwise.
 *
 * <p>Deliberately restricted to {@link GameMode#SURVIVAL} — Creative
 * inventories aren't a real resource to spend, and Adventure/Spectator
 * players either can't break blocks or can't interact with the world at
 * all, so the "spend a real item you're holding" gag doesn't make sense in
 * any of those modes.
 */
public class PoppyCommand extends SafeCommand {

    public PoppyCommand(JavaPlugin plugin, Messages messages) {
        super(plugin, messages);
    }

    @Override
    protected boolean execute(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }

        if (player.getGameMode() != GameMode.SURVIVAL) {
            player.sendMessage(messages.get("poppy.wrong-gamemode"));
            return true;
        }

        ItemStack poppy = new ItemStack(Material.POPPY, 1);
        if (!player.getInventory().containsAtLeast(poppy, 1)) {
            player.sendMessage(messages.get("poppy.no-poppy"));
            return true;
        }

        player.getInventory().removeItem(poppy);

        Block feetBlock = player.getLocation().getBlock();
        Block belowBlock = feetBlock.getRelative(BlockFace.DOWN);

        if (feetBlock.getType() == Material.AIR && belowBlock.getType().isSolid()) {
            feetBlock.setType(Material.POPPY);
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_CROP_PLANT, 1.0f, 1.0f);
        } else {
            player.getWorld().dropItemNaturally(player.getLocation(), poppy);
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_GRASS_BREAK, 1.0f, 1.0f);
        }

        player.sendMessage(messages.get("poppy.success"));
        return true;
    }
}