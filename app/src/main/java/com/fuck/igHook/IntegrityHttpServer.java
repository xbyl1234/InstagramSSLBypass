package com.fuck.igHook;

import android.content.Context;
import android.text.TextUtils;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Tasks;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import fi.iki.elonen.NanoHTTPD;

/**
 * 运行在 Instagram 主进程内的轻量 HTTP 服务。
 * 远端客户端可以直接通过 HTTP 请求获取 Play Integrity token。
 */
public final class IntegrityHttpServer extends NanoHTTPD {
    private static final int DEFAULT_PORT = 19091;
    private static final long TOKEN_TIMEOUT_SECONDS = 90L;
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private final IntegrityRpc integrityRpc;

    private IntegrityHttpServer(Context context) {
        super(DEFAULT_PORT);
        this.integrityRpc = new IntegrityRpc(context);
    }

    /**
     * 只启动一次 HTTP 服务，避免 Instagram 多进程重复监听同一端口。
     */
    public static void ensureStarted(Context context) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        Context appContext = context.getApplicationContext();
        if (appContext == null) {
            appContext = context;
        }

        try {
            IntegrityHttpServer server = new IntegrityHttpServer(appContext);
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            ModuleLog.log("Integrity HTTP 服务已启动: http://0.0.0.0:" + DEFAULT_PORT);
        } catch (IOException exception) {
            STARTED.set(false);
            ModuleLog.log("启动 Integrity HTTP 服务失败", exception);
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            if (Method.OPTIONS.equals(session.getMethod())) {
                return jsonResponse(Response.Status.OK, new JSONObject());
            }

            Map<String, String> params = collectParams(session);
            String uri = session.getUri();
            switch (uri) {
                case "/":
                    return handleIndex();
                case "/health":
                    return handleHealth();
                case "/token/standard/prepare":
                    return handlePrepareStandard(session, params);
                case "/token/standard/clear":
                    return handleClearStandard(session);
                case "/token/standard":
                    return handleStandardToken(session, params);
                case "/token/classic":
                    return handleClassicToken(session, params);
                default:
                    return errorResponse(Response.Status.NOT_FOUND, "not_found", "未找到接口: " + uri);
            }
        } catch (IllegalArgumentException exception) {
            return errorResponse(Response.Status.BAD_REQUEST, "bad_request", exception);
        } catch (Throwable throwable) {
            ModuleLog.log("处理 HTTP 请求失败", throwable);
            return errorResponse(Response.Status.INTERNAL_ERROR, "internal_error", throwable);
        }
    }

    private Response handleIndex() throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("service", "instagram-play-integrity");
        payload.put("port", DEFAULT_PORT);
        payload.put("charset", "utf-8");
        payload.put("routes", new JSONObject()
                .put("health", "/health")
                .put("prepareStandard", "/token/standard/prepare")
                .put("clearStandard", "/token/standard/clear")
                .put("standard", "/token/standard")
                .put("classic", "/token/classic"));
        return jsonResponse(Response.Status.OK, payload);
    }

    private Response handleHealth() throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("status", "ok");
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("service", "instagram-play-integrity");
        payload.put("port", DEFAULT_PORT);
        return jsonResponse(Response.Status.OK, payload);
    }

    private Response handlePrepareStandard(IHTTPSession session, Map<String, String> params) throws Exception {
        requireMethod(session, Method.GET, Method.POST);
        long cloudProjectNumber = requireLong(params, "cloudProjectNumber");
        Tasks.await(
                integrityRpc.prepareStandardTokenProvider(cloudProjectNumber),
                TOKEN_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);

        JSONObject payload = new JSONObject();
        payload.put("success", true);
        payload.put("type", "standard_prepare");
        payload.put("cloudProjectNumber", cloudProjectNumber);
        return jsonResponse(Response.Status.OK, payload);
    }

    private Response handleClearStandard(IHTTPSession session) throws JSONException {
        requireMethod(session, Method.GET, Method.POST);
        integrityRpc.clearStandardTokenProvider();

        JSONObject payload = new JSONObject();
        payload.put("success", true);
        payload.put("type", "standard_clear");
        return jsonResponse(Response.Status.OK, payload);
    }

    private Response handleStandardToken(IHTTPSession session, Map<String, String> params) throws Exception {
        requireMethod(session, Method.GET, Method.POST);
        long cloudProjectNumber = requireLong(params, "cloudProjectNumber");
        String requestHash = requireString(params, "requestHash");
        String token = Tasks.await(
                integrityRpc.requestStandardToken(cloudProjectNumber, requestHash),
                TOKEN_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);

        JSONObject payload = new JSONObject();
        payload.put("success", true);
        payload.put("type", "standard");
        payload.put("cloudProjectNumber", cloudProjectNumber);
        payload.put("requestHash", requestHash);
        payload.put("token", token);
        return jsonResponse(Response.Status.OK, payload);
    }

    private Response handleClassicToken(IHTTPSession session, Map<String, String> params) throws Exception {
        requireMethod(session, Method.GET, Method.POST);
        String nonce = requireString(params, "nonce");
        Long cloudProjectNumber = optionalLong(params, "cloudProjectNumber");
        String token = Tasks.await(
                integrityRpc.requestClassicToken(nonce, cloudProjectNumber),
                TOKEN_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);

        JSONObject payload = new JSONObject();
        payload.put("success", true);
        payload.put("type", "classic");
        payload.put("nonce", nonce);
        if (cloudProjectNumber != null) {
            payload.put("cloudProjectNumber", cloudProjectNumber);
        }
        payload.put("token", token);
        return jsonResponse(Response.Status.OK, payload);
    }

    /**
     * 同时兼容 Query 参数和 JSON Body。
     * 这样命令行客户端和业务客户端都可以直接调用。
     */
    private static Map<String, String> collectParams(IHTTPSession session) throws Exception {
        Map<String, String> params = new LinkedHashMap<>(session.getParms());
        if (!Method.POST.equals(session.getMethod()) && !Method.PUT.equals(session.getMethod())) {
            return params;
        }

        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String postData = files.get("postData");
        if (TextUtils.isEmpty(postData)) {
            return params;
        }

        String contentType = session.getHeaders().get("content-type");
        if (contentType != null && contentType.toLowerCase().contains("application/json")) {
            JSONObject jsonObject = new JSONObject(postData);
            for (java.util.Iterator<String> it = jsonObject.keys(); it.hasNext(); ) {
                String key = it.next();
                Object value = jsonObject.opt(key);
                params.put(key, value == null ? null : String.valueOf(value));
            }
        }
        return params;
    }

    private static void requireMethod(IHTTPSession session, Method... allowedMethods) {
        for (Method allowedMethod : allowedMethods) {
            if (allowedMethod == session.getMethod()) {
                return;
            }
        }
        throw new IllegalArgumentException("不支持的 HTTP 方法: " + session.getMethod());
    }

    private static String requireString(Map<String, String> params, String key) {
        String value = params.get(key);
        if (TextUtils.isEmpty(value) || "null".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("缺少参数: " + key);
        }
        return value;
    }

    private static long requireLong(Map<String, String> params, String key) {
        String value = requireString(params, key);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("参数不是合法 long: " + key);
        }
    }

    private static Long optionalLong(Map<String, String> params, String key) {
        String value = params.get(key);
        if (TextUtils.isEmpty(value) || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("参数不是合法 long: " + key);
        }
    }

    private static Response jsonResponse(Response.IStatus status, JSONObject payload) {
        Response response = NanoHTTPD.newFixedLengthResponse(
                status,
                "application/json; charset=utf-8",
                payload.toString());
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");
        return response;
    }

    private static Response errorResponse(Response.IStatus status, String code, String message) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("success", false);
            payload.put("code", code);
            payload.put("message", message);
            return jsonResponse(status, payload);
        } catch (JSONException exception) {
            return NanoHTTPD.newFixedLengthResponse(
                    status,
                    "application/json; charset=utf-8",
                    "{\"success\":false,\"code\":\"serialization_error\",\"message\":\"JSON 序列化失败\"}");
        }
    }

    private static Response errorResponse(Response.IStatus status, String code, Throwable throwable) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("success", false);
            payload.put("code", code);
            payload.put("errorClass", throwable.getClass().getName());
            payload.put("message", throwable.getMessage());
            if (throwable instanceof ApiException) {
                payload.put("statusCode", ((ApiException) throwable).getStatusCode());
            }
            return jsonResponse(status, payload);
        } catch (JSONException exception) {
            return NanoHTTPD.newFixedLengthResponse(
                    status,
                    "application/json; charset=utf-8",
                    "{\"success\":false,\"code\":\"serialization_error\",\"message\":\"JSON 序列化失败\"}");
        }
    }
}
