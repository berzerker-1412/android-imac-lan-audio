package com.openbot.lanaudio;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ServiceStatePolicyTest {
    @Test public void idleServiceQueryDoesNotPublishStateThatCanReplaceNavigation() {
        assertFalse(ServiceStatePolicy.shouldBroadcastQuery(false));
        assertTrue(ServiceStatePolicy.shouldBroadcastQuery(true));
    }

    @Test public void projectionStopCannotEraseAnExistingError() {
        String error = "Error: Couldn’t connect to the Mac.";
        assertEquals(error, ServiceStatePolicy.afterProjectionStopped(error));
        assertEquals("Stopped", ServiceStatePolicy.afterProjectionStopped("Sending audio"));
        assertEquals("Stopped", ServiceStatePolicy.afterProjectionStopped(null));
    }

    @Test public void staleSessionBroadcastCannotReplaceCurrentSession() {
        assertTrue(ServiceStatePolicy.isCurrentSession(null, "new-session"));
        assertTrue(ServiceStatePolicy.isCurrentSession("session-a", "session-a"));
        assertFalse(ServiceStatePolicy.isCurrentSession("session-b", "session-a"));
    }

    @Test public void startWhileStoppedWorkerIsStillAliveReportsToNewSession() {
        assertEquals(ServiceStatePolicy.BusyStartDisposition.PREVIOUS_SESSION_STOPPING,
                ServiceStatePolicy.busyStartDisposition("old", "new", true));
        assertEquals(ServiceStatePolicy.BusyStartDisposition.OTHER_SESSION_ACTIVE,
                ServiceStatePolicy.busyStartDisposition("old", "new", false));
        assertEquals(ServiceStatePolicy.BusyStartDisposition.SAME_SESSION,
                ServiceStatePolicy.busyStartDisposition("same", "same", true));
    }
}
