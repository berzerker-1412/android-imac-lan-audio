package com.openbot.lanaudio;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Validates the untrusted pairing URI before copying any values into the sender. */
final class PairingQr {
    static final class Pairing {
        final String host, pin, name;
        final boolean hasName;
        Pairing(String host, String pin, String name, boolean hasName) { this.host = host; this.pin = pin; this.name = name; this.hasName = hasName; }
    }

    static Pairing parse(String raw) throws IllegalArgumentException {
        try {
            URI uri = URI.create(raw.trim());
            if (!"openbot-lanaudio".equals(uri.getScheme()) || !"pair".equals(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getPort() != -1
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) throw new IllegalArgumentException("Unsupported pairing QR.");
            Map<String, String> q = new HashMap<>();
            if (uri.getRawQuery() == null) throw new IllegalArgumentException("Pairing QR is missing values.");
            for (String part : uri.getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length != 2) throw new IllegalArgumentException("Invalid pairing QR.");
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8.name());
                String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name());
                if (q.put(key, value) != null) throw new IllegalArgumentException("Duplicate pairing QR value.");
            }
            if ((q.size() != 4 && q.size() != 5) || !q.keySet().containsAll(java.util.Arrays.asList("v", "host", "port", "pin"))
                    || (q.size() == 5 && !q.containsKey("name")) || !"1".equals(q.get("v")) || !"50555".equals(q.get("port"))
                    || !q.getOrDefault("pin", "").matches("\\d{6}")) throw new IllegalArgumentException("Unsupported pairing QR values.");
            String host = q.get("host");
            if (host == null || !host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) throw new IllegalArgumentException("QR must contain an IPv4 address.");
            InetAddress address = InetAddress.getByName(host);
            if (!(address instanceof Inet4Address) || !(address.isSiteLocalAddress() || address.isLinkLocalAddress()) || address.isLoopbackAddress())
                throw new IllegalArgumentException("QR address must be private or link-local.");
            String name = q.getOrDefault("name", "iMac");
            if (name.trim().isEmpty() || name.length() > 80) throw new IllegalArgumentException("Invalid receiver name.");
            return new Pairing(address.getHostAddress(), q.get("pin"), name, q.containsKey("name"));
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Invalid pairing QR.", e); }
    }

    private PairingQr() { }
}
