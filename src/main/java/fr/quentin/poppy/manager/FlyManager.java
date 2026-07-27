package fr.quentin.poppy.manager;

import fr.quentin.poppy.util.CooldownStore;
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

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Backs /fly: disables flight the moment a flying (or fly-enabled) player
 * takes any damage, and blocks /fly for {@code fly-lockout-seconds}
 * afterward via a shared {@link CooldownStore}.
 *
 * <p><b>Only ever acts on flight this class itself granted.</b> Every
 * place that would disable a player's flight — {@link #disableFlightAndLock}
 * on damage, {@link #onWorldChange} entering the End — gates on
 * {@link #activeFly} first, not on the player's raw
 * {@link Player#getAllowFlight()}/{@link Player#isFlying()} state. Without
 * that gate, Poppy would cut the flight of any Survival/Adventure player
 * regardless of who granted it: a VIP rank's flight perk, a spawn-area
 * flight zone, a staff mode, an event plugin — a silent, very hard to
 * diagnose incompatibility for an admin running more than just this
 * plugin.
 *
 * <p>{@link #onJoin} and {@link #onGameModeChange}'s "force allowFlight
 * off if not tracked as active" safety net is the one place that
 * necessarily still can't distinguish Poppy-granted flight from
 * third-party flight — that's the whole point of the check, closing the
 * "disconnect while /fly is on to keep flying forever" persisted-data
 * exploit (see the class-level doc further down). Since that's
 * unavoidably broad, it's gated behind {@code fly-force-disable-on-join}
 * in config.yml (default {@code true}) so a server running another
 * flight-granting plugin can opt out.
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
 * which is also true for Creative/Spectator flight, or flight granted by
 * another plugin. This is what lets {@link #spawnFlightParticles} show a
 * particle ring only for genuine /fly users, visually telling them apart
 * from someone flying for any other reason.
 *
 * <p><b>Rejoin/gamemode desync protection:</b> {@code allowFlight} is part
 * of a player's persisted data, not something Bukkit resets on its own —
 * so a naive quit handler that only forgot {@link #activeFly} without
 * also clearing the actual flight flags would leave a player able to fly
 * for free forever after reconnecting. {@link #onQuit} force-clears both
 * {@code allowFlight} and {@code isFlying} for anyone still in
 * {@link #activeFly} before removing them — this part is always safe,
 * since it only acts on players Poppy itself tracked as flying.
 * {@link #onJoin} additionally re-syncs on every login (if
 * {@code fly-force-disable-on-join} is on): a Survival/Adventure player
 * who isn't tracked in {@link #activeFly} gets {@code allowFlight} forced
 * off. {@link #onGameModeChange} keeps things in sync the other way too:
 * switching to Creative/Spectator drops the player from
 * {@link #activeFly}, and switching back to Survival/Adventure forces
 * {@code allowFlight} off (same opt-out) unless still tracked as active.
 *
 * <p>The post-damage lockout is deliberately <b>not</b> cleared on quit —
 * same reasoning as {@code FeedCommand}/{@code HealCommand}: clearing it
 * would let a player dodge the wait by disconnecting and reconnecting.
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
    private final CooldownStore lockoutStore;

    private final Set<UUID> activeFly = new HashSet<>();

    public FlyManager(JavaPlugin plugin, Messages messages, PoppyConfig config, CombatManager combatManager) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.combatManager = combatManager;
        this.lockoutStore = new CooldownStore(plugin);

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
     * post-damage lockout, or an active PvP combat tag.
     */
    public boolean isLocked(UUID uuid) {
        return lockoutStore.isActive(uuid) || combatManager.isInCombat(uuid);
    }

    /**
     * The longer of the player's own lockout and their remaining PvP
     * combat-tag time — used for the /fly denial message so it always
     * shows the actual wait, whichever system is currently the binding one.
     */
    public long displayRemainingSeconds(UUID uuid) {
        return Math.max(lockoutStore.remainingSeconds(uuid), combatManager.remainingSeconds(uuid));
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

    /**
     * Only forces {@code allowFlight} off if {@code fly-force-disable-on-join}
     * is enabled — see the class-level doc for why this specific safety
     * net can't distinguish Poppy-granted flight from third-party flight,
     * and why it's opt-out rather than always-on.
     */
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

            // Switching into Survival/Adventure — same opt-out as onJoin, and the
            // same reasoning: this can't tell Poppy-granted flight apart from
            // flight another plugin might legitimately want active here.
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
    }

    /**
     * @param target the entity the player just attacked, for the
     *               notification message — null when called from the
     *               generic {@link #onDamage} path.
     */
    private void disableFlightAndLock(Player player, Entity target) {
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return;
        }

        // Only act on flight this class itself granted — see the class-level
        // doc. A player flying via another plugin's own permission/perk is
        // left entirely alone here.
        if (!activeFly.contains(player.getUniqueId())) {
            return;
        }

        player.setFlying(false);
        player.setAllowFlight(false);
        activeFly.remove(player.getUniqueId());

        lockoutStore.start(player.getUniqueId(), config.flyLockoutMillis());

        if (target != null) {
            player.sendMessage(messages.get("fly.disabled-attack", "target", targetName(target)));
        } else {
            player.sendMessage(messages.get("fly.disabled-damage"));
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