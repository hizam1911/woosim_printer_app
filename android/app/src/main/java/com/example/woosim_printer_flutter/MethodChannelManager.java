package com.example.woosim_printer_flutter;

import android.os.Handler;
import android.os.Looper;
import io.flutter.plugin.common.MethodChannel;

public class MethodChannelManager {
    private static MethodChannel channel;
    private static final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    public static void setMethodChannel(MethodChannel methodChannel) {
        channel = methodChannel;
    }

    public static void sendStatusToFlutter(String status) {
        if (channel != null) {
            mainThreadHandler.post(() -> channel.invokeMethod("updateStatusConnection", status));
        }
    }

    public static void sendStatusToFlutter(String status, String deviceName) {
        if (channel != null) {
            mainThreadHandler.post(() -> channel.invokeMethod("updateStatusConnection", status + "|" + deviceName));
        }
    }
}
