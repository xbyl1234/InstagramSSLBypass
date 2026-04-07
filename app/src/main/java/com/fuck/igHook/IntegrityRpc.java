package com.fuck.igHook;

import android.content.Context;
import android.text.TextUtils;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.android.play.core.integrity.IntegrityManager;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.IntegrityTokenRequest;
import com.google.android.play.core.integrity.StandardIntegrityException;
import com.google.android.play.core.integrity.StandardIntegrityManager;
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode;

/**
 * Play Integrity 客户端封装。
 * 统一提供标准模式和经典模式的 token 获取能力，供 Hook 和 HTTP 服务复用。
 */
public class IntegrityRpc {
    private static final long UNSET_CLOUD_PROJECT_NUMBER = Long.MIN_VALUE;

    private final Object providerLock = new Object();
    private final IntegrityManager classicIntegrityManager;
    private final StandardIntegrityManager standardIntegrityManager;

    private StandardIntegrityManager.StandardIntegrityTokenProvider standardTokenProvider;
    private long preparedCloudProjectNumber = UNSET_CLOUD_PROJECT_NUMBER;

    public IntegrityRpc(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext == null) {
            appContext = context;
        }
        this.classicIntegrityManager = IntegrityManagerFactory.create(appContext);
        this.standardIntegrityManager = IntegrityManagerFactory.createStandard(appContext);
    }

    public void clearStandardTokenProvider() {
        synchronized (providerLock) {
            standardTokenProvider = null;
            preparedCloudProjectNumber = UNSET_CLOUD_PROJECT_NUMBER;
        }
    }

    /**
     * 预热标准模式 provider，减少后续正式取 token 的延迟。
     */
    public Task<Void> prepareStandardTokenProvider(long cloudProjectNumber) {
        Exception validationError = validateCloudProjectNumber(cloudProjectNumber);
        if (validationError != null) {
            return Tasks.forException(validationError);
        }

        TaskCompletionSource<Void> taskSource = new TaskCompletionSource<>();
        standardIntegrityManager
                .prepareIntegrityToken(
                        StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                                .setCloudProjectNumber(cloudProjectNumber)
                                .build())
                .addOnSuccessListener(provider -> {
                    synchronized (providerLock) {
                        standardTokenProvider = provider;
                        preparedCloudProjectNumber = cloudProjectNumber;
                    }
                    taskSource.setResult(null);
                })
                .addOnFailureListener(taskSource::setException);
        return taskSource.getTask();
    }

    public Task<String> requestStandardToken(long cloudProjectNumber, String requestHash) {
        Exception validationError = validateCloudProjectNumber(cloudProjectNumber);
        if (validationError != null) {
            return Tasks.forException(validationError);
        }
        if (TextUtils.isEmpty(requestHash)) {
            return Tasks.forException(new IllegalArgumentException("requestHash must not be empty"));
        }

        TaskCompletionSource<String> taskSource = new TaskCompletionSource<>();
        requestStandardTokenInternal(cloudProjectNumber, requestHash, true, taskSource);
        return taskSource.getTask();
    }

    public Task<String> requestClassicToken(String nonce) {
        return requestClassicToken(nonce, null);
    }

    /**
     * 获取经典模式 token。
     * cloudProjectNumber 不是必填，所以这里允许调用方按需传入。
     */
    public Task<String> requestClassicToken(String nonce, Long cloudProjectNumber) {
        if (TextUtils.isEmpty(nonce)) {
            return Tasks.forException(new IllegalArgumentException("nonce must not be empty"));
        }
        if (cloudProjectNumber != null) {
            Exception validationError = validateCloudProjectNumber(cloudProjectNumber);
            if (validationError != null) {
                return Tasks.forException(validationError);
            }
        }

        IntegrityTokenRequest.Builder requestBuilder = IntegrityTokenRequest.builder()
                .setNonce(nonce);
        if (cloudProjectNumber != null) {
            requestBuilder.setCloudProjectNumber(cloudProjectNumber);
        }

        TaskCompletionSource<String> taskSource = new TaskCompletionSource<>();
        classicIntegrityManager.requestIntegrityToken(requestBuilder.build())
                .addOnSuccessListener(response -> taskSource.setResult(response.token()))
                .addOnFailureListener(taskSource::setException);
        return taskSource.getTask();
    }

    private void requestStandardTokenInternal(
            long cloudProjectNumber,
            String requestHash,
            boolean retryOnInvalidProvider,
            TaskCompletionSource<String> taskSource) {
        getOrPrepareStandardTokenProvider(cloudProjectNumber)
                .addOnSuccessListener(provider -> provider.request(
                                StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                                        .setRequestHash(requestHash)
                                        .build())
                        .addOnSuccessListener(token -> taskSource.setResult(token.token()))
                        .addOnFailureListener(exception -> {
                            if (retryOnInvalidProvider && isInvalidProvider(exception)) {
                                clearStandardTokenProvider();
                                requestStandardTokenInternal(
                                        cloudProjectNumber,
                                        requestHash,
                                        false,
                                        taskSource);
                                return;
                            }
                            taskSource.setException(exception);
                        }))
                .addOnFailureListener(taskSource::setException);
    }

    private Task<StandardIntegrityManager.StandardIntegrityTokenProvider> getOrPrepareStandardTokenProvider(
            long cloudProjectNumber) {
        synchronized (providerLock) {
            if (standardTokenProvider != null && preparedCloudProjectNumber == cloudProjectNumber) {
                return Tasks.forResult(standardTokenProvider);
            }
        }

        TaskCompletionSource<StandardIntegrityManager.StandardIntegrityTokenProvider> taskSource =
                new TaskCompletionSource<>();
        standardIntegrityManager
                .prepareIntegrityToken(
                        StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                                .setCloudProjectNumber(cloudProjectNumber)
                                .build())
                .addOnSuccessListener(provider -> {
                    synchronized (providerLock) {
                        standardTokenProvider = provider;
                        preparedCloudProjectNumber = cloudProjectNumber;
                    }
                    taskSource.setResult(provider);
                })
                .addOnFailureListener(taskSource::setException);
        return taskSource.getTask();
    }

    private static boolean isInvalidProvider(Exception exception) {
        return exception instanceof StandardIntegrityException
                && ((StandardIntegrityException) exception).getErrorCode()
                == StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID;
    }

    private static Exception validateCloudProjectNumber(long cloudProjectNumber) {
        if (cloudProjectNumber <= 0L) {
            return new IllegalArgumentException("cloudProjectNumber must be greater than 0");
        }
        return null;
    }
}
