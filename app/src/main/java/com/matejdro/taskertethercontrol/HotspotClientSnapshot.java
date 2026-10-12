package com.matejdro.taskertethercontrol;

/** Pure policy used to avoid accidentally unboxing an unknown client count. */
final class HotspotClientSnapshot {
    private HotspotClientSnapshot() {}

    /**
     * A stopped hotspot has zero connected clients. When the hotspot is
     * running or transitioning, a listener that hasn't reported yet is
     * UNKNOWN (null), not zero. Never unbox that nullable count.
     */
    static Integer knownCount(Boolean hotspotEnabled,
                              boolean clientListenerAvailable,
                              Integer reportedCount) {
        if (Boolean.FALSE.equals(hotspotEnabled)) return Integer.valueOf(0);
        if (!clientListenerAvailable) return null;
        return reportedCount;
    }
}
