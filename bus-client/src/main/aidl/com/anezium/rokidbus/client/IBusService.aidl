package com.anezium.rokidbus.client;

import com.anezium.rokidbus.client.IBusCallback;

interface IBusService {
    int apiVersion();
    void register(String clientId, in String[] pathPrefixes, IBusCallback cb);
    void unregister(in IBusCallback cb);
    oneway void send(String path, String id, in byte[] payload);
    int linkState();
    oneway void sendBinary(String path, String id, in byte[] meta, in byte[] data);
    int capabilities();
}
