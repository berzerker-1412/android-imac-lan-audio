package com.openbot.lanaudio;

import org.json.JSONObject;
import org.json.JSONArray;
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
    static final class UnsupportedOutputControlsException extends IllegalStateException {
        UnsupportedOutputControlsException(String message) { super(message); }
    }

    static final class AudioOutput {
        final String uid, name;
        final boolean isDefault, selectable, volumeReadable, volumeSettable;
        final Integer volume;
        AudioOutput(String uid, String name, boolean isDefault, boolean selectable, boolean volumeReadable, boolean volumeSettable, Integer volume) {
            this.uid = uid; this.name = name; this.isDefault = isDefault; this.selectable = selectable;
            this.volumeReadable = volumeReadable; this.volumeSettable = volumeSettable; this.volume = volume;
        }
        @Override public String toString() { return name + (isDefault ? " · In use" : ""); }
    }
    static final class OutputChange { final String uid, name; OutputChange(String uid, String name) { this.uid = uid; this.name = name; } }
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

    static java.util.List<AudioOutput> listOutputs(PairedReceiver paired) throws Exception {
        JSONObject response = controlJson(paired, new JSONObject().put("id", 3).put("cmd", "list_outputs"), 3);
        return parseOutputs(response);
    }

    static java.util.List<AudioOutput> parseOutputs(JSONObject response) throws Exception {
        JSONArray array = response.getJSONArray("outputs");
        java.util.List<AudioOutput> outputs = new java.util.ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            String uid = item.getString("uid"), name = item.getString("name");
            if (uid.trim().isEmpty() || name.trim().isEmpty()) throw new IllegalStateException("iMac returned an invalid output device.");
            boolean readable = item.optBoolean("volume_readable", false), settable = item.optBoolean("volume_settable", false);
            Integer volume = item.has("volume") ? item.getInt("volume") : null;
            if (volume != null && (volume < 0 || volume > 100)) throw new IllegalStateException("iMac returned an invalid output volume.");
            for (AudioOutput existing : outputs) if (existing.uid.equals(uid)) throw new IllegalStateException("iMac returned duplicate output devices.");
            outputs.add(new AudioOutput(uid, name, item.optBoolean("is_default", false), item.optBoolean("selectable", false), readable, settable, volume));
        }
        return outputs;
    }

    static boolean isUnsupportedOutputCommand(String error) {
        return error != null && (error.equalsIgnoreCase("unsupported command")
                || error.equalsIgnoreCase("list_outputs requires paired LANCTRL2 authentication"));
    }

    static OutputChange setOutput(PairedReceiver paired, String uid) throws Exception {
        JSONObject response = controlJson(paired, new JSONObject().put("id", 4).put("cmd", "set_output").put("output_uid", uid), 4);
        return new OutputChange(response.getString("output_uid"), response.getString("name"));
    }

    static RemoteControlClient.Result getVolume(PairedReceiver paired, String outputUid) throws Exception {
        JSONObject request = new JSONObject().put("id", 1).put("cmd", "get_volume");
        if (outputUid != null) request.put("output_uid", outputUid);
        return volumeResult(controlJson(paired, request, 1), outputUid);
    }

    static RemoteControlClient.Result setVolume(PairedReceiver paired, String outputUid, int value) throws Exception {
        if (value < 0 || value > 100) throw new IllegalArgumentException("Volume must be from 0 to 100.");
        JSONObject request = new JSONObject().put("id", 1).put("cmd", "set_volume").put("value", value);
        if (outputUid != null) request.put("output_uid", outputUid);
        return volumeResult(controlJson(paired, request, 1), outputUid);
    }

    private static RemoteControlClient.Result volumeResult(JSONObject response, String expectedUid) throws Exception {
        if (expectedUid != null && !expectedUid.equals(response.optString("output_uid"))) throw new IllegalStateException("iMac confirmed a different output device.");
        int volume = response.getInt("volume");
        if (volume < 0 || volume > 100) throw new IllegalStateException("iMac returned an invalid volume.");
        return new RemoteControlClient.Result(volume);
    }

    static void unpair(PairedReceiver paired) throws Exception {
        control(paired, new JSONObject().put("id", 1).put("cmd", "unpair"), false);
    }

    private static RemoteControlClient.Result control(PairedReceiver paired, JSONObject request, boolean needsVolume) throws Exception {
        JSONObject response = controlJson(paired, request, 1);
        if (!needsVolume) return null;
        int volume = response.getInt("volume");
        if (volume < 0 || volume > 100) throw new IllegalStateException("iMac returned an invalid volume.");
        return new RemoteControlClient.Result(volume);
    }

    private static JSONObject controlJson(PairedReceiver paired, JSONObject request, int requestId) throws Exception {
        try (Connection connection = connect(paired.host)) {
            connection.out.write("LANCTRL2\n"); connection.out.write(paired.deviceId); connection.out.write('\n');
            connection.out.write(paired.token); connection.out.write('\n'); connection.out.flush();
            if (!"OK".equals(readLine(connection.in, 16))) throw new IllegalStateException("iMac rejected this paired device.");
            connection.out.write(request.toString()); connection.out.write('\n'); connection.out.flush();
            JSONObject response = new JSONObject(readLine(connection.in, 4096));
            if (response.optInt("id", -1) != requestId || !response.optBoolean("ok", false)) {
                String error = response.optString("error", "iMac could not complete the request.");
                if (requestId == 3 && isUnsupportedOutputCommand(error))
                    throw new UnsupportedOutputControlsException(error);
                throw new IllegalStateException(error);
            }
            return response;
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
