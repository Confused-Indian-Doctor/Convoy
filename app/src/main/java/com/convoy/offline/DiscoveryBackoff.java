package com.convoy.offline;

/** Bounded retry cadence prevents discovery failures from flooding the local network. */
public final class DiscoveryBackoff {
    private int failures;
    public long nextDelayMillis() {
        long delay = Math.min(30000L, 2000L << Math.min(failures, 4));
        if (failures < 5) failures++;
        return delay;
    }
    public void recovered() { failures = 0; }
}
