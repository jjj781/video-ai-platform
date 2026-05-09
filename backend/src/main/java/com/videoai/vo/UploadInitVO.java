package com.videoai.vo;

import lombok.Data;

/**
 * 分片上传初始化响应
 */
@Data
public class UploadInitVO {

    /** 上传任务ID (用于断点续传) */
    private String taskId;

    /** OSS Upload ID */
    private String uploadId;

    /** 分片大小 */
    private long chunkSize;

    /** 总分片数 */
    private int totalChunks;

    /** 已完成的分片索引列表 (断点续传时使用) */
    private java.util.List<Integer> completedChunks;

    /** 视频ID (上传完成后用于跳转) */
    private Long videoId;
}
