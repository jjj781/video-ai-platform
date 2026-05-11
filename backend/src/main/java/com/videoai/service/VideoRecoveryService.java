package com.videoai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoai.entity.Video;
import com.videoai.mapper.VideoMapper;
import com.videoai.mq.VideoTranscodeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 视频恢复服务 —— 终极兜底扫描
 * 仅在延迟消息也丢失时生效（如 RocketMQ Broker 长时间宕机）
 * 延迟消息机制已覆盖绝大多数场景，此处作为每30分钟一次的保险丝
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoRecoveryService {

    private final VideoMapper videoMapper;
    private final VideoTranscodeService videoTranscodeService;

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    /**
     * 每30分钟扫描一次，将滞留超过10分钟的待转码视频重新触发转码
     */
    @Scheduled(cron = "0 */30 * * * *")
    public void recoverStuckVideos() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);

        List<Video> stuckVideos = videoMapper.selectList(
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getStatus, 1)
                        .lt(Video::getUpdatedAt, threshold)
                        .last("LIMIT 20"));

        if (stuckVideos.isEmpty()) return;

        log.info("兜底扫描: 发现 {} 个卡在待转码的视频", stuckVideos.size());

        for (Video video : stuckVideos) {
            try {
                VideoTranscodeMessage msg = VideoTranscodeMessage.of(
                        video.getId(),
                        video.getOssKey(),
                        video.getOriginalFilename(),
                        video.getFileSize());

                // 优先 RocketMQ
                if (rocketMQTemplate != null) {
                    try {
                        rocketMQTemplate.convertAndSend("VIDEO_TRANSCODE", msg);
                        log.info("兜底恢复(RocketMQ): videoId={}", video.getId());
                        continue;
                    } catch (Exception e) {
                        log.warn("RocketMQ兜底发送失败，降级@Async: videoId={}", video.getId(), e);
                    }
                }
                // 降级路径
                videoTranscodeService.transcodeAsync(msg);
                log.info("兜底恢复(@Async): videoId={}", video.getId());

            } catch (Exception e) {
                log.error("兜底恢复失败: videoId={}", video.getId(), e);
            }
        }
    }
}
