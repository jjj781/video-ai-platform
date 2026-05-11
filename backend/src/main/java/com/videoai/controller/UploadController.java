package com.videoai.controller;

import com.videoai.aspect.RateLimitAspect.RateLimit;
import com.videoai.dto.UploadInitDTO;
import com.videoai.service.MinioService;
import com.videoai.service.UploadService;
import com.videoai.vo.Result;
import com.videoai.vo.UploadInitVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 视频上传控制器 (基于MinIO)
 * 前端凭预签名URL直传MinIO，消除业务服务器带宽瓶颈
 */
@Tag(name = "视频上传", description = "分片上传、断点续传、预签名URL直传")
@RestController
@RequestMapping("/upload")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;
    private final MinioService minioService;

    /**
     * 初始化分片上传
     * 返回uploadId和分片信息，前端用uploadId获取每个分片的预签名URL
     */
    @Operation(summary = "初始化分片上传")
    @PostMapping("/init")
    @RateLimit(permitsPerMinute = 10, key = "upload-init")
    public Result<UploadInitVO> initUpload(@Valid @RequestBody UploadInitDTO dto) {
        return Result.ok(uploadService.initUpload(dto));
    }

    /**
     * 获取单个分片的预签名上传URL
     * 前端拿到URL后直接PUT文件到MinIO，无需经过业务服务器
     */
    @Operation(summary = "获取分片预签名上传URL")
    @GetMapping("/presigned-url/{taskId}/{chunkIndex}")
    @RateLimit(permitsPerMinute = 3000, key = "presigned-url")
    public Result<Map<String, String>> getPresignedUrl(
            @PathVariable String taskId,
            @PathVariable int chunkIndex) {
        String url = uploadService.getChunkPresignedUrl(taskId, chunkIndex);
        return Result.ok(Map.of("presignedUrl", url));
    }

    /**
     * 前端直传MinIO后回调，更新分片进度
     */
    @Operation(summary = "分片上传完成回调")
    @PostMapping("/chunk-callback/{taskId}/{chunkIndex}")
    public Result<Void> chunkCallback(
            @PathVariable String taskId,
            @PathVariable int chunkIndex) {
        uploadService.updateChunkProgress(taskId, chunkIndex);
        return Result.ok();
    }

    /**
     * 服务端代理上传（兜底方案，前端也可通过此接口上传）
     */
    @Operation(summary = "上传分片(服务端代理)")
    @PostMapping("/chunk/{taskId}/{chunkIndex}")
    @RateLimit(permitsPerMinute = 1200, key = "upload-chunk")
    public Result<Void> uploadChunk(
            @PathVariable String taskId,
            @PathVariable int chunkIndex,
            @RequestParam("file") MultipartFile file) {
        try {
            uploadService.uploadChunk(taskId, chunkIndex, file.getInputStream());
            return Result.ok();
        } catch (Exception e) {
            return Result.fail("分片上传失败: " + e.getMessage());
        }
    }

    /**
     * 查询上传进度（断点续传）
     */
    @Operation(summary = "查询上传进度")
    @GetMapping("/progress/{taskId}")
    public Result<UploadInitVO> getProgress(@PathVariable String taskId) {
        return Result.ok(uploadService.getUploadProgress(taskId));
    }

    /**
     * 获取文件的公开访问URL
     */
    @Operation(summary = "获取文件访问URL")
    @GetMapping("/file-url")
    public Result<Map<String, String>> getFileUrl(@RequestParam String objectKey) {
        return Result.ok(Map.of("url", minioService.getObjectUrl(objectKey)));
    }
}
