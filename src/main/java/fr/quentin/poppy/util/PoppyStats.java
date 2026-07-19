package fr.quentin.poppy.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Session-only counters displayed in the startup/shutdown console banners
 * (see {@code Poppy#logStartupBanner} / {@code Poppy#logShutdownSummary}).
 * Nothing here is persisted to disk — counts reset to zero on every restart.
 *
 * <p>Backed by {@link AtomicLong} even though every current increment call
 * happens on the main thread: cheap insurance in case a future stat gets
 * incremented from an async task (e.g. during a disk I/O callback).
 */
public final class PoppyStats {

    private final AtomicLong homesCreated = new AtomicLong();
    private final AtomicLong homesDeleted = new AtomicLong();
    private final AtomicLong teleportsPerformed = new AtomicLong();
    private final AtomicLong rtpUsed = new AtomicLong();
    private final AtomicLong sharesCreated = new AtomicLong();

    public void incrementHomesCreated() { homesCreated.incrementAndGet(); }
    public void incrementHomesDeleted() { homesDeleted.incrementAndGet(); }
    public void incrementTeleports() { teleportsPerformed.incrementAndGet(); }
    public void incrementRtpUsed() { rtpUsed.incrementAndGet(); }
    public void incrementSharesCreated() { sharesCreated.incrementAndGet(); }

    public long getHomesCreated() { return homesCreated.get(); }
    public long getHomesDeleted() { return homesDeleted.get(); }
    public long getTeleportsPerformed() { return teleportsPerformed.get(); }
    public long getRtpUsed() { return rtpUsed.get(); }
    public long getSharesCreated() { return sharesCreated.get(); }
}