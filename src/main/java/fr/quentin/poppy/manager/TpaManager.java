package fr.quentin.poppy.manager;

import fr.quentin.poppy.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks pending /tpa and /tpahere requests. A target can have at most one
 * pending request per requester (a new request from the same requester
 * replaces the old one and resets its expiry timer); different requesters
 * can have independent pending requests to the same target at once, which
 * is why /tpaccept and /tpadeny both take a player name argument.
 *
 * <p>Requests expire automatically after {@code tpa-expiry-seconds}
 * (config.yml), notifying both sides in {@code &4} — see {@link #expire}.
 * Manual removals (accept/deny/cancel) go through {@link #removeRequest}
 * directly and send their own messages from the calling command instead.
 *
 * <p>{@link #removePlayer(UUID)} must be called on quit — see
 * {@link TpaQuitListener} — for both directions: as a target (their
 * incoming requests) and as a requester (their outgoing requests to
 * others), so no stale request survives a disconnect.
 */
public class TpaManager {

    public enum Type { NORMAL, HERE }

    public record Request(UUID requester, Type type) {
    }

    private final JavaPlugin plugin;
    private final Messages messages;
    private final long expirySeconds;

    private final Map<UUID, Map<UUID, Request>> requestsByTarget = new HashMap<>();
    private final Map<UUID, Map<UUID, BukkitTask>> expiryTasks = new HashMap<>();

    public TpaManager(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.expirySeconds = Math.max(5, plugin.getConfig().getInt("tpa-expiry-seconds", 60));
    }

    public void createRequest(UUID target, UUID requester, Type type) {
        cancelExpiry(target, requester);

        requestsByTarget.computeIfAbsent(target, key -> new HashMap<>()).put(requester, new Request(requester, type));

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> expire(target, requester), expirySeconds * 20L);
        expiryTasks.computeIfAbsent(target, key -> new HashMap<>()).put(requester, task);
    }

    /**
     * Looks up a pending request to {@code target} by the requester's current
     * name (case-insensitive) — names rather than UUIDs because that's what
     * /tpaccept and /tpadeny take as an argument.
     */
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

    /**
     * Looks up the target of a pending outgoing request from {@code requester},
     * by the target's current name — used by /tpacancel.
     */
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

    /**
     * Suggests requester names for tab-completion on /tpaccept and
     * /tpadeny: only players who currently have a pending request to
     * {@code target}, mirroring {@link HomeManager#suggestHomeNames}.
     */
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

    /**
     * Suggests target names for tab-completion on /tpacancel: only players
     * this sender currently has an outgoing pending request to.
     */
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

    /**
     * Called when a request's scheduled timeout fires: notifies both the
     * requester and the target (if still online) that the request expired,
     * then removes it. Never called for manual removals (accept/deny/cancel),
     * which each send their own, different messages.
     */
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

    /**
     * Removes every request where this player appears as either target or
     * requester.
     */
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
}