package fr.quentin.poppy.commands;

import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.SafeCommand;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Handles /poppy: a small easter egg. Works in Survival and Creative
 * (Adventure and Spectator can't interact with the world at all, so
 * they're blocked); if the block at the sender's feet accepts poppy
 * BlockData per {@link Block#canPlace(BlockData)} — the same placement
 * validity check the game itself runs on a real right-click, covering
 * both "is this spot replaceable" and "is the soil below valid" in one
 * call — the poppy is placed there as a real block. Otherwise it's
 * dropped as an item on the ground.
 *
 * <p>In Survival this consumes one poppy from the sender's inventory (and
 * requires having one); in Creative it never touches the inventory,
 * mirroring how creative block placement normally works.
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

        if (feetBlock.canPlace(poppyData)) {
            feetBlock.setBlockData(poppyData);
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_CROP_PLANT, 1.0f, 1.0f);
        } else {
            player.getWorld().dropItemNaturally(player.getLocation(), poppy);
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_GRASS_BREAK, 1.0f, 1.0f);
        }

        player.sendMessage(messages.get("poppy.success"));
        return true;
    }
}