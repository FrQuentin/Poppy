package fr.quentin.poppy.manager.fly;

import fr.quentin.poppy.manager.combat.CombatManager;
import fr.quentin.poppy.util.DurationFormat;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.cooldown.CooldownStore;
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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
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
 * <p><b>Only ever usable in Survival.</b> {@link #toggle} rejects turning
 * flight ON in any other gamemode ({@link ToggleResult#WRONG_GAMEMODE}) —
 * Creative and Spectator already have their own native flight, and
 * Adventure was never meant to have it either. Without this check,
 * {@code toggle()} used to have no gamemode gate at all: only
 * {@link #disableFlightAndLock} (the post-damage auto-disable) checked
 * the player's mode, so a Creative or Spectator player typing /fly was
 * silently added to {@link #activeFly} — wrongly pulling them into the
 * particle effect, the flight-time budget, and the lockout system for a
 * flight state that was never actually granted by this class. Turning
 * flight OFF is still always allowed regardless of current gamemode (a
 * player who was flying in Survival and got switched to Creative by an
 * admin shouldn't get stuck unable to toggle it back).
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
 * {@link #activeFly}) drains it 1 second per second, via the per-tick
 * increments in {@link #tickFlightDuration}. NOT flying regenerates it —
 * also 1 second per second — computed lazily from wall-clock time via
 * {@link #computeCurrentUsedMillis} rather than a separate ticking task,
 * so it keeps regenerating even while the player is offline. Once fully
 * drained, {@code /fly} is blocked until enough has regenerated (see
 * {@link ToggleResult#NO_BUDGET}).
 *
 * <p>The budget must never reset for free — a relog, or a short
 * post-damage lockout, must not restore it (see {@link #disableFlightAndLock}
 * and {@link #onQuit}, which both freeze the current value rather than
 * clearing it) — and it must not leak memory for players who log out and
 * never return ({@link #purgeStaleBudgets}).
 *
 * <p>{@link #remainingFlightBudgetSeconds} returns 0 whenever
 * {@link #isLocked} is true — a display choice, not a reflection of the
 * underlying budget, which is preserved under a lockout and becomes
 * visible again once it ends.
 *
 * <p>Fall damage never triggers a lockout. Flight can't be enabled while
 * in the End, and is force-disabled if an already-flying player ends up
 * there anyway.
 */
public class FlyManager implements Listener {

    public enum ToggleResult { ENABLED, DISABLED, BLOCKED_END, NO_BUDGET, WRONG_GAMEMODE }

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
            if (currentUsed >= maxMillis) {
                return ToggleResult.NO_BUDGET;
            }

            if (currentUsed <= 0) {
                flightBudgets.remove(uuid);
            } else {
                flightBudgets.put(uuid, new FlightBudget(currentUsed, now));
            }
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

            // Switching to Adventure or Survival while Poppy wasn't tracking active
            // flight for this player — force allowFlight off (opt-out below), same
            // reasoning as onJoin. If switching specifically INTO Survival while
            // active flight is tracked, nothing to do: flight was already only ever
            // grantable in Survival to begin with.
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

    private void freezeBudget(UUID uuid) {
        FlightBudget budget = flightBudgets.get(uuid);
        if (budget != null) {
            flightBudgets.put(uuid, new FlightBudget(budget.usedMillis(), System.currentTimeMillis()));
        }
    }

    /**
     * {@link #onDamage} never sees this: it explicitly ignores
     * {@link org.bukkit.event.entity.EntityDamageEvent.DamageCause#FALL},
     * and fall damage is precisely how a /fly user is most likely to die —
     * cutting flight mid-air (a simple double-tap of space) puts them into
     * ordinary free fall, no command involved. Without this handler,
     * {@link #activeFly} kept the UUID forever after such a death: the
     * server itself resets {@code allowFlight} on Survival respawn, so
     * Poppy's internal state ("this player is actively flying") and the
     * player's real state ("standing on the ground, can't fly") diverged
     * permanently. The visible symptoms were compounding — the budget kept
     * draining a full second per second while the player just walked around
     * post-respawn (see {@link #tickFlightDuration}, which only checks
     * {@code activeFly}/online, never {@code isFlying()}), eventually
     * "running out" of flight time despite never having taken off again;
     * and {@code /fly} returned {@code DISABLED} on the very next press
     * (the {@code activeFly.contains} branch in {@link #toggle}), requiring
     * two presses to actually take off.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(@NonNull PlayerDeathEvent event) {
        endFlightSession(event.getEntity().getUniqueId());
    }

    /**
     * Companion to {@link #onDeath} — ends the tracked session (redundant
     * with {@link #onDeath} in the common case, but a safety net for any
     * path a death could be missed) and, after a one-tick delay, re-syncs
     * {@code allowFlight} the same way {@link #onJoin} does. The delay
     * matters: the server re-applies the respawning player's attributes
     * during the respawn dispatch itself, so a {@code setAllowFlight} call
     * made synchronously here would just get overwritten.
     */
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

    /**
     * Single point where a flight session ends outside the normal toggle-off
     * path: freezes the budget at its current value (regeneration starts
     * counting from now) and clears any pending {@link #warnedThisCycle}
     * flag — always, regardless of whether the player was actually being
     * tracked in {@link #activeFly}, since a caller like {@link #onQuit} may
     * call this defensively without knowing in advance. Without this single
     * point of truth, "end a flight session" used to be written six times
     * slightly differently across the class — one of those variants
     * (regenerating the budget back to zero in {@link #toggle}) removed the
     * player's {@link #flightBudgets} entry without ever touching
     * {@link #warnedThisCycle}, permanently orphaning that UUID in the set:
     * the warning could then never fire again for that player, and the set
     * itself grew by one entry per unique player on the server with no purge
     * until a restart.
     */
    private void endFlightSession(UUID uuid) {
        if (activeFly.remove(uuid)) {
            freezeBudget(uuid);
        }
        warnedThisCycle.remove(uuid);
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
                warnedThisCycle.remove(uuid);

                player.sendMessage(messages.get("fly.max-duration-reached", "time", DurationFormat.format(maxMillis / 1000)));
                continue;
            }

            flightBudgets.put(uuid, new FlightBudget(used, now));

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