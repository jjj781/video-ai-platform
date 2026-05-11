package com.videoai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 分片上传初始化请求
 */
@Data
public class UploadInitDTO {

    @NotBlank(message = "文件名不能为空")
    private String filename;

    @NotNull(message = "文件大小不能为空")
    @Min(value = 1, message = "文件大小必须大于0")
    private Long fileSize;

    @NotBlank(message = "文件MD5不能为空")
    private String fileMd5;

    /** 快速哈希（首尾采样，用于初步去重匹配） */
    private String quickHash;

    /** 视频标题 */
    private String title;

    /** 视频描述 */
    private String description;
}
