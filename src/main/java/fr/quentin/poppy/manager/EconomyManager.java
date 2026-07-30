package fr.quentin.poppy.manager;

import fr.quentin.poppy.util.AtomicYamlWriter;
import fr.quentin.poppy.util.PoppyConfig;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Tracks every player's balance, backing /money and /pay. Persisted to a
 * single {@code economy.yml} — one file for every balance, not one per
 * player like {@link HomeManager}, since balances are small (a UUID and a
 * long each) and there's no reason to load/unload them per session; the
 * whole map is simply kept in memory for the plugin's lifetime.
 *
 * <p>Every balance is clamped to {@code [min-money, max-money]}
 * (config.yml) — a mutation that would cross either bound is rejected
 * outright (see {@link Result}) rather than silently clamped, so a
 * command always either fully succeeds or the caller gets an explicit
 * reason it didn't.
 *
 * <p>Persistence follows the same debounced pattern as
 * {@code DeathChestManager}: a {@code dirty} flag set on every mutation,
 * flushed at most once per second by a periodic task, plus an
 * unconditional final flush at {@link #shutdown()}. Writes go through
 * {@link AtomicYamlWriter} on a dedicated single-thread executor, never
 * blocking the main thread.
 *
 * <p>{@link #nameCache} maps a lowercase player name to their UUID,
 * populated on join — this is what lets the admin subcommands of /money
 * target a currently-offline player by name without a blocking
 * {@code Bukkit.getOfflinePlayer(String)} lookup. A player who has never
 * joined this server can't be a valid target either way, so this cache
 * is a complete enough index for the purpose.
 */
public class EconomyManager implements Listener {

    public enum Result { OK, WOULD_EXCEED_MAX, WOULD_GO_BELOW_MIN }

    private final JavaPlugin plugin;
    private final PoppyConfig config;
    private final File file;

    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<String, UUID> nameCache = new HashMap<>();

    private final ScheduledExecutorService ioExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Poppy-Economy-IO");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean dirty;
    private ScheduledFuture<?> flushTask;

    public EconomyManager(JavaPlugin plugin, PoppyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.file = new File(plugin.getDataFolder(), "economy.yml");

        loadAll();
        flushTask = ioExecutor.scheduleAtFixedRate(this::flushIfDirty, 1, 1, TimeUnit.SECONDS);
    }

    @EventHandler
    public void onJoin(@NonNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        nameCache.put(player.getName().toLowerCase(Locale.ROOT), player.getUniqueId());
        balances.computeIfAbsent(player.getUniqueId(), uuid -> {
            dirty = true;
            return clamp(config.economyStartingMoney());
        });
    }

    public long getBalance(UUID uuid) {
        return balances.getOrDefault(uuid, clamp(config.economyStartingMoney()));
    }

    /**
     * The top {@code limit} balances, sorted highest first, with each UUID
     * resolved to a display name via {@link Bukkit#getOfflinePlayer(UUID)} —
     * acceptable here since /baltop is a rare, deliberate lookup, not a hot
     * path, unlike the join-time {@link #nameCache} used for /money's
     * frequent admin-command target resolution.
     */
    public List<Map.Entry<String, Long>> getTopBalances(int limit) {
        return balances.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> {
                    String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                    return Map.entry(name != null ? name : entry.getKey().toString(), entry.getValue());
                })
                .toList();
    }

    /**
     * Resolves a player name to their UUID via {@link #nameCache} — works
     * for a currently-offline player as long as they've joined this
     * server at least once before.
     */
    public UUID resolveByName(String name) {
        return nameCache.get(name.toLowerCase(Locale.ROOT));
    }

    public Result deposit(UUID uuid, long amount) {
        long current = getBalance(uuid);
        long updated = current + amount;
        if (updated > config.economyMaxMoney()) {
            return Result.WOULD_EXCEED_MAX;
        }
        balances.put(uuid, updated);
        dirty = true;
        return Result.OK;
    }

    public Result withdraw(UUID uuid, long amount) {
        long current = getBalance(uuid);
        long updated = current - amount;
        if (updated < config.economyMinMoney()) {
            return Result.WOULD_GO_BELOW_MIN;
        }
        balances.put(uuid, updated);
        dirty = true;
        return Result.OK;
    }

    public Result setBalance(UUID uuid, long amount) {
        if (amount > config.economyMaxMoney()) {
            return Result.WOULD_EXCEED_MAX;
        }
        if (amount < config.economyMinMoney()) {
            return Result.WOULD_GO_BELOW_MIN;
        }
        balances.put(uuid, amount);
        dirty = true;
        return Result.OK;
    }

    /**
     * Transfers money from one player to another: both bounds are checked
     * before either balance is mutated, so a transfer either fully
     * succeeds or leaves both balances completely untouched.
     */
    public Result transfer(UUID from, UUID to, long amount) {
        long fromBalance = getBalance(from);
        long toBalance = getBalance(to);

        if (fromBalance - amount < config.economyMinMoney()) {
            return Result.WOULD_GO_BELOW_MIN;
        }
        if (toBalance + amount > config.economyMaxMoney()) {
            return Result.WOULD_EXCEED_MAX;
        }

        balances.put(from, fromBalance - amount);
        balances.put(to, toBalance + amount);
        dirty = true;
        return Result.OK;
    }

    private long clamp(long amount) {
        return Math.max(config.economyMinMoney(), Math.min(config.economyMaxMoney(), amount));
    }

    private void loadAll() {
        if (!file.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("balances");
        if (section == null) {
            return;
        }

        for (String uuidString : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidString);
                balances.put(uuid, section.getLong(uuidString));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().log(Level.WARNING, "Skipping an invalid UUID in economy.yml: " + uuidString);
            }
        }
    }

    private void flushIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        persist(false);
    }

    private void persist(boolean blocking) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Long> entry : new HashMap<>(balances).entrySet()) {
            yaml.set("balances." + entry.getKey(), entry.getValue());
        }

        try {
            if (blocking) {
                Future<?> future = ioExecutor.submit(() -> AtomicYamlWriter.save(yaml, file, plugin, "economy.yml"));
                future.get();
            } else {
                ioExecutor.execute(() -> AtomicYamlWriter.save(yaml, file, plugin, "economy.yml"));
            }
        } catch (RejectedExecutionException e) {
            plugin.getLogger().log(Level.WARNING, "Could not queue economy save (I/O executor already shut down)", e);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error flushing economy.yml", e);
        }
    }

    /**
     * Cancels the periodic flush and does a final blocking save — must be
     * called from {@code Poppy#onDisable}.
     */
    public void shutdown() {
        if (flushTask != null) {
            flushTask.cancel(false);
        }
        persist(true);
        ioExecutor.shutdown();
        try {
            ioExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}