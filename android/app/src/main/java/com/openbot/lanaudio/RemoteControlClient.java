package com.openbot.lanaudio;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** One authenticated control request per TCP connection, as specified by PROTOCOL.md. */
final class RemoteControlClient {
    static final int PORT = 50556;
    static final class Result {
        final int volume;
        Result(int volume) { this.volume = volume; }
    }

    static Result getVolume(String host, String pin) throws Exception { return request(host, pin, "get_volume", null); }
    static Result setVolume(String host, String pin, int value) throws Exception {
        if (value < 0 || value > 100) throw new IllegalArgumentException("Volume must be from 0 to 100.");
        return request(host, pin, "set_volume", value);
    }

    private static Result request(String host, String pin, String command, Integer value) throws Exception {
        if (!pin.matches("\\d{6}")) throw new IllegalArgumentException("Enter the six-digit pairing code.");
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(InetAddress.getByName(host), PORT), 2500);
            socket.setSoTimeout(2500);
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.write("LANCTRL1\n"); out.write(pin); out.write('\n'); out.flush();
            String auth = readLine(in, 16);
            if (!"OK".equals(auth)) throw new ControlException("Pairing code rejected.");
            JSONObject request = new JSONObject().put("id", 1).put("cmd", command);
            if (value != null) request.put("value", value);
            out.write(request.toString()); out.write('\n'); out.flush();
            JSONObject response = new JSONObject(readLine(in, 4096));
            if (response.optInt("id", -1) != 1 || !response.optBoolean("ok", false)) {
                String error = response.optString("error", "Mac could not change its output volume.");
                throw new ControlException(error);
            }
            int volume = response.getInt("volume");
            if (volume < 0 || volume > 100) throw new ControlException("Receiver returned an invalid volume.");
            return new Result(volume);
        }
    }

    private static String readLine(BufferedReader in, int limit) throws Exception {
        StringBuilder line = new StringBuilder();
        for (int c; (c = in.read()) != -1;) {
            if (c == '\n') return line.toString();
            if (line.length() >= limit) throw new ControlException("Receiver response is too long.");
            if (c != '\r') line.append((char)c);
        }
        throw new ControlException("Receiver closed the control connection.");
    }

    static final class ControlException extends Exception { ControlException(String message) { super(message); } }
    private RemoteControlClient() { }
}
