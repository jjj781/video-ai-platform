package com.videoai.mq;

import com.videoai.entity.Video;
import com.videoai.mapper.VideoMapper;
import com.videoai.service.VideoTranscodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 延迟检查消费者 —— 5分钟延迟消息到达后检查视频是否卡在待转码
 * 如果已转码完成则跳过，如果还卡着则重新触发转码
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "VIDEO_TRANSCODE_CHECK",
        consumerGroup = "video-transcode-check-consumer-group"
)
public class VideoTranscodeCheckConsumer implements RocketMQListener<VideoTranscodeMessage> {

    private final VideoMapper videoMapper;
    private final VideoTranscodeService videoTranscodeService;

    @Override
    public void onMessage(VideoTranscodeMessage message) {
        Long videoId = message.getVideoId();
        Video video = videoMapper.selectById(videoId);

        if (video == null) {
            log.warn("延迟检查: 视频不存在, videoId={}", videoId);
            return;
        }

        if (video.getStatus() == 3) {
            log.info("延迟检查: 视频已转码完成, 跳过, videoId={}", videoId);
            return;
        }

        if (video.getStatus() == 4) {
            log.info("延迟检查: 视频已标记失败, 跳过, videoId={}", videoId);
            return;
        }

        // 还卡在 status=1(待转码) 或 status=2(转码中) → 重新触发
        log.warn("延迟检查: 视频仍卡在待转码, 重新触发转码, videoId={}, status={}", videoId, video.getStatus());
        try {
            videoTranscodeService.transcodeAsync(message);
        } catch (Exception e) {
            log.error("延迟检查重发转码失败: videoId={}", videoId, e);
        }
    }
}
