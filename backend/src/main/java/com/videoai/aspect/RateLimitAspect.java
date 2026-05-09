package com.videoai.aspect;

import com.videoai.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collections;

/**
 * 基于Redis令牌桶算法的接口限流
 * 遏制恶意请求导致的API调用成本飙升
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 限流注解
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface RateLimit {
        /** 每分钟允许的请求数 */
        int permitsPerMinute() default 300;

        /** 限流key前缀 */
        String key() default "";

        /** 限流类型: IP / USER / GLOBAL */
        LimitType type() default LimitType.IP;
    }

    public enum LimitType {
        IP, USER, GLOBAL
    }

    /**
     * 令牌桶Lua脚本
     * KEYS[1]: 令牌桶key
     * ARGV[1]: 桶容量
     * ARGV[2]: 每秒补充令牌数
     * ARGV[3]: 当前时间戳(秒)
     */
    private static final String TOKEN_BUCKET_LUA = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            local bucket = redis.call('HMGET', key, 'tokens', 'last_time')
            local tokens = tonumber(bucket[1]) or capacity
            local last_time = tonumber(bucket[2]) or now

            local delta = math.max(0, now - last_time)
            local new_tokens = math.min(capacity, tokens + delta * rate)

            if new_tokens >= 1 then
                redis.call('HMSET', key, 'tokens', new_tokens - 1, 'last_time', now)
                redis.call('EXPIRE', key, 120)
                return 1
            else
                return 0
            end
            """;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = buildKey(rateLimit, joinPoint);
        int ppm = rateLimit.permitsPerMinute();
        double ratePerSecond = ppm / 60.0;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(TOKEN_BUCKET_LUA, Long.class);
        Long result = redisTemplate.execute(script,
                Collections.singletonList(key),
                ppm,                        // 桶容量 = 每分钟请求数
                ratePerSecond,              // 每秒补充速率
                System.currentTimeMillis() / 1000);

        if (result == null || result == 0L) {
            log.warn("接口限流触发: key={}, ppm={}", key, ppm);
            throw new BusinessException(429, "请求过于频繁，请稍后重试");
        }

        return joinPoint.proceed();
    }

    private String buildKey(RateLimit rateLimit, ProceedingJoinPoint joinPoint) {
        String prefix = rateLimit.key().isEmpty()
                ? joinPoint.getSignature().toShortString()
                : rateLimit.key();
        String suffix = switch (rateLimit.type()) {
            case IP -> getClientIp();
            case USER -> getUserId();
            case GLOBAL -> "global";
        };
        return "rate_limit:" + prefix + ":" + suffix;
    }

    private String getClientIp() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return "unknown";
        HttpServletRequest request = attrs.getRequest();
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty()) {
            ip = ip.split(",")[0].trim();
        }
        if (ip == null || ip.isEmpty()) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    private String getUserId() {
        // TODO: 从登录态获取用户ID
        return getClientIp();
    }
}
