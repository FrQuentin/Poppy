package fr.quentin.poppy.manager;

import fr.quentin.poppy.util.CooldownStore;
import fr.quentin.poppy.util.Messages;
import fr.quentin.poppy.util.PoppyConfig;
import fr.quentin.poppy.util.PoppyLogger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Tracks pending /tpa and /tpahere requests, the {@code /tpatoggle}
 * opt-out ({@link #requestsDisabled}), and per-requester cooldown (via a
 * shared {@link CooldownStore}).
 *
 * <p>{@link #requestsDisabled} is persisted to {@code tpatoggle.yml} —
 * unlike a plain cooldown, this is a deliberate, durable player
 * preference, often used specifically to opt out of harassment via
 * repeated teleport requests. Losing it silently on every server restart
 * would mean a player relying on it for that reason has their protection
 * quietly disabled without ever being told. Written synchronously (a
 * small file, changed rarely — only on an actual {@code /tpatoggle}
 * command, not a hot path) via {@link fr.quentin.poppy.util.AtomicYamlWriter}.
 */
public class TpaManager {

    public enum Type { NORMAL, HERE }

    public record Request(UUID requester, Type type) {
    }

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PoppyConfig config;
    private final PoppyLogger logger;
    private final File toggleFile;

    private final Map<UUID, Map<UUID, Request>> requestsByTarget = new HashMap<>();
    private final Map<UUID, Map<UUID, BukkitTask>> expiryTasks = new HashMap<>();
    private final Set<UUID> requestsDisabled = new HashSet<>();
    private final CooldownStore requestCooldown;

    public TpaManager(JavaPlugin plugin, Messages messages, PoppyConfig config, PoppyLogger logger) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.logger = logger;
        this.requestCooldown = new CooldownStore(plugin);
        this.toggleFile = new File(plugin.getDataFolder(), "tpatoggle.yml");

        loadDisabledRequests();
    }

    public void createRequest(UUID target, UUID requester, Type type) {
        cancelExpiry(target, requester);

        requestsByTarget.computeIfAbsent(target, key -> new HashMap<>()).put(requester, new Request(requester, type));

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> expire(target, requester), config.tpaExpirySeconds() * 20L);
        expiryTasks.computeIfAbsent(target, key -> new HashMap<>()).put(requester, task);
    }

    public Request findRequestByName(UUID target, String requesterName) {
        Map<UUID, Request> requests = requestsByTarget.get(target);
        if (requests == null) {
            return null;
        }

        for (Request request : requests.values()) {
            Player requesterPlayer = Bukkit.getPlayer(request.requester());
            if (requesterPlayer != null && requesterPlayer.getName().equalsIgnoreCase(requesterName)) {
                return request;
            }
        }

        return null;
    }

    public Player findOutgoingTargetByName(UUID requester, String targetName) {
        for (Map.Entry<UUID, Map<UUID, Request>> entry : requestsByTarget.entrySet()) {
            if (!entry.getValue().containsKey(requester)) {
                continue;
            }
            Player targetPlayer = Bukkit.getPlayer(entry.getKey());
            if (targetPlayer != null && targetPlayer.getName().equalsIgnoreCase(targetName)) {
                return targetPlayer;
            }
        }
        return null;
    }

    public List<String> pendingRequesterNames(UUID target, String prefix) {
        Map<UUID, Request> requests = requestsByTarget.get(target);
        if (requests == null) {
            return List.of();
        }

        String partial = prefix.toLowerCase();
        List<String> names = new ArrayList<>();
        for (UUID requesterUuid : requests.keySet()) {
            Player requester = Bukkit.getPlayer(requesterUuid);
            if (requester != null && requester.getName().toLowerCase().startsWith(partial)) {
                names.add(requester.getName());
            }
        }
        return names;
    }

    public List<String> outgoingTargetNames(UUID requester, String prefix) {
        String partial = prefix.toLowerCase();
        List<String> names = new ArrayList<>();

        for (Map.Entry<UUID, Map<UUID, Request>> entry : requestsByTarget.entrySet()) {
            if (!entry.getValue().containsKey(requester)) {
                continue;
            }
            Player targetPlayer = Bukkit.getPlayer(entry.getKey());
            if (targetPlayer != null && targetPlayer.getName().toLowerCase().startsWith(partial)) {
                names.add(targetPlayer.getName());
            }
        }

        return names;
    }

    public void removeRequest(UUID target, UUID requester) {
        Map<UUID, Request> requests = requestsByTarget.get(target);
        if (requests != null) {
            requests.remove(requester);
            if (requests.isEmpty()) {
                requestsByTarget.remove(target);
            }
        }
        cancelExpiry(target, requester);
    }

    private void expire(UUID target, UUID requester) {
        Map<UUID, Request> requests = requestsByTarget.get(target);
        if (requests == null || !requests.containsKey(requester)) {
            return;
        }

        Player targetPlayer = Bukkit.getPlayer(target);
        Player requesterPlayer = Bukkit.getPlayer(requester);

        if (requesterPlayer != null && targetPlayer != null) {
            requesterPlayer.sendMessage(messages.get("tpa.expired-requester", "player", targetPlayer.getName()));
        }
        if (targetPlayer != null && requesterPlayer != null) {
            targetPlayer.sendMessage(messages.get("tpa.expired-target", "player", requesterPlayer.getName()));
        }

        if (requesterPlayer != null && targetPlayer != null) {
            logger.log(PoppyLogger.Category.TPA, requesterPlayer, "request to " + targetPlayer.getName() + " expired");
        }

        removeRequest(target, requester);
    }

    private void cancelExpiry(UUID target, UUID requester) {
        Map<UUID, BukkitTask> tasks = expiryTasks.get(target);
        if (tasks == null) {
            return;
        }

        BukkitTask task = tasks.remove(requester);
        if (task != null) {
            task.cancel();
        }
        if (tasks.isEmpty()) {
            expiryTasks.remove(target);
        }
    }

    public void removePlayer(UUID uuid) {
        Map<UUID, BukkitTask> tasksAsTarget = expiryTasks.remove(uuid);
        if (tasksAsTarget != null) {
            tasksAsTarget.values().forEach(BukkitTask::cancel);
        }
        requestsByTarget.remove(uuid);

        List<UUID> otherTargets = new ArrayList<>(requestsByTarget.keySet());
        for (UUID target : otherTargets) {
            removeRequest(target, uuid);
        }
    }

    /**
     * @return true if requests are now accepted, false if now blocked
     */
    public boolean toggleRequests(UUID uuid) {
        boolean nowAccepting;
        if (requestsDisabled.remove(uuid)) {
            nowAccepting = true;
        } else {
            requestsDisabled.add(uuid);
            nowAccepting = false;
        }

        saveDisabledRequests();
        return nowAccepting;
    }

    public boolean isAcceptingRequests(UUID uuid) {
        return !requestsDisabled.contains(uuid);
    }

    public long requestCooldownRemainingSeconds(UUID uuid) {
        return requestCooldown.remainingSeconds(uuid);
    }

    public void recordRequestSent(UUID uuid) {
        requestCooldown.start(uuid, config.tpaRequestCooldownMillis());
    }

    private void loadDisabledRequests() {
        if (!toggleFile.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(toggleFile);
        for (String uuidString : yaml.getStringList("disabled")) {
            try {
                requestsDisabled.add(UUID.fromString(uuidString));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().log(Level.WARNING, "Skipping an invalid UUID in tpatoggle.yml: " + uuidString);
            }
        }
    }

    /**
     * Small file, changed only on an actual {@code /tpatoggle} command —
     * not a hot path, so a synchronous atomic write here is fine.
     */
    private void saveDisabledRequests() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> uuids = new ArrayList<>();
        for (UUID uuid : requestsDisabled) {
            uuids.add(uuid.toString());
        }
        yaml.set("disabled", uuids);

        fr.quentin.poppy.util.AtomicYamlWriter.save(yaml, toggleFile, plugin, "tpatoggle.yml");
    }
}