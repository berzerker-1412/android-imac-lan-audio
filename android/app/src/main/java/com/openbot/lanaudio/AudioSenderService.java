package com.openbot.lanaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.Manifest;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioAttributes;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjection.Callback;
import android.os.Build;
import android.os.IBinder;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

public final class AudioSenderService extends Service {
    static final String ACTION_STATE = "com.openbot.lanaudio.STATE";
    static final String ACTION_START = "START", ACTION_STOP = "STOP", ACTION_QUERY = "QUERY";
    private static final String CHANNEL = "audio_sender";
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Socket socket;
    private volatile AudioRecord recorder;
    private volatile MediaProjection projection;
    private Thread worker;
    private final Object resourceLock = new Object();
    private volatile String serviceStatus = "Stopped", sessionHost = "", sessionCaptureLabel = "All compatible apps";
    private volatile String sessionId;

    @Override public void onCreate() { super.onCreate(); createChannel(); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_QUERY.equals(intent.getAction())) {
            if (!ServiceStatePolicy.shouldBroadcastQuery(worker != null && worker.isAlive())) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            sendState(serviceStatus, sessionHost, sessionCaptureLabel, sessionId);
            return START_NOT_STICKY;
        }
        if (intent == null || ACTION_STOP.equals(intent.getAction())) { stopCapture("Stopped"); return START_NOT_STICKY; }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        Thread previousWorker = worker;
        boolean previousWorkerAlive = previousWorker != null && previousWorker.isAlive();
        boolean previousSessionStopping = stopRequested.get() || serviceStatus.startsWith("Error:");
        if (!previousWorkerAlive || previousSessionStopping) {
            Notification foregroundNotification = notification("Connecting to iMac…");
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(7, foregroundNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(7, foregroundNotification);
        }
        String requestedSessionId = intent.getStringExtra("sessionId");
        if (requestedSessionId == null || requestedSessionId.isEmpty()) requestedSessionId = UUID.randomUUID().toString();
        if (previousWorkerAlive) {
            ServiceStatePolicy.BusyStartDisposition disposition = ServiceStatePolicy.busyStartDisposition(
                    sessionId, requestedSessionId, previousSessionStopping);
            String requestedHost = intent.getStringExtra("host");
            int uid = intent.getIntExtra("selectedUid", -1);
            String label = intent.getStringExtra("captureLabel");
            String requestedCapture = uid >= 0 && label != null ? label : "All compatible apps";
            if (disposition == ServiceStatePolicy.BusyStartDisposition.SAME_SESSION) {
                sendState(serviceStatus, sessionHost, sessionCaptureLabel, sessionId);
            } else {
                String error = disposition == ServiceStatePolicy.BusyStartDisposition.PREVIOUS_SESSION_STOPPING
                        ? "Error: The previous session is still stopping. Wait a moment, then try again."
                        : "Error: Another audio session is already active. Stop it before starting again.";
                sendState(error, requestedHost == null ? "" : requestedHost, requestedCapture, requestedSessionId);
                if (disposition == ServiceStatePolicy.BusyStartDisposition.PREVIOUS_SESSION_STOPPING)
                    stopForeground(STOP_FOREGROUND_REMOVE);
            }
            return START_NOT_STICKY;
        }
        stopRequested.set(false);
        String host = intent.getStringExtra("host"), pin = intent.getStringExtra("code");
        sessionHost = host == null ? "" : host;
        sessionId = requestedSessionId;
        final String startedSessionId = sessionId;
        String pairedId = intent.getStringExtra("pairedDeviceId"), pairedToken = intent.getStringExtra("pairedToken");
        int selectedUid = intent.getIntExtra("selectedUid", -1);
        String providedCaptureLabel = intent.getStringExtra("captureLabel");
        final String captureLabel = providedCaptureLabel == null || providedCaptureLabel.trim().isEmpty() ? "selected app" : providedCaptureLabel;
        sessionCaptureLabel = selectedUid >= 0 ? captureLabel : "All compatible apps";
        notifyState("Connecting to " + sessionHost);
        int result = intent.getIntExtra("resultCode", 0); Intent consent = intent.getParcelableExtra("projection");
        worker = new Thread(() -> stream(host, pin, pairedId, pairedToken, result, consent, selectedUid, captureLabel, startedSessionId), "lan-pcm-sender"); worker.start();
        return START_NOT_STICKY;
    }

