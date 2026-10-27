package com.openbot.lanaudio;

final class ServiceStatePolicy {
    enum BusyStartDisposition { SAME_SESSION, PREVIOUS_SESSION_STOPPING, OTHER_SESSION_ACTIVE }

    static boolean shouldBroadcastQuery(boolean workerAlive) { return workerAlive; }

    static String afterProjectionStopped(String currentStatus) {
        return currentStatus != null && currentStatus.startsWith("Error:") ? currentStatus : "Stopped";
    }

    static boolean isCurrentSession(String currentSessionId, String incomingSessionId) {
        return currentSessionId == null || currentSessionId.equals(incomingSessionId);
    }

    static BusyStartDisposition busyStartDisposition(String currentSessionId, String requestedSessionId, boolean stopping) {
        if (currentSessionId != null && currentSessionId.equals(requestedSessionId)) return BusyStartDisposition.SAME_SESSION;
        return stopping ? BusyStartDisposition.PREVIOUS_SESSION_STOPPING : BusyStartDisposition.OTHER_SESSION_ACTIVE;
    }

    private ServiceStatePolicy() { }
}
