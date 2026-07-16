package fr.quentin.poppy.gui;

import fr.quentin.poppy.manager.HomeManager;
import fr.quentin.poppy.model.Home;
import fr.quentin.poppy.util.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the double-chest inventory listing every home the player owns.
 */
public final class HomesGUI {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    private final Messages messages;
    private final NamespacedKey homeNameKey;

    public HomesGUI(Plugin plugin, Messages messages) {
        this.messages = messages;
        this.homeNameKey = new NamespacedKey(plugin, "home_name");
    }

    public NamespacedKey getHomeNameKey() {
        return homeNameKey;
    }

    /**
     * Opens (or refreshes) the /homes GUI for a player.
     */
    public void open(Player player, HomeManager homeManager) {
        PoppyHomesHolder holder = new PoppyHomesHolder();
        Inventory inventory = Bukkit.createInventory(holder, HomeManager.MAX_HOMES, messages.get("gui.title"));
        holder.setInventory(inventory);

        for (Home home : homeManager.getHomes(player.getUniqueId()).values()) {
            inventory.addItem(buildItem(player, home));
        }

        ItemStack filler = buildFiller();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }

        player.openInventory(inventory);
    }

    private ItemStack buildFiller() {
        ItemStack filler = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        var meta = filler.getItemMeta();
        meta.displayName(messages.get("gui.filler"));
        filler.setItemMeta(meta);
        return filler;
    }

    private ItemStack buildItem(Player owner, Home home) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();

        meta.setOwningPlayer(owner);
        meta.displayName(messages.get("gui.home-name", "home", home.name()));

        String date = DATE_FORMAT.format(Instant.ofEpochMilli(home.createdAt()).atZone(ZoneId.systemDefault()));
        String worldLabel = worldLabel(home.worldName());

        List<Component> lore = new ArrayList<>();
        lore.add(messages.get("gui.lore-created", "date", date));
        lore.add(messages.get("gui.lore-world", "world", worldLabel));
        lore.add(messages.get("gui.lore-position",
                "x", String.valueOf((int) home.x()),
                "y", String.valueOf((int) home.y()),
                "z", String.valueOf((int) home.z())));
        lore.add(Component.empty());
        lore.add(messages.get("gui.lore-teleport"));
        lore.add(messages.get("gui.lore-delete"));

        meta.lore(lore);
        meta.getPersistentDataContainer().set(homeNameKey, PersistentDataType.STRING, home.name());

        item.setItemMeta(meta);
        return item;
    }

    private String worldLabel(String worldName) {
        World world = Bukkit.getWorld(worldName);
        World.Environment environment = world != null ? world.getEnvironment() : World.Environment.NORMAL;

        return switch (environment) {
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> "Overworld";
        };
    }
}