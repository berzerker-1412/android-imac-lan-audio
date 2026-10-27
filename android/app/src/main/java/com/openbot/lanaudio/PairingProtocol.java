package com.openbot.lanaudio;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

final class PairingProtocol {
    static final int CONTROL_PORT = 50556;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static String newDeviceId(SecureRandom random) {
        byte[] bytes = new byte[24]; random.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String newToken(SecureRandom random) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        char[] result = new char[64];
        for (int i = 0; i < bytes.length; i++) { result[i * 2] = HEX[(bytes[i] >>> 4) & 15]; result[i * 2 + 1] = HEX[bytes[i] & 15]; }
        return new String(result);
    }

    static boolean isValidPin(String pin) { return pin != null && pin.matches("\\d{6}"); }

    static void validatePairFields(String pin, String deviceId, String name, String token) {
        if (!isValidPin(pin)) throw new IllegalArgumentException("Enter the 6-digit pairing code.");
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9_-]{16,64}")) throw new IllegalArgumentException("Invalid phone device ID.");
        int nameBytes = name == null ? 0 : name.getBytes(StandardCharsets.UTF_8).length;
        if (nameBytes < 1 || nameBytes > 80 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) throw new IllegalArgumentException("Invalid phone name.");
        if (token == null || !token.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Invalid device token.");
    }

    private PairingProtocol() { }
}
