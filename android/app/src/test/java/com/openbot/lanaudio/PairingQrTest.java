package com.openbot.lanaudio;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PairingQrTest {
    @Test public void acceptsValidPairingAndOptionalName() {
        PairingQr.Pairing result = PairingQr.parse("openbot-lanaudio://pair?v=1&host=192.168.1.8&port=50555&pin=123456&name=Living%20Room%20iMac");
        assertEquals("192.168.1.8", result.host);
        assertEquals("123456", result.pin);
        assertEquals("Living Room iMac", result.name);
        assertTrue(result.hasName);
    }

    @Test public void missingReceiverNameMustBeResolvedBeforeDurablePairing() {
        PairingQr.Pairing result = PairingQr.parse("openbot-lanaudio://pair?v=1&host=192.168.1.8&port=50555&pin=123456");
        assertEquals("iMac", result.name);
        assertFalse(result.hasName);
    }

    @Test public void rejectsBadSchemePortPinAndPublicHost() {
        rejects("https://pair?v=1&host=192.168.1.8&port=50555&pin=123456");
        rejects("openbot-lanaudio://pair?v=1&host=192.168.1.8&port=80&pin=123456");
        rejects("openbot-lanaudio://pair?v=1&host=192.168.1.8&port=50555&pin=12345");
        rejects("openbot-lanaudio://pair?v=1&host=8.8.8.8&port=50555&pin=123456");
    }

    private void rejects(String value) {
        try { PairingQr.parse(value); fail("Expected invalid pairing QR"); }
        catch (IllegalArgumentException expected) { }
    }
}
