package fr.quentin.poppy.manager.treecapitator;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks which currently-standing log/stem blocks were placed by a
 * player rather than grown naturally — {@code TreeCapitatorListener}
 * must never chain onto these, or someone felling an unrelated natural
 * tree nearby could chop down a player's wooden build by accident.
 *
 * <p>In-memory only, populated by {@link #onPlace} since this plugin
 * last started — see the class this is wired from for why that's an
 * accepted tradeoff, not an oversight. Entries are removed on ANY break
 * of that block ({@link #onBreak}, {@code MONITOR}, unconditional) —
 * whether a normal player break, treecapitator's own synthetic event, an
 * explosion, or anything else — so the map never grows for a block
 * that's no longer actually there.
 *
 * <p>Keyed by world UID + packed coordinates rather than a
 * {@link Block}/{@link org.bukkit.Location} directly, to avoid
 * allocating an object per lookup — this is checked once per flood-fill
 * neighbor candidate (up to ~3,300 times per chop), not just once per
 * place/break.
 */
public class PlacedLogManager implements Listener {

    private final Map<UUID, Set<Long>> placedByWorld = new HashMap<>();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(@NonNull BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (!isLog(block.getType())) {
            return;
        }
        placedByWorld.computeIfAbsent(block.getWorld().getUID(), key -> new HashSet<>())
                .add(packCoords(block.getX(), block.getY(), block.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(@NonNull BlockBreakEvent event) {
        Block block = event.getBlock();
        Set<Long> set = placedByWorld.get(block.getWorld().getUID());
        if (set != null) {
            set.remove(packCoords(block.getX(), block.getY(), block.getZ()));
        }
    }

    public boolean isPlacedByPlayer(Block block) {
        Set<Long> set = placedByWorld.get(block.getWorld().getUID());
        return set != null && set.contains(packCoords(block.getX(), block.getY(), block.getZ()));
    }

    private boolean isLog(Material material) {
        return material.name().endsWith("_LOG") || material.name().endsWith("_STEM");
    }

    private static long packCoords(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) ((y + 2048) & 0xFFF) << 26) | ((long) (z & 0x3FFFFFF));
    }
}