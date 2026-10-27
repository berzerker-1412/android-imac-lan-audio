package com.openbot.lanaudio;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ReceiverAddressTest {
    @Test public void manualIpNeedsPairingCodeUnlessAlreadyPaired() {
        assertEquals(ReceiverAddress.ManualTargetAction.PAIR,
                ReceiverAddress.manualTargetAction("192.168.1.8", false, ""));
        assertEquals(ReceiverAddress.ManualTargetAction.CONNECT,
                ReceiverAddress.manualTargetAction("192.168.1.8", false, "123456"));
        assertEquals(ReceiverAddress.ManualTargetAction.CONNECT,
                ReceiverAddress.manualTargetAction("192.168.1.8", true, ""));
    }

    @Test public void manualIpRejectsPublicLoopbackAndMalformedAddresses() {
        assertEquals(ReceiverAddress.ManualTargetAction.INVALID,
                ReceiverAddress.manualTargetAction("8.8.8.8", false, "123456"));
        assertEquals(ReceiverAddress.ManualTargetAction.INVALID,
                ReceiverAddress.manualTargetAction("127.0.0.1", false, "123456"));
        assertFalse(ReceiverAddress.isPrivateOrLinkLocal("not-an-ip"));
    }
}
