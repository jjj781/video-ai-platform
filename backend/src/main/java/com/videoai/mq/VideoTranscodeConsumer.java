package com.videoai.mq;

import com.videoai.service.VideoTranscodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 视频转码消费者
 * RocketMQ异步解耦长耗时任务
 * 委托给VideoTranscodeService执行实际转码逻辑
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "VIDEO_TRANSCODE",
        consumerGroup = "video-transcode-consumer-group"
)
public class VideoTranscodeConsumer implements RocketMQListener<VideoTranscodeMessage> {

    private final VideoTranscodeService videoTranscodeService;

    @Override
    public void onMessage(VideoTranscodeMessage message) {
        log.info("收到视频转码消息: videoId={}, ossKey={}", message.getVideoId(), message.getOssKey());
        try {
            videoTranscodeService.doTranscode(message);
        } catch (Exception e) {
            // CAS冲突（已被其他消费者处理）→ 正常确认，不重试
            if (e.getMessage() != null && e.getMessage().contains("CAS更新失败")) {
                log.info("消息已被其他消费者处理，确认消费: videoId={}", message.getVideoId());
                return;
            }
            // 其他异常 → 重新投递
            log.error("转码失败，消息重新投递: videoId={}", message.getVideoId(), e);
            throw new RuntimeException("转码失败，重新投递", e);
        }
    }
}
