package com.openbot.lanaudio;

final class PairedReceiver {
    final String deviceId, token, host, name, receiverName;
    PairedReceiver(String deviceId, String token, String host, String name) { this(deviceId, token, host, name, "iMac"); }
    PairedReceiver(String deviceId, String token, String host, String name, String receiverName) {
        this.deviceId = deviceId; this.token = token; this.host = host; this.name = name; this.receiverName = receiverName;
    }

    boolean matchesServiceName(String serviceName) { return receiverName.equals(serviceName); }

    PairedReceiver withHost(String newHost) {
        return new PairedReceiver(deviceId, token, newHost, name, receiverName);
    }
}
