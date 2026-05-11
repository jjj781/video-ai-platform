package com.videoai.service.impl;

import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.videoai.config.AiConfig;
import com.videoai.entity.AiConversation;
import com.videoai.entity.Video;
import com.videoai.mapper.AiConversationMapper;
import com.videoai.mapper.VideoMapper;
import com.videoai.service.AiService;
import com.videoai.util.RetryUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * AI智能问答服务
 * 接入硅基流动大模型，基于Redis构建会话记忆上下文
 * 通过Function Calling实现业务数据的精准查询与结构化总结
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceImpl implements AiService {

    private record LlmResult(String content, Integer tokenUsage) {}

    private final AiConfig aiConfig;
    private final StringRedisTemplate redisTemplate;
    private final VideoMapper videoMapper;
    private final AiConversationMapper conversationMapper;
    private final TaskExecutor sseExecutor;

    private static final String CONTEXT_PREFIX = "ai:ctx:";
    private static final int MAX_CONTEXT_MESSAGES = 20;
    private static final int RECENT_MSG_COUNT = 10;    // 保留最近10条原文
    private static final int SUMMARIZE_TRIGGER = 10;   // >10条触发异步摘要

    /**
     * AI智能问答（带Function Calling）
     */
    @Override
    public String chat(String conversationId, Long videoId, String userMessage, Long userId) {
        // 1. 获取/创建会话上下文（system prompt 每次从 videoId 重建，summary + 最近消息从 Redis 读取）
        List<Map<String, String>> messages = getContext(conversationId, videoId);

        // 2. 添加用户消息
        messages.add(Map.of("role", "user", "content", userMessage));

        // 3. 调用大模型（含Function Calling）
        LlmResult llmResult = callLlmWithFunctions(messages, videoId);

        // 4. 保存上下文
        messages.add(Map.of("role", "assistant", "content", llmResult.content()));
        saveContext(conversationId, messages, videoId);

        // 5. 持久化对话记录
        AiConversation conversation = new AiConversation();
        conversation.setConversationId(conversationId);
        conversation.setVideoId(videoId);
        conversation.setUserId(userId);
        conversation.setUserMessage(userMessage);
        conversation.setAiResponse(llmResult.content());
        conversation.setTokenUsed(llmResult.tokenUsage());
        conversationMapper.insert(conversation);

        return llmResult.content();
    }

    /**
     * AI智能问答（流式SSE输出）
     * 两步走策略：先用非流式处理Function Calling，再用流式输出最终回答
     */
    @Override
    public SseEmitter chatStream(String conversationId, Long videoId, String userMessage, Long userId) {
        SseEmitter emitter = new SseEmitter(120000L); // 2分钟超时

        // 发送conversationId让前端保存（用于后续对话上下文）
        try {
            emitter.send(SseEmitter.event().name("meta").data("{\"conversationId\":\"" + conversationId + "\"}"));
        } catch (Exception ignored) {}

        // 异步执行，避免阻塞主线程
        final StringBuilder[] fullResponseHolder = new StringBuilder[1];
        sseExecutor.execute(() -> {
            try {
                List<Map<String, String>> messages = getContext(conversationId, videoId);
                messages.add(Map.of("role", "user", "content", userMessage));

                // Step 1: 非流式调用 + Function Calling
                List<Map<String, String>> resolvedMessages = resolveFunctionCalls(messages, videoId);

                // Step 2: 流式输出最终回答
                StringBuilder fullResponse = streamFinalResponse(resolvedMessages, emitter);
                fullResponseHolder[0] = fullResponse;

                // 保存上下文
                resolvedMessages.add(Map.of("role", "assistant", "content", fullResponse.toString()));
                saveContext(conversationId, resolvedMessages, videoId);

                // 持久化
                AiConversation conversation = new AiConversation();
                conversation.setConversationId(conversationId);
                conversation.setVideoId(videoId);
                conversation.setUserId(userId);
                conversation.setUserMessage(userMessage);
                conversation.setAiResponse(fullResponse.toString());
                conversationMapper.insert(conversation);

                emitter.complete();

            } catch (Exception e) {
                log.error("流式AI问答失败", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 第一步：非流式调用LLM处理Function Calling
     * 返回已加入工具调用结果后的消息列表
     */
    private List<Map<String, String>> resolveFunctionCalls(List<Map<String, String>> messages, Long videoId) {
        JSONObject requestBody = new JSONObject();
        requestBody.set("model", aiConfig.getModel());
        requestBody.set("messages", messages);
        requestBody.set("max_tokens", aiConfig.getMaxTokens());
        requestBody.set("temperature", aiConfig.getTemperature());
        requestBody.set("stream", false);
        requestBody.set("tools", buildFunctionTools());
        requestBody.set("tool_choice", "auto");

        String responseBody = RetryUtil.retry(() -> {
            HttpResponse response = HttpRequest.post(aiConfig.getApiUrl())
                    .header(Header.AUTHORIZATION, "Bearer " + aiConfig.getApiKey())
                    .header(Header.CONTENT_TYPE, "application/json")
                    .body(requestBody.toString())
                    .timeout(60000)
                    .execute();
            if (!response.isOk()) {
                throw new RuntimeException("AI API调用失败: " + response.getStatus());
            }
            return response.body();
        });

        JSONObject responseJson = JSONUtil.parseObj(responseBody);
        JSONArray choices = responseJson.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("AI返回空响应");
        }

        JSONObject message = choices.getJSONObject(0).getJSONObject("message");

        if (message.containsKey("tool_calls")) {
            JSONArray toolCalls = message.getJSONArray("tool_calls");

            Map<String, Object> assistantMsg = new HashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", "");
            assistantMsg.put("tool_calls", toolCalls);
            messages.add((Map) assistantMsg);

            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject toolCall = toolCalls.getJSONObject(i);
                String functionName = toolCall.getJSONObject("function").getStr("name");
                String args = toolCall.getJSONObject("function").getStr("arguments");
                String toolCallId = toolCall.getStr("id");

                String result = executeFunction(functionName, args, videoId);

                Map<String, String> toolMsg = new HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("content", result);
                toolMsg.put("tool_call_id", toolCallId);
                messages.add(toolMsg);
            }

            return messages;
        }

        // 无工具调用，把assistant的content放到一个临时消息里，然后移除（由streamFinalResponse重新生成）
        String directContent = message.getStr("content", "");
        Map<String, String> directMsg = new HashMap<>();
        directMsg.put("role", "assistant");
        directMsg.put("content", directContent);
        messages.add(directMsg);

        return messages;
    }

    /**
     * 第二步：流式调用LLM，通过SseEmitter将增量内容发送给前端
     */
    private StringBuilder streamFinalResponse(List<Map<String, String>> messages, SseEmitter emitter) throws Exception {
        // 如果最后一条消息是assistant且无tool_calls（即上一阶段LLM直接回答了），
        // 移除它重新流式生成（保持对话完整性）
        Map<String, String> lastMsg = messages.get(messages.size() - 1);
        if ("assistant".equals(lastMsg.get("role")) && !lastMsg.containsKey("tool_calls")) {
            messages.remove(messages.size() - 1);
        }

        JSONObject requestBody = new JSONObject();
        requestBody.set("model", aiConfig.getModel());
        requestBody.set("messages", messages);
        requestBody.set("max_tokens", aiConfig.getMaxTokens());
        requestBody.set("temperature", aiConfig.getTemperature());
        requestBody.set("stream", true);

        HttpResponse response = HttpRequest.post(aiConfig.getApiUrl())
                .header(Header.AUTHORIZATION, "Bearer " + aiConfig.getApiKey())
                .header(Header.CONTENT_TYPE, "application/json")
                .body(requestBody.toString())
                .timeout(120000)
                .execute();

        if (!response.isOk()) {
            throw new RuntimeException("LLM流式调用失败: " + response.getStatus());
        }

        StringBuilder fullContent = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.bodyStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || !line.startsWith("data: ")) continue;
                String data = line.substring(6);
                if ("[DONE]".equals(data)) break;

                try {
                    JSONObject json = JSONUtil.parseObj(data);
                    JSONArray choices = json.getJSONArray("choices");
                    if (choices == null || choices.isEmpty()) continue;

                    JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                    if (delta == null) continue;

                    String content = delta.getStr("content");
                    if (content != null && !content.isEmpty()) {
                        fullContent.append(content);
                        emitter.send(SseEmitter.event().name("token").data(content));
                    }
                } catch (Exception e) {
                    log.debug("解析流式数据块失败: {}", data, e);
                }
            }
        }

        emitter.send(SseEmitter.event().name("done").data(""));
        return fullContent;
    }

    /**
     * 调用硅基流动大模型，支持Function Calling
     */
    private LlmResult callLlmWithFunctions(List<Map<String, String>> messages, Long videoId) {
        JSONObject requestBody = new JSONObject();
        requestBody.set("model", aiConfig.getModel());
        requestBody.set("messages", messages);
        requestBody.set("max_tokens", aiConfig.getMaxTokens());
        requestBody.set("temperature", aiConfig.getTemperature());
        requestBody.set("stream", false);

        // 注册Function Calling工具
        requestBody.set("tools", buildFunctionTools());
        requestBody.set("tool_choice", "auto");

        // 指数退避重试调用
        String responseBody = RetryUtil.retry(() -> {
            HttpResponse response = HttpRequest.post(aiConfig.getApiUrl())
                    .header(Header.AUTHORIZATION, "Bearer " + aiConfig.getApiKey())
                    .header(Header.CONTENT_TYPE, "application/json")
                    .body(requestBody.toString())
                    .timeout(60000)
                    .execute();

            if (!response.isOk()) {
                throw new RuntimeException("AI API调用失败: " + response.getStatus() + " " + response.body());
            }
            return response.body();
        });

        // 解析响应，处理Function Calling
        JSONObject responseJson = JSONUtil.parseObj(responseBody);
        JSONArray choices = responseJson.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("AI返回空响应");
        }

        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        Integer tokenUsage = extractTokenUsage(responseJson);

        // 如果有tool_calls，执行函数调用后再次请求AI
        if (message.containsKey("tool_calls")) {
            return handleToolCalls(message, messages, videoId, tokenUsage);
        }

        return new LlmResult(message.getStr("content"), tokenUsage);
    }

    private Integer extractTokenUsage(JSONObject responseJson) {
        JSONObject usage = responseJson.getJSONObject("usage");
        if (usage != null) {
            return usage.getInt("total_tokens");
        }
        return null;
    }

    /**
     * 处理Function Calling的工具调用
     */
    private LlmResult handleToolCalls(JSONObject message, List<Map<String, String>> messages, Long videoId, Integer firstCallTokens) {
        JSONArray toolCalls = message.getJSONArray("tool_calls");

        // 将assistant的tool_calls消息加入上下文
        // 注意：带tool_calls的assistant消息content应为空，tool_calls单独存放
        Map<String, Object> assistantMsg = new HashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", "");
        assistantMsg.put("tool_calls", message.getJSONArray("tool_calls"));
        messages.add((Map) assistantMsg);

        // 执行每个工具调用
        for (int i = 0; i < toolCalls.size(); i++) {
            JSONObject toolCall = toolCalls.getJSONObject(i);
            String functionName = toolCall.getJSONObject("function").getStr("name");
            String args = toolCall.getJSONObject("function").getStr("arguments");
            String toolCallId = toolCall.getStr("id");

            // 执行函数
            String result = executeFunction(functionName, args, videoId);

            // 将结果加入上下文
            Map<String, String> toolMsg = new HashMap<>();
            toolMsg.put("role", "tool");
            toolMsg.put("content", result);
            toolMsg.put("tool_call_id", toolCallId);
            messages.add(toolMsg);
        }

        // 再次请求AI获取最终回答
        JSONObject requestBody = new JSONObject();
        requestBody.set("model", aiConfig.getModel());
        requestBody.set("messages", messages);
        requestBody.set("max_tokens", aiConfig.getMaxTokens());
        requestBody.set("temperature", aiConfig.getTemperature());

        String responseBody = RetryUtil.retry(() -> {
            HttpResponse response = HttpRequest.post(aiConfig.getApiUrl())
                    .header(Header.AUTHORIZATION, "Bearer " + aiConfig.getApiKey())
                    .header(Header.CONTENT_TYPE, "application/json")
                    .body(requestBody.toString())
                    .timeout(60000)
                    .execute();
            if (!response.isOk()) {
                throw new RuntimeException("AI API调用失败: " + response.getStatus());
            }
            return response.body();
        });

        JSONObject responseJson = JSONUtil.parseObj(responseBody);
        String content = responseJson.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getStr("content");

        Integer secondCallTokens = extractTokenUsage(responseJson);
        Integer totalTokens = (firstCallTokens != null && secondCallTokens != null)
                ? firstCallTokens + secondCallTokens
                : secondCallTokens;

        return new LlmResult(content, totalTokens);
    }

    /**
     * 定义Function Calling工具集
     */
    private JSONArray buildFunctionTools() {
        JSONArray tools = new JSONArray();

        // 查询视频信息
        tools.add(JSONUtil.createObj()
                .set("type", "function")
                .set("function", JSONUtil.createObj()
                        .set("name", "get_video_info")
                        .set("description", "查询视频的详细信息，包括标题、描述、时长、状态、AI摘要、标签等")
                        .set("parameters", JSONUtil.createObj()
                                .set("type", "object")
                                .set("properties", JSONUtil.createObj()
                                        .set("video_id", JSONUtil.createObj()
                                                .set("type", "integer")
                                                .set("description", "视频ID")))
                                .set("required", List.of("video_id")))));

        // 搜索视频
        tools.add(JSONUtil.createObj()
                .set("type", "function")
                .set("function", JSONUtil.createObj()
                        .set("name", "search_videos")
                        .set("description", "根据关键词搜索视频列表")
                        .set("parameters", JSONUtil.createObj()
                                .set("type", "object")
                                .set("properties", JSONUtil.createObj()
                                        .set("keyword", JSONUtil.createObj()
                                                .set("type", "string")
                                                .set("description", "搜索关键词"))
                                        .set("limit", JSONUtil.createObj()
                                                .set("type", "integer")
                                                .set("description", "返回数量限制，默认10")))
                                .set("required", List.of("keyword")))));

        // 获取视频统计
        tools.add(JSONUtil.createObj()
                .set("type", "function")
                .set("function", JSONUtil.createObj()
                        .set("name", "get_video_stats")
                        .set("description", "获取视频平台的统计数据，包括视频总数、状态分布等")
                        .set("parameters", JSONUtil.createObj()
                                .set("type", "object")
                                .set("properties", JSONUtil.createObj()))));

        return tools;
    }

    /**
     * 执行Function Calling
     */
    private String executeFunction(String functionName, String args, Long videoId) {
        JSONObject argsJson = JSONUtil.parseObj(args);

        switch (functionName) {
            case "get_video_info": {
                Long vid = argsJson.getLong("video_id", videoId);
                Video video = videoMapper.selectById(vid);
                if (video == null) return JSONUtil.toJsonStr(Map.of("error", "视频不存在"));
                return JSONUtil.toJsonStr(video);
            }
            case "search_videos": {
                String keyword = argsJson.getStr("keyword");
                int limit = argsJson.getInt("limit", 10);
                List<Video> videos = videoMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Video>()
                                .like(Video::getTitle, keyword)
                                .or().like(Video::getDescription, keyword)
                                .last("LIMIT " + limit));
                return JSONUtil.toJsonStr(videos);
            }
            case "get_video_stats": {
                long total = videoMapper.selectCount(null);
                // 简化统计
                return JSONUtil.toJsonStr(Map.of("total_videos", total));
            }
            default:
                return JSONUtil.toJsonStr(Map.of("error", "未知函数: " + functionName));
        }
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt(Long videoId) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能视频分析助手。你可以帮助用户：\n");
        sb.append("1. 查询和分析视频内容\n");
        sb.append("2. 搜索视频库中的视频\n");
        sb.append("3. 提供视频平台的统计数据\n");
        sb.append("4. 基于视频内容回答问题\n\n");
        sb.append("请使用提供的工具函数来获取准确的数据，而不是凭空编造。");
        if (videoId != null) {
            sb.append("\n\n当前用户正在查看视频ID: ").append(videoId);
            Video video = videoMapper.selectById(videoId);
            if (video != null) {
                sb.append("\n视频标题: ").append(video.getTitle());
                if (video.getSummary() != null && !video.getSummary().isBlank()) {
                    sb.append("\n视频摘要: ").append(video.getSummary());
                }
                if (video.getTranscript() != null && !video.getTranscript().isBlank()) {
                    String transcript = video.getTranscript();
                    if (transcript.length() > 8000) {
                        transcript = transcript.substring(0, 8000) + "...(后续内容已截断)";
                    }
                    sb.append("\n\n音频转写内容:\n").append(transcript);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 组装完整上下文: system prompt(每次重建) + 摘要(如存在) + 最近消息
     */
    private List<Map<String, String>> getContext(String conversationId, Long videoId) {
        List<Map<String, String>> messages = new ArrayList<>();

        // 1. system prompt — 每次都从 videoId 重建（确保切视频时上下文不泄漏）
        messages.add(Map.of("role", "system", "content", buildSystemPrompt(videoId)));

        // 2. 摘要 — 旧消息的压缩版
        String summary = redisTemplate.opsForValue().get(CONTEXT_PREFIX + conversationId + ":summary");
        if (summary != null && !summary.isBlank()) {
            messages.add(Map.of("role", "user",
                    "content", "【历史对话背景】" + summary + "\n请基于以上背景继续回答用户的问题。"));
        }

        // 3. 最近消息 — 从 Redis List 读取
        String msgsKey = CONTEXT_PREFIX + conversationId + ":msgs";
        List<String> raw = redisTemplate.opsForList().range(msgsKey, 0, -1);
        if (raw != null) {
            for (String json : raw) {
                try {
                    Map<String, String> msg = JSONUtil.toBean(json, Map.class);
                    messages.add(msg);
                } catch (Exception e) {
                    log.warn("解析会话消息失败: conversationId={}", conversationId, e);
                }
            }
        }

        return messages;
    }

    /**
     * 保存会话上下文到 Redis（List 存储 + 异步摘要触发）
     */
    private void saveContext(String conversationId, List<Map<String, String>> messages, Long videoId) {
        // 过滤掉 system prompt，只保存对话消息
        List<Map<String, String>> conversation = new ArrayList<>();
        for (Map<String, String> msg : messages) {
            if (!"system".equals(msg.get("role"))) {
                conversation.add(msg);
            }
        }

        String msgsKey = CONTEXT_PREFIX + conversationId + ":msgs";
        // 先清旧数据，再写入全部
        redisTemplate.delete(msgsKey);
        for (Map<String, String> msg : conversation) {
            redisTemplate.opsForList().rightPush(msgsKey, JSONUtil.toJsonStr(msg));
        }
        // 滑动窗口裁剪
        redisTemplate.opsForList().trim(msgsKey, -MAX_CONTEXT_MESSAGES, -1);
        redisTemplate.expire(msgsKey, 24, TimeUnit.HOURS);

        // 异步触发摘要压缩
        if (conversation.size() > SUMMARIZE_TRIGGER) {
            String lockKey = CONTEXT_PREFIX + conversationId + ":summary-lock";
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", 30, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(acquired)) {
                sseExecutor.execute(() -> summarizeAsync(conversationId, conversation));
            }
        }
    }

    /**
     * 异步调用 LLM 将旧消息压缩为简短摘要，存回 Redis
     */
    private void summarizeAsync(String conversationId, List<Map<String, String>> conversation) {
        try {
            // 取前 half 条作为压缩素材（保留后面 half 条原文）
            int half = conversation.size() / 2;
            List<Map<String, String>> oldMessages = conversation.subList(0, half);

            // 读取已有摘要
            String existingSummary = redisTemplate.opsForValue()
                    .get(CONTEXT_PREFIX + conversationId + ":summary");

            // 构建 prompt
            StringBuilder prompt = new StringBuilder();
            prompt.append("你是一个对话摘要助手。请将以下多轮对话压缩成一段简短的背景描述（100字以内）。\n");
            prompt.append("只记录关键信息：用户问了什么、AI查了什么信息、得出的结论。\n\n");

            if (existingSummary != null && !existingSummary.isBlank()) {
                prompt.append("现有摘要：").append(existingSummary).append("\n\n");
            }

            prompt.append("待压缩的对话：\n");
            for (Map<String, String> msg : oldMessages) {
                prompt.append("[").append(msg.get("role")).append("]: ")
                      .append(msg.get("content")).append("\n");
            }

            prompt.append("\n请输出合并后的完整摘要（仅摘要内容，不要其他文字）：");

            // 调用 LLM
            String newSummary = callLlmForSummary(prompt.toString());
            if (newSummary != null && !newSummary.isBlank()) {
                String summaryKey = CONTEXT_PREFIX + conversationId + ":summary";
                redisTemplate.opsForValue().set(summaryKey, newSummary, 24, TimeUnit.HOURS);
                log.info("对话摘要生成成功: conversationId={}, len={}", conversationId, newSummary.length());
            }

        } catch (Exception e) {
            log.warn("对话摘要生成失败: conversationId={}", conversationId, e);
        } finally {
            // 释放锁
            redisTemplate.delete(CONTEXT_PREFIX + conversationId + ":summary-lock");
        }
    }

    /**
     * 调用 LLM 生成摘要（简化调用，无 Function Calling，无重试）
     */
    private String callLlmForSummary(String prompt) {
        JSONArray messages = new JSONArray();
        messages.add(new JSONObject().set("role", "user").set("content", prompt));

        JSONObject body = new JSONObject();
        body.set("model", aiConfig.getModel());
        body.set("messages", messages);
        body.set("max_tokens", 300);
        body.set("temperature", 0.3);
        body.set("stream", false);

        HttpResponse response = HttpRequest.post(aiConfig.getApiUrl())
                .header(Header.AUTHORIZATION, "Bearer " + aiConfig.getApiKey())
                .header(Header.CONTENT_TYPE, "application/json")
                .body(body.toString())
                .timeout(60000)
                .execute();

        if (!response.isOk()) {
            log.warn("摘要LLM调用失败: status={}", response.getStatus());
            return null;
        }

        JSONObject json = JSONUtil.parseObj(response.body());
        JSONArray choices = json.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) return null;

        return choices.getJSONObject(0).getJSONObject("message").getStr("content");
    }
}
