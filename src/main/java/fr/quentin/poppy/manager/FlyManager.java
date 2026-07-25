package fr.quentin.poppy.manager;

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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.jspecify.annotations.NonNull;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Backs /fly: disables flight the moment a flying (or fly-enabled) player
 * takes any damage, and blocks /fly for {@code fly-lockout-seconds}
 * afterward via its own {@link #lockoutUntil} timer.
 *
 * <p>Integrated with {@link CombatManager}: {@link #isLocked} also treats
 * an active PvP combat tag as a lockout. {@link #onPvpDamage} grounds the
 * attacker on every hit they land (PvP or PvE), which the generic
 * {@link #onDamage} (only reacts to whoever received the damage) never
 * covers on its own, and tells them who/what they just attacked.
 *
 * <p>Fall damage never triggers this: turning /fly off mid-air naturally
 * causes fall damage on landing, which shouldn't relock the player right
 * after they voluntarily grounded themselves.
 *
 * <p>Flight can't be enabled while in the End — see {@link #toggle} — and
 * is force-disabled if an already-flying player ends up there anyway (an
 * End portal, an ender pearl thrown while flying, etc. — see
 * {@link #onWorldChange}), since free flight trivializes finding End
 * cities/elytras. Not restricted in the Overworld or Nether.
 *
 * <p>{@link #activeFly} tracks who currently has flight active
 * specifically via this system — distinct from {@link Player#isFlying()},
 * which is also true for Creative/Spectator flight. This is what lets
 * {@link #spawnFlightParticles} show a particle ring only for genuine
 * /fly users, visually telling them apart from someone flying because of
 * their gamemode.
 *
 * <p>The post-damage lockout timer is deliberately <b>not</b> cleared on
 * quit — same reasoning as {@code FeedCommand}/{@code HealCommand}:
 * clearing it would let a player dodge the wait by disconnecting and
 * reconnecting. {@link #activeFly} itself IS cleared on quit (see
 * {@link #onQuit}), since Bukkit resets actual flight state on rejoin
 * anyway and there's no reason to keep tracking an offline player as
 * "currently flying".
 */
public class FlyManager implements Listener {

    public enum ToggleResult { ENABLED, DISABLED, BLOCKED_END }

    private static final long PARTICLE_INTERVAL_TICKS = 4L;
    private static final double PARTICLE_RADIUS = 0.6;
    private static final int PARTICLE_POINTS = 8;

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyConfig config;
    private final CombatManager combatManager;

    private final Map<UUID, Long> lockoutUntil = new HashMap<>();
    private final Set<UUID> activeFly = new HashSet<>();

    public FlyManager(JavaPlugin plugin, Messages messages, PoppyConfig config, CombatManager combatManager) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.combatManager = combatManager;

        Bukkit.getScheduler().runTaskTimer(plugin, this::tickParticles, PARTICLE_INTERVAL_TICKS, PARTICLE_INTERVAL_TICKS);
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
     * post-damage lockout timer, or an active PvP combat tag.
     */
    public boolean isLocked(UUID uuid) {
        return lockoutRemainingSeconds(uuid) > 0 || combatManager.isInCombat(uuid);
    }

    /**
     * The longer of the player's own lockout timer and their remaining
     * PvP combat-tag time — used for the /fly denial message so it always
     * shows the actual wait, whichever system is currently the binding one.
     */
    public long displayRemainingSeconds(UUID uuid) {
        return Math.max(lockoutRemainingSeconds(uuid), combatManager.remainingSeconds(uuid));
    }

    private long lockoutRemainingSeconds(UUID uuid) {
        Long until = lockoutUntil.get(uuid);
        if (until == null) {
            return 0;
        }

        long remainingMillis = until - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            lockoutUntil.remove(uuid);
            return 0;
        }

        return (remainingMillis / 1000) + 1;
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

            disableFlightAndLock(player, null);
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

            disableFlightAndLock(attacker, event.getEntity());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error in FlyManager#onPvpDamage", e);
        }
    }

    /**
     * Force-disables flight if an active /fly user ends up in the End by
     * any means other than the /fly command itself (portal, ender pearl
     * while flying, etc.) — {@link #toggle} alone only stops them from
     * turning it on there, not from arriving there already flying.
     */
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
    public void onQuit(@NonNull PlayerQuitEvent event) {
        activeFly.remove(event.getPlayer().getUniqueId());
    }

    /**
     * @param target the entity the player just attacked, for the
     *               notification message — null when called from the
     *               generic {@link #onDamage} path, where the player is
     *               the one who got hit rather than the one attacking.
     */
    private void disableFlightAndLock(Player player, Entity target) {
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return;
        }

        boolean wasFlyEnabled = player.getAllowFlight() || player.isFlying();
        if (!wasFlyEnabled) {
            return;
        }

        player.setFlying(false);
        player.setAllowFlight(false);
        activeFly.remove(player.getUniqueId());

        long lockoutMillis = config.flyLockoutMillis();
        if (lockoutMillis > 0) {
            lockoutUntil.put(player.getUniqueId(), System.currentTimeMillis() + lockoutMillis);
        }

        if (target != null) {
            player.sendMessage(messages.get("fly.disabled-attack", "target", targetName(target)));
        } else {
            player.sendMessage(messages.get("fly.disabled-damage"));
        }
    }

    /**
     * Draws a small ring of particles at each active /fly user's feet,
     * only while they're actually airborne ({@link Player#isFlying()}) —
     * grounded-but-toggled-on doesn't need the visual. Skipped entirely
     * if {@code fly-particles-enabled} is off.
     */
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
            if (builder.length() > 0) {
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