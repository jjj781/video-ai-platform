package com.videoai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 视频实体
 */
@Data
@TableName("t_video")
public class Video {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 视频标题 */
    private String title;

    /** 视频描述 */
    private String description;

    /** 上传用户ID */
    private Long userId;

    /** OSS对象键 */
    private String ossKey;

    /** 原始文件名 */
    private String originalFilename;

    /** 文件大小(bytes) */
    private Long fileSize;

    /** 文件MD5 (用于去重) */
    private String fileMd5;

    /** 视频时长(秒) */
    private Integer duration;

    /** 视频封面URL */
    private String coverUrl;

    /** 视频状态: 0-上传中, 1-待转码, 2-转码中, 3-已就绪, 4-转码失败 (删除由 @TableLogic 统一处理) */
    private Integer status;

    /** 转码后OSS Key */
    private String transcodedKey;

    /** 视频内容摘要 (AI生成) */
    private String summary;

    /** 音频转写全文 */
    private String transcript;

    /** AI分析标签 (JSON数组) */
    private String tags;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
