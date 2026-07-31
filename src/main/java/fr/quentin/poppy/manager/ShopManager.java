package fr.quentin.poppy.manager;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Backs /shop: an admin shop (the server creates/destroys items on every
 * transaction — there's no stock to manage), with categories and prices
 * loaded from {@code shop.yml} rather than hardcoded, so an admin can
 * rebalance the economy without recompiling anything.
 *
 * <p>A price of -1 for {@code buy} or {@code sell} means that direction
 * is disabled for that item — used for weapons/armor and finished potions
 * (buy-only), matching the design goal of not letting automated farms
 * turn those into free money.
 *
 * <p>Every transaction re-checks live state ({@link EconomyManager}'s own
 * balance floor/ceiling, actual inventory space, actual item count) —
 * nothing about a purchase or sale is decided from what the GUI happens
 * to display, which could be stale.
 */
public class ShopManager {

    public enum TransactionResult {
        OK, INSUFFICIENT_FUNDS, INVENTORY_FULL, NOT_ENOUGH_ITEMS, NOT_SELLABLE, NOT_PURCHASABLE, BALANCE_FULL
    }

    public record ShopItem(Material material, String displayName, long buyPrice, long sellPrice) {
        public boolean purchasable() {
            return buyPrice >= 0;
        }

        public boolean sellable() {
            return sellPrice >= 0;
        }
    }

    public record ShopCategory(String id, String displayName, Material icon, String headTexture, List<ShopItem> items) {
    }

    private final JavaPlugin plugin;
    private final EconomyManager economy;
    private final File file;

    private final List<ShopCategory> categories = new ArrayList<>();

    public ShopManager(JavaPlugin plugin, EconomyManager economy) {
        this.plugin = plugin;
        this.economy = economy;
        this.file = new File(plugin.getDataFolder(), "shop.yml");

        load();
    }

    public List<ShopCategory> getCategories() {
        return categories;
    }

    public ShopCategory findCategory(String id) {
        for (ShopCategory category : categories) {
            if (category.id().equals(id)) {
                return category;
            }
        }
        return null;
    }

    /**
     * Reloads shop.yml — call after an admin edits it, e.g. from
     * {@code /poppy reload}.
     */
    public void reapply() {
        load();
    }

    private void load() {
        if (!file.exists()) {
            plugin.saveResource("shop.yml", false);
        }

        categories.clear();

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection categoriesSection = yaml.getConfigurationSection("categories");
        if (categoriesSection == null) {
            return;
        }

        for (String categoryId : categoriesSection.getKeys(false)) {
            ConfigurationSection categorySection = categoriesSection.getConfigurationSection(categoryId);
            if (categorySection == null) {
                continue;
            }

            String displayName = categorySection.getString("display-name", categoryId);
            Material icon = parseMaterial(categorySection.getString("icon", "CHEST"));
            if (icon == null) {
                icon = Material.CHEST;
            }
            String headTexture = categorySection.getString("head-texture");

            List<ShopItem> items = new ArrayList<>();
            for (Map<?, ?> raw : categorySection.getMapList("items")) {
                Material material = parseMaterial(String.valueOf(raw.get("material")));
                if (material == null) {
                    plugin.getLogger().warning("Skipping unknown shop material in category '" + categoryId + "': " + raw.get("material"));
                    continue;
                }

                long buy = raw.get("buy") instanceof Number number ? number.longValue() : -1;
                long sell = raw.get("sell") instanceof Number number ? number.longValue() : -1;
                String itemName = raw.get("display-name") != null ? String.valueOf(raw.get("display-name")) : formatMaterialName(material);

                items.add(new ShopItem(material, itemName, buy, sell));
            }

            categories.add(new ShopCategory(categoryId, displayName, icon, headTexture, items));
        }
    }

    private Material parseMaterial(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Material.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String formatMaterialName(Material material) {
        String[] parts = material.name().split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            builder.append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }

    public TransactionResult buy(Player player, ShopItem item, int amount) {
        if (!item.purchasable()) {
            return TransactionResult.NOT_PURCHASABLE;
        }

        ItemStack stack = new ItemStack(item.material(), amount);
        if (!hasSpaceFor(player, stack)) {
            return TransactionResult.INVENTORY_FULL;
        }

        long totalCost = item.buyPrice() * amount;
        EconomyManager.Result result = economy.withdraw(player.getUniqueId(), totalCost);
        if (result != EconomyManager.Result.OK) {
            return TransactionResult.INSUFFICIENT_FUNDS;
        }

        player.getInventory().addItem(stack);
        return TransactionResult.OK;
    }

    public TransactionResult sell(Player player, ShopItem item, int amount) {
        if (!item.sellable()) {
            return TransactionResult.NOT_SELLABLE;
        }

        if (countMaterial(player, item.material()) < amount) {
            return TransactionResult.NOT_ENOUGH_ITEMS;
        }

        long totalValue = item.sellPrice() * amount;
        EconomyManager.Result result = economy.deposit(player.getUniqueId(), totalValue);
        if (result != EconomyManager.Result.OK) {
            return TransactionResult.BALANCE_FULL;
        }

        removeMaterial(player, item.material(), amount);
        return TransactionResult.OK;
    }

    /**
     * Approximates whether {@code stack} fully fits without actually
     * giving it — checks existing partial stacks of the same item first,
     * then empty slots. Accurate for plain, meta-less shop items.
     */
    private boolean hasSpaceFor(Player player, ItemStack stack) {
        int remaining = stack.getAmount();
        int maxStackSize = stack.getMaxStackSize();

        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot != null && slot.isSimilar(stack)) {
                remaining -= Math.max(0, maxStackSize - slot.getAmount());
                if (remaining <= 0) {
                    return true;
                }
            }
        }

        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType() == Material.AIR) {
                remaining -= maxStackSize;
                if (remaining <= 0) {
                    return true;
                }
            }
        }

        return remaining <= 0;
    }

    private int countMaterial(Player player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material && isPlain(stack)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * Only removes plain, unnamed stacks of the material — a renamed or
     * otherwise customized item isn't sold at the shop's listed price
     * even if it happens to share the same base material.
     */
    private void removeMaterial(Player player, Material material, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();

        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material || !isPlain(stack)) {
                continue;
            }

            int take = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            remaining -= take;
            contents[i] = stack.getAmount() <= 0 ? null : stack;
        }

        player.getInventory().setStorageContents(contents);
    }

    private boolean isPlain(ItemStack stack) {
        return !stack.hasItemMeta() || !stack.getItemMeta().hasDisplayName();
    }
}