package fr.quentin.poppy.listeners;

import fr.quentin.poppy.util.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Easter egg: right-clicking an Iron Golem while holding a poppy tells the
 * player a short made-up story about how the flower ended up in the game —
 * a nod to the vanilla detail that villages give poppies to their iron
 * golems. Rate-limited via {@code poppy-lore-cooldown-minutes} in
 * config.yml so it can't be spammed.
 *
 * <p>{@link EquipmentSlot#HAND} check avoids firing twice for the same
 * click, since Bukkit fires this event once per hand when applicable —
 * this also means only the main hand is checked for the poppy, matching
 * how most other item-in-hand checks in this plugin work.
 *
 * <p>Unlike most other cooldown maps in this plugin (e.g.
 * {@code RtpCommand.lastUse}), {@code lastUse} here is deliberately
 * <b>not</b> cleared on quit: the cooldown is meant to survive a
 * disconnect/reconnect, and only resets on a full server restart. This is
 * safe memory-wise since each entry is just a UUID and a long.
 */
public class PoppyLoreListener implements Listener {

    private final JavaPlugin plugin;
    private final Messages messages;
    private final long cooldownMillis;

    private final Map<UUID, Long> lastUse = new HashMap<>();

    public PoppyLoreListener(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        long cooldownMinutes = Math.max(0, plugin.getConfig().getInt("poppy-lore-cooldown-minutes", 30));
        this.cooldownMillis = cooldownMinutes * 60L * 1000L;
    }

    @EventHandler
    public void onInteract(@NonNull PlayerInteractEntityEvent event) {
        try {
            if (event.getRightClicked().getType() != EntityType.IRON_GOLEM) {
                return;
            }
            if (event.getHand() != EquipmentSlot.HAND) {
                return;
            }

            Player player = event.getPlayer();

            ItemStack itemInHand = player.getInventory().getItemInMainHand();
            if (itemInHand.getType() != Material.POPPY) {
                return;
            }

            long remainingSeconds = cooldownRemainingSeconds(player.getUniqueId());
            if (remainingSeconds > 0) {
                player.sendMessage(messages.get("poppy.lore-cooldown", "minutes", String.valueOf((remainingSeconds / 60) + 1)));
                return;
            }

            lastUse.put(player.getUniqueId(), System.currentTimeMillis());
            tellStory(player);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in PoppyLoreListener#onInteract for " + event.getPlayer().getName(), e);
        }
    }

    private long cooldownRemainingSeconds(UUID uuid) {
        if (cooldownMillis <= 0) {
            return 0;
        }
        Long last = lastUse.get(uuid);
        if (last == null) {
            return 0;
        }
        long remainingMillis = cooldownMillis - (System.currentTimeMillis() - last);
        return remainingMillis <= 0 ? 0 : (remainingMillis / 1000) + 1;
    }

    private void tellStory(Player player) {
        player.sendMessage(Component.empty());
        for (String path : new String[] {
                "poppy.lore-line-1",
                "poppy.lore-line-2",
                "poppy.lore-line-3",
                "poppy.lore-line-4"
        }) {
            player.sendMessage(messages.get(path));
        }
        player.sendMessage(Component.empty());
    }
}