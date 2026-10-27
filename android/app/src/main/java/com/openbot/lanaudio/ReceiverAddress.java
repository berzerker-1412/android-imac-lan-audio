package com.openbot.lanaudio;

import java.net.Inet6Address;
import java.net.InetAddress;

final class ReceiverAddress {
    enum ManualTargetAction { INVALID, CONNECT, PAIR }

    static boolean isPrivateOrLinkLocal(String value) {
        if (value == null || !value.matches("[0-9a-fA-F:.%]+")) return false;
        try {
            InetAddress address = InetAddress.getByName(value);
            if (address.isLoopbackAddress()) return false;
            if (address.isLinkLocalAddress() || address.isSiteLocalAddress()) return true;
            return address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc;
        } catch (Exception ignored) { return false; }
    }

    static ManualTargetAction manualTargetAction(String address, boolean alreadyPaired, String code) {
        if (!isPrivateOrLinkLocal(address)) return ManualTargetAction.INVALID;
        return alreadyPaired || (code != null && code.matches("\\d{6}"))
                ? ManualTargetAction.CONNECT : ManualTargetAction.PAIR;
    }

    private ReceiverAddress() { }
}
