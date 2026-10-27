package com.openbot.lanaudio;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public final class OutputCapabilityTest {
    @Test public void parsesOutputCapabilitiesWithoutAssumingVolumeSupport() throws Exception {
        JSONObject response = new JSONObject("{\"outputs\":[{\"uid\":\"speaker-1\",\"name\":\"Built-in Output\",\"is_default\":true,\"selectable\":true,\"volume_readable\":false,\"volume_settable\":false}]}");
        java.util.List<PairedReceiverClient.AudioOutput> outputs = PairedReceiverClient.parseOutputs(response);
        assertEquals(1, outputs.size());
        assertEquals("speaker-1", outputs.get(0).uid);
        assertTrue(outputs.get(0).isDefault);
        assertFalse(outputs.get(0).volumeReadable);
        assertFalse(outputs.get(0).volumeSettable);
        assertNull(outputs.get(0).volume);
    }

    @Test public void onlyExplicitUnsupportedCommandErrorsTriggerLegacyFallback() {
        assertTrue(PairedReceiverClient.isUnsupportedOutputCommand("unsupported command"));
        assertTrue(PairedReceiverClient.isUnsupportedOutputCommand("list_outputs requires paired LANCTRL2 authentication"));
        assertFalse(PairedReceiverClient.isUnsupportedOutputCommand("output list exceeds control response limit"));
        assertFalse(PairedReceiverClient.isUnsupportedOutputCommand("receiver unavailable"));
    }
}
