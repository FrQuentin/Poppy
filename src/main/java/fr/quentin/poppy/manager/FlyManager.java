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
 * opts out of the one safety net that can't fully avoid touching
 * third-party-granted flight.
 *
 * <p><b>Regenerating flight budget (a stamina bar, not a hard cooldown):</b>
 * {@link #flightBudgets} tracks, per player, how much of
 * {@code fly-max-duration-minutes} is currently used up. Flying (being in
 * {@link #activeFly}) drains it 1 second per second, exactly, via the
 * per-tick increments in {@link #tickFlightDuration}. NOT flying
 * regenerates it — also 1 second per second — computed lazily from wall-clock
 * time via {@link #computeCurrentUsedMillis} rather than a separate ticking
 * task, so it keeps regenerating even while the player is offline, exactly
 * like a stamina bar would. There is no separate fixed "cooldown after
 * hitting the cap" anymore: once the budget is fully drained, {@code /fly}
 * is simply blocked until enough of it has regenerated (see
 * {@link #toggle}'s {@link ToggleResult#NO_BUDGET}) — the wait time is
 * whatever's left to regenerate, shrinking every second, not a flat timer.
 *
 * <p>Two invariants that motivated this design, both closed by earlier
 * fixes and preserved here: the budget must never reset for free (a
 * relog, or a short post-damage lockout, must not restore it — see
 * {@link #disableFlightAndLock} and {@link #onQuit}, which both freeze
 * the current value rather than clearing it), and it must not leak memory
 * for players who log out and never return — {@link #purgeStaleBudgets}
 * removes any entry that's regenerated back to zero, computed the same
 * wall-clock way whether the player is online or not, so no separate
 * retention window is needed.
 *
 * <p>{@link #remainingFlightBudgetSeconds} returns 0 whenever
 * {@link #isLocked} is true (post-damage lockout or an active combat
 * tag) — a display choice, not a reflection of the underlying budget,
 * which is preserved under a lockout and becomes visible again once it
 * ends.
 *
 * <p>Fall damage never triggers a lockout. Flight can't be enabled while
 * in the End, and is force-disabled if an already-flying player ends up
 * there anyway.
 */
public class FlyManager implements Listener {

    public enum ToggleResult { ENABLED, DISABLED, BLOCKED_END, NO_BUDGET }

    /**
     * {@code usedMillis} is only guaranteed exact as of
     * {@code lastUpdateMillis} — see {@link #computeCurrentUsedMillis} for
     * how it's projected forward/backward from there.
     */
    private record FlightBudget(long usedMillis, long lastUpdateMillis) {
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

    public FlyManager(JavaPlugin plugin, Messages messages, PoppyConfig config, CombatManager combatManager) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.combatManager = combatManager;
        this.lockoutStore = new CooldownStore(plugin);

        Bukkit.getScheduler().runTaskTimer(plugin, this::tickParticles, PARTICLE_INTERVAL_TICKS, PARTICLE_INTERVAL_TICKS);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickFlightDuration, DURATION_TICK_INTERVAL_TICKS, DURATION_TICK_INTERVAL_TICKS);
        Bukkit.getScheduler().runTaskTimer(plugin, this::purgeStaleBudgets, BUDGET_PURGE_INTERVAL_TICKS, BUDGET_PURGE_INTERVAL_TICKS);
    }

    /**
     * Toggles flight for the player. Returns {@link ToggleResult#BLOCKED_END}
     * if they're in the End, or {@link ToggleResult#NO_BUDGET} if their
     * flight budget is currently fully drained (nothing has regenerated
     * yet) — turning it off is always allowed regardless of either.
     */
    public ToggleResult toggle(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (activeFly.contains(uuid)) {
            // Freeze the exact budget state at the moment flight stops, so
            // regeneration starts counting from right now, not from whenever
            // the last per-second tick happened to run.
            FlightBudget budget = flightBudgets.get(uuid);
            if (budget != null) {
                flightBudgets.put(uuid, new FlightBudget(budget.usedMillis(), now));
            }
            activeFly.remove(uuid);
            player.setAllowFlight(false);
            player.setFlying(false);
            return ToggleResult.DISABLED;
        }

        if (player.getWorld().getEnvironment() == World.Environment.THE_END) {
            return ToggleResult.BLOCKED_END;
        }

        long maxMillis = config.flyMaxDurationMillis();
        if (maxMillis > 0) {
            long currentUsed = computeCurrentUsedMillis(uuid, now);
            if (currentUsed >= maxMillis) {
                return ToggleResult.NO_BUDGET;
            }

            // Materialize whatever regenerated while not flying, with "now" as
            // the fresh baseline for the upcoming drain.
            if (currentUsed <= 0) {
                flightBudgets.remove(uuid);
            } else {
                flightBudgets.put(uuid, new FlightBudget(currentUsed, now));
            }
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
     * Seconds remaining in the player's flight budget right now. Returns
     * -1 if {@code fly-max-duration-minutes} is 0 (no limit). Returns 0
     * whenever {@link #isLocked} is true — see the class-level doc.
     */
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

    /**
     * Seconds until the budget is fully drained back to a usable state
     * again (i.e. any amount above zero) — used for the "you're out of
     * flight time" message. Since regeneration is exactly 1:1 with real
     * time, this is just the currently-used amount.
     */
    public long secondsUntilAnyBudget(UUID uuid) {
        long used = computeCurrentUsedMillis(uuid, System.currentTimeMillis());
        return used <= 0 ? 0 : (used / 1000) + 1;
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

            freezeBudget(uuid);
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
                if (activeFly.contains(uuid)) {
                    freezeBudget(uuid);
                }
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
            freezeBudget(uuid);
            player.setAllowFlight(false);
            player.setFlying(false);
        }
        // No further budget cleanup here — regeneration is computed lazily
        // from wall-clock time and correctly continues (or stays put) whether
        // the player is online or not. purgeStaleBudgets handles eventually
        // forgetting a fully-regenerated entry.
    }

    /**
     * Freezes a player's stored budget at its current exact value with
     * {@code lastUpdateMillis = now} — used whenever flight stops for a
     * reason other than the player's own {@link #toggle} call (damage,
     * quitting, entering the End, switching gamemode), so regeneration
     * starts counting from this precise moment rather than from
     * whenever the last per-second tick happened to run.
     */
    private void freezeBudget(UUID uuid) {
        FlightBudget budget = flightBudgets.get(uuid);
        if (budget != null) {
            flightBudgets.put(uuid, new FlightBudget(budget.usedMillis(), System.currentTimeMillis()));
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
        freezeBudget(player.getUniqueId());
        activeFly.remove(player.getUniqueId());
        // warnedThisCycle deliberately left as-is: the budget itself wasn't
        // reset, just frozen, so a warning already shown for this same
        // remaining amount doesn't need to fire again immediately.

        lockoutStore.start(player.getUniqueId(), lockoutMillis);

        if (target != null && targetPlaceholderName != null) {
            player.sendMessage(messages.get(messagePath, targetPlaceholderName, targetName(target)));
        } else {
            player.sendMessage(messages.get(messagePath));
        }
    }

    /**
     * Runs every second: drains 1 second of budget for everyone currently
     * flying, forcing landing the moment it's fully drained. Regeneration
     * for everyone NOT flying happens lazily instead (see
     * {@link #computeCurrentUsedMillis}), not here — there's nothing to do
     * for them on this tick.
     */
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
                warnedThisCycle.remove(uuid);

                player.sendMessage(messages.get("fly.max-duration-reached",
                        "time", DurationFormat.format((maxMillis / 1000))));
                continue;
            }

            flightBudgets.put(uuid, new FlightBudget(used, now));

            long remaining = maxMillis - used;
            if (warningMillis > 0 && remaining <= warningMillis && warnedThisCycle.add(uuid)) {
                player.sendMessage(messages.get("fly.max-duration-warning", "time", DurationFormat.format((remaining / 1000) + 1)));
            }
        }
    }

    /**
     * Projects a player's stored budget forward to "now": unchanged while
     * they're actively flying (kept exact by {@link #tickFlightDuration}'s
     * per-second increments), or regenerated 1ms per elapsed real
     * millisecond since {@code lastUpdateMillis} while not flying — this
     * is what makes regeneration continue even while the player is
     * offline, exactly like a stamina bar governed by wall-clock time
     * rather than only ticking while logged in.
     */
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

    /**
     * Removes any budget entry that has fully regenerated back to zero —
     * computed the same wall-clock way regardless of whether the player
     * is currently online, so this naturally reclaims memory for a
     * player who logs out and never returns, without needing a separate
     * fixed retention window.
     */
    private void purgeStaleBudgets() {
        long now = System.currentTimeMillis();
        flightBudgets.entrySet().removeIf(entry -> {
            UUID uuid = entry.getKey();
            if (activeFly.contains(uuid)) {
                return false;
            }
            if (computeCurrentUsedMillis(uuid, now) > 0) {
                return false;
            }
            warnedThisCycle.remove(uuid);
            return true;
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