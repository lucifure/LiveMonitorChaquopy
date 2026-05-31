package com.livemonitor.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

/**
 * Monitors network connectivity using ConnectivityManager.
 *
 * Used by MonitorService to:
 * - detect internet drops
 * - pause active monitoring/recording state
 * - resume checks when internet returns
 * - write network logs
 */
public class NetworkMonitor {

    public interface Listener {
        void onNetworkAvailable();

        void onNetworkLost();

        void onNetworkChanged(boolean connected);
    }

    private final Context appContext;
    private final ConnectivityManager connectivityManager;
    private final AppStorage storage;

    private ConnectivityManager.NetworkCallback networkCallback;
    private Listener listener;
    private boolean started;
    private boolean lastConnected;

    public NetworkMonitor(Context context) {
        this.appContext = context.getApplicationContext();
        this.connectivityManager =
            (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.storage = new AppStorage(appContext);
        this.started = false;
        this.lastConnected = isConnectedNow();
    }

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (started) {
            return;
        }

        started = true;
        lastConnected = isConnectedNow();

        storage.appendLog(new LogItem(
            LogItem.LEVEL_INFO,
            LogItem.SOURCE_NETWORK,
            "",
            "",
            "",
            "",
            lastConnected ? "Network monitor started. Internet available." :
                "Network monitor started. Internet unavailable.",
            ""
        ));

        if (connectivityManager == null) {
            storage.appendLog(new LogItem(
                LogItem.LEVEL_WARNING,
                LogItem.SOURCE_NETWORK,
                "",
                "",
                "",
                "",
                "ConnectivityManager is unavailable.",
                ""
            ));
            return;
        }

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handleNetworkState(true);
            }

            @Override
            public void onLost(Network network) {
                handleNetworkState(isConnectedNow());
            }

            @Override
            public void onCapabilitiesChanged(
                Network network,
                NetworkCapabilities networkCapabilities
            ) {
                boolean connected = hasInternetCapability(networkCapabilities);
                handleNetworkState(connected);
            }

            @Override
            public void onUnavailable() {
                handleNetworkState(false);
            }
        };

        try {
            NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

            connectivityManager.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            storage.appendLog(new LogItem(
                LogItem.LEVEL_WARNING,
                LogItem.SOURCE_NETWORK,
                "",
                "",
                "",
                "",
                "Network callback registration failed.",
                e.getMessage()
            ));
        }
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }

        started = false;

        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
                // Callback may already be unregistered by Android.
            }
        }

        networkCallback = null;

        storage.appendLog(new LogItem(
            LogItem.LEVEL_INFO,
            LogItem.SOURCE_NETWORK,
            "",
            "",
            "",
            "",
            "Network monitor stopped.",
            ""
        ));
    }

    public boolean isConnectedNow() {
        if (connectivityManager == null) {
            return false;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNetwork = connectivityManager.getActiveNetwork();

                if (activeNetwork == null) {
                    return false;
                }

                NetworkCapabilities capabilities =
                    connectivityManager.getNetworkCapabilities(activeNetwork);

                return hasInternetCapability(capabilities);
            }

            android.net.NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();

            return networkInfo != null && networkInfo.isConnected();
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean isStarted() {
        return started;
    }

    private void handleNetworkState(boolean connected) {
        Listener currentListener;

        synchronized (this) {
            if (!started) {
                return;
            }

            if (connected == lastConnected) {
                return;
            }

            lastConnected = connected;
            currentListener = listener;
        }

        storage.appendLog(new LogItem(
            connected ? LogItem.LEVEL_SUCCESS : LogItem.LEVEL_WARNING,
            LogItem.SOURCE_NETWORK,
            "",
            "",
            "",
            "",
            connected ? "Internet connection restored." : "Internet connection lost.",
            ""
        ));

        if (currentListener != null) {
            currentListener.onNetworkChanged(connected);

            if (connected) {
                currentListener.onNetworkAvailable();
            } else {
                currentListener.onNetworkLost();
            }
        }
    }

    private static boolean hasInternetCapability(NetworkCapabilities capabilities) {
        if (capabilities == null) {
            return false;
        }

        boolean hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean validated =
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);

            return hasInternet && validated;
        }

        return hasInternet;
    }
}
