package com.videoai.mq;

import com.videoai.entity.Video;
import com.videoai.mapper.VideoMapper;
import com.videoai.service.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 死信队列消费者 —— RocketMQ重试耗尽后进入此队列
 * 记录失败信息、标记视频为转码失败、通知前端
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "%DLQ%video-transcode-consumer-group",
        consumerGroup = "video-transcode-dlq-consumer-group"
)
public class VideoTranscodeDlqConsumer implements RocketMQListener<VideoTranscodeMessage> {

    private final VideoMapper videoMapper;
    private final SseService sseService;

    @Override
    public void onMessage(VideoTranscodeMessage message) {
        Long videoId = message.getVideoId();
        log.error("转码死信: videoId={}, ossKey={}，已重试耗尽标记为失败", videoId, message.getOssKey());

        // 标记视频为转码失败
        Video video = videoMapper.selectById(videoId);
        if (video != null && video.getStatus() == 2) {
            Video update = new Video();
            update.setId(videoId);
            update.setStatus(4);
            videoMapper.updateById(update);

            // SSE 通知前端
            sseService.push(videoId, "status-change", Map.of("videoId", videoId, "status", 4));
            log.info("已通过死信队列标记视频转码失败并通知前端: videoId={}", videoId);
        }
    }
}
