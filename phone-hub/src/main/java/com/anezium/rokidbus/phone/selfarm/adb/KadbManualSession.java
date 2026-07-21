package com.anezium.rokidbus.phone.selfarm.adb;

import com.flyfishxu.kadb.Kadb;

import java.io.IOException;

public final class KadbManualSession implements ManualAdbSession {
    private final String host;
    private final int port;
    private final Kadb kadb;

    private KadbManualSession(String host, int port, Kadb kadb) {
        this.host = host;
        this.port = port;
        this.kadb = kadb;
    }

    public static KadbManualSession connect(String host, int port) throws IOException {
        KadbManualSession session = new KadbManualSession(host, port, new Kadb(host, port, 5000, 15000));
        try {
            ManualShellResult probe = session.shell("echo rokid-nexus-manual");
            if (!probe.getOutput().trim().equals("rokid-nexus-manual")) {
                throw new IOException("Unexpected ADB probe response: " + probe.getAllOutput());
            }
            return session;
        } catch (IOException exception) {
            session.close();
            throw exception;
        }
    }

    @Override
    public String getHost() {
        return host;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public ManualShellResult shell(String command) throws IOException {
        try {
            com.flyfishxu.kadb.shell.AdbShellResponse response = kadb.shell(command);
            return new ManualShellResult(
                    response.getOutput(),
                    response.getErrorOutput(),
                    response.getExitCode());
        } catch (RuntimeException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
    }

    @Override
    public void close() {
        try {
            kadb.close();
        } catch (RuntimeException ignored) {
            // Best-effort transport cleanup.
        }
    }
}
