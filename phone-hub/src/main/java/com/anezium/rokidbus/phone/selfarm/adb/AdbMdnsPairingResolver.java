package com.anezium.rokidbus.phone.selfarm.adb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Relay-proven Android NSD resolver for Wireless Debugging pairing and shell services. */
public final class AdbMdnsPairingResolver {
    private static final String TAG = "NexusManualMdns";
    private static final String ADB_TLS_PAIRING_TYPE = "_adb-tls-pairing._tcp.";
    private static final String ADB_TLS_CONNECT_TYPE = "_adb-tls-connect._tcp.";
    private static final long DEFAULT_TIMEOUT_MS = 8000L;
    private static final long CONNECT_SETTLE_MS = 900L;

    private final Context context;

    public AdbMdnsPairingResolver(Context context) {
        this.context = context.getApplicationContext();
    }

    public Endpoint resolvePairingEndpoint(String expectedHost) throws IOException {
        return resolvePairingEndpoint(expectedHost, DEFAULT_TIMEOUT_MS);
    }

    public Endpoint resolvePairingEndpoint(String expectedHost, long timeoutMs) throws IOException {
        return resolveEndpoint(
                expectedHost,
                timeoutMs,
                ADB_TLS_PAIRING_TYPE,
                "_adb-tls-pairing",
                "pairing",
                false);
    }

    public Endpoint resolveConnectEndpoint(String expectedHost) throws IOException {
        return resolveConnectEndpoint(expectedHost, DEFAULT_TIMEOUT_MS);
    }

    public Endpoint resolveConnectEndpoint(String expectedHost, long timeoutMs) throws IOException {
        return resolveEndpoint(
                expectedHost,
                timeoutMs,
                ADB_TLS_CONNECT_TYPE,
                "_adb-tls-connect",
                "connect",
                true);
    }

    private Endpoint resolveEndpoint(
            String expectedHost,
            long timeoutMs,
            String serviceType,
            String serviceNeedle,
            String label,
            boolean preferLastEndpoint) throws IOException {
        NsdManager manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (manager == null) {
            throw new IOException("Android NSD service is unavailable");
        }
        return new DiscoveryRun(
                manager,
                cleanHost(expectedHost),
                timeoutMs,
                serviceType,
                serviceNeedle,
                label,
                preferLastEndpoint).execute();
    }

    public static final class Endpoint {
        public final String host;
        public final int port;

        public Endpoint(String host, int port) {
            this.host = host == null ? "" : host;
            this.port = port;
        }
    }

    private final class DiscoveryRun {
        private final NsdManager manager;
        private final String expectedHost;
        private final long timeoutMs;
        private final String serviceType;
        private final String serviceNeedle;
        private final String label;
        private final boolean preferLastEndpoint;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final Queue<NsdServiceInfo> pending = new ArrayDeque<>();
        private final Object lock = new Object();

        private Endpoint result;
        private String failure = "";
        private boolean discoveryStarted;
        private boolean resolving;
        private boolean stopped;
        private boolean settleStarted;

        private final NsdManager.DiscoveryListener discoveryListener =
                new NsdManager.DiscoveryListener() {
                    @Override
                    public void onDiscoveryStarted(String type) {
                        Log.d(TAG, label + " discovery started");
                    }

                    @Override
                    public void onServiceFound(NsdServiceInfo serviceInfo) {
                        if (!isTargetService(serviceInfo)) {
                            return;
                        }
                        synchronized (lock) {
                            pending.add(serviceInfo);
                        }
                        pumpResolve();
                    }

                    @Override
                    public void onServiceLost(NsdServiceInfo serviceInfo) {
                        Log.d(TAG, label + " service lost");
                    }

                    @Override
                    public void onDiscoveryStopped(String type) {
                        Log.d(TAG, label + " discovery stopped");
                    }

                    @Override
                    public void onStartDiscoveryFailed(String type, int errorCode) {
                        completeFailure("mDNS " + label + " discovery failed to start: " + errorCode);
                    }

                    @Override
                    public void onStopDiscoveryFailed(String type, int errorCode) {
                        Log.d(TAG, label + " discovery stop failed: " + errorCode);
                    }
                };

        DiscoveryRun(
                NsdManager manager,
                String expectedHost,
                long timeoutMs,
                String serviceType,
                String serviceNeedle,
                String label,
                boolean preferLastEndpoint) {
            this.manager = manager;
            this.expectedHost = expectedHost;
            this.timeoutMs = timeoutMs;
            this.serviceType = serviceType;
            this.serviceNeedle = serviceNeedle;
            this.label = label;
            this.preferLastEndpoint = preferLastEndpoint;
        }

