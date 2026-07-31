package fr.quentin.poppy.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import fr.quentin.poppy.manager.ShopManager;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.MoneyFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the /shop GUIs: the main category menu and each category's item
 * listing, both paginated at {@link #PAGE_SIZE} entries per page — the
 * first 5 rows of the 54-slot inventory hold content, the last row is
 * reserved for navigation. Added specifically so a category (or the
 * category list itself) can grow past a single inventory's worth of
 * entries without anything being silently cut off — Colored Blocks alone
 * is already 48 items, over the old single-page limit of 54.
 *
 * <p>Left-click buys 1, shift+left buys 16, right-click sells 1,
 * shift+right sells 16 — handled by {@code ShopListener}.
 */
public final class ShopGUI {

    private static final int PAGE_SIZE = 45; // 5 rows; the 6th is reserved for navigation

    private final Messages messages;
    private final NamespacedKey categoryKey;
    private final NamespacedKey itemIndexKey;
    private final NamespacedKey backButtonKey;
    private final NamespacedKey prevPageKey;
    private final NamespacedKey nextPageKey;

    public ShopGUI(Plugin plugin, Messages messages) {
        this.messages = messages;
        this.categoryKey = new NamespacedKey(plugin, "shop_category");
        this.itemIndexKey = new NamespacedKey(plugin, "shop_item_index");
        this.backButtonKey = new NamespacedKey(plugin, "shop_back_button");
        this.prevPageKey = new NamespacedKey(plugin, "shop_prev_page");
        this.nextPageKey = new NamespacedKey(plugin, "shop_next_page");
    }

    public NamespacedKey getCategoryKey() {
        return categoryKey;
    }

    public NamespacedKey getItemIndexKey() {
        return itemIndexKey;
    }

    public NamespacedKey getBackButtonKey() {
        return backButtonKey;
    }

    public NamespacedKey getPrevPageKey() {
        return prevPageKey;
    }

    public NamespacedKey getNextPageKey() {
        return nextPageKey;
    }

    public void openMain(Player player, List<ShopManager.ShopCategory> categories, int page) {
        int totalPages = totalPages(categories.size());
        int clampedPage = clampPage(page, totalPages);

        ShopMainHolder holder = new ShopMainHolder(clampedPage);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.get("shop.main-title"));
        holder.setInventory(inventory);

        ItemStack filler = buildFiller();
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        int start = clampedPage * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, categories.size());
        for (int i = start; i < end; i++) {
            inventory.setItem(i - start, buildCategoryIcon(categories.get(i)));
        }

        placeNavigation(inventory, clampedPage, totalPages, false);

        player.openInventory(inventory);
    }

    public void openCategory(Player player, ShopManager.ShopCategory category, int page) {
        List<ShopManager.ShopItem> items = category.items();
        int totalPages = totalPages(items.size());
        int clampedPage = clampPage(page, totalPages);

        ShopCategoryHolder holder = new ShopCategoryHolder(category.id(), clampedPage);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.get("shop.category-title", "category", category.displayName()));
        holder.setInventory(inventory);

        ItemStack filler = buildFiller();
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        int start = clampedPage * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, items.size());
        for (int i = start; i < end; i++) {
            inventory.setItem(i - start, buildItemIcon(items.get(i), i));
        }

        placeNavigation(inventory, clampedPage, totalPages, true);

        player.openInventory(inventory);
    }

    /**
     * Places prev/indicator/next side-by-side in the middle of the reserved
     * last row (slots 48, 49, 50). The previous-page button is ALWAYS shown
     * in a category view — even on page 0, where there's technically no
     * previous page — because it's the only way back to the main category
     * menu; without it, a player stuck on a category's first page would
     * have no way out short of closing the inventory entirely. Its behavior
     * on page 0 (go to the main menu instead of decrementing the page) is
     * handled by {@code ShopListener}, not here.
     */
    private void placeNavigation(Inventory inventory, int page, int totalPages, boolean isCategoryView) {
        int size = inventory.getSize();

        if (page > 0 || isCategoryView) {
            inventory.setItem(size - 6, buildNavButton(Material.ARROW, messages.get("shop.prev-page"), prevPageKey));
        }

        inventory.setItem(size - 5, buildPageIndicator(page, totalPages));

        if (page < totalPages - 1) {
            inventory.setItem(size - 4, buildNavButton(Material.ARROW, messages.get("shop.next-page"), nextPageKey));
        }
    }

    private int totalPages(int itemCount) {
        return Math.max(1, (int) Math.ceil(itemCount / (double) PAGE_SIZE));
    }

    private int clampPage(int page, int totalPages) {
        return Math.max(0, Math.min(page, totalPages - 1));
    }

    /**
     * Uses a real villager-profession skin (a PLAYER_HEAD with a custom
     * texture) when {@code head-texture} is set in shop.yml for this
     * category, otherwise falls back to the plain material icon — so a
     * category never breaks visually just because an admin hasn't filled in
     * a head texture yet.
     */
    private ItemStack buildCategoryIcon(ShopManager.ShopCategory category) {
        ItemStack icon = category.headTexture() != null
                ? buildHeadIcon(category.headTexture())
                : new ItemStack(category.icon());

        ItemMeta meta = icon.getItemMeta();
        meta.displayName(messages.get("shop.category-name", "category", category.displayName()));
        meta.getPersistentDataContainer().set(categoryKey, PersistentDataType.STRING, category.id());
        icon.setItemMeta(meta);
        return icon;
    }

    /**
     * Builds a PLAYER_HEAD carrying a custom skin texture directly from a
     * minecraft-heads.com "Value" string — no network lookup needed, the
     * base64 already embeds the skin URL. Uses
     * {@link PlayerProfile#setProperty} with the raw {@code "textures"}
     * property rather than resolving a real player name, so this works
     * fully offline and isn't tied to any actual Mojang account.
     */
    private ItemStack buildHeadIcon(String headTexture) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();

        PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
        profile.setProperty(new ProfileProperty("textures", headTexture));
        meta.setPlayerProfile(profile);

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildItemIcon(ShopManager.ShopItem item, int index) {
        ItemStack icon = new ItemStack(item.material());
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(messages.get("shop.item-name", "item", item.displayName()));

        List<Component> lore = new ArrayList<>();
        if (item.purchasable()) {
            lore.add(messages.get("shop.item-lore-buy", "price", MoneyFormat.format(item.buyPrice())));
        }
        if (item.sellable()) {
            lore.add(messages.get("shop.item-lore-sell", "price", MoneyFormat.format(item.sellPrice())));
        }
        lore.add(Component.empty());
        if (item.purchasable()) {
            lore.add(messages.get("shop.item-lore-buy-hint"));
        }
        if (item.sellable()) {
            lore.add(messages.get("shop.item-lore-sell-hint"));
        }
        meta.lore(lore);

        meta.getPersistentDataContainer().set(itemIndexKey, PersistentDataType.INTEGER, index);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack buildBackButton() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.get("shop.back-button"));
        meta.getPersistentDataContainer().set(backButtonKey, PersistentDataType.INTEGER, 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildNavButton(Material material, Component name, NamespacedKey key) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildPageIndicator(int page, int totalPages) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.get("shop.page-indicator", "page", String.valueOf(page + 1), "total", String.valueOf(totalPages)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildFiller() {
        ItemStack filler = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(Component.empty());
        filler.setItemMeta(meta);
        return filler;
    }
}