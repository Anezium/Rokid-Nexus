package com.anezium.rokidbus.phone.selfarm.adb;

import android.content.Context;
import android.util.Log;

import com.flyfishxu.kadb.Kadb;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;

/** Phone-side KADB pairing/session transport ported from Rokid Relay issue #4. */
public final class ManualAdbClient {
    private static final String TAG = "NexusManualAdb";
    private static final long PAIRING_TIMEOUT_MS = 12000L;
    private static final int REACHABILITY_TIMEOUT_MS = 2500;
    private static volatile boolean kadbCertConfigured;

    private final Context context;

    public ManualAdbClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public void pairWirelessDebugging(String host, int port, String code) throws IOException {
        String cleanHost = host == null ? "" : host.trim();
        String cleanCode = code == null ? "" : code.trim();
        if (cleanHost.isEmpty() || cleanCode.isEmpty() || port <= 0) {
            throw new IOException("Wireless Debugging pairing details are incomplete");
        }
        ensureReachable(cleanHost, port, "pairing");
        configureKadbCert();
        Log.i(TAG, "pair start host=" + redactHost(cleanHost) + " port=" + port);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread pairingThread = new Thread(() -> {
            try {
                runKadbPair(cleanHost, port, cleanCode);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        }, "nexus-manual-adb-pair");
        pairingThread.setDaemon(true);
        pairingThread.start();
        long deadline = android.os.SystemClock.elapsedRealtime() + PAIRING_TIMEOUT_MS;
        try {
            while (done.getCount() > 0) {
                long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0L) {
                    pairingThread.interrupt();
                    throw new IOException("Wireless Debugging pairing timed out");
                }
                done.await(Math.min(remaining, 250L), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            pairingThread.interrupt();
            Thread.currentThread().interrupt();
            throw new IOException("Wireless Debugging pairing was interrupted", exception);
        }
        Throwable cause = failure.get();
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        if (cause != null) {
            throw new IOException("Wireless Debugging pairing failed: " + shortMessage(cause), cause);
        }
        Log.i(TAG, "pair success host=" + redactHost(cleanHost) + " port=" + port);
    }

    public ManualAdbSession connect(String host, int port) throws IOException {
        String cleanHost = host == null ? "" : host.trim();
        if (cleanHost.isEmpty() || port <= 0) {
            throw new IOException("Wireless Debugging connect details are incomplete");
        }
        ensureReachable(cleanHost, port, "connect");
        configureKadbCert();
        Log.i(TAG, "connect start host=" + redactHost(cleanHost) + " port=" + port);
        try {
            return KadbManualSession.connect(cleanHost, port);
        } catch (Throwable throwable) {
            throw new IOException(
                    "Could not open the Wireless Debugging shell at "
                            + redactHost(cleanHost) + ":" + port + ": " + shortMessage(throwable),
                    throwable);
        }
    }

    private void runKadbPair(String host, int port, String code) throws InterruptedException {
        BuildersKt.runBlocking(
                EmptyCoroutineContext.INSTANCE,
                (CoroutineScope scope, Continuation<? super Unit> continuation) ->
                        Kadb.Companion.pair(host, port, code, "Rokid Nexus", continuation));
    }

    private synchronized void configureKadbCert() {
        if (kadbCertConfigured) {
            return;
        }
        File privateKey = new File(new File(context.getFilesDir(), "kadb"), "adbkey.pem");
        File directory = privateKey.getParentFile();
        if (directory != null && !directory.isDirectory()
                && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Could not create KADB key directory");
        }
        com.flyfishxu.kadb.cert.KadbCert.INSTANCE.configure(
                new com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore(
                        okio.Path.Companion.get(privateKey.getAbsolutePath()),
                        okio.FileSystem.SYSTEM),
                new com.flyfishxu.kadb.cert.KadbCertPolicy(),
                Collections.emptyList());
        kadbCertConfigured = true;
    }

    private void ensureReachable(String host, int port, String label) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), REACHABILITY_TIMEOUT_MS);
        } catch (IOException exception) {
            throw new IOException(
                    "Phone and glasses must be on the same Wi-Fi. Could not reach the Wireless "
                            + "Debugging " + label + " endpoint at " + redactHost(host) + ":" + port + ".",
                    exception);
        }
    }

    private static String redactHost(String host) {
        String clean = host == null ? "" : host.trim();
        int lastDot = clean.lastIndexOf('.');
        return lastDot > 0 ? clean.substring(0, lastDot + 1) + "x" : "redacted";
    }

    private static String shortMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message.trim();
    }
}