        Endpoint execute() throws IOException {
            WifiManager.MulticastLock multicastLock = acquireMulticastLock();
            try {
                try {
                    manager.discoverServices(
                            serviceType,
                            NsdManager.PROTOCOL_DNS_SD,
                            discoveryListener);
                    discoveryStarted = true;
                } catch (RuntimeException exception) {
                    throw new IOException("Could not start mDNS " + label + " discovery", exception);
                }
                try {
                    finished.await(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("mDNS " + label + " discovery was interrupted", exception);
                }
                synchronized (lock) {
                    if (result != null) {
                        return result;
                    }
                    if (!failure.isEmpty()) {
                        throw new IOException(failure);
                    }
                }
                throw new IOException("No matching Wireless Debugging " + label + " mDNS service found");
            } finally {
                stopDiscovery();
                if (multicastLock != null && multicastLock.isHeld()) {
                    try {
                        multicastLock.release();
                    } catch (RuntimeException ignored) {
                        // Best-effort multicast cleanup.
                    }
                }
            }
        }

        private WifiManager.MulticastLock acquireMulticastLock() {
            WifiManager wifiManager =
                    (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return null;
            }
            try {
                WifiManager.MulticastLock multicastLock =
                        wifiManager.createMulticastLock("nexus-manual-adb-" + label);
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
                return multicastLock;
            } catch (RuntimeException exception) {
                Log.d(TAG, "multicast lock unavailable");
                return null;
            }
        }

        private boolean isTargetService(NsdServiceInfo serviceInfo) {
            String value = serviceInfo == null ? "" : serviceInfo.getServiceType();
            return value != null && value.toLowerCase(Locale.US).contains(serviceNeedle);
        }

        private void pumpResolve() {
            NsdServiceInfo next;
            synchronized (lock) {
                if (stopped || resolving || (result != null && !preferLastEndpoint)) {
                    return;
                }
                next = pending.poll();
                if (next == null) {
                    return;
                }
                resolving = true;
            }
            try {
                manager.resolveService(next, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                        Log.d(TAG, label + " service resolve failed: " + errorCode);
                        finishResolveAndContinue();
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo serviceInfo) {
                        Endpoint endpoint = endpointFrom(serviceInfo);
                        if (endpoint != null && hostMatches(endpoint.host)) {
                            Log.d(
                                    TAG,
                                    label + " service resolved host="
                                            + redactedHost(endpoint.host)
                                            + " port=" + endpoint.port);
                            if (preferLastEndpoint) {
                                rememberEndpoint(endpoint);
                                finishResolveAndContinue();
                            } else {
                                complete(endpoint);
                            }
                            return;
                        }
                        finishResolveAndContinue();
                    }
                });
            } catch (RuntimeException exception) {
                finishResolveAndContinue();
            }
        }

        private Endpoint endpointFrom(NsdServiceInfo serviceInfo) {
            if (serviceInfo == null || serviceInfo.getPort() <= 0) {
                return null;
            }
            InetAddress address = serviceInfo.getHost();
            if (!(address instanceof Inet4Address)) {
                return null;
            }
            String host = cleanHost(address.getHostAddress());
            return host.isEmpty() ? null : new Endpoint(host, serviceInfo.getPort());
        }

        private boolean hostMatches(String host) {
            return expectedHost.isEmpty() || expectedHost.equals(cleanHost(host));
        }

        private void finishResolveAndContinue() {
            synchronized (lock) {
                resolving = false;
            }
            pumpResolve();
        }

        private void complete(Endpoint endpoint) {
            synchronized (lock) {
                if (stopped || result != null) {
                    return;
                }
                result = endpoint;
                stopped = true;
            }
            finished.countDown();
        }

        private void rememberEndpoint(Endpoint endpoint) {
            synchronized (lock) {
                if (stopped) {
                    return;
                }
                result = endpoint;
                if (settleStarted) {
                    return;
                }
                settleStarted = true;
            }
            Thread settleThread = new Thread(() -> {
                try {
                    Thread.sleep(CONNECT_SETTLE_MS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                synchronized (lock) {
                    if (stopped || result == null) {
                        return;
                    }
                    stopped = true;
                }
                finished.countDown();
            }, "nexus-manual-mdns-settle");
            settleThread.setDaemon(true);
            settleThread.start();
        }

        private void completeFailure(String message) {
            synchronized (lock) {
                if (stopped || result != null) {
                    return;
                }
                failure = message == null ? "mDNS discovery failed" : message;
                stopped = true;
            }
            finished.countDown();
        }

        private void stopDiscovery() {
            synchronized (lock) {
                if (!discoveryStarted) {
                    stopped = true;
                    return;
                }
                stopped = true;
                discoveryStarted = false;
            }
            try {
                manager.stopServiceDiscovery(discoveryListener);
            } catch (RuntimeException ignored) {
                // Android may throw if discovery stopped concurrently.
            }
        }
    }

    private static String cleanHost(String host) {
        return host == null ? "" : host.trim();
    }

    private static String redactedHost(String host) {
        String clean = cleanHost(host);
        int lastDot = clean.lastIndexOf('.');
        return lastDot > 0 ? clean.substring(0, lastDot + 1) + "x" : "redacted";
    }
}
