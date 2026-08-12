package com.anezium.rokidbus.client;

import com.anezium.rokidbus.client.IBusCallback;
import android.os.ParcelFileDescriptor;

interface IBusService {
    int apiVersion();
    void register(String clientId, in String[] pathPrefixes, IBusCallback cb);
    void unregister(in IBusCallback cb);
    oneway void send(String path, String id, in byte[] payload);
    int linkState();
    oneway void sendBinary(String path, String id, in byte[] meta, in byte[] data);
    int registerPlugin(String packageName, String pluginId, IBusCallback cb);
    int capabilities();
    /**
     * The caller's own approved capabilities, comma-separated, or "" when it has none.
     *
     * registerPlugin answers APPROVED synchronously while the grant list follows behind
     * it as a /plugin/registration message. A plugin that acts the instant it is
     * approved therefore reads an empty grant set and is refused a capability the
     * wearer did approve. This closes that window without waiting for the message.
     *
     * Scoped to the calling UID: it never reports another plugin's grants.
     * Appended last on purpose - AIDL transaction ids are ordinal, so an older hub
     * fails this one call and the SDK falls back to waiting for the message.
     */
    String approvedCapabilities(String pluginId);
    /** Appended last: the hub authenticates the caller and validates its live bulk-link lease. */
    ParcelFileDescriptor openBulkChannel(String sessionId, String purpose);
}
