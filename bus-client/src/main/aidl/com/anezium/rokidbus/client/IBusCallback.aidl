package com.anezium.rokidbus.client;

oneway interface IBusCallback {
    void onMessage(String path, String id, in byte[] payload);
    void onLinkState(int state);
    void onBinaryMessage(String path, String id, in byte[] meta, in byte[] data);
}
