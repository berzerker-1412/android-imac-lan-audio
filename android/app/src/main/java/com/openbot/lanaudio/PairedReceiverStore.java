package com.openbot.lanaudio;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private multi-receiver ciphertext protected by an Android Keystore AES-GCM key. */
final class PairedReceiverStore {
    private static final String PREFS = "paired_receiver_ciphertext";
    private static final String VALUE = "record";
    private static final String ALIAS = "openbot_lan_audio_pairing_v1";

    static final class State {
        final List<PairedReceiver> receivers;
        final String selectedDeviceId;
        State(List<PairedReceiver> receivers, String selectedDeviceId) {
            this.receivers = receivers; this.selectedDeviceId = selectedDeviceId;
        }
        PairedReceiver selected() {
            for (PairedReceiver receiver : receivers) if (receiver.deviceId.equals(selectedDeviceId)) return receiver;
            return receivers.isEmpty() ? null : receivers.get(0);
        }
    }

    static State load(Context context) throws Exception {
        String encoded = prefs(context).getString(VALUE, null);
        if (encoded == null) return new State(new ArrayList<>(), null);
        byte[] blob = Base64.decode(encoded, Base64.NO_WRAP);
        if (blob.length < 30) throw new IllegalStateException("Saved pairing data is invalid.");
        int ivLength = blob[0] & 0xff;
        if (ivLength < 12 || ivLength > 16 || blob.length <= ivLength + 1) throw new IllegalStateException("Saved pairing data is invalid.");
        byte[] iv = new byte[ivLength], ciphertext = new byte[blob.length - ivLength - 1];
        System.arraycopy(blob, 1, iv, 0, ivLength);
        System.arraycopy(blob, ivLength + 1, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return decodeState(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
    }

    static State decodeState(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        List<PairedReceiver> receivers = new ArrayList<>();
        String selectedId;
        if (root.has("pairedReceivers")) {
            JSONArray array = root.getJSONArray("pairedReceivers");
            for (int i = 0; i < array.length(); i++) receivers.add(readReceiver(array.getJSONObject(i)));
            selectedId = root.optString("selectedDeviceId", null);
        } else {
            // Migrate the previous single-receiver encrypted record in memory; the next save writes v2.
            receivers.add(readReceiver(root));
            selectedId = receivers.get(0).deviceId;
        }
        State state = new State(receivers, selectedId);
        return new State(receivers, state.selected() == null ? null : state.selected().deviceId);
    }

    static void save(Context context, PairedReceiver receiver) throws Exception { save(context, receiver, false); }

    static void save(Context context, PairedReceiver receiver, boolean makeSelected) throws Exception {
        persist(context, upsert(load(context), receiver, makeSelected));
    }

    static State upsert(State old, PairedReceiver receiver, boolean makeSelected) {
        List<PairedReceiver> receivers = new ArrayList<>(old.receivers);
        boolean replaced = false;
        for (int i = 0; i < receivers.size(); i++) {
            if (receivers.get(i).deviceId.equals(receiver.deviceId)) { receivers.set(i, receiver); replaced = true; break; }
        }
        if (!replaced) receivers.add(receiver);
        String selected = makeSelected || old.selectedDeviceId == null ? receiver.deviceId : old.selectedDeviceId;
        return new State(receivers, selected);
    }

    static State select(Context context, String deviceId) throws Exception {
        State old = load(context);
        boolean exists = false;
        for (PairedReceiver receiver : old.receivers) if (receiver.deviceId.equals(deviceId)) exists = true;
        if (!exists) throw new IllegalArgumentException("Paired iMac was not found.");
        State selected = new State(new ArrayList<>(old.receivers), deviceId);
        persist(context, selected);
        return selected;
    }

    static State remove(Context context, String deviceId) throws Exception {
        State old = load(context);
        State result = without(old, deviceId);
        if (result.receivers.size() == old.receivers.size()) return old;
        if (result.receivers.isEmpty()) clear(context); else persist(context, result);
        return result;
    }

    static State without(State old, String deviceId) {
        List<PairedReceiver> remaining = new ArrayList<>();
        for (PairedReceiver receiver : old.receivers) if (!receiver.deviceId.equals(deviceId)) remaining.add(receiver);
        if (remaining.size() == old.receivers.size()) return old;
        String selected = deviceId.equals(old.selectedDeviceId) ? (remaining.isEmpty() ? null : remaining.get(0).deviceId) : old.selectedDeviceId;
        return new State(remaining, selected);
    }

    static void clear(Context context) {
        if (!prefs(context).edit().remove(VALUE).commit()) throw new IllegalStateException("Could not clear saved pairing from this phone.");
    }

    static String encodeState(State state) throws Exception {
        JSONArray array = new JSONArray();
        for (PairedReceiver receiver : state.receivers) array.put(writeReceiver(receiver));
        return new JSONObject().put("version", 2).put("selectedDeviceId", state.selectedDeviceId).put("pairedReceivers", array).toString();
    }

    private static JSONObject writeReceiver(PairedReceiver receiver) throws Exception {
        return new JSONObject().put("deviceId", receiver.deviceId).put("token", receiver.token).put("host", receiver.host)
                .put("name", receiver.name).put("receiverName", receiver.receiverName);
    }

    private static PairedReceiver readReceiver(JSONObject value) throws Exception {
        return new PairedReceiver(value.getString("deviceId"), value.getString("token"), value.getString("host"),
                value.getString("name"), value.optString("receiverName", "iMac"));
    }

    private static void persist(Context context, State state) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV(), encrypted = cipher.doFinal(encodeState(state).getBytes(StandardCharsets.UTF_8));
        byte[] blob = new byte[1 + iv.length + encrypted.length];
        blob[0] = (byte)iv.length;
        System.arraycopy(iv, 0, blob, 1, iv.length);
        System.arraycopy(encrypted, 0, blob, iv.length + 1, encrypted.length);
        if (!prefs(context).edit().putString(VALUE, Base64.encodeToString(blob, Base64.NO_WRAP)).commit())
            throw new IllegalStateException("Could not save pairing data securely on this phone.");
    }

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey)existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private PairedReceiverStore() { }
}
