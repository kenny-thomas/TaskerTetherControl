package com.matejdro.taskertethercontrol;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HotspotClientSnapshotTest {
    @Test public void hotspotEnabledBeforeAnyClientEventIsUnknownNotCrash() {
        assertNull(HotspotClientSnapshot.knownCount(Boolean.TRUE, true, null));
    }

    @Test public void callbackNotRegisteredMeansUnknown() {
        assertNull(HotspotClientSnapshot.knownCount(Boolean.TRUE, false, 0));
    }

    @Test public void offHotspotHasNoConnectedClients() {
        assertEquals(Integer.valueOf(0),
                HotspotClientSnapshot.knownCount(Boolean.FALSE, false, null));
    }

    @Test public void enabledHotspotWithoutClientsIsKnownZeroAfterCallback() {
        assertEquals(Integer.valueOf(0),
                HotspotClientSnapshot.knownCount(Boolean.TRUE, true, 0));
    }

    @Test public void enabledHotspotWithConnectedClient() {
        assertEquals(Integer.valueOf(1),
                HotspotClientSnapshot.knownCount(Boolean.TRUE, true, 1));
    }

    @Test public void transitionalHotspotPreservesUnknown() {
        assertNull(HotspotClientSnapshot.knownCount(null, true, null));
    }
}
