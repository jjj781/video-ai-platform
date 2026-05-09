package com.videoai.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoai.dto.UploadInitDTO;
import com.videoai.entity.UploadTask;
import com.videoai.entity.Video;
import com.videoai.exception.BusinessException;
import com.videoai.event.VideoMergedEvent;
import com.videoai.mapper.UploadTaskMapper;
import com.videoai.mapper.VideoMapper;
import com.videoai.mq.VideoTranscodeMessage;
import com.videoai.service.MinioService;
import com.videoai.service.UploadService;
import com.videoai.service.VideoTranscodeService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import com.videoai.vo.UploadInitVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 分片上传服务 (基于MinIO预签名URL + composeObject合并)
 *
 * 上传流程：
 *   1. 前端计算MD5，调用initUpload获取taskId和分片数
 *   2. 前端逐个获取分片预签名URL: GET /presigned-url/{taskId}/{index}
 *   3. 前端PUT分片直传MinIO (存储为临时chunk对象)
 *   4. 前端回调后端更新进度: POST /chunk-callback/{taskId}/{index}
 *   5. 全部完成后，后端composeObject合并chunks为最终文件
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadServiceImpl implements UploadService {

    private static final String CHUNK_BITMAP_PREFIX = "upload:chunk:";

    /**
     * 原子化分片进度 Lua 脚本
     * KEYS[1]: bitmap key
     * KEYS[2]: merge guard key (SETNX 防重复合并)
     * ARGV[1]: chunkIndex
     * ARGV[2]: totalChunks
     * 返回: [wasSet, completedCount, shouldMerge]
     */
    private static final String CHUNK_PROGRESS_LUA = """
            local bitmap_key = KEYS[1]
            local merge_key = KEYS[2]
            local chunk_index = tonumber(ARGV[1])
            local total_chunks = tonumber(ARGV[2])

            local was_set = redis.call('SETBIT', bitmap_key, chunk_index, 1)
            local completed = redis.call('BITCOUNT', bitmap_key)

            local should_merge = 0
            if completed >= total_chunks then
                should_merge = redis.call('SET', merge_key, '1', 'NX', 'EX', 300)
                if should_merge then
                    should_merge = 1
                else
                    should_merge = 0
                end
            end

            return {was_set, completed, should_merge}
            """;

    private final MinioService minioService;
    private final VideoMapper videoMapper;
    private final UploadTaskMapper uploadTaskMapper;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ApplicationEventPublisher eventPublisher;

    private final VideoTranscodeService videoTranscodeService;

    /** 自注入解决同类内 @Transactional 方法调用的 AOP 代理问题 */
    @Lazy
    @Autowired
    private UploadServiceImpl self;

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    @Value("${upload.chunk-size:5242880}")
    private long chunkSize;

    @Override
    @Transactional
    public UploadInitVO initUpload(UploadInitDTO dto) {
        // 1. MD5秒传检查（@TableLogic自动过滤deleted=1，无需手动排除status=5）
        List<Video> existingVideos = videoMapper.selectList(
                new LambdaQueryWrapper<Video>().eq(Video::getFileMd5, dto.getFileMd5())
                        .orderByDesc(Video::getCreatedAt)
                        .last("LIMIT 1"));
        if (!existingVideos.isEmpty() && existingVideos.get(0).getStatus() == 3) {
            throw new BusinessException("该视频已存在，无需重复上传");
        }

        // 2. Redisson分布式锁防并发
        String lockKey = "upload:lock:" + dto.getFileMd5();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(3, 30, TimeUnit.SECONDS)) {
                throw new BusinessException("该文件正在上传中，请稍后");
            }

            // 3. 双重检查锁
            List<UploadTask> existingTasks = uploadTaskMapper.selectList(
                    new LambdaQueryWrapper<UploadTask>()
                            .eq(UploadTask::getFileMd5, dto.getFileMd5())
                            .eq(UploadTask::getStatus, 0)
                            .orderByDesc(UploadTask::getCreatedAt)
                            .last("LIMIT 1"));
            if (!existingTasks.isEmpty()) {
                return buildInitVO(existingTasks.get(0));
            }

            // 4. 创建视频记录
            Video video = new Video();
            video.setTitle(StrUtil.isBlank(dto.getTitle()) ? dto.getFilename() : dto.getTitle());
            video.setDescription(dto.getDescription());
            video.setOriginalFilename(dto.getFilename());
            video.setFileSize(dto.getFileSize());
            video.setFileMd5(dto.getFileMd5());
            video.setStatus(0);
            video.setUserId(1L); // TODO: 从登录态获取
            videoMapper.insert(video);

            // 5. 计算分片
            int totalChunks = (int) Math.ceil((double) dto.getFileSize() / chunkSize);
            String objectKey = "videos/" + IdUtil.fastSimpleUUID() + "/" + dto.getFilename();

            // 6. 创建上传任务
            UploadTask task = new UploadTask();
            task.setTaskId(IdUtil.fastSimpleUUID());
            task.setVideoId(video.getId());
            task.setUserId(1L);
            task.setFilename(dto.getFilename());
            task.setFileSize(dto.getFileSize());
            task.setFileMd5(dto.getFileMd5());
            task.setChunkSize(chunkSize);
            task.setTotalChunks(totalChunks);
            task.setCompletedChunks(0);
            task.setStatus(0);
            // uploadId字段暂用于存储最终对象键 (compose方案不需要S3 uploadId)
            task.setUploadId(objectKey);

            video.setOssKey(objectKey);
            videoMapper.updateById(video);
            uploadTaskMapper.insert(task);

            log.info("分片上传任务创建: taskId={}, totalChunks={}", task.getTaskId(), totalChunks);
            return buildInitVO(task);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("获取锁被中断");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 获取单个分片的预签名上传URL (前端直传MinIO)
     */
    @Override
    public String getChunkPresignedUrl(String taskId, int chunkIndex) {
        UploadTask task = getTask(taskId);
        if (task.getStatus() != 0) {
            throw new BusinessException("上传任务已结束");
        }
        return minioService.getPresignedChunkUploadUrl(taskId, task.getFilename(), chunkIndex);
    }

    /**
     * 前端直传MinIO后回调，更新分片进度
     * 使用Redis Lua脚本原子化 SETBIT + BITCOUNT + 合并判定，消除并发竞态
     * 全部分片完成后由唯一线程执行 composeObject 合并
     */
    @Override
    public void updateChunkProgress(String taskId, int chunkIndex) {
        UploadTask task = getTask(taskId);
        if (task.getStatus() != 0) return;

        String bitmapKey = CHUNK_BITMAP_PREFIX + taskId;
        String mergeKey = "upload:merge:" + taskId;

        // Lua脚本原子化: SETBIT + BITCOUNT + SETNX合并判定
        DefaultRedisScript<List> script = new DefaultRedisScript<>(CHUNK_PROGRESS_LUA, List.class);
        List<Long> result = (List<Long>) stringRedisTemplate.execute(
                script,
                List.of(bitmapKey, mergeKey),
                String.valueOf(chunkIndex),
                String.valueOf(task.getTotalChunks()));

        long wasSet = result.get(0);
        long completedCount = result.get(1);
        long shouldMerge = result.get(2);

        // 重复回调，跳过
        if (wasSet == 1) {
            log.debug("分片 {} 已完成，跳过重复回调: taskId={}", chunkIndex, taskId);
            return;
        }

        task.setCompletedChunks((int) completedCount);

        if (shouldMerge == 1) {
            // 唯一线程执行分片合并
            String finalKey;
            try {
                finalKey = minioService.composeChunks(taskId, task.getFilename(), task.getTotalChunks());
            } catch (Exception e) {
                log.error("分片合并失败: taskId={}", taskId, e);
                stringRedisTemplate.delete(mergeKey);
                throw new BusinessException("文件合并失败");
            }

            // 通过代理调用确保 @Transactional 生效（事务提交后由监听器发送MQ）
            self.completeMergeStage(task, finalKey);

            task.setStatus(1);

            // 清理 Redis
            stringRedisTemplate.delete(bitmapKey);
            stringRedisTemplate.delete(mergeKey);
        }

        // 写入DB: 仅更新 completedChunks (非合并线程不碰status，防止覆盖)
        UploadTask dbUpdate = new UploadTask();
        dbUpdate.setId(task.getId());
        dbUpdate.setCompletedChunks(task.getCompletedChunks());
        if (shouldMerge == 1) {
            dbUpdate.setStatus(1);
        }
        uploadTaskMapper.updateById(dbUpdate);
    }

    /**
     * 事务性完成合并阶段：更新视频ossKey和状态，发布事件（MQ在事务提交后发送）
     * 必须是public + 通过 self 代理调用，确保 @Transactional 和 @TransactionalEventListener 生效
     */
    @Transactional
    public void completeMergeStage(UploadTask task, String finalKey) {
        Video video = videoMapper.selectById(task.getVideoId());
        if (video != null) {
            video.setOssKey(finalKey);
            video.setStatus(1);
            videoMapper.updateById(video);
        }
        log.info("所有分片合并完成, 视频进入待转码: taskId={}, videoId={}", task.getTaskId(), task.getVideoId());

        // 发布事件，由事务提交后的监听器发送MQ（避免DB+MQ双写不一致）
        eventPublisher.publishEvent(new VideoMergedEvent(
                task.getVideoId(), finalKey, task.getFilename(), task.getFileSize()));
    }

    /**
     * 事务提交后发送转码消息（保证DB已持久化后才触发转码）
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVideoMerged(VideoMergedEvent event) {
        VideoTranscodeMessage transcodeMsg = VideoTranscodeMessage.of(
                event.videoId(), event.ossKey(), event.filename(), event.fileSize());
        if (rocketMQTemplate != null) {
            rocketMQTemplate.convertAndSend("VIDEO_TRANSCODE", transcodeMsg);
            log.info("已发送RocketMQ转码消息: videoId={}", event.videoId());
        } else {
            log.warn("RocketMQ未配置, 降级为@Async异步转码: videoId={}", event.videoId());
            videoTranscodeService.transcodeAsync(transcodeMsg);
        }
    }

    /**
     * 服务端代理上传（兜底方案）
     */
    @Override
    public void uploadChunk(String taskId, int chunkIndex, InputStream inputStream) {
        UploadTask task = getTask(taskId);
        if (task.getStatus() != 0) {
            throw new BusinessException("上传任务已结束");
        }

        // 检查分片是否已完成（通过Redis Bitmap）
        String bitmapKey = CHUNK_BITMAP_PREFIX + taskId;
        Boolean alreadyCompleted = stringRedisTemplate.opsForValue().getBit(bitmapKey, chunkIndex);
        if (Boolean.TRUE.equals(alreadyCompleted)) {
            return;
        }

        // 服务端代理上传到MinIO临时chunk位置
        String chunkKey = "videos/" + taskId + "/chunks/" + chunkIndex;
        minioService.uploadObject(chunkKey, inputStream, -1, "application/octet-stream");

        // 更新进度
        updateChunkProgress(taskId, chunkIndex);
    }

    @Override
    public UploadInitVO getUploadProgress(String taskId) {
        return buildInitVO(getTask(taskId));
    }

    private UploadTask getTask(String taskId) {
        UploadTask task = uploadTaskMapper.selectOne(
                new LambdaQueryWrapper<UploadTask>().eq(UploadTask::getTaskId, taskId));
        if (task == null) {
            throw new BusinessException("上传任务不存在");
        }
        return task;
    }

    private UploadInitVO buildInitVO(UploadTask task) {
        UploadInitVO vo = new UploadInitVO();
        vo.setTaskId(task.getTaskId());
        vo.setUploadId(task.getUploadId());
        vo.setChunkSize(task.getChunkSize());
        vo.setTotalChunks(task.getTotalChunks());

        // 从Redis Bitmap读取已完成的分片列表（用于断点续传）
        String bitmapKey = CHUNK_BITMAP_PREFIX + task.getTaskId();
        List<Integer> completedChunks = new ArrayList<>();
        for (int i = 0; i < task.getTotalChunks(); i++) {
            Boolean isCompleted = stringRedisTemplate.opsForValue().getBit(bitmapKey, i);
            if (Boolean.TRUE.equals(isCompleted)) {
                completedChunks.add(i);
            }
        }
        vo.setCompletedChunks(completedChunks);
        vo.setVideoId(task.getVideoId());

        return vo;
    }
}
