package com.videoai.controller;

import com.videoai.service.SseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE服务端推送控制器
 * 前端通过EventSource订阅视频状态变更通知
 */
@Tag(name = "SSE推送", description = "视频状态变更实时通知")
@RestController
@RequestMapping("/sse")
@RequiredArgsConstructor
public class SseController {

    private final SseService sseService;

    @Operation(summary = "订阅视频状态变更")
    @GetMapping("/subscribe/{videoId}")
    public SseEmitter subscribe(@PathVariable Long videoId) {
        return sseService.subscribe(videoId);
    }
}