    private void stream(String host, String pin, String pairedId, String pairedToken, int result, Intent consent, int selectedUid, String captureLabel, String startedSessionId) {
        try {
            Socket s = new Socket(); synchronized (resourceLock) { socket = s; } s.connect(new InetSocketAddress(host, 50555), 5000); s.setTcpNoDelay(true);
            OutputStream out = new BufferedOutputStream(s.getOutputStream()); InputStream in = new BufferedInputStream(s.getInputStream());
            if (pairedId != null && pairedToken != null) {
                out.write("LANPCM2\n".getBytes(StandardCharsets.UTF_8)); out.write((pairedId + "\n").getBytes(StandardCharsets.UTF_8));
                out.write((pairedToken + "\n").getBytes(StandardCharsets.UTF_8));
            } else {
                out.write("LANPCM1\n".getBytes(StandardCharsets.UTF_8)); out.write((pin + "\n").getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
            String response = readLine(in);
            if (!"OK".equals(response)) throw new IllegalStateException(pairedId != null
                    ? "Saved pairing was rejected. Pair this phone again with the current code."
                    : "The Mac didn’t accept this code. Check it or scan a fresh QR.");
            if (stopRequested.get()) return;
            MediaProjectionManager manager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            MediaProjection newProjection;
            synchronized (resourceLock) { newProjection = manager.getMediaProjection(result, consent); projection = newProjection; }
            if (newProjection == null) throw new IllegalStateException("Android capture session is no longer available");
            newProjection.registerCallback(new Callback() {
                @Override public void onStop() {
                    if (!startedSessionId.equals(sessionId)) return;
                    stopRequested.set(true);
                    running.set(false);
                    closeResources();
                    if (startedSessionId.equals(sessionId)) notifyState(ServiceStatePolicy.afterProjectionStopped(serviceStatus));
                }
            }, new android.os.Handler(getMainLooper()));
            if (stopRequested.get()) return;
            AudioPlaybackCaptureConfiguration.Builder captureBuilder = new AudioPlaybackCaptureConfiguration.Builder(newProjection);
            if (selectedUid >= 0) captureBuilder.addMatchingUid(selectedUid);
            AudioPlaybackCaptureConfiguration config = captureBuilder.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Microphone permission was revoked");
            int mask = AudioFormat.CHANNEL_IN_STEREO;
            int min = AudioRecord.getMinBufferSize(48000, mask, AudioFormat.ENCODING_PCM_16BIT);
            int chunk = Math.max(min, 8192);
            AudioRecord newRecorder = new AudioRecord.Builder().setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000).setChannelMask(mask).build()).setBufferSizeInBytes(chunk * 2)
                    .setAudioPlaybackCaptureConfig(config).build();
            synchronized (resourceLock) { recorder = newRecorder; }
            if (newRecorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Audio capture could not start");
            running.set(true);
            String sendingStatus = selectedUid >= 0 ? "Sending only " + captureLabel + " to " + host : "Sending audio from all compatible apps to " + host;
            notifyState(sendingStatus); updateNotification(sendingStatus);
            newRecorder.startRecording(); byte[] buffer = new byte[chunk];
            long quietBytes = 0;
            boolean silenceReported = false;
            while (running.get() && !stopRequested.get()) {
                int n = newRecorder.read(buffer, 0, buffer.length);
                if (n < 0) throw new IllegalStateException("Audio read failed: " + n);
                if (n > 0) {
                    boolean quiet = true;
                    for (int i = 0; i + 1 < n; i += 2) {
                        int sample = (short)((buffer[i + 1] << 8) | (buffer[i] & 0xff));
                        if (Math.abs(sample) > 256) { quiet = false; break; }
                    }
                    if (quiet && selectedUid >= 0) quietBytes += n;
                    else {
                        quietBytes = 0;
                        if (silenceReported) notifyState(sendingStatus);
                        silenceReported = false;
                    }
                    if (selectedUid >= 0 && !silenceReported && quietBytes >= 48000L * 4 * 3) {
                        silenceReported = true;
                        notifyState(sendingStatus + " · No audio detected from " + captureLabel + ". Check that it’s playing; some apps block playback capture.");
                    }
                    out.write(buffer, 0, n); out.flush();
                }
            }
        } catch (Exception e) {
            if (startedSessionId.equals(sessionId) && !stopRequested.get()) {
                String message = e instanceof java.io.IOException
                        ? "Couldn’t connect to " + (host == null ? "the Mac" : host) + ". Check that both devices are on the same trusted network."
                        : (e.getMessage() == null ? "Audio sending failed. Check the receiver and try again." : e.getMessage());
                notifyState("Error: " + message);
            }
        }
        finally { release(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    }

    private String readLine(InputStream in) throws Exception { StringBuilder b = new StringBuilder(); for (int c; (c = in.read()) != -1 && c != '\n';) { if (b.length() > 8) throw new IllegalStateException("Bad receiver response"); b.append((char)c); } return b.toString(); }
    private void stopCapture(String message) { stopRequested.set(true); running.set(false); closeResources(); notifyState(message); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    private void closeResources() {
        Socket oldSocket;
        synchronized (resourceLock) {
            if (projection != null) { MediaProjection old = projection; projection = null; try { old.stop(); } catch (Exception ignored) {} }
            oldSocket = socket; socket = null;
            if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) {} try { recorder.release(); } catch (Exception ignored) {} recorder = null; }
        }
        if (oldSocket != null) try { oldSocket.close(); } catch (Exception ignored) {}
    }
    private void release() { running.set(false); stopRequested.set(true); closeResources(); }
    private void notifyState(String text) {
        serviceStatus = text;
        sendState(text, sessionHost, sessionCaptureLabel, sessionId);
    }
    private void sendState(String text, String host, String captureLabel, String targetSessionId) {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName()).putExtra("status", text)
                .putExtra("host", host).putExtra("captureLabel", captureLabel).putExtra("sessionId", targetSessionId);
        sendBroadcast(i);
    }
    private void createChannel() { if (Build.VERSION.SDK_INT >= 26) { NotificationChannel c = new NotificationChannel(CHANNEL, "LAN audio sending", NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager.class).createNotificationChannel(c); } }
    private Notification notification(String text) { Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this); return b.setContentTitle("LAN Audio Sender").setContentText(text).setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build(); }
    private void updateNotification(String text) { getSystemService(NotificationManager.class).notify(7, notification(text)); }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() { release(); super.onDestroy(); }
}
