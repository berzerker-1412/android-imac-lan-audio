package com.openbot.lanaudio;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PairedReceiverTest {
    @Test public void hostUpdatePreservesPairingIdentityAndOriginalValue() {
        PairedReceiver original = new PairedReceiver("device_1234567890", "a".repeat(64), "192.168.1.10", "Android", "Studio iMac");
        PairedReceiver updated = original.withHost("192.168.1.42");

        assertEquals("192.168.1.10", original.host);
        assertEquals("192.168.1.42", updated.host);
        assertEquals(original.deviceId, updated.deviceId);
        assertEquals(original.token, updated.token);
        assertEquals(original.name, updated.name);
        assertEquals(original.receiverName, updated.receiverName);
    }

    @Test public void serviceMatchUsesExactBonjourInstanceName() {
        PairedReceiver paired = new PairedReceiver("device_1234567890", "a".repeat(64), "192.168.1.10", "Android", "Studio iMac");
        assertTrue(paired.matchesServiceName("Studio iMac"));
        assertFalse(paired.matchesServiceName("Studio iMac (2)"));
    }

    @Test public void multipleReceiverStatePreservesSelectionAndUnpairsOnlyChosenReceiver() {
        PairedReceiver imac = new PairedReceiver("device_imac_123456", "a".repeat(64), "192.168.1.10", "Android", "Studio iMac");
        PairedReceiver macbook = new PairedReceiver("device_air_123456", "b".repeat(64), "192.168.1.11", "Android", "MacBook Air");
        PairedReceiverStore.State state = PairedReceiverStore.upsert(
                PairedReceiverStore.upsert(new PairedReceiverStore.State(new java.util.ArrayList<>(), null), imac, true), macbook, true);
        PairedReceiverStore.State afterUnpair = PairedReceiverStore.without(state, macbook.deviceId);

        assertEquals(1, afterUnpair.receivers.size());
        assertEquals("Studio iMac", afterUnpair.selected().receiverName);
        assertEquals(imac.token, afterUnpair.selected().token);
    }

    @Test public void addressRefreshUpdatesOnlyHostAndKeepsBothPairedTokens() {
        PairedReceiver imac = new PairedReceiver("device_imac_123456", "a".repeat(64), "192.168.1.10", "Android", "Studio iMac");
        PairedReceiver macbook = new PairedReceiver("device_air_123456", "b".repeat(64), "192.168.1.11", "Android", "MacBook Air");
        PairedReceiverStore.State state = new PairedReceiverStore.State(java.util.Arrays.asList(imac, macbook), imac.deviceId);
        PairedReceiverStore.State refreshed = PairedReceiverStore.upsert(state, macbook.withHost("192.168.1.42"), false);

        assertEquals(2, refreshed.receivers.size());
        assertEquals(imac.deviceId, refreshed.receivers.get(0).deviceId);
        assertEquals(imac.token, refreshed.receivers.get(0).token);
        assertEquals("192.168.1.42", refreshed.receivers.get(1).host);
        assertEquals(macbook.token, refreshed.receivers.get(1).token);
        assertEquals(imac.deviceId, refreshed.selectedDeviceId);
    }
}
