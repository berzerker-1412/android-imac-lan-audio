package com.openbot.lanaudio;

/** Tracks one output-list request and makes late callbacks harmless. UI calls this on the main thread. */
final class OutputListRequestState {
    static final long NO_REQUEST = -1L;
    private long generation;
    private boolean inFlight;
    private String deviceId;

    long begin(String nextDeviceId) {
        if (inFlight && nextDeviceId.equals(deviceId)) return NO_REQUEST;
        generation++;
        deviceId = nextDeviceId;
        inFlight = true;
        return generation;
    }

    boolean finish(long requestId) {
        if (!inFlight || requestId != generation) return false;
        inFlight = false;
        return true;
    }

    void invalidate() {
        generation++;
        inFlight = false;
        deviceId = null;
    }
}
