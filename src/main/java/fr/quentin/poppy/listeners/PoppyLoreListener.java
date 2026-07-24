package fr.quentin.poppy.listeners;

import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
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

public class PoppyLoreListener implements Listener {

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyConfig config;
    private final PoppyLogger logger;

    private final Map<UUID, Long> lastUse = new HashMap<>();

    public PoppyLoreListener(JavaPlugin plugin, Messages messages, PoppyConfig config, PoppyLogger logger) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.logger = logger;
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
                player.sendMessage(messages.get("poppy.lore-cooldown", "time", DurationFormat.format(remainingSeconds)));
                return;
            }

            lastUse.put(player.getUniqueId(), System.currentTimeMillis());
            logger.log(PoppyLogger.Category.EASTER_EGG, player, "triggered the iron golem poppy story");
            tellStory(player);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in PoppyLoreListener#onInteract for " + event.getPlayer().getName(), e);
        }
    }

    private long cooldownRemainingSeconds(UUID uuid) {
        long cooldownMillis = config.poppyLoreCooldownMillis();
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