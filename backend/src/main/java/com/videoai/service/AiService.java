package com.videoai.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AiService {

    /**
     * AI智能问答
     *
     * @param conversationId 会话ID
     * @param videoId        关联视频ID (可选)
     * @param userMessage    用户消息
     * @param userId         用户ID
     * @return AI回复
     */
    String chat(String conversationId, Long videoId, String userMessage, Long userId);

    /**
     * AI智能问答（流式SSE输出）
     */
    SseEmitter chatStream(String conversationId, Long videoId, String userMessage, Long userId);
}
