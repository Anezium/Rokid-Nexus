package com.anezium.rokidbus.phone.selfarm.adb;

import java.io.IOException;

public interface ManualAdbSession extends AutoCloseable {
    String getHost();

    int getPort();

    ManualShellResult shell(String command) throws IOException;

    @Override
    void close();
}
