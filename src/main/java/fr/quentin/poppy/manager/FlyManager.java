package fr.quentin.poppy.manager;

import fr.quentin.poppy.util.CooldownRegistry;
import fr.quentin.poppy.util.CooldownStore;
import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
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
import java.util.List;
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
 * <p><b>Only ever acts on flight this class itself granted</b> —
 * {@link #disableFlightAndLock}, {@link #onJoin}, {@link #onGameModeChange}
 * all gate on {@link #activeFly}. {@code fly-force-disable-on-join}
 * (default true) opts out of the one safety net that can't fully avoid
 * touching third-party-granted flight (a VIP rank's flight perk, a staff
 * mode, another plugin).
 *
 * <p><b>Cumulative flight time budget:</b> {@link #flightBudgets} tracks,
 * per player, how much total time they've had /fly enabled, alongside the
 * last moment they were actively tracked. Incremented every second by
 * {@link #tickFlightDuration} for anyone in {@link #activeFly}, whether
 * airborne or not. Deliberately cumulative, not per-session — toggling
 * /fly off and back on does NOT reset it; only actually hitting
 * {@code fly-max-duration-minutes} and paying the real
 * {@code fly-max-duration-cooldown-minutes} cooldown does. Two invariant
 * bypasses were found and closed for this exact rule:
 * <ul>
 *   <li>{@link #disableFlightAndLock} (the short post-damage lockout)
 *   used to clear the budget too — trading the long cap cooldown for the
 *   short damage lockout by taking a trivial hit right before the cap.
 *   It no longer touches the budget at all.</li>
 *   <li>{@link #onQuit} used to clear the budget as an anti-memory-leak
 *   measure — once the above was fixed, that turned a 5-second relog into
 *   the cheapest bypass of all, with not even the 30s lockout the first
 *   exploit cost. {@link #purgeStaleBudgets}, run every 5 minutes, closes
 *   this without reintroducing the leak: each entry is only evicted after
 *   being inactive for at least {@code fly-max-duration-cooldown-minutes}
 *   — a player offline that long has, in effect, already paid the
 *   cooldown, so there's nothing left to protect by keeping the entry.</li>
 * </ul>
 *
 * <p>{@link #remainingFlightBudgetSeconds} returns 0 whenever
 * {@link #isLocked} is true — a display choice ("you can't fly right
 * now"), not a reflection of the underlying budget, which is preserved
 * under a lockout and becomes visible again once it ends.
 *
 * <p>Registered in {@link CooldownRegistry} via
 * {@code registerCustom("Fly", this::displayRemainingSeconds)} rather
 * than exposing {@link #lockoutStore} directly — {@code displayRemainingSeconds}
 * already combines the lockout with an active combat tag, which
 * {@code /fly}/{@code /flytime} also show; registering the raw store
 * alone would have let {@code /cooldowns} claim "Fly: Ready" while a
 * player was actually blocked purely by their combat tag.
 *
 * <p>Fall damage never triggers a lockout. Flight can't be enabled while
 * in the End, and is force-disabled if an already-flying player ends up
 * there anyway.
 */
public class FlyManager implements Listener {

    public enum ToggleResult { ENABLED, DISABLED, BLOCKED_END }

    private record FlightBudget(long usedMillis, long lastActiveMillis) {
    }

    private static final long PARTICLE_INTERVAL_TICKS = 4L;
    private static final double PARTICLE_RADIUS = 0.6;
    private static final int PARTICLE_POINTS = 8;
    private static final long DURATION_TICK_INTERVAL_TICKS = 20L;
    private static final long BUDGET_PURGE_INTERVAL_TICKS = 20L * 60 * 5;

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyConfig config;
    private final CombatManager combatManager;
    private final CooldownStore lockoutStore;

    private final Set<UUID> activeFly = new HashSet<>();
    private final Map<UUID, FlightBudget> flightBudgets = new HashMap<>();
    private final Set<UUID> warnedThisCycle = new HashSet<>();

    public FlyManager(JavaPlugin plugin, Messages messages, PoppyConfig config, CombatManager combatManager, CooldownRegistry registry) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.combatManager = combatManager;
        this.lockoutStore = new CooldownStore(plugin);
        registry.registerCustom("Fly", this::displayRemainingSeconds);

        Bukkit.getScheduler().runTaskTimer(plugin, this::tickParticles, PARTICLE_INTERVAL_TICKS, PARTICLE_INTERVAL_TICKS);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickFlightDuration, DURATION_TICK_INTERVAL_TICKS, DURATION_TICK_INTERVAL_TICKS);
        Bukkit.getScheduler().runTaskTimer(plugin, this::purgeStaleBudgets, BUDGET_PURGE_INTERVAL_TICKS, BUDGET_PURGE_INTERVAL_TICKS);
    }

    /**
     * Toggles flight for the player. Returns {@link ToggleResult#BLOCKED_END}
     * without changing anything if they're currently in the End and trying
     * to turn flight on — turning it off is always allowed regardless of
     * world.
     */
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

    /**
     * Whether /fly is currently blocked for this player — either their own
     * post-damage/max-duration lockout, or an active PvP combat tag.
     */
    public boolean isLocked(UUID uuid) {
        return lockoutStore.isActive(uuid) || combatManager.isInCombat(uuid);
    }

    /**
     * The longer of the player's own lockout and their remaining PvP
     * combat-tag time.
     */
    public long displayRemainingSeconds(UUID uuid) {
        return Math.max(lockoutStore.remainingSeconds(uuid), combatManager.remainingSeconds(uuid));
    }

    /**
     * Seconds remaining in the player's cumulative flight budget. Returns
     * -1 if {@code fly-max-duration-minutes} is 0 (no limit configured).
     * Returns 0 whenever {@link #isLocked} is true — see the class-level
     * doc: this is purely a display choice, the real budget underneath is
     * preserved and reappears once the lockout ends.
     */
    public long remainingFlightBudgetSeconds(UUID uuid) {
        long maxMillis = config.flyMaxDurationMillis();
        if (maxMillis <= 0) {
            return -1;
        }

        if (isLocked(uuid)) {
            return 0;
        }

        FlightBudget budget = flightBudgets.get(uuid);
        long used = budget != null ? budget.usedMillis() : 0L;
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

    /**
     * {@link #flightBudgets} is deliberately NOT cleared here — see the
     * class-level doc and {@link #purgeStaleBudgets} for why clearing it
     * on quit made a relog the cheapest bypass of {@code fly-max-duration}.
     */
    @EventHandler
    public void onQuit(@NonNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (activeFly.remove(uuid)) {
            player.setAllowFlight(false);
            player.setFlying(false);
        }
    }

    /**
     * @param target the entity the player just attacked, for the
     *               notification message — null when called from the
     *               generic {@link #onDamage} path.
     */
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
        // flightBudgets and warnedThisCycle are DELIBERATELY left untouched here
        // — the cumulative budget only resets by actually paying the real cap
        // cooldown (see tickFlightDuration), never by this short post-damage
        // lockout. See the class-level doc.

        lockoutStore.start(player.getUniqueId(), lockoutMillis);

        if (target != null && targetPlaceholderName != null) {
            player.sendMessage(messages.get(messagePath, targetPlaceholderName, targetName(target)));
        } else {
            player.sendMessage(messages.get(messagePath));
        }
    }

    /**
     * Runs every second, adding one second of usage for every player
     * currently in {@link #activeFly} and refreshing their
     * {@link FlightBudget#lastActiveMillis()}. Sends a one-shot warning at
     * {@code fly-max-duration-warning-seconds} remaining, then forces
     * flight off and starts the long {@link #lockoutStore} cooldown once
     * the budget is fully exhausted.
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

            long now = System.currentTimeMillis();
            FlightBudget budget = flightBudgets.merge(uuid,
                    new FlightBudget(1000L, now),
                    (old, add) -> new FlightBudget(old.usedMillis() + 1000L, now));
            long used = budget.usedMillis();
            long remaining = maxMillis - used;

            if (remaining <= 0) {
                player.setFlying(false);
                player.setAllowFlight(false);
                activeFly.remove(uuid);
                flightBudgets.remove(uuid);
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

    /**
     * The budget is no longer cleared on quit — that was the anti-memory-leak
     * measure, but once the post-damage lockout stopped clearing the budget,
     * a simple relog became the cheapest way to reset fly-max-duration — the
     * same invariant bypassed through a third door. Instead, each entry
     * carries its own last-active timestamp and is only purged after an
     * absence at least as long as the cap's own cooldown: a player who's
     * been offline that long has, in effect, already paid it.
     */
    private void purgeStaleBudgets() {
        long retentionMillis = Math.max(config.flyMaxDurationCooldownMillis(), 60_000L);
        long cutoff = System.currentTimeMillis() - retentionMillis;
        flightBudgets.entrySet().removeIf(entry -> {
            boolean stale = entry.getValue().lastActiveMillis() < cutoff;
            if (stale) {
                warnedThisCycle.remove(entry.getKey());
            }
            return stale;
        });
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