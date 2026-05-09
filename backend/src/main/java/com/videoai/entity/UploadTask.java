package com.videoai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 分片上传任务
 */
@Data
@TableName("t_upload_task")
public class UploadTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务唯一标识 (用于断点续传) */
    private String taskId;

    /** 关联视频ID */
    private Long videoId;

    /** 上传用户ID */
    private Long userId;

    /** OSS Upload ID */
    private String uploadId;

    /** 原始文件名 */
    private String filename;

    /** 文件大小 */
    private Long fileSize;

    /** 文件MD5 */
    private String fileMd5;

    /** 分片大小 */
    private Long chunkSize;

    /** 总分片数 */
    private Integer totalChunks;

    /** 已完成分片数 */
    private Integer completedChunks;

    /** 已完成分片索引 (JSON数组, 如 [0,1,2]) */
    private String completedChunkIndexes;

    /** 任务状态: 0-进行中, 1-已完成, 2-已失败, 3-已取消 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
