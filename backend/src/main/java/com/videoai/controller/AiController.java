package com.videoai.controller;

import com.videoai.aspect.RateLimitAspect.RateLimit;
import com.videoai.service.AiService;
import com.videoai.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * AI智能问答控制器
 * 接入硅基流动大模型，基于Redis会话记忆上下文
 */
@Tag(name = "AI智能问答", description = "大模型对话、Function Calling")
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    @Data
    public static class ChatRequest {
        private String conversationId;
        private Long videoId;
        private String message;
    }

    @Operation(summary = "AI对话")
    @PostMapping("/chat")
    @RateLimit(permitsPerMinute = 300, key = "ai-chat")
    public Result<Map<String, String>> chat(@RequestBody ChatRequest request) {
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isEmpty()) {
            conversationId = java.util.UUID.randomUUID().toString();
        }

        String reply = aiService.chat(
                conversationId,
                request.getVideoId(),
                request.getMessage(),
                1L // TODO: 从登录态获取
        );

        return Result.ok(Map.of(
                "conversationId", conversationId,
                "reply", reply
        ));
    }

    @Operation(summary = "AI对话（流式SSE输出）")
    @PostMapping("/chat/stream")
    @RateLimit(permitsPerMinute = 300, key = "ai-chat-stream")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isEmpty()) {
            conversationId = java.util.UUID.randomUUID().toString();
        }

        return aiService.chatStream(
                conversationId,
                request.getVideoId(),
                request.getMessage(),
                1L // TODO: 从登录态获取
        );
    }
}
