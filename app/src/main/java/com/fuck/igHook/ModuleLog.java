package com.fuck.igHook;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/**
 * 模块统一日志工具。
 * 所有 Hook、RPC 和 HTTP 服务都复用这一套日志输出。
 */
final class ModuleLog {
    static final String TAG = "InstagramSSLBypass";

    private ModuleLog() {
    }

    static void log(String message) {
        Log.d(TAG, message);
        XposedBridge.log("[" + TAG + "] " + message);
    }

    static void log(String message, Throwable throwable) {
        log(message + " | " + Log.getStackTraceString(throwable));
    }
}
