package com.openbot.lanaudio;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** Discovers the matching Bonjour/DNS-SD receiver; it never connects to it. */
final class ReceiverDiscovery {
    static final String SERVICE_TYPE = "_openbot-lanaudio._tcp.";
    static final int PORT = 50555;

    interface Listener {
        void onState(String state);
        void onReceiver(Receiver receiver);
        void onReceiverLost(String name);
    }

    static final class Receiver {
        final String name;
        final String address;
        final int port;
        final String status;
        Receiver(String name, String address, int port, String status) {
            this.name = name; this.address = address; this.port = port; this.status = status;
        }
    }

    private final NsdManager nsd;
    private final Listener listener;
    private final WifiManager.MulticastLock multicastLock;
    private final Executor mainExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object servicesLock = new Object();
    private final Map<String, Boolean> resolving = new HashMap<>();
    private final Map<String, NsdServiceInfo> services = new HashMap<>();
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean restartAfterStop;

    private void notifyState(String state) {
        mainHandler.post(() -> listener.onState(state));
    }

    private void notifyReceiver(Receiver receiver) {
        mainHandler.post(() -> listener.onReceiver(receiver));
    }

    private void notifyReceiverLost(String name) {
        mainHandler.post(() -> listener.onReceiverLost(name));
    }

    ReceiverDiscovery(Context context, Listener listener) {
        this.nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
        this.mainExecutor = context.getMainExecutor();
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.multicastLock = wifi == null ? null : wifi.createMulticastLock("lan-audio-nsd");
        if (this.multicastLock != null) this.multicastLock.setReferenceCounted(false);
    }

    void start() {
        if (discoveryListener != null) return;
        if (nsd == null) { notifyState("Discovery unavailable"); return; }
        if (multicastLock != null && !multicastLock.isHeld()) multicastLock.acquire();
        synchronized (servicesLock) { resolving.clear(); services.clear(); }
        NsdManager.DiscoveryListener current = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { notifyState("Searching for iMac receivers…"); }
            @Override public void onServiceFound(NsdServiceInfo service) {
                if (!sameType(service.getServiceType())) return;
                String name = service.getServiceName();
                synchronized (servicesLock) {
                    if (services.containsKey(name) || resolving.putIfAbsent(name, true) != null) return;
                    services.put(name, service);
                }
                notifyReceiver(new Receiver(name, "", PORT, "Resolving…"));
                NsdManager.ResolveListener resolved = new NsdManager.ResolveListener() {
                    @Override public void onServiceResolved(NsdServiceInfo info) {
                        synchronized (servicesLock) { resolving.remove(name); }
                        String version = txt(info, "v");
                        InetAddress address = info.getHost();
                        int port = info.getPort();
                        if (!"1".equals(version)) {
                            synchronized (servicesLock) { services.remove(name); }
                            notifyReceiverLost(name);
                        } else if (port != PORT || address == null || !isLanAddress(address)) {
                            synchronized (servicesLock) { services.remove(name); }
                            notifyReceiverLost(name);
                        } else {
                            notifyReceiver(new Receiver(name, address.getHostAddress(), port, "Available"));
                        }
                    }
                    @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                        synchronized (servicesLock) { resolving.remove(name); }
                        notifyReceiver(new Receiver(name, "", PORT, "Couldn’t resolve"));
                    }
                };
                try {
                    if (Build.VERSION.SDK_INT >= 33) nsd.resolveService(service, mainExecutor, resolved);
                    else nsd.resolveService(service, resolved);
                } catch (IllegalArgumentException e) {
                    synchronized (servicesLock) { resolving.remove(name); }
                    notifyReceiver(new Receiver(name, "", PORT, "Couldn’t resolve"));
                }
            }
            @Override public void onServiceLost(NsdServiceInfo service) {
                String name = service.getServiceName();
                synchronized (servicesLock) { resolving.remove(name); services.remove(name); }
                notifyReceiverLost(name);
            }
            @Override public void onDiscoveryStopped(String type) { mainHandler.post(() -> finish(this)); }
            @Override public void onStartDiscoveryFailed(String type, int errorCode) {
                mainHandler.post(() -> {
                    listener.onState("Discovery failed (" + errorCode + ")");
                    stopInternal(this);
                });
            }
            @Override public void onStopDiscoveryFailed(String type, int errorCode) {
                notifyState("Could not stop discovery (" + errorCode + ")");
            }
        };
        discoveryListener = current;
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, current); }
        catch (IllegalArgumentException e) { stopInternal(current); notifyState("Discovery unavailable"); }
    }

    void stop() {
        restartAfterStop = false;
        NsdManager.DiscoveryListener current = discoveryListener;
        if (current == null) { releaseLock(); return; }
        try { nsd.stopServiceDiscovery(current); }
        catch (IllegalArgumentException ignored) { finish(current); }
    }

    void restart() {
        NsdManager.DiscoveryListener current = discoveryListener;
        if (current == null) { start(); return; }
        restartAfterStop = true;
        try { nsd.stopServiceDiscovery(current); }
        catch (IllegalArgumentException ignored) { finish(current); }
    }

    void retry(String name) {
        NsdServiceInfo service;
        synchronized (servicesLock) {
            service = services.get(name);
            if (service == null || resolving.putIfAbsent(name, true) != null) return;
        }
        notifyReceiver(new Receiver(name, "", PORT, "Resolving…"));
        NsdManager.ResolveListener resolved = new NsdManager.ResolveListener() {
            @Override public void onServiceResolved(NsdServiceInfo info) {
                synchronized (servicesLock) { resolving.remove(name); }
                InetAddress address = info.getHost();
                int port = info.getPort();
                if (!"1".equals(txt(info, "v")) || port != PORT || address == null || !isLanAddress(address)) {
                    synchronized (servicesLock) { services.remove(name); }
                    notifyReceiverLost(name);
                } else notifyReceiver(new Receiver(name, address.getHostAddress(), port, "Available"));
            }
            @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                synchronized (servicesLock) { resolving.remove(name); }
                notifyReceiver(new Receiver(name, "", PORT, "Couldn’t resolve"));
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) nsd.resolveService(service, mainExecutor, resolved);
            else nsd.resolveService(service, resolved);
        } catch (IllegalArgumentException e) {
            synchronized (servicesLock) { resolving.remove(name); }
            notifyReceiver(new Receiver(name, "", PORT, "Couldn’t resolve"));
        }
    }

    private void stopInternal(NsdManager.DiscoveryListener current) {
        if (discoveryListener == current) discoveryListener = null;
        releaseLock();
        if (restartAfterStop) {
            restartAfterStop = false;
            start();
        }
    }

    private void finish(NsdManager.DiscoveryListener current) { stopInternal(current); }
    private void releaseLock() { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); }

    private static boolean sameType(String type) {
        return type != null && (type.endsWith(".") ? type : type + ".").equalsIgnoreCase(SERVICE_TYPE);
    }

    private static String txt(NsdServiceInfo info, String key) {
        Map<String, byte[]> attributes = info.getAttributes();
        if (attributes == null) return null;
        byte[] value = attributes.get(key);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    private static boolean isLanAddress(InetAddress address) {
        if (address.isLoopbackAddress()) return false;
        if (address.isSiteLocalAddress() || address.isLinkLocalAddress()) return true;
        if (address instanceof Inet6Address) return (address.getAddress()[0] & 0xfe) == 0xfc;
        return false;
    }
}
