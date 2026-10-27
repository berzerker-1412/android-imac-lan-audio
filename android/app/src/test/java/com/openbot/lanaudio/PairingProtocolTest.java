package com.openbot.lanaudio;

import org.junit.Test;
import java.io.StringWriter;
import java.security.SecureRandom;
import static org.junit.Assert.*;

public final class PairingProtocolTest {
    @Test public void pairingButtonPinValidationRequiresExactlySixDigits() {
        assertTrue(PairingProtocol.isValidPin("123456"));
        assertFalse(PairingProtocol.isValidPin("12345"));
        assertFalse(PairingProtocol.isValidPin("1234567"));
        assertFalse(PairingProtocol.isValidPin("12a456"));
        assertFalse(PairingProtocol.isValidPin(null));
    }

    @Test public void generatesFieldsWithProtocolLengthsAndAlphabet() {
        SecureRandom random = new SecureRandom();
        String id = PairingProtocol.newDeviceId(random), token = PairingProtocol.newToken(random);
        assertTrue(id.matches("[A-Za-z0-9_-]{16,64}"));
        assertTrue(token.matches("[0-9a-f]{64}"));
    }

    @Test public void writesPairHandshakeInExactOrder() throws Exception {
        StringWriter writer = new StringWriter();
        PairedReceiverClient.writePairHandshake(writer, "123456", "device_id_123456", "Xiaomi Phone", "a".repeat(64));
        assertEquals("LANPAIR1\n123456\ndevice_id_123456\nXiaomi Phone\n" + "a".repeat(64) + "\n", writer.toString());
    }

    @Test public void rejectsNewlineInDeviceName() {
        try {
            PairingProtocol.validatePairFields("123456", "device_id_123456", "phone\nLANCTRL2", "a".repeat(64));
            fail("Expected invalid field");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void rejectsNameLongerThanEightyUtf8Bytes() {
        try {
            PairingProtocol.validatePairFields("123456", "device_id_123456", "界".repeat(27), "a".repeat(64));
            fail("Expected invalid field");
        } catch (IllegalArgumentException expected) { }
    }
}
