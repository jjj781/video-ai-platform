package com.videoai.mq;

import lombok.Data;
import java.io.Serializable;

/**
 * 视频转码MQ消息
 */
@Data
public class VideoTranscodeMessage implements Serializable {

    private Long videoId;
    private String ossKey;
    private String filename;
    private Long fileSize;

    public static VideoTranscodeMessage of(Long videoId, String ossKey, String filename, Long fileSize) {
        VideoTranscodeMessage msg = new VideoTranscodeMessage();
        msg.setVideoId(videoId);
        msg.setOssKey(ossKey);
        msg.setFilename(filename);
        msg.setFileSize(fileSize);
        return msg;
    }
}
