package com.fuck.igHook;

import android.app.Application;
import android.content.Context;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class IGHook implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.instagram.android";
    private static final AtomicBoolean HTTP_SERVER_HOOKED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        ModuleLog.log("模块已注入 Instagram, process=" + lpparam.processName);

        hookApplicationAttach(lpparam);
        hookTigonMNS(lpparam);
        hookCheckTrustedRecursive(lpparam);
        hookSSLContextInit(lpparam);
    }

    /**
     * 在主进程拿到 Application Context 后启动 HTTP 服务。
     * 这样 Play Integrity 调用会运行在 Instagram 自己的进程环境里。
     */
    private void hookApplicationAttach(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.processName)) {
            ModuleLog.log("跳过非主进程 HTTP 服务启动: " + lpparam.processName);
            return;
        }

        if (!HTTP_SERVER_HOOKED.compareAndSet(false, true)) {
            return;
        }

        XposedHelpers.findAndHookMethod(
                Application.class,
                "attach",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Context context = (Context) param.args[0];
                        IntegrityHttpServer.ensureStarted(context);
                    }
                }
        );
    }

    /**
     * Hook Instagram 自己的 Tigon 网络配置，在 native 层关闭证书校验。
     */
    private void hookTigonMNS(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(
                "com.facebook.soloader.MergedSoMapping$Invoke_JNI_OnLoad",
                lpparam.classLoader,
                "libappstatelogger2_so",
                new XC_MethodHook() {
                    private boolean hooked = false;

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (hooked) {
                            return;
                        }
                        hooked = true;
                        doHookTigonMNS(lpparam.classLoader);
                    }
                }
        );
    }

    private void doHookTigonMNS(ClassLoader classLoader) {
        try {
            Class<?> holder = XposedHelpers.findClass(
                    "com.facebook.tigon.tigonmns.TigonMNSServiceHolder", classLoader);
            Class<?> config = XposedHelpers.findClass(
                    "com.facebook.tigon.tigonmns.TigonMNSConfig", classLoader);

            Method target = null;
            for (Method method : holder.getDeclaredMethods()) {
                if (!"initHybrid".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length >= 4 && params[0] == config) {
                    target = method;
                    ModuleLog.log("匹配到 initHybrid: " + getMethodSignature(method));
                }
            }

            if (target == null) {
                ModuleLog.log("未找到 initHybrid overload");
                return;
            }

            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object configObject = param.args[0];
                    invokeIfExists(configObject, "setEnableCertificateVerificationWithProofOfPossession", boolean.class, false);
                    invokeIfExists(configObject, "setTrustSandboxCertificates", boolean.class, true);
                    invokeIfExists(configObject, "setForceHttp2", boolean.class, true);
                    invokeIfExists(configObject, "setEnableCertificateVerification", boolean.class, false);
                    invokeIfExists(configObject, "setCertificatePinningEnabled", boolean.class, false);
                    ModuleLog.log("已修改 TigonMNSConfig");
                }
            });
        } catch (Throwable throwable) {
            ModuleLog.log("Hook TigonMNS 失败: " + throwable.getMessage());
        }
    }

    /**
     * 兜底 Hook 系统 TrustManager，直接返回空证书链，绕过递归信任校验。
     */
    private void hookCheckTrustedRecursive(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> cls = XposedHelpers.findClass(
                    "com.android.org.conscrypt.TrustManagerImpl", lpparam.classLoader);

            for (Method method : cls.getDeclaredMethods()) {
                if ("checkTrustedRecursive".equals(method.getName())) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(new ArrayList<>());
                        }
                    });
                    ModuleLog.log("已 Hook checkTrustedRecursive");
                    return;
                }
            }
        } catch (Throwable throwable) {
            ModuleLog.log("Hook checkTrustedRecursive 失败: " + throwable.getMessage());
        }
    }

    /**
     * 再兜底替换 SSLContext 的 TrustManager，覆盖 Java 层常见的证书校验路径。
     */
    private void hookSSLContextInit(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final TrustManager[] emptyTrustManagers = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };

            XposedHelpers.findAndHookMethod(
                    "javax.net.ssl.SSLContext", lpparam.classLoader, "init",
                    javax.net.ssl.KeyManager[].class, javax.net.ssl.TrustManager[].class, SecureRandom.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.args[1] = emptyTrustManagers;
                        }
                    }
            );
            ModuleLog.log("已 Hook SSLContext.init");
        } catch (Throwable throwable) {
            ModuleLog.log("Hook SSLContext.init 失败: " + throwable.getMessage());
        }
    }

    private static void invokeIfExists(Object obj, String methodName, Class<?> paramType, Object value) {
        try {
            obj.getClass().getMethod(methodName, paramType).invoke(obj, value);
        } catch (Throwable ignored) {
        }
    }

    private static String getMethodSignature(Method method) {
        StringBuilder builder = new StringBuilder("(");
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (builder.length() > 1) {
                builder.append(", ");
            }
            builder.append(parameterType.getName());
        }
        return builder.append(")").toString();
    }
}
