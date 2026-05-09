package com.videoai.service.impl.deduplication;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 双重去重防并发服务
 * - 短请求: MD5 + Redisson分布式锁拦截并发重复提交
 * - 长耗时任务: 数据库状态机去重，避免重复消耗AI Token
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeduplicationService {

    private final RedissonClient redissonClient;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String DEDUP_PREFIX = "dedup:";
    private static final String LOCK_PREFIX = "dedup:lock:";

    /**
     * 短请求去重：基于MD5 + Redisson分布式锁
     * 适用于上传等需要即时响应的操作
     *
     * @param bizType 业务类型
     * @param md5     内容MD5
     * @return true=允许执行, false=重复请求
     */
    public boolean tryDedup(String bizType, String md5) {
        String key = DEDUP_PREFIX + bizType + ":" + md5;
        String lockKey = LOCK_PREFIX + bizType + ":" + md5;

        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                return false; // 获取锁失败，视为重复请求
            }

            // 检查是否已处理
            Boolean exists = redisTemplate.hasKey(key);
            if (Boolean.TRUE.equals(exists)) {
                return false;
            }

            // 标记为处理中
            redisTemplate.opsForValue().set(key, "processing", 5, TimeUnit.MINUTES);
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 完成去重标记
     */
    public void markDone(String bizType, String md5) {
        String key = DEDUP_PREFIX + bizType + ":" + md5;
        redisTemplate.opsForValue().set(key, "done", 24, TimeUnit.HOURS);
    }

    /**
     * 清除去重标记（用于重试场景）
     */
    public void clearDedup(String bizType, String md5) {
        String key = DEDUP_PREFIX + bizType + ":" + md5;
        redisTemplate.delete(key);
    }

    /**
     * 长耗时任务去重锁：基于数据库状态机
     * 适用于AI分析等需要消耗Token的操作
     * 业务层通过查询数据库status字段实现：只有status=待处理的才能被拾取
     */
    public boolean tryLongTaskLock(String taskId) {
        String key = "task:lock:" + taskId;
        RLock lock = redissonClient.getLock(key);
        try {
            return lock.tryLock(0, 300, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void releaseLongTaskLock(String taskId) {
        String key = "task:lock:" + taskId;
        RLock lock = redissonClient.getLock(key);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
