package com.videoai.service;

import com.videoai.entity.Video;
import com.videoai.mapper.VideoMapper;
import com.videoai.mq.VideoTranscodeMessage;
import com.videoai.service.impl.deduplication.DeduplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 视频转码服务
 * 提供给 RocketMQ Consumer 和 直接调用两种方式
 * RocketMQ 不可用时，通过 @Async 直接异步执行转码
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoTranscodeService {

    private final VideoMapper videoMapper;
    private final DeduplicationService deduplicationService;
    private final MinioService minioService;
    private final VideoAnalysisService videoAnalysisService;
    private final SseService sseService;
    private final FfmpegService ffmpegService;

    /**
     * 异步执行转码 (Spring @Async 降级方案)
     */
    @Async("transcodeExecutor")
    public void transcodeAsync(VideoTranscodeMessage message) {
        doTranscode(message);
    }

    /**
     * 执行转码 (供 Consumer 和 直接调用 共用)
     */
    public void doTranscode(VideoTranscodeMessage message) {
        Long videoId = message.getVideoId();
        log.info("开始转码: videoId={}, ossKey={}", videoId, message.getOssKey());

        // 状态机去重: 只有 status=1(待转码) 才能被拾取
        Video video = videoMapper.selectById(videoId);
        if (video == null || video.getStatus() != 1) {
            log.info("视频状态不是待转码, 跳过: videoId={}", videoId);
            return;
        }

        // 分布式锁防重复消费
        if (!deduplicationService.tryLongTaskLock("transcode:" + videoId)) {
            log.warn("获取转码锁失败, 跳过: videoId={}", videoId);
            return;
        }

        try {
            // CAS: 1(待转码) -> 2(转码中)
            int updated = updateStatus(videoId, 1, 2);
            if (updated == 0) {
                log.info("CAS更新失败, 已被其他消费者处理: videoId={}", videoId);
                throw new RuntimeException("CAS更新失败, 视频 " + videoId + " 状态已变更, 等待重试");
            }

            // === 转码流程 ===
            Path tempDir = null;
            try {
                tempDir = Files.createTempDirectory("video-transcode-" + videoId + "-");

                // 1. 下载原始视频
                Video fullVideo = videoMapper.selectById(videoId);
                Path sourcePath = tempDir.resolve(fullVideo.getOriginalFilename());
                minioService.downloadObject(message.getOssKey(), sourcePath);
                log.info("视频下载完成: videoId={}, size={}bytes", videoId, Files.size(sourcePath));

                // 2. 转码为MP4 (H.264+AAC)
                Path transcodedPath = ffmpegService.transcodeToMp4(sourcePath, tempDir);

                // 3. 上传转码后视频到MinIO
                String transcodedKey = "videos/" + videoId + "/transcoded.mp4";
                try (FileInputStream fis = new FileInputStream(transcodedPath.toFile())) {
                    minioService.uploadObject(transcodedKey, fis, Files.size(transcodedPath), "video/mp4");
                }
                log.info("转码视频上传完成: videoId={}, transcodedKey={}", videoId, transcodedKey);

                // 4. 生成封面URL
                String coverUrl = minioService.getPresignedDownloadUrl(transcodedKey);

                // 5. AI视频分析（关键帧提取 + ASR语音识别 + Vision视觉分析 + 摘要合成）
                VideoAnalysisService.AnalysisResult analysisResult;
                try {
                    analysisResult = videoAnalysisService.analyze(fullVideo);
                    log.info("AI分析完成: videoId={}, summaryLen={}, tags={}",
                            videoId,
                            analysisResult.summary() != null ? analysisResult.summary().length() : 0,
                            analysisResult.tags());
                } catch (Exception e) {
                    log.error("AI分析异常，使用降级方案: videoId={}", videoId, e);
                    analysisResult = VideoAnalysisService.AnalysisResult.fallback(fullVideo);
                }

                String summary = analysisResult.summary();
                String tags = analysisResult.tags();

                // 转码完成: 2(转码中) -> 3(已就绪)
                Video update = new Video();
                update.setId(videoId);
                update.setStatus(3);
                update.setTranscodedKey(transcodedKey);
                update.setCoverUrl(coverUrl);
                update.setSummary(summary);
                update.setTags(tags);
                if (analysisResult.transcript() != null) {
                    update.setTranscript(analysisResult.transcript());
                }
                videoMapper.updateById(update);

            } finally {
                cleanupTempDir(tempDir);
            }

            log.info("视频转码完成: videoId={}", videoId);
            sseService.push(videoId, "status-change", Map.of("videoId", videoId, "status", 3));

        } catch (Exception e) {
            log.error("视频转码失败: videoId={}", videoId, e);
            Video failUpdate = new Video();
            failUpdate.setId(videoId);
            failUpdate.setStatus(4);
            videoMapper.updateById(failUpdate);
            sseService.push(videoId, "status-change", Map.of("videoId", videoId, "status", 4));
        } finally {
            deduplicationService.releaseLongTaskLock("transcode:" + videoId);
        }
    }

    private int updateStatus(Long videoId, int fromStatus, int toStatus) {
        Video update = new Video();
        update.setId(videoId);
        update.setStatus(toStatus);
        return videoMapper.update(update,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Video>()
                        .eq(Video::getId, videoId)
                        .eq(Video::getStatus, fromStatus));
    }

    private void cleanupTempDir(Path tempDir) {
        if (tempDir == null) return;
        try (Stream<Path> stream = Files.walk(tempDir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        } catch (Exception e) {
            log.warn("清理临时目录失败: {}", tempDir, e);
        }
    }
}
