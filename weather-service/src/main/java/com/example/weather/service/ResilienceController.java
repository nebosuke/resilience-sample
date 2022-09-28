package com.example.weather.service;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * Resilience4j の設定オブジェクトをシングルトンとして生成して供給する
 */
@Component
public class ResilienceController {

    private static final RetryRegistry RETRY_REGISTRY = RetryRegistry.of(
            RetryConfig.custom().maxAttempts(5) // 最大リトライ回数
                    .intervalFunction(IntervalFunction.ofExponentialBackoff()) // リトライ間隔
                    .retryExceptions(IOException.class, RuntimeException.class) // リトライする例外
                    .build());

    private static final TimeLimiterRegistry TIME_LIMITER_REGISTRY = TimeLimiterRegistry.of(
            TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(1000)) // 1000ミリ秒でタイムアウト
                    .build());

    public RetryRegistry getRetryRegistry() {
        return RETRY_REGISTRY;
    }

    public TimeLimiterRegistry getTimeLimiterRegistry() {
        return TIME_LIMITER_REGISTRY;
    }
}
