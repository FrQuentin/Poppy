package fr.quentin.poppy.util;

import java.util.concurrent.atomic.AtomicLong;

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