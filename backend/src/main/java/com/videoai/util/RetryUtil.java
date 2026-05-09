package com.videoai.util;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * 指数退避重试工具
 * 应对三方API网络抖动，结合最终一致性保障
 */
@Slf4j
public class RetryUtil {

    /**
     * 指数退避重试
     *
     * @param action      要执行的操作
     * @param maxRetries  最大重试次数
     * @param baseDelayMs 基础延迟(毫秒)
     * @param <T>         返回类型
     * @return 执行结果
     */
    public static <T> T retryWithExponentialBackoff(Supplier<T> action, int maxRetries, long baseDelayMs) {
        Exception lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.get();
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    long delay = baseDelayMs * (1L << attempt); // 指数退避: 1s, 2s, 4s, 8s...
                    // 加入随机抖动 (±25%)
                    delay = delay + (long) (delay * (Math.random() * 0.5 - 0.25));
                    log.warn("操作失败, 第{}次重试, 等待{}ms, 原因: {}",
                            attempt + 1, delay, e.getMessage());
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试被中断", ie);
                    }
                }
            }
        }
        throw new RuntimeException("重试" + maxRetries + "次后仍然失败", lastException);
    }

    /**
     * 简化版本：默认3次重试，基础延迟1秒
     */
    public static <T> T retry(Supplier<T> action) {
        return retryWithExponentialBackoff(action, 3, 1000);
    }
}
