package fr.quentin.poppy.manager.fly;

import fr.quentin.poppy.manager.combat.CombatManager;
import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.cooldown.CooldownManager;
import fr.quentin.poppy.util.cooldown.CooldownStore;
import fr.quentin.poppy.util.io.AtomicYamlWriter;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Backs /fly: disables flight the moment a flying (or fly-enabled) player
 * takes any damage, and blocks /fly for {@code fly-lockout-seconds}
 * afterward via a shared {@link CooldownStore} obtained from
 * {@link CooldownManager}.
 *
 * <p>Only ever usable in Survival. Only ever acts on flight this class
 * itself granted. Death/respawn are explicitly handled since
 * {@link #onDamage} ignores fall damage. Budget regenerates over time,
 * never resets for free, and is persisted to {@code flybudgets.yml}.
 * {@link #endFlightSession} is the single point where a flight session
 * ends outside the normal toggle-off path.
 */
public class FlyManager implements Listener {

    public enum ToggleResult { ENABLED, DISABLED, BLOCKED_END, NO_BUDGET, WRONG_GAMEMODE }

    private record FlightBudget(long usedMillis, long lastUpdateMillis) {
    }

    private static final long PARTICLE_INTERVAL_TICKS = 4L;
    private static final double PARTICLE_RADIUS = 0.6;
    private static final int PARTICLE_POINTS = 8;
    private static final long DURATION_TICK_INTERVAL_TICKS = 20L;
    private static final long BUDGET_PURGE_INTERVAL_TICKS = 20L * 60 * 5;
    private static final long BUDGET_SAVE_INTERVAL_TICKS = 20L * 60;

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyConfig config;
    private final CombatManager combatManager;
    private final CooldownStore lockoutStore;
    private final File file;

    private final Set<UUID> activeFly = new HashSet<>();
    private final Map<UUID, FlightBudget> flightBudgets = new HashMap<>();
    private final Set<UUID> warnedThisCycle = new HashSet<>();

    private volatile boolean dirty;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Poppy-Fly-IO");
        thread.setDaemon(true);
        return thread;
    });

    public FlyManager(JavaPlugin plugin, Messages messages, PoppyConfig config, CombatManager combatManager, CooldownManager cooldownManager) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.combatManager = combatManager;
        this.lockoutStore = cooldownManager.get("fly-lockout");
        this.file = new File(plugin.getDataFolder(), "flybudgets.yml");

        loadBudgets();

        Bukkit.getScheduler().runTaskTimer(plugin, this::tickParticles, PARTICLE_INTERVAL_TICKS, PARTICLE_INTERVAL_TICKS);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickFlightDuration, DURATION_TICK_INTERVAL_TICKS, DURATION_TICK_INTERVAL_TICKS);
        Bukkit.getScheduler().runTaskTimer(plugin, this::purgeStaleBudgets, BUDGET_PURGE_INTERVAL_TICKS, BUDGET_PURGE_INTERVAL_TICKS);
        Bukkit.getScheduler().runTaskTimer(plugin, this::flushIfDirty, BUDGET_SAVE_INTERVAL_TICKS, BUDGET_SAVE_INTERVAL_TICKS);
    }

    public ToggleResult toggle(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (activeFly.contains(uuid)) {
            endFlightSession(uuid);
            player.setAllowFlight(false);
            player.setFlying(false);
            return ToggleResult.DISABLED;
        }

        if (player.getGameMode() != GameMode.SURVIVAL) {
            return ToggleResult.WRONG_GAMEMODE;
        }

        if (player.getWorld().getEnvironment() == World.Environment.THE_END) {
            return ToggleResult.BLOCKED_END;
        }

        long maxMillis = config.flyMaxDurationMillis();
        if (maxMillis > 0) {
            long currentUsed = computeCurrentUsedMillis(uuid, now);
            long minimum = Math.min(minimumBudgetMillis(), maxMillis);
            if (maxMillis - currentUsed < minimum) {
                return ToggleResult.NO_BUDGET;
            }

            if (currentUsed <= 0) {
                flightBudgets.remove(uuid);
            } else {
                flightBudgets.put(uuid, new FlightBudget(currentUsed, now));
            }
            dirty = true;
        }

        warnedThisCycle.remove(uuid);
        activeFly.add(uuid);
        player.setAllowFlight(true);
        player.setFlying(true);
        return ToggleResult.ENABLED;
    }

    public boolean isLocked(UUID uuid) {
        return lockoutStore.isActive(uuid) || combatManager.isInCombat(uuid);
    }

    public long displayRemainingSeconds(UUID uuid) {
        return Math.max(lockoutStore.remainingSeconds(uuid), combatManager.remainingSeconds(uuid));
    }

    public long remainingFlightBudgetSeconds(UUID uuid) {
        long maxMillis = config.flyMaxDurationMillis();
        if (maxMillis <= 0) {
            return -1;
        }
        if (isLocked(uuid)) {
            return 0;
        }
        long used = computeCurrentUsedMillis(uuid, System.currentTimeMillis());
        long remaining = maxMillis - used;
        return remaining <= 0 ? 0 : (remaining / 1000) + 1;
    }

    public long secondsUntilAnyBudget(UUID uuid) {
        long maxMillis = config.flyMaxDurationMillis();
        if (maxMillis <= 0) {
            return 0;
        }
        long used = computeCurrentUsedMillis(uuid, System.currentTimeMillis());
        long minimum = Math.min(minimumBudgetMillis(), maxMillis);
        long missing = minimum - (maxMillis - used);
        return missing <= 0 ? 0 : (missing / 1000) + 1;
    }

    private long minimumBudgetMillis() {
        long warningMillis = config.flyMaxDurationWarningSeconds() * 1000L;
        return warningMillis > 0 ? warningMillis : 10_000L;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(@NonNull EntityDamageEvent event) {
        try {
            if (event.isCancelled() || event.getFinalDamage() <= 0) {
                return;
            }
            if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
                return;
            }
            if (!(event.getEntity() instanceof Player player)) {
                return;
            }
            disableFlightAndLock(player, null, config.flyLockoutMillis(), "fly.disabled-damage", null);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in FlyManager#onDamage for " + event.getEntity().getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPvpDamage(@NonNull EntityDamageByEntityEvent event) {
        try {
            if (event.isCancelled() || event.getFinalDamage() <= 0) {
                return;
            }
            Player attacker = resolveAttacker(event.getDamager());
            if (attacker == null) {
                return;
            }
            disableFlightAndLock(attacker, event.getEntity(), config.flyLockoutMillis(), "fly.disabled-attack", "target");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in FlyManager#onPvpDamage", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(@NonNull PlayerDeathEvent event) {
        endFlightSession(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(@NonNull PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        endFlightSession(uuid);

        if (!config.flyForceDisableOnJoin()) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            GameMode mode = player.getGameMode();
            if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
                return;
            }
            if (!activeFly.contains(uuid) && player.getAllowFlight()) {
                player.setAllowFlight(false);
                player.setFlying(false);
            }
        });
    }

    @EventHandler
    public void onWorldChange(@NonNull PlayerChangedWorldEvent event) {
        try {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();

            if (!activeFly.contains(uuid)) {
                return;
            }
            if (player.getWorld().getEnvironment() != World.Environment.THE_END) {
                return;
            }

            endFlightSession(uuid);
            player.setAllowFlight(false);
            player.setFlying(false);
            player.sendMessage(messages.get("fly.blocked-end"));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in FlyManager#onWorldChange for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler
    public void onJoin(@NonNull PlayerJoinEvent event) {
        try {
            if (!config.flyForceDisableOnJoin()) {
                return;
            }
            Player player = event.getPlayer();
            GameMode mode = player.getGameMode();
            if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
                return;
            }
            if (!activeFly.contains(player.getUniqueId()) && player.getAllowFlight()) {
                player.setAllowFlight(false);
                player.setFlying(false);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in FlyManager#onJoin for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler
    public void onGameModeChange(@NonNull PlayerGameModeChangeEvent event) {
        try {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();
            GameMode newMode = event.getNewGameMode();

            if (newMode == GameMode.CREATIVE || newMode == GameMode.SPECTATOR) {
                endFlightSession(uuid);
                return;
            }

            if (config.flyForceDisableOnJoin() && !activeFly.contains(uuid) && player.getAllowFlight()) {
                player.setAllowFlight(false);
                player.setFlying(false);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in FlyManager#onGameModeChange for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler
    public void onQuit(@NonNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        boolean wasFlying = activeFly.contains(uuid);
        endFlightSession(uuid);
        if (wasFlying) {
            player.setAllowFlight(false);
            player.setFlying(false);
        }
    }

    private void endFlightSession(UUID uuid) {
        if (activeFly.remove(uuid)) {
            freezeBudget(uuid);
        }
        warnedThisCycle.remove(uuid);
    }

    private void freezeBudget(UUID uuid) {
        FlightBudget budget = flightBudgets.get(uuid);
        if (budget != null) {
            flightBudgets.put(uuid, new FlightBudget(budget.usedMillis(), System.currentTimeMillis()));
            dirty = true;
        }
    }

    private void disableFlightAndLock(Player player, Entity target, long lockoutMillis, String messagePath, String targetPlaceholderName) {
        UUID uuid = player.getUniqueId();
        if (!activeFly.contains(uuid)) {
            return;
        }

        player.setFlying(false);
        player.setAllowFlight(false);
        endFlightSession(uuid);

        lockoutStore.start(uuid, lockoutMillis);

        if (target != null && targetPlaceholderName != null) {
            player.sendMessage(messages.get(messagePath, targetPlaceholderName, targetName(target)));
        } else {
            player.sendMessage(messages.get(messagePath));
        }
    }

    private void tickFlightDuration() {
        long maxMillis = config.flyMaxDurationMillis();
        if (maxMillis <= 0 || activeFly.isEmpty()) {
            return;
        }

        long warningMillis = config.flyMaxDurationWarningSeconds() * 1000L;
        long now = System.currentTimeMillis();

        for (UUID uuid : new ArrayList<>(activeFly)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }

            FlightBudget previous = flightBudgets.get(uuid);
            long usedBefore = previous != null ? previous.usedMillis() : 0L;
            long used = usedBefore + 1000L;

            if (used >= maxMillis) {
                player.setFlying(false);
                player.setAllowFlight(false);
                activeFly.remove(uuid);
                flightBudgets.put(uuid, new FlightBudget(maxMillis, now));
                dirty = true;
                warnedThisCycle.remove(uuid);

                player.sendMessage(messages.get("fly.max-duration-reached", "time", DurationFormat.format(maxMillis / 1000)));
                continue;
            }

            flightBudgets.put(uuid, new FlightBudget(used, now));
            dirty = true;

            long remaining = maxMillis - used;
            if (warningMillis > 0 && remaining <= warningMillis && warnedThisCycle.add(uuid)) {
                player.sendMessage(messages.get("fly.max-duration-warning", "time", DurationFormat.format((remaining / 1000) + 1)));
            }
        }
    }

    private long computeCurrentUsedMillis(UUID uuid, long now) {
        FlightBudget budget = flightBudgets.get(uuid);
        if (budget == null) {
            return 0L;
        }
        if (activeFly.contains(uuid)) {
            return budget.usedMillis();
        }
        long elapsed = Math.max(0L, now - budget.lastUpdateMillis());
        return Math.max(0L, budget.usedMillis() - elapsed);
    }

    private void purgeStaleBudgets() {
        long now = System.currentTimeMillis();
        boolean[] removedAny = {false};

        flightBudgets.entrySet().removeIf(entry -> {
            UUID uuid = entry.getKey();
            if (activeFly.contains(uuid)) {
                return false;
            }
            if (computeCurrentUsedMillis(uuid, now) > 0) {
                return false;
            }
            warnedThisCycle.remove(uuid);
            removedAny[0] = true;
            return true;
        });

        if (removedAny[0]) {
            dirty = true;
        }
    }

    private void loadBudgets() {
        if (!file.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("budgets");
        if (section == null) {
            return;
        }

        for (String uuidString : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidString);
                long used = section.getLong(uuidString + ".used");
                long lastUpdate = section.getLong(uuidString + ".last-update", System.currentTimeMillis());
                flightBudgets.put(uuid, new FlightBudget(used, lastUpdate));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Skipping an invalid flight budget entry: " + uuidString, e);
            }
        }
    }

    private void flushIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        persistBudgets(false);
    }

    private void persistBudgets(boolean blocking) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, FlightBudget> entry : new HashMap<>(flightBudgets).entrySet()) {
            String base = "budgets." + entry.getKey();
            yaml.set(base + ".used", entry.getValue().usedMillis());
            yaml.set(base + ".last-update", entry.getValue().lastUpdateMillis());
        }

        try {
            if (blocking) {
                Future<?> future = ioExecutor.submit(() -> AtomicYamlWriter.save(yaml, file, plugin, "flybudgets.yml"));
                future.get();
            } else {
                ioExecutor.execute(() -> AtomicYamlWriter.save(yaml, file, plugin, "flybudgets.yml"));
            }
        } catch (RejectedExecutionException e) {
            plugin.getLogger().log(Level.WARNING, "Could not queue flight budget save (I/O executor already shut down)", e);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error flushing flight budgets", e);
        }
    }

    public void shutdown() {
        persistBudgets(true);
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Timed out flushing flight budgets to disk");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void tickParticles() {
        if (!config.flyParticlesEnabled() || activeFly.isEmpty()) {
            return;
        }
        for (UUID uuid : activeFly) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline() || !player.isFlying()) {
                continue;
            }
            spawnFlightParticles(player);
        }
    }

    private void spawnFlightParticles(Player player) {
        Location center = player.getLocation();
        World world = player.getWorld();

        for (int i = 0; i < PARTICLE_POINTS; i++) {
            double angle = 2 * Math.PI * i / PARTICLE_POINTS;
            double x = center.getX() + PARTICLE_RADIUS * Math.cos(angle);
            double z = center.getZ() + PARTICLE_RADIUS * Math.sin(angle);
            world.spawnParticle(Particle.END_ROD, x, center.getY() + 0.1, z, 1, 0, 0, 0, 0);
        }
    }

    private String targetName(Entity target) {
        if (target instanceof Player targetPlayer) {
            return targetPlayer.getName();
        }
        return formatEntityTypeName(target.getType());
    }

    private String formatEntityTypeName(EntityType entityType) {
        String[] parts = entityType.name().split("_");
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

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }
        return null;
    }
}