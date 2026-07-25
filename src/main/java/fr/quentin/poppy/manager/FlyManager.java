package fr.quentin.poppy.manager;

import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Backs /fly: disables flight the moment a flying (or fly-enabled) player
 * takes any damage, and blocks /fly for {@code fly-lockout-seconds}
 * afterward via its own {@link #lockoutUntil} timer — long enough to deal
 * with whatever hit them before flight is available again.
 *
 * <p>Integrated with {@link CombatManager}: {@link #isLocked} also treats
 * an active PvP combat tag as a lockout, not just this class's own timer.
 * That matters because {@link #onDamage} (generic {@link EntityDamageEvent})
 * only ever fires for whichever entity <b>received</b> the damage — on its
 * own, an attacker landing hits (but never taking one back) could keep
 * flying indefinitely. {@link #onPvpDamage} closes that gap by also
 * grounding the resolved attacker on every hit they land (PvP or PvE), the
 * same attacker resolution {@link CombatListener} uses for tagging — and
 * tells them who/what they just attacked, since they may not otherwise
 * notice their flight got cut mid-swing.
 *
 * <p>Fall damage never triggers this: turning /fly off mid-air naturally
 * causes fall damage on landing, which is the player's own doing, not
 * combat, and shouldn't relock them right after they voluntarily grounded
 * themselves.
 *
 * <p>Only touches Survival/Adventure players. Creative and Spectator
 * already have their own native flight tied to the gamemode itself; this
 * system never interacts with that.
 *
 * <p>The lockout is deliberately <b>not</b> cleared on quit — same
 * reasoning as {@code FeedCommand}/{@code HealCommand}: clearing it would
 * let a player dodge the wait entirely by disconnecting and reconnecting
 * right after taking damage.
 */
public class FlyManager implements Listener {

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyConfig config;
    private final CombatManager combatManager;

    private final Map<UUID, Long> lockoutUntil = new HashMap<>();

    public FlyManager(JavaPlugin plugin, Messages messages, PoppyConfig config, CombatManager combatManager) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.combatManager = combatManager;
    }

    /**
     * @return true if flight was toggled on, false if toggled off
     */
    public boolean toggle(Player player) {
        boolean nowFlying = !player.getAllowFlight();
        player.setAllowFlight(nowFlying);
        player.setFlying(nowFlying);
        return nowFlying;
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
                // Turning off /fly mid-air naturally causes fall damage on landing —
                // that's the player's own doing, not combat, so it shouldn't relock them.
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

    /**
     * Fires on the same event {@link CombatListener} tags PvP combat from —
     * this specifically grounds the attacker, which the generic
     * {@link #onDamage} above never touches since it only reacts to the
     * entity receiving damage. Also covers PvE (attacking a mob), not just
     * PvP, since "who/what got attacked" is passed straight to the target
     * for the notification message.
     */
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

    private String targetName(Entity target) {
        if (target instanceof Player targetPlayer) {
            return targetPlayer.getName();
        }
        return formatEntityTypeName(target.getType());
    }

    /**
     * Converts a vanilla entity type constant into a readable name, e.g.
     * {@code WITHER_SKELETON} → "Wither Skeleton" — same approach as
     * {@code SilkSpawnerListener#formatName}, works for any entity type
     * without a hand-maintained name list.
     */
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