package com.openbot.lanaudio;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

final class PairedReceiverClient {
    static PairedReceiver pair(String host, String pin, String name, String receiverName) throws Exception {
        SecureRandom random = new SecureRandom();
        String id = PairingProtocol.newDeviceId(random), token = PairingProtocol.newToken(random);
        PairingProtocol.validatePairFields(pin, id, name, token);
        try (Connection connection = connect(host)) {
            writePairHandshake(connection.out, pin, id, name, token);
            connection.out.flush();
            if (!"OK".equals(readLine(connection.in, 16))) throw new IllegalStateException("iMac rejected pairing. Check the code and try again.");
        }
        return new PairedReceiver(id, token, host, name, receiverName);
    }

    static void writePairHandshake(Writer out, String pin, String deviceId, String name, String token) throws Exception {
        PairingProtocol.validatePairFields(pin, deviceId, name, token);
        out.write("LANPAIR1\n"); out.write(pin); out.write('\n'); out.write(deviceId); out.write('\n');
        out.write(name); out.write('\n'); out.write(token); out.write('\n');
    }

    static RemoteControlClient.Result getVolume(PairedReceiver paired) throws Exception {
        return control(paired, new JSONObject().put("id", 1).put("cmd", "get_volume"), true);
    }

    static RemoteControlClient.Result setVolume(PairedReceiver paired, int value) throws Exception {
        if (value < 0 || value > 100) throw new IllegalArgumentException("Volume must be from 0 to 100.");
        return control(paired, new JSONObject().put("id", 1).put("cmd", "set_volume").put("value", value), true);
    }

    static void unpair(PairedReceiver paired) throws Exception {
        control(paired, new JSONObject().put("id", 1).put("cmd", "unpair"), false);
    }

    private static RemoteControlClient.Result control(PairedReceiver paired, JSONObject request, boolean needsVolume) throws Exception {
        try (Connection connection = connect(paired.host)) {
            connection.out.write("LANCTRL2\n"); connection.out.write(paired.deviceId); connection.out.write('\n');
            connection.out.write(paired.token); connection.out.write('\n'); connection.out.flush();
            if (!"OK".equals(readLine(connection.in, 16))) throw new IllegalStateException("iMac rejected this paired device.");
            connection.out.write(request.toString()); connection.out.write('\n'); connection.out.flush();
            JSONObject response = new JSONObject(readLine(connection.in, 4096));
            if (response.optInt("id", -1) != 1 || !response.optBoolean("ok", false))
                throw new IllegalStateException(response.optString("error", "iMac could not complete the request."));
            if (!needsVolume) return null;
            int volume = response.getInt("volume");
            if (volume < 0 || volume > 100) throw new IllegalStateException("iMac returned an invalid volume.");
            return new RemoteControlClient.Result(volume);
        }
    }

    private static Connection connect(String host) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getByName(host), PairingProtocol.CONTROL_PORT), 3000);
            socket.setSoTimeout(3000);
            return new Connection(socket);
        } catch (Exception e) { socket.close(); throw e; }
    }

    private static String readLine(BufferedReader in, int maxChars) throws Exception {
        StringBuilder line = new StringBuilder();
        for (int c; (c = in.read()) != -1;) {
            if (c == '\n') return line.toString();
            if (c != '\r') line.append((char)c);
            if (line.length() > maxChars) throw new IllegalStateException("iMac response is too long.");
        }
        throw new IllegalStateException("iMac closed the connection.");
    }

    private static final class Connection implements AutoCloseable {
        final Socket socket; final BufferedWriter out; final BufferedReader in;
        Connection(Socket socket) throws Exception {
            this.socket = socket;
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }
        @Override public void close() throws Exception { socket.close(); }
    }

    private PairedReceiverClient() { }
}
