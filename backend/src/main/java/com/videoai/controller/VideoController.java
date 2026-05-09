package com.videoai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.videoai.entity.Video;
import com.videoai.mapper.VideoMapper;
import com.videoai.service.MinioService;
import com.videoai.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 视频管理控制器
 */
@Tag(name = "视频管理", description = "视频CRUD、列表查询")
@RestController
@RequestMapping("/video")
@RequiredArgsConstructor
public class VideoController {

    private final VideoMapper videoMapper;
    private final MinioService minioService;

    @Operation(summary = "分页查询视频列表")
    @GetMapping("/list")
    public Result<Page<Video>> listVideos(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {

        LambdaQueryWrapper<Video> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(Video::getTitle, keyword)
                    .or().like(Video::getDescription, keyword));
        }
        if (status != null) {
            wrapper.eq(Video::getStatus, status);
        }
        wrapper.orderByDesc(Video::getCreatedAt);

        Page<Video> result = videoMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.ok(result);
    }

    @Operation(summary = "获取视频详情")
    @GetMapping("/{id}")
    public Result<Video> getVideo(@PathVariable Long id) {
        Video video = videoMapper.selectById(id);
        if (video == null) {
            return Result.fail(404, "视频不存在");
        }
        return Result.ok(video);
    }

    @Operation(summary = "删除视频(逻辑删除)")
    @DeleteMapping("/{id}")
    public Result<Void> deleteVideo(@PathVariable Long id) {
        videoMapper.deleteById(id);
        return Result.ok();
    }

    @Operation(summary = "获取视频播放URL")
    @GetMapping("/{id}/play-url")
    public Result<Map<String, String>> getPlayUrl(@PathVariable Long id) {
        Video video = videoMapper.selectById(id);
        if (video == null) {
            return Result.fail(404, "视频不存在");
        }
        String key = video.getTranscodedKey() != null
                ? video.getTranscodedKey()
                : video.getOssKey();
        return Result.ok(Map.of("url", minioService.getPresignedDownloadUrl(key)));
    }
}
