package com.openbot.lanaudio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import android.graphics.drawable.Drawable;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int CAPTURE_REQUEST = 40;
    private static final int RECORD_REQUEST = 41;
    private final Handler handler = new Handler();
    private final Map<String, ReceiverDiscovery.Receiver> receivers = new LinkedHashMap<>();
    private final Map<String, ReceiverRow> receiverRows = new LinkedHashMap<>();
    private final List<PairedReceiver> pairedReceivers = new ArrayList<>();
    private EditText host, code;
    private TextView portChip, selectedHint, discoveryStatus, validation, connectionStatus;
    private TextView volumeStatus, volumeValue;
    private TextView selectedAppLabel, appSelectionError;
    private TextView pairStatus;
    private LinearLayout receiverList;
    private LinearLayout appChoiceRow;
    private ImageView selectedAppIcon;
    private RadioGroup sourceModeGroup;
    private RadioButton allAppsRadio, oneAppRadio;
    private ProgressBar searchSpinner;
    private Button scanButton, qrButton, startButton, stopButton, muteButton, changeAppButton, pairButton, unpairButton;
    private LinearLayout volumePanel;
    private SeekBar volumeSlider;
    private ReceiverDiscovery discovery;
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor();
    private int confirmedVolume = -1, lastAudibleVolume = 50;
    private boolean changingVolume;
    private CaptureApp selectedCaptureApp;
    private PairedReceiver pairedReceiver;
    private String pairingStoreError;
    private boolean pendingUnpairAfterStop;
    private boolean pairedReceiverFound;
    private final Map<String, String> pendingPairedHosts = new LinkedHashMap<>();
    private boolean oneAppMode, staleAppSelection, updatingSourceMode;
    private int pendingSourceChange;
    private CaptureApp pendingCaptureApp;
    private static final int PENDING_NONE = 0, PENDING_CHOOSE_APP = 1, PENDING_ALL_APPS = 2, PENDING_SELECT_APP = 3;
    private final Runnable sendVolumeChange = () -> requestVolume(volumeSlider.getProgress());
    private String selectedName;
    private boolean fillingFromDiscovery, scanning, connectionActive;
    private final Runnable scanTimeout = () -> {
        if (!scanning) return;
        scanning = false;
        searchSpinner.setVisibility(View.GONE);
        if (receivers.isEmpty()) {
            discoveryStatus.setText("No compatible receiver found. Check that the receiver is running on the same Wi-Fi/LAN, or enter its private IP below.");
            scanButton.setText("Scan receivers");
        } else {
            discoveryStatus.setText("Discovery paused. Choose a listed receiver or scan again.");
            scanButton.setText("Scan receivers");
        }
            scanButton.setText(pairedReceiver == null ? "Scan receivers" : "Refresh selected Mac");
        scanButton.setEnabled(true);
        if (pairedReceiver != null && !pairedReceiverFound)
            pairStatus.setText("Selected Mac not found. Check the same Wi-Fi/LAN or refresh.");
        discovery.stop();
    };
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            setConnectionStatus(intent.getStringExtra("status"));
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            PairedReceiverStore.State savedPairs = PairedReceiverStore.load(this);
            pairedReceivers.addAll(savedPairs.receivers);
            pairedReceiver = savedPairs.selected();
        }
        catch (Exception e) { pairingStoreError = "Saved pairing could not be read securely. Pairing is unavailable on this installation."; }
        String savedPackage = CaptureAppSelection.packageName(this);
        selectedCaptureApp = CaptureAppSelection.load(this, getPackageManager());
        oneAppMode = savedPackage != null;
        staleAppSelection = oneAppMode && selectedCaptureApp == null;
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, new IntentFilter(AudioSenderService.ACTION_STATE), RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver, new IntentFilter(AudioSenderService.ACTION_STATE));
        setContentView(makeContent());
        if (pairedReceiver != null) {
            fillingFromDiscovery = true;
            host.setText(pairedReceiver.host);
            fillingFromDiscovery = false;
            selectedName = pairedReceiver.receiverName;
            selectedHint.setText("Paired with " + pairedReceiver.receiverName);
        }
        updatePairingUi();
        updateSourceModeUi();
        discovery = new ReceiverDiscovery(this, new ReceiverDiscovery.Listener() {
            @Override public void onState(String state) {
                if (isFinishing() || isDestroyed()) return;
                if (state.startsWith("Discovery failed") || state.equals("Discovery unavailable")) {
                    scanning = false;
                    handler.removeCallbacks(scanTimeout);
                    searchSpinner.setVisibility(View.GONE);
                    discoveryStatus.setText("Couldn’t search for receivers. Bonjour discovery may be blocked on this network.");
            scanButton.setText(pairedReceiver == null ? "Try again" : "Refresh selected Mac");
                    scanButton.setEnabled(true);
                    if (pairedReceiver != null) pairStatus.setText("Couldn’t find " + pairedReceiver.receiverName + ". Check the same Wi-Fi/LAN or refresh.");
                    return;
                }
                discoveryStatus.setText(state);
                if (pairedReceiver != null && state.startsWith("Searching")) pairStatus.setText("Looking for " + pairedReceiver.receiverName + "…");
            }
            @Override public void onReceiver(ReceiverDiscovery.Receiver receiver) {
                if (isFinishing() || isDestroyed()) return;
                receivers.put(receiver.name, receiver);
                ReceiverRow row = receiverRows.get(receiver.name);
                if (row == null) {
                    row = new ReceiverRow(receiver.name);
                    receiverRows.put(receiver.name, row);
                    receiverList.addView(row.root, params(-1, -2));
                }
                row.update(receiver);
                PairedReceiver matchingPair = pairedByServiceName(receiver.name);
                if (matchingPair != null) {
                    if (receiver.status.equals("Available")) updatePairedReceiverAddress(matchingPair, receiver);
                    else if (isSelectedPair(matchingPair) && receiver.status.startsWith("Resolving")) pairStatus.setText("Resolving " + matchingPair.receiverName + "…");
                    else if (isSelectedPair(matchingPair)) pairStatus.setText("Couldn’t resolve " + matchingPair.receiverName + ". Check the same Wi-Fi/LAN or refresh.");
                }
                if (receiver.status.equals("Available")) discoveryStatus.setText("Select a receiver, or enter its private IP below.");
            }
            @Override public void onReceiverLost(String name) {
                if (isFinishing() || isDestroyed()) return;
                PairedReceiver lostPair = pairedByServiceName(name);
                if (lostPair != null) {
                    ReceiverDiscovery.Receiver offline = new ReceiverDiscovery.Receiver(name, lostPair.host, 50555, "Not found");
                    receivers.put(name, offline);
                    ReceiverRow row = receiverRows.get(name);
                    if (row == null) { row = new ReceiverRow(name); receiverRows.put(name, row); receiverList.addView(row.root, params(-1, -2)); }
                    row.update(offline);
                } else {
                    receivers.remove(name);
                    ReceiverRow row = receiverRows.remove(name);
                    if (row != null) receiverList.removeView(row.root);
                }
                if (lostPair != null && isSelectedPair(lostPair)) {
                    pairedReceiverFound = false;
                    pairStatus.setText(pairedReceiver.receiverName + " not found. Check the same Wi-Fi/LAN or refresh.");
                }
                if (name.equals(selectedName)) selectedHint.setText("Receiver no longer visible. Check the address or choose another receiver.");
            }
        });
        setConnectionStatus("Stopped");
        updateStartEnabled();
        beginScan();
    }

    private View makeContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(248, 249, 252));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("Send audio to Mac", 24, true);
        root.addView(title, params(-1, -2));

        TextView security = text("⚠  Trusted network only · Audio and pairing credentials are sent without encryption.", 15, false);
        security.setPadding(dp(16), dp(14), dp(16), dp(14));
        security.setBackground(cardBackground(false));
        root.addView(security, params(-1, -2));

        TextView discoveryTitle = text("Receivers on this network", 18, true);
        discoveryTitle.setPadding(0, dp(6), 0, dp(2));
        root.addView(discoveryTitle, params(-1, -2));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(6));
        panel.setBackground(cardBackground(false));
        root.addView(panel, params(-1, -2));

        LinearLayout statusLine = new LinearLayout(this);
        statusLine.setGravity(Gravity.CENTER_VERTICAL);
        searchSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        statusLine.addView(searchSpinner, new LinearLayout.LayoutParams(dp(22), dp(22)));
        discoveryStatus = text("Searching for iMac and MacBook receivers on the same trusted Wi-Fi/LAN.", 14, false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0, -2, 1);
        statusParams.leftMargin = dp(10);
        statusLine.addView(discoveryStatus, statusParams);
        panel.addView(statusLine, params(-1, -2));

        receiverList = new LinearLayout(this);
        receiverList.setOrientation(LinearLayout.VERTICAL);
        panel.addView(receiverList, params(-1, -2));

        scanButton = new Button(this);
        scanButton.setText("Scan receivers");
        qrButton = new Button(this);
        qrButton.setText("Scan receiver QR");
        panel.addView(qrButton, params(-1, dp(48)));
        qrButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Scan receiver pairing QR")
                .setMessage("Scan the pairing QR shown on the Mac. It contains the LAN address and pairing code.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open scanner", (dialog, which) -> scanPairingQr()).show());
        panel.addView(scanButton, params(-1, dp(48)));
        scanButton.setOnClickListener(v -> beginScan());

        selectedHint = text("", 14, false);
        selectedHint.setTextColor(Color.rgb(35, 91, 156));
        selectedHint.setPadding(dp(4), dp(5), dp(4), dp(5));
        root.addView(selectedHint, params(-1, -2));

        TextView manualTitle = text("Or enter IP manually", 18, true);
        manualTitle.setPadding(0, dp(8), 0, 0);
        root.addView(manualTitle, params(-1, -2));

        LinearLayout endpoint = new LinearLayout(this);
        endpoint.setGravity(Gravity.CENTER_VERTICAL);
        host = field("Private Mac IP address", InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams hostParams = new LinearLayout.LayoutParams(0, dp(54), 1);
        hostParams.rightMargin = dp(10);
        endpoint.addView(host, hostParams);
        portChip = text("Port\n50555", 14, true);
        portChip.setGravity(Gravity.CENTER);
        portChip.setMinWidth(dp(84));
        portChip.setPadding(dp(10), 0, dp(10), 0);
        portChip.setBackground(cardBackground(false));
        endpoint.addView(portChip, new LinearLayout.LayoutParams(-2, dp(54)));
        root.addView(endpoint, params(-1, -2));

        validation = text("", 14, false);
        validation.setTextColor(Color.rgb(176, 39, 39));
        root.addView(validation, params(-1, -2));

        code = field("6-digit pairing code", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        root.addView(code, params(-1, dp(54)));

        pairStatus = text("Pair this phone with a Mac to stop entering a code next time.", 14, false);
        pairStatus.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.addView(pairStatus, params(-1, -2));
        pairButton = new Button(this);
        pairButton.setText("Pair this Mac");
        root.addView(pairButton, params(-1, dp(48)));
        unpairButton = new Button(this);
        unpairButton.setText("Unpair selected");
        root.addView(unpairButton, params(-1, dp(48)));
        pairButton.setOnClickListener(v -> pairCurrentReceiver());
        unpairButton.setOnClickListener(v -> confirmUnpair());

        TextView sourceTitle = text("Audio source", 18, true);
        sourceTitle.setPadding(0, dp(8), 0, dp(2));
        root.addView(sourceTitle, params(-1, -2));
        LinearLayout sourcePanel = new LinearLayout(this);
        sourcePanel.setOrientation(LinearLayout.VERTICAL);
        sourcePanel.setPadding(dp(12), dp(8), dp(12), dp(8));
        sourcePanel.setBackground(cardBackground(false));
        root.addView(sourcePanel, params(-1, -2));

        sourceModeGroup = new RadioGroup(this);
        sourceModeGroup.setOrientation(LinearLayout.VERTICAL);
        allAppsRadio = new RadioButton(this);
        allAppsRadio.setId(View.generateViewId());
        allAppsRadio.setText("All compatible apps");
        allAppsRadio.setMinHeight(dp(48));
        oneAppRadio = new RadioButton(this);
        oneAppRadio.setId(View.generateViewId());
        oneAppRadio.setText("One app");
        oneAppRadio.setMinHeight(dp(48));
        sourceModeGroup.addView(allAppsRadio, params(-1, dp(48)));
        sourceModeGroup.addView(oneAppRadio, params(-1, dp(48)));
        allAppsRadio.setChecked(!oneAppMode);
        oneAppRadio.setChecked(oneAppMode);
        sourcePanel.addView(sourceModeGroup, params(-1, -2));

        appChoiceRow = new LinearLayout(this);
        appChoiceRow.setGravity(Gravity.CENTER_VERTICAL);
        appChoiceRow.setPadding(dp(6), dp(4), 0, dp(4));
        selectedAppIcon = new ImageView(this);
        selectedAppIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        appChoiceRow.addView(selectedAppIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        selectedAppLabel = text("Choose an app", 15, true);
        LinearLayout.LayoutParams selectedLabelParams = new LinearLayout.LayoutParams(0, -2, 1);
        selectedLabelParams.leftMargin = dp(10);
        appChoiceRow.addView(selectedAppLabel, selectedLabelParams);
        changeAppButton = new Button(this);
        appChoiceRow.addView(changeAppButton, new LinearLayout.LayoutParams(-2, dp(48)));
        sourcePanel.addView(appChoiceRow, params(-1, -2));

        appSelectionError = text("", 14, false);
        appSelectionError.setTextColor(Color.rgb(176, 39, 39));
        sourcePanel.addView(appSelectionError, params(-1, -2));
        sourceModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (updatingSourceMode) return;
            if (checkedId == oneAppRadio.getId()) requestOneAppMode();
            else if (checkedId == allAppsRadio.getId()) requestAllAppsMode();
        });
        changeAppButton.setOnClickListener(v -> requestAppPicker());
        updateSourceModeUi();

        TextView sourceNote = text("Playback capture copies eligible audio to the selected Mac; it does not make the source app silent on this phone. Some apps, including DRM/protected apps, may block capture and send silence.", 14, false);
        sourceNote.setPadding(0, dp(5), 0, dp(6));
        root.addView(sourceNote, params(-1, -2));

        TextView captureNote = text("Android asks for microphone permission for playback capture; this app does not record microphone audio. Some apps, including DRM/protected apps, may block capture and send silence.", 14, false);
        captureNote.setPadding(0, dp(8), 0, dp(8));
        root.addView(captureNote, params(-1, -2));

        connectionStatus = text("Stopped", 16, false);
        connectionStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(connectionStatus, params(-1, dp(40)));

        volumePanel = new LinearLayout(this);
        volumePanel.setOrientation(LinearLayout.VERTICAL);
        volumePanel.setPadding(dp(14), dp(10), dp(14), dp(10));
        volumePanel.setBackground(cardBackground(false));
        volumePanel.setVisibility(View.GONE);
        TextView volumeTitle = text("Mac speaker volume", 16, true);
        volumePanel.addView(volumeTitle, params(-1, -2));
        LinearLayout volumeLine = new LinearLayout(this);
        volumeLine.setGravity(Gravity.CENTER_VERTICAL);
        volumeSlider = new SeekBar(this);
        volumeSlider.setMax(100);
        volumeLine.addView(volumeSlider, new LinearLayout.LayoutParams(0, dp(48), 1));
        volumeValue = text("—%", 16, true);
        volumeLine.addView(volumeValue, new LinearLayout.LayoutParams(dp(56), -2));
        muteButton = new Button(this);
        muteButton.setText("Mute");
        volumeLine.addView(muteButton, new LinearLayout.LayoutParams(-2, dp(48)));
        volumePanel.addView(volumeLine, params(-1, dp(52)));
        volumeStatus = text("Connect to adjust Mac volume", 14, false);
        volumeStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        volumePanel.addView(volumeStatus, params(-1, -2));
        root.addView(volumePanel, params(-1, -2));
        volumeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) volumeValue.setText(value + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { changingVolume = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                changingVolume = false;
                handler.removeCallbacks(sendVolumeChange);
                handler.postDelayed(sendVolumeChange, 250);
            }
        });
        volumeSlider.setContentDescription("Mac speaker volume");
        muteButton.setOnClickListener(v -> {
            if (confirmedVolume > 0) requestVolume(0);
            else requestVolume(lastAudibleVolume);
        });

        startButton = new Button(this);
        startButton.setText("Start sending");
        root.addView(startButton, params(-1, dp(56)));
        stopButton = new Button(this);
        stopButton.setText("Stop");
        root.addView(stopButton, params(-1, dp(56)));
        stopButton.setEnabled(false);

        host.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!fillingFromDiscovery && selectedName != null) {
                    selectedName = null;
                    selectedHint.setText("");
                    refreshSelectedRows();
                }
                if (!fillingFromDiscovery && pairedReceiver != null) {
                    pairedReceiver = null;
                    pairedReceiverFound = false;
                    updatePairingUi();
                }
                updateStartEnabled();
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        code.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateStartEnabled(); }
            @Override public void afterTextChanged(Editable e) { }
        });
        startButton.setOnClickListener(v -> beginCapture());
        stopButton.setOnClickListener(v -> {
            Intent i = new Intent(this, AudioSenderService.class).setAction(AudioSenderService.ACTION_STOP);
            startService(i);
            setConnectionStatus("Stopping…");
        });
        return scroll;
    }

    private void beginScan() {
        if (scanning) return;
        scanning = true;
        pairedReceiverFound = false;
        receivers.clear();
        receiverRows.clear();
        receiverList.removeAllViews();
        for (PairedReceiver paired : pairedReceivers) {
            ReceiverDiscovery.Receiver offline = new ReceiverDiscovery.Receiver(paired.receiverName, paired.host, 50555, "Not found");
            receivers.put(offline.name, offline);
            ReceiverRow row = new ReceiverRow(offline.name);
            receiverRows.put(offline.name, row);
            receiverList.addView(row.root, params(-1, -2));
            row.update(offline);
        }
        scanButton.setEnabled(false);
        scanButton.setText("Searching…");
        searchSpinner.setVisibility(View.VISIBLE);
        discoveryStatus.setText("Searching for iMac and MacBook receivers… Keep devices on the same trusted Wi-Fi/LAN.");
        if (pairedReceiver != null) pairStatus.setText("Looking for " + pairedReceiver.receiverName + "…");
        discovery.restart();
        handler.removeCallbacks(scanTimeout);
        handler.postDelayed(scanTimeout, 9000);
    }

    private void selectReceiver(ReceiverDiscovery.Receiver receiver) {
        if (connectionActive) {
            Toast.makeText(this, "Stop sending before switching receivers.", Toast.LENGTH_SHORT).show();
            return;
        }
        PairedReceiver match = pairedByServiceName(receiver.name);
        if (match != null) { selectPairedReceiver(match, receiver.status.equals("Available") ? receiver : null); return; }
        pairedReceiver = null;
        pairedReceiverFound = false;
        selectedName = receiver.name;
        fillingFromDiscovery = true;
        host.setText(receiver.address);
        host.setSelection(host.length());
        fillingFromDiscovery = false;
        code.setText("");
        portChip.setText("Port\n" + receiver.port);
        selectedHint.setText("Selected " + receiver.name + " · " + receiver.address + ":" + receiver.port);
        validation.setText("");
        refreshSelectedRows();
        updatePairingUi();
        updateStartEnabled();
    }

    private void selectPairedReceiver(PairedReceiver receiver, ReceiverDiscovery.Receiver discovered) {
        if (connectionActive) {
            Toast.makeText(this, "Stop sending before switching receivers.", Toast.LENGTH_SHORT).show();
            return;
        }
        PairedReceiver selected = discovered == null ? receiver : receiver.withHost(discovered.address);
        controlExecutor.execute(() -> {
            try {
                PairedReceiverStore.save(this, selected, true);
                PairedReceiverStore.State state = PairedReceiverStore.load(this);
                runOnUiThread(() -> {
                    pairedReceivers.clear(); pairedReceivers.addAll(state.receivers);
                    pairedReceiver = state.selected();
                    pairedReceiverFound = discovered != null;
                    selectedName = pairedReceiver.receiverName;
                    fillingFromDiscovery = true;
                    host.setText(pairedReceiver.host);
                    fillingFromDiscovery = false;
                    code.setText("");
                    portChip.setText("Port\n50555");
                    selectedHint.setText("Selected paired " + pairedReceiver.receiverName + " · " + pairedReceiver.host);
                    validation.setText("");
                    updatePairingUi(); refreshSelectedRows();
                });
            } catch (Exception e) {
                runOnUiThread(() -> pairStatus.setText("Could not save the selected receiver. The previous selection is unchanged."));
            }
        });
    }

    private PairedReceiver pairedByServiceName(String serviceName) {
        for (PairedReceiver receiver : pairedReceivers) if (receiver.matchesServiceName(serviceName)) return receiver;
        return null;
    }

    private PairedReceiver pairedById(String deviceId) {
        for (PairedReceiver receiver : pairedReceivers) if (receiver.deviceId.equals(deviceId)) return receiver;
        return null;
    }

    private boolean isSelectedPair(PairedReceiver receiver) {
        return receiver != null && pairedReceiver != null && receiver.deviceId.equals(pairedReceiver.deviceId);
    }

    private void updatePairingUi() {
        if (pairStatus == null) return;
        boolean paired = pairedReceiver != null;
        code.setVisibility(paired ? View.GONE : View.VISIBLE);
        pairButton.setVisibility(paired ? View.GONE : View.VISIBLE);
        unpairButton.setVisibility(paired ? View.VISIBLE : View.GONE);
        host.setEnabled(true);
        qrButton.setEnabled(true);
        scanButton.setText(paired ? "Refresh selected Mac" : "Scan receivers");
        scanButton.setEnabled(!scanning);
        if (paired) pairStatus.setText(pairedReceiverFound
                ? "Selected: " + pairedReceiver.receiverName + " · Found on this network"
                : "Selected: " + pairedReceiver.receiverName + " · Not found yet; refresh to search.");
        else if (pairingStoreError != null) pairStatus.setText(pairingStoreError);
        else if (!pairedReceivers.isEmpty()) pairStatus.setText("Select a paired receiver from the list, or pair another Mac.");
        else pairStatus.setText("Pair this phone with a Mac to stop entering a code next time.");
        pairButton.setEnabled(!paired && pairingStoreError == null);
        updateStartEnabled();
    }

    private void updatePairedReceiverAddress(PairedReceiver previous, ReceiverDiscovery.Receiver receiver) {
        boolean selected = pairedReceiver != null && pairedReceiver.deviceId.equals(previous.deviceId);
        if (selected) pairedReceiverFound = true;
        if (previous.host.equals(receiver.address)) {
            if (selected) pairStatus.setText("Selected: " + previous.receiverName + " · Found on this network");
            ReceiverRow row = receiverRows.get(receiver.name);
            if (row != null) row.update(receiver);
            return;
        }
        if (receiver.address.equals(pendingPairedHosts.get(previous.deviceId))) return;
        PairedReceiver updated = previous.withHost(receiver.address);
        pendingPairedHosts.put(previous.deviceId, receiver.address);
        if (selected) pairStatus.setText(previous.receiverName + " found at a new address. Saving it securely…");
        controlExecutor.execute(() -> {
            try {
                PairedReceiverStore.save(this, updated);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (pairedById(previous.deviceId) != null && pairedById(previous.deviceId).token.equals(previous.token)) {
                        replacePairedReceiver(updated);
                        if (pairedReceiver != null && pairedReceiver.deviceId.equals(previous.deviceId)) {
                            pairedReceiver = updated;
                            pairedReceiverFound = true;
                        fillingFromDiscovery = true;
                        host.setText(updated.host);
                        fillingFromDiscovery = false;
                        selectedHint.setText("Paired with " + updated.receiverName + " · " + updated.host);
                            pairStatus.setText("Selected: " + updated.receiverName + " · Address updated");
                        }
                        ReceiverRow row = receiverRows.get(receiver.name);
                        if (row != null) row.update(receiver);
                    }
                    pendingPairedHosts.remove(previous.deviceId);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    pendingPairedHosts.remove(previous.deviceId);
                    if (selected) pairStatus.setText(previous.receiverName + " found, but its new address could not be saved. Kept the previous address; refresh to retry.");
                    else discoveryStatus.setText(previous.receiverName + " found, but its new address could not be saved.");
                });
            }
        });
    }

    private void replacePairedReceiver(PairedReceiver updated) {
        for (int i = 0; i < pairedReceivers.size(); i++) {
            if (pairedReceivers.get(i).deviceId.equals(updated.deviceId)) {
                pairedReceivers.set(i, updated);
                return;
            }
        }
    }

    private void pairCurrentReceiver() {
        String address = host.getText().toString().trim(), pin = code.getText().toString().trim();
        if (!isLanAddress(address)) { validation.setText("Enter a private or link-local Mac IP address first."); return; }
        if (!pin.matches("\\d{6}")) { validation.setText("Enter the current 6-digit code first."); code.requestFocus(); return; }
        String receiverName = pairedServiceName(address);
        if (receiverName == null) {
            pairStatus.setText("To recover if this Mac’s IP changes, select it from Bonjour or scan a QR that includes its name.");
            return;
        }
        pairButton.setEnabled(false);
        pairStatus.setText("Pairing with Mac…");
        String deviceName = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        controlExecutor.execute(() -> {
            try {
                PairedReceiver paired = PairedReceiverClient.pair(address, pin, deviceName, receiverName);
                try {
                    PairedReceiverStore.save(this, paired, true);
                }
                catch (Exception saveError) {
                    try { PairedReceiverClient.unpair(paired); } catch (Exception ignored) { }
                    throw new IllegalStateException("Pairing could not be saved securely on this phone.");
                }
                PairedReceiverStore.State savedState = PairedReceiverStore.load(this);
                runOnUiThread(() -> {
                    pairedReceivers.clear(); pairedReceivers.addAll(savedState.receivers); pairedReceiver = savedState.selected();
                    pairedReceiverFound = false;
                    code.setText("");
                    selectedName = paired.receiverName;
                    selectedHint.setText("Paired with " + paired.receiverName + " · " + paired.host);
                    validation.setText("");
                    updatePairingUi();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    pairStatus.setText(error.getMessage() == null ? "Pairing failed. Check the code and connection, then retry." : error.getMessage());
                    pairButton.setEnabled(true);
                });
            }
        });
    }

    private String pairedServiceName(String address) {
        if (selectedName != null && !selectedName.isEmpty()) return selectedName;
        for (ReceiverDiscovery.Receiver receiver : receivers.values())
            if (receiver.status.equals("Available") && address.equals(receiver.address)) return receiver.name;
        return null;
    }

    private void confirmUnpair() {
        if (pairedReceiver == null) return;
        new AlertDialog.Builder(this).setTitle("Unpair this phone?")
                .setMessage("The selected Mac will revoke this phone's saved access. You can pair again with a current code.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(connectionActive ? "Stop and unpair" : "Unpair", (dialog, which) -> {
                    if (connectionActive) {
                        pendingUnpairAfterStop = true;
                        startService(new Intent(this, AudioSenderService.class).setAction(AudioSenderService.ACTION_STOP));
                        setConnectionStatus("Stopping…");
                    } else performUnpair();
                }).show();
    }

    private void performUnpair() {
        if (pairedReceiver == null) return;
        PairedReceiver toRemove = pairedReceiver;
        unpairButton.setEnabled(false);
        pairStatus.setText("Unpairing from selected Mac…");
        controlExecutor.execute(() -> {
            try {
                PairedReceiverClient.unpair(toRemove);
                PairedReceiverStore.State state = PairedReceiverStore.remove(this, toRemove.deviceId);
                runOnUiThread(() -> {
                    pairedReceivers.clear(); pairedReceivers.addAll(state.receivers);
                    pairedReceiver = state.selected();
                    pairedReceiverFound = false;
                    code.setText("");
                    if (pairedReceiver != null) {
                        fillingFromDiscovery = true;
                        host.setText(pairedReceiver.host);
                        fillingFromDiscovery = false;
                        selectedName = pairedReceiver.receiverName;
                    } else selectedName = null;
                    updatePairingUi();
                    pairStatus.setText(pairedReceiver == null ? "Unpaired. Select another receiver or enter its code." : "Unpaired the selected receiver. Now selected: " + pairedReceiver.receiverName);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    unpairButton.setEnabled(true);
                    pairStatus.setText("Unpair failed; saved pairing kept. Try again when the Mac is available.");
                });
            }
        });
    }

    private void updateSourceModeUi() {
        if (sourceModeGroup == null) return;
        updatingSourceMode = true;
        allAppsRadio.setChecked(!oneAppMode);
        oneAppRadio.setChecked(oneAppMode);
        updatingSourceMode = false;
        appChoiceRow.setVisibility(oneAppMode ? View.VISIBLE : View.GONE);
        appSelectionError.setVisibility(View.GONE);
        if (selectedCaptureApp != null) {
            selectedAppIcon.setImageDrawable(selectedCaptureApp.icon);
            selectedAppLabel.setText("Only " + selectedCaptureApp.label);
            appChoiceRow.setContentDescription(selectedCaptureApp.label + ", selected");
            appSelectionError.setText("");
            changeAppButton.setText("Change");
        } else {
            selectedAppIcon.setImageDrawable(null);
            selectedAppLabel.setText(staleAppSelection ? "Previously selected app unavailable" : "Choose one app");
            appChoiceRow.setContentDescription(staleAppSelection ? "Previously selected app unavailable" : "Choose one app");
            changeAppButton.setText("Choose app");
            if (staleAppSelection) {
                appSelectionError.setText("This app is no longer available. Choose another app or use all compatible apps.");
                appSelectionError.setVisibility(View.VISIBLE);
            }
        }
        updateStartEnabled();
    }

    private void requestOneAppMode() {
        if (oneAppMode) {
            updateSourceModeUi();
            if (selectedCaptureApp == null) requestAppPicker();
            return;
        }
        if (connectionActive) confirmSourceChange(PENDING_CHOOSE_APP);
        else {
            oneAppMode = true;
            staleAppSelection = CaptureAppSelection.packageName(this) != null && selectedCaptureApp == null;
            updateSourceModeUi();
            requestAppPicker();
        }
    }

    private void requestAllAppsMode() {
        if (!oneAppMode) { updateSourceModeUi(); return; }
        if (connectionActive) confirmSourceChange(PENDING_ALL_APPS);
        else applyAllAppsMode();
    }

    private void confirmSourceChange(int action) {
        new AlertDialog.Builder(this)
                .setTitle("Stop sending to change audio source?")
                .setMessage("Changing the source requires a new Android audio capture session. You’ll need to press Start and approve it again.")
                .setNegativeButton("Keep sending", (dialog, which) -> updateSourceModeUi())
                .setPositiveButton("Stop and choose", (dialog, which) -> {
                    pendingSourceChange = action;
                    startService(new Intent(this, AudioSenderService.class).setAction(AudioSenderService.ACTION_STOP));
                    setConnectionStatus("Stopping…");
                }).show();
        updateSourceModeUi();
    }

    private void confirmSelectApp(CaptureApp app, AlertDialog picker) {
        if (selectedCaptureApp != null && selectedCaptureApp.packageName.equals(app.packageName)
                && selectedCaptureApp.uid == app.uid) {
            picker.dismiss();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Stop sending to change audio source?")
                .setMessage("Stop the current capture and select " + app.label + "? You’ll need to press Start and approve a new Android capture session.")
                .setNegativeButton("Keep sending", (dialog, which) -> picker.dismiss())
                .setPositiveButton("Stop and choose", (dialog, which) -> {
                    pendingCaptureApp = app;
                    pendingSourceChange = PENDING_SELECT_APP;
                    picker.dismiss();
                    startService(new Intent(this, AudioSenderService.class).setAction(AudioSenderService.ACTION_STOP));
                    setConnectionStatus("Stopping…");
                }).show();
    }

    private void applyAllAppsMode() {
        oneAppMode = false;
        selectedCaptureApp = null;
        staleAppSelection = false;
        CaptureAppSelection.clear(this);
        updateSourceModeUi();
    }

    private void requestAppPicker() {
        List<CaptureApp> apps;
        try { apps = CaptureAppCatalog.launchableApps(getPackageManager(), getPackageName()); }
        catch (RuntimeException error) {
            showAppListUnavailable("Couldn’t load apps. Try again or use all compatible apps.");
            return;
        }
        if (apps.isEmpty()) {
            showAppListUnavailable("No launchable apps found.");
            return;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), 0, dp(20), 0);
        EditText search = field("Search apps", InputType.TYPE_CLASS_TEXT);
        content.addView(search, new LinearLayout.LayoutParams(-1, dp(52)));
        TextView empty = text("No matching apps.", 15, false);
        empty.setGravity(Gravity.CENTER);
        content.addView(empty, new LinearLayout.LayoutParams(-1, dp(64)));
        ListView list = new ListView(this);
        AppPickerAdapter adapter = new AppPickerAdapter(apps);
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setFastScrollEnabled(true);
        content.addView(list, new LinearLayout.LayoutParams(-1, dp(360)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Choose app")
                .setView(content).setNegativeButton("Cancel", null).create();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { adapter.filter(s.toString()); }
            @Override public void afterTextChanged(Editable e) { }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            CaptureApp choice = adapter.getItem(position);
            if (choice == null) return;
            if (connectionActive) {
                confirmSelectApp(choice, dialog);
                return;
            }
            selectedCaptureApp = choice;
            staleAppSelection = false;
            oneAppMode = true;
            CaptureAppSelection.save(this, choice);
            validation.setText("");
            updateSourceModeUi();
            dialog.dismiss();
        });
        dialog.show();
    }

    private void showAppListUnavailable(String message) {
        new AlertDialog.Builder(this).setTitle("Apps unavailable").setMessage(message)
                .setNeutralButton("Try again", (dialog, which) -> handler.post(this::requestAppPicker))
                .setPositiveButton("Use all compatible apps", (dialog, which) -> {
                    handler.post(() -> {
                        if (connectionActive) confirmSourceChange(PENDING_ALL_APPS);
                        else applyAllAppsMode();
                    });
                })
                .setNegativeButton("Cancel", (dialog, which) -> updateSourceModeUi()).show();
    }

    private void onSourceStopped() {
        int next = pendingSourceChange;
        pendingSourceChange = PENDING_NONE;
        if (next == PENDING_SELECT_APP) {
            selectedCaptureApp = pendingCaptureApp;
            pendingCaptureApp = null;
            oneAppMode = true;
            staleAppSelection = false;
            if (selectedCaptureApp != null) CaptureAppSelection.save(this, selectedCaptureApp);
            updateSourceModeUi();
            return;
        }
        if (next == PENDING_CHOOSE_APP) {
            oneAppMode = true;
            staleAppSelection = CaptureAppSelection.packageName(this) != null && selectedCaptureApp == null;
            updateSourceModeUi();
            requestAppPicker();
        } else if (next == PENDING_ALL_APPS) applyAllAppsMode();
    }

    private boolean selectedAppIsValid() {
        if (!oneAppMode) return true;
        if (selectedCaptureApp == null || staleAppSelection) return false;
        try {
            int uid = getPackageManager().getApplicationInfo(selectedCaptureApp.packageName, 0).uid;
            if (uid != selectedCaptureApp.uid) {
                List<CaptureApp> apps = CaptureAppCatalog.launchableApps(getPackageManager(), getPackageName());
                for (CaptureApp app : apps) if (app.packageName.equals(selectedCaptureApp.packageName)) {
                    selectedCaptureApp = app;
                    CaptureAppSelection.save(this, app);
                    return true;
                }
                return false;
            }
            return true;
        } catch (Exception ignored) { return false; }
    }

    private void scanPairingQr() {
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build();
        GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener(barcode -> {
                    try {
                        PairingQr.Pairing pairing = PairingQr.parse(barcode.getRawValue());
                        PairedReceiver existing = pairing.hasName ? pairedByServiceName(pairing.name) : null;
                        if (existing != null) {
                            ReceiverDiscovery.Receiver resolved = new ReceiverDiscovery.Receiver(existing.receiverName, pairing.host, 50555, "Available");
                            selectPairedReceiver(existing, resolved);
                            return;
                        }
                        pairedReceiver = null;
                        pairedReceiverFound = false;
                        fillingFromDiscovery = true;
                        host.setText(pairing.host); host.setSelection(host.length());
                        code.setText(pairing.pin);
                        portChip.setText("Port\n50555");
                        selectedName = pairing.hasName ? pairing.name : null;
                        selectedHint.setText("Details filled for " + pairing.name + ". Press Start sending when ready.");
                        validation.setText(""); refreshSelectedRows(); updateStartEnabled();
                    } catch (IllegalArgumentException e) {
                        validation.setText("This QR is not a supported LAN Audio pairing code.");
                    } finally { fillingFromDiscovery = false; }
                })
                .addOnCanceledListener(() -> { })
                .addOnFailureListener(error -> validation.setText("Couldn’t scan this QR. Enter details manually or try again."));
    }

    private void refreshSelectedRows() {
        for (Map.Entry<String, ReceiverRow> entry : receiverRows.entrySet()) {
            ReceiverDiscovery.Receiver receiver = receivers.get(entry.getKey());
            if (receiver != null) entry.getValue().update(receiver);
            else entry.getValue().setSelected(entry.getKey().equals(selectedName));
        }
    }

    private void beginCapture() {
        String address = host.getText().toString().trim();
        String pin = code.getText().toString().trim();
        if (!isLanAddress(address)) {
            validation.setText("Enter a private or link-local IP address.");
            host.requestFocus();
            return;
        }
        if (pairedReceiver == null && !pin.matches("\\d{6}")) {
            validation.setText("Enter the 6-digit pairing code.");
            code.requestFocus();
            return;
        }
        if (!selectedAppIsValid()) {
            validation.setText("Choose an available app, or switch to All compatible apps.");
            if (oneAppMode) requestAppPicker();
            updateStartEnabled();
            return;
        }
        validation.setText("");
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_REQUEST);
            return;
        }
        showCaptureConsent();
    }

    private void showCaptureConsent() {
        Intent permission = ((MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE)).createScreenCaptureIntent();
        startActivityForResult(permission, CAPTURE_REQUEST);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == RECORD_REQUEST) {
            if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) showCaptureConsent();
            else if (connectionStatus != null) setConnectionStatus("Microphone permission is required by Android for playback capture; no mic audio is recorded.");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CAPTURE_REQUEST) return;
        if (resultCode != RESULT_OK || data == null) { setConnectionStatus("Capture permission denied."); return; }
        if (!selectedAppIsValid()) {
            setConnectionStatus("Selected app is no longer available. Choose it again before starting.");
            updateStartEnabled();
            return;
        }
        Intent service = new Intent(this, AudioSenderService.class).setAction(AudioSenderService.ACTION_START)
                .putExtra("host", pairedReceiver == null ? host.getText().toString().trim() : pairedReceiver.host)
                .putExtra("code", pairedReceiver == null ? code.getText().toString().trim() : "")
                .putExtra("resultCode", resultCode).putExtra("projection", data);
        if (pairedReceiver != null) service.putExtra("pairedDeviceId", pairedReceiver.deviceId).putExtra("pairedToken", pairedReceiver.token);
        if (oneAppMode && selectedCaptureApp != null) service.putExtra("selectedUid", selectedCaptureApp.uid)
                .putExtra("selectedPackage", selectedCaptureApp.packageName).putExtra("captureLabel", selectedCaptureApp.label);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        setConnectionStatus("Connecting…");
    }

    private void setConnectionStatus(String text) {
        if (connectionStatus == null) return;
        connectionStatus.setText(text == null ? "Stopped" : text);
        connectionActive = text != null && (text.startsWith("Connecting") || text.startsWith("Sending") || text.startsWith("Stopping"));
        if (stopButton != null) stopButton.setEnabled(connectionActive);
        boolean sending = text != null && text.startsWith("Sending");
        if (volumePanel != null) {
            volumePanel.setVisibility(sending ? View.VISIBLE : View.GONE);
            if (sending) loadMacVolume();
            else { confirmedVolume = -1; volumeStatus.setText("Connect to adjust Mac volume"); }
        }
        if ("Stopped".equals(text) && pendingSourceChange != PENDING_NONE) onSourceStopped();
        if ("Stopped".equals(text) && pendingUnpairAfterStop) {
            pendingUnpairAfterStop = false;
            performUnpair();
        }
    }

    private void loadMacVolume() {
        String address = host.getText().toString().trim(), pin = code.getText().toString().trim();
        volumeStatus.setText("Reading Mac volume…");
        volumeSlider.setEnabled(false); muteButton.setEnabled(false);
        controlExecutor.execute(() -> {
            try {
                int value = (pairedReceiver == null ? RemoteControlClient.getVolume(address, pin)
                        : PairedReceiverClient.getVolume(pairedReceiver)).volume;
                runOnUiThread(() -> showConfirmedVolume(value));
            } catch (Exception e) { runOnUiThread(() -> showVolumeError(e, false)); }
        });
    }

    private void requestVolume(int value) {
        if (!connectionActive) return;
        String address = host.getText().toString().trim(), pin = code.getText().toString().trim();
        volumeStatus.setText("Updating…");
        controlExecutor.execute(() -> {
            try {
                RemoteControlClient.Result result = pairedReceiver == null ? RemoteControlClient.setVolume(address, pin, value)
                        : PairedReceiverClient.setVolume(pairedReceiver, value);
                runOnUiThread(() -> showConfirmedVolume(result.volume));
            } catch (Exception e) { runOnUiThread(() -> showVolumeError(e, true)); }
        });
    }

    private void showConfirmedVolume(int value) {
        confirmedVolume = value;
        if (value > 0) lastAudibleVolume = value;
        volumeSlider.setProgress(value);
        volumeValue.setText(value + "%");
        volumeSlider.setContentDescription("Mac speaker volume, " + value + " percent");
        volumeStatus.setText("Mac output volume");
        volumeSlider.setEnabled(connectionActive); muteButton.setEnabled(connectionActive);
        muteButton.setText(value == 0 ? "Unmute" : "Mute");
    }

    private void showVolumeError(Exception error, boolean restoreLastConfirmed) {
        if (restoreLastConfirmed && confirmedVolume >= 0) {
            volumeSlider.setProgress(confirmedVolume);
            volumeValue.setText(confirmedVolume + "%");
        }
        volumeStatus.setText("Couldn’t change/read Mac volume: " + (error.getMessage() == null ? "receiver unavailable" : error.getMessage()));
        volumeSlider.setEnabled(false); muteButton.setEnabled(false);
    }

    private void updateStartEnabled() {
        if (startButton != null) startButton.setEnabled(isLanAddress(host.getText().toString().trim())
                && (pairedReceiver != null || code.getText().toString().trim().matches("\\d{6}"))
                && (!oneAppMode || (selectedCaptureApp != null && !staleAppSelection)));
    }

    private boolean isLanAddress(String value) {
        if (!value.matches("[0-9a-fA-F:.%]+")) return false;
        try {
            InetAddress address = InetAddress.getByName(value);
            if (address.isLoopbackAddress()) return false;
            if (address.isLinkLocalAddress() || address.isSiteLocalAddress()) return true;
            if (address instanceof Inet6Address) { byte first = address.getAddress()[0]; return (first & 0xfe) == 0xfc; }
        } catch (Exception ignored) { }
        return false;
    }

    private EditText field(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setSingleLine(true); e.setHint(hint); e.setTextSize(16); e.setInputType(inputType);
        e.setPadding(dp(12), 0, dp(12), 0);
        return e;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(Color.rgb(28, 30, 34));
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private GradientDrawable cardBackground(boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.WHITE); d.setCornerRadius(dp(15)); d.setStroke(dp(selected ? 2 : 1), selected ? Color.rgb(40, 105, 180) : Color.rgb(218, 222, 229));
        return d;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height); p.bottomMargin = dp(10); return p;
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }

    private final class ReceiverRow {
        final LinearLayout root = new LinearLayout(MainActivity.this);
        final TextView name = text("", 16, true);
        final TextView detail = text("", 14, false);
        ReceiverDiscovery.Receiver receiver;
        ReceiverRow(String label) {
            root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(12), dp(9), dp(12), dp(9)); root.setMinimumHeight(dp(72)); root.setFocusable(true);
            root.addView(name, params(-1, -2)); root.addView(detail, params(-1, -2));
            name.setText(label);
        }
        void update(ReceiverDiscovery.Receiver value) {
            receiver = value;
            name.setText(value.name);
            PairedReceiver paired = pairedByServiceName(value.name);
            String pairState = paired == null ? "Not paired" : (isSelectedPair(paired) ? "Paired · selected" : "Paired");
            detail.setText((value.address.isEmpty() ? value.status : value.address + " · " + value.status) + " · " + pairState);
            root.setContentDescription(value.name + ", " + pairState + ", " + value.status
                    + (value.address.isEmpty() ? "" : ", " + value.address) + ", port " + value.port
                    + (value.name.equals(selectedName) ? ", selected" : ""));
            root.setSelected(value.name.equals(selectedName));
            setSelected(value.name.equals(selectedName));
            root.setOnClickListener(v -> {
                if (receiver.status.equals("Available") || paired != null) selectReceiver(receiver);
                else if (receiver.status.equals("Couldn’t resolve")) discovery.retry(receiver.name);
            });
            root.setEnabled(value.status.equals("Available") || value.status.equals("Couldn’t resolve") || paired != null);
        }
        void setSelected(boolean selected) { root.setBackground(cardBackground(selected)); }
    }

    private final class AppPickerAdapter extends BaseAdapter {
        private final List<CaptureApp> all;
        private final List<CaptureApp> visible = new ArrayList<>();
        AppPickerAdapter(List<CaptureApp> apps) { all = new ArrayList<>(apps); visible.addAll(apps); }
        void filter(String query) {
            String needle = query.trim().toLowerCase(Locale.getDefault());
            visible.clear();
            for (CaptureApp app : all) {
                if (needle.isEmpty() || app.label.toLowerCase(Locale.getDefault()).contains(needle)
                        || app.packageName.toLowerCase(Locale.ROOT).contains(needle)) visible.add(app);
            }
            notifyDataSetChanged();
        }
        @Override public int getCount() { return visible.size(); }
        @Override public CaptureApp getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return getItem(position).packageName.hashCode(); }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            ImageView icon;
            TextView label, selected;
            if (recycled == null || recycled.getTag() == null) {
                row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(4), dp(8), dp(4));
                row.setMinimumHeight(dp(56));
                icon = new ImageView(MainActivity.this);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
                label = text("", 16, false);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
                labelParams.leftMargin = dp(12);
                row.addView(label, labelParams);
                selected = text("✓", 18, true);
                selected.setGravity(Gravity.CENTER);
                row.addView(selected, new LinearLayout.LayoutParams(dp(32), dp(48)));
                row.setTag(new AppRowHolder(icon, label, selected));
            } else {
                row = (LinearLayout)recycled;
                AppRowHolder holder = (AppRowHolder)row.getTag();
                icon = holder.icon; label = holder.label; selected = holder.selected;
            }
            CaptureApp app = getItem(position);
            icon.setImageDrawable(app.icon);
            label.setText(app.label);
            boolean isSelected = selectedCaptureApp != null && selectedCaptureApp.packageName.equals(app.packageName);
            selected.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            row.setContentDescription(app.label + (isSelected ? ", selected" : ", not selected"));
            return row;
        }
    }

    private static final class AppRowHolder {
        final ImageView icon;
        final TextView label, selected;
        AppRowHolder(ImageView icon, TextView label, TextView selected) {
            this.icon = icon; this.label = label; this.selected = selected;
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(scanTimeout);
        if (discovery != null) discovery.stop();
        handler.removeCallbacks(sendVolumeChange);
        controlExecutor.shutdownNow();
        unregisterReceiver(stateReceiver);
        super.onDestroy();
    }
}
