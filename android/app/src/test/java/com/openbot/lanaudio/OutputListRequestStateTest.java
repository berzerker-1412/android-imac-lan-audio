package com.openbot.lanaudio;

import org.junit.Test;
import static org.junit.Assert.*;

public final class OutputListRequestStateTest {
    @Test public void outputSwitchCanStartARefreshAfterCurrentListCompletes() {
        OutputListRequestState state = new OutputListRequestState();
        long initialList = state.begin("device-a");
        assertFalse(state.finish(initialList + 1));
        assertTrue(state.finish(initialList));

        long refreshAfterSwitch = state.begin("device-a");
        assertNotEquals(OutputListRequestState.NO_REQUEST, refreshAfterSwitch);
        assertFalse(state.finish(initialList));
        assertTrue(state.finish(refreshAfterSwitch));
    }

    @Test public void duplicateLoadIsDeduplicatedButRetryStartsAfterFailureCompletes() {
        OutputListRequestState state = new OutputListRequestState();
        long failedAttempt = state.begin("device-a");
        assertEquals(OutputListRequestState.NO_REQUEST, state.begin("device-a"));
        assertTrue(state.finish(failedAttempt));

        long retry = state.begin("device-a");
        assertNotEquals(OutputListRequestState.NO_REQUEST, retry);
        assertTrue(state.finish(retry));
    }

    @Test public void newerRequestInvalidatesLateCallback() {
        OutputListRequestState state = new OutputListRequestState();
        long oldRequest = state.begin("device-a");
        long changedTarget = state.begin("device-b");
        assertFalse(state.finish(oldRequest));
        assertTrue(state.finish(changedTarget));
    }
}
