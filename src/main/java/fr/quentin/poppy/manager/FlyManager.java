package fr.quentin.poppy.manager;

import fr.quentin.poppy.util.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Backs /fly: disables flight the moment a flying (or fly-enabled) player
 * takes any damage, and blocks /fly for {@code fly-lockout-seconds}
 * afterward via a shared {@link CooldownStore}.
 *
 * <p><b>Only ever acts on flight this class itself granted</b> — see
 * {@link #disableFlightAndLock}, {@link #onJoin}, {@link #onGameModeChange}
 * for how {@link #activeFly} gates every action.
 *
 * <p><b>Cumulative flight time budget:</b> {@link #flyTimeUsedMillis}
 * tracks, per player, how much total time they've had /fly enabled —
 * incremented every second by {@link #tickFlightDuration} for anyone in
 * {@link #activeFly}, whether airborne or not. Cumulative, not
 * per-session: toggling /fly off and back on does NOT reset the counter
 * — only actually hitting {@code fly-max-duration-minutes} does,
 * otherwise a player could dodge the whole limit by toggling off just
 * before the cap. {@link #warnedThisCycle} (a one-shot warning at
 * {@code fly-max-duration-warning-seconds} remaining) follows the exact
 * same lifecycle as {@link #flyTimeUsedMillis} for the same reason: it's
 * only cleared alongside it (cap hit, a damage lockout, or quit), never
 * on a manual toggle — so a player can't dodge the warning either by
 * toggling off and back on right after seeing it.
 *
 * <p>Hitting the cap forces flight off and starts {@link #lockoutStore}
 * for {@code fly-max-duration-cooldown-minutes} — the same store used for
 * the post-damage lockout, so {@link #isLocked}/{@link #displayRemainingSeconds}
 * handle both cases automatically with no separate code path.
 *
 * <p>Fall damage never triggers a lockout. Flight can't be enabled while
 * in the End, and is force-disabled if an already-flying player ends up
 * there anyway.
 */
public class FlyManager implements Listener {

    public enum ToggleResult { ENABLED, DISABLED, BLOCKED_END }

    private static final long PARTICLE_INTERVAL_TICKS = 4L;
    private static final double PARTICLE_RADIUS = 0.6;
    private static final int PARTICLE_POINTS = 8;
    private static final long DURATION_TICK_INTERVAL_TICKS = 20L;

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyConfig config;
    private final CombatManager combatManager;
    private final CooldownStore lockoutStore;

    private final Set<UUID> activeFly = new HashSet<>();
    private final Map<UUID, Long> flyTimeUsedMillis = new HashMap<>();
    private final Set<UUID> warnedThisCycle = new HashSet<>();

    public FlyManager(JavaPlugin plugin, Messages messages, PoppyConfig config, CombatManager combatManager, CooldownRegistry registry) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.combatManager = combatManager;
        this.lockoutStore = new CooldownStore(plugin);
        registry.register("Fly", lockoutStore);

        Bukkit.getScheduler().runTaskTimer(plugin, this::tickParticles, PARTICLE_INTERVAL_TICKS, PARTICLE_INTERVAL_TICKS);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickFlightDuration, DURATION_TICK_INTERVAL_TICKS, DURATION_TICK_INTERVAL_TICKS);
    }

    public ToggleResult toggle(Player player) {
        UUID uuid = player.getUniqueId();

        if (activeFly.contains(uuid)) {
            activeFly.remove(uuid);
            player.setAllowFlight(false);
            player.setFlying(false);
            return ToggleResult.DISABLED;
        }

        if (player.getWorld().getEnvironment() == World.Environment.THE_END) {
            return ToggleResult.BLOCKED_END;
        }

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

    /**
     * Seconds remaining in the player's cumulative flight budget — see the
     * class-level doc on {@link #flyTimeUsedMillis}. Returns -1 if
     * {@code fly-max-duration-minutes} is 0 (no limit configured), so a
     * caller can distinguish "unlimited" from "budget exhausted" (0).
     *
     * <p>Returns 0 whenever {@link #isLocked} is true (a post-damage or
     * post-max-duration lockout, or an active combat tag) — this is purely a
     * display choice ("you can't fly right now"), not a reflection of the
     * underlying budget: {@link #flyTimeUsedMillis} is NOT cleared by a
     * post-damage lockout (see {@link #disableFlightAndLock}), so the real
     * remaining budget is preserved underneath and becomes visible again as
     * soon as the lockout ends.
     */
    public long remainingFlightBudgetSeconds(UUID uuid) {
        long maxMillis = config.flyMaxDurationMillis();
        if (maxMillis <= 0) {
            return -1;
        }

        if (isLocked(uuid)) {
            return 0;
        }

        long used = flyTimeUsedMillis.getOrDefault(uuid, 0L);
        long remaining = maxMillis - used;
        return remaining <= 0 ? 0 : (remaining / 1000) + 1;
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

            activeFly.remove(uuid);
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
                activeFly.remove(uuid);
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

        if (activeFly.remove(uuid)) {
            player.setAllowFlight(false);
            player.setFlying(false);
        }

        // Not just activeFly — flyTimeUsedMillis/warnedThisCycle are otherwise
        // never cleared for a player who quits mid-cycle without hitting the cap
        // or taking damage, which would leave a permanent entry behind.
        flyTimeUsedMillis.remove(uuid);
        warnedThisCycle.remove(uuid);
    }

    private void disableFlightAndLock(Player player, Entity target, long lockoutMillis, String messagePath, String targetPlaceholderName) {
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return;
        }

        if (!activeFly.contains(player.getUniqueId())) {
            return;
        }

        player.setFlying(false);
        player.setAllowFlight(false);
        activeFly.remove(player.getUniqueId());
        // flyTimeUsedMillis and warnedThisCycle are DELIBERATELY kept here: the
        // cumulative budget only resets by actually paying the real cap cooldown
        // (see tickFlightDuration). Clearing them on the post-damage lockout let a
        // player trade fly-max-duration-cooldown-minutes for fly-lockout-seconds
        // by taking a trivial hit right before the cap — a full bypass of the
        // limit. The warning already shown stays valid for the same reason: the
        // budget cycle hasn't actually changed.
        lockoutStore.start(player.getUniqueId(), lockoutMillis);

        if (target != null && targetPlaceholderName != null) {
            player.sendMessage(messages.get(messagePath, targetPlaceholderName, targetName(target)));
        } else {
            player.sendMessage(messages.get(messagePath));
        }
    }

    /**
     * Runs every second, adding one second of usage for every player
     * currently in {@link #activeFly}. Sends a one-shot warning at
     * {@code fly-max-duration-warning-seconds} remaining (via
     * {@link #warnedThisCycle}, so it's never repeated for the same
     * budget cycle), then forces flight off and starts the long
     * {@link #lockoutStore} cooldown once the budget is fully exhausted.
     */
    private void tickFlightDuration() {
        long maxMillis = config.flyMaxDurationMillis();
        if (maxMillis <= 0 || activeFly.isEmpty()) {
            return;
        }

        long warningMillis = config.flyMaxDurationWarningSeconds() * 1000L;

        for (UUID uuid : new ArrayList<>(activeFly)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }

            long used = flyTimeUsedMillis.merge(uuid, 1000L, Long::sum);
            long remaining = maxMillis - used;

            if (remaining <= 0) {
                player.setFlying(false);
                player.setAllowFlight(false);
                activeFly.remove(uuid);
                flyTimeUsedMillis.remove(uuid);
                warnedThisCycle.remove(uuid);

                long cooldownMillis = config.flyMaxDurationCooldownMillis();
                lockoutStore.start(uuid, cooldownMillis);

                player.sendMessage(messages.get("fly.max-duration-reached", "time", DurationFormat.format((cooldownMillis / 1000) + 1)));
                continue;
            }

            if (warningMillis > 0 && remaining <= warningMillis && warnedThisCycle.add(uuid)) {
                player.sendMessage(messages.get("fly.max-duration-warning", "time", DurationFormat.format((remaining / 1000) + 1)));
            }
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