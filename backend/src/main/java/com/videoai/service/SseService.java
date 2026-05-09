package com.videoai.service;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE服务端推送
 * 管理按videoId分组的SseEmitter注册表，转码完成时推送通知到订阅该视频的前端
 */
@Slf4j
@Service
public class SseService {

    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    private static final long TIMEOUT = 30 * 60 * 1000L; // 30分钟

    public SseEmitter subscribe(Long videoId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT);
        emitters.computeIfAbsent(videoId, k -> ConcurrentHashMap.newKeySet()).add(emitter);

        emitter.onCompletion(() -> remove(videoId, emitter));
        emitter.onTimeout(() -> remove(videoId, emitter));
        emitter.onError(e -> remove(videoId, emitter));

        log.info("SSE订阅: videoId={}, 当前订阅数={}", videoId, emitters.get(videoId).size());
        return emitter;
    }

    public void push(Long videoId, String eventName, Object data) {
        Set<SseEmitter> set = emitters.get(videoId);
        if (set == null || set.isEmpty()) return;

        String json = JSONUtil.toJsonStr(data);
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(json));
            } catch (IOException e) {
                emitter.completeWithError(e);
                set.remove(emitter);
            }
        }
        log.info("SSE推送: videoId={}, event={}, 接收方={}", videoId, eventName, set.size());
    }

    private void remove(Long videoId, SseEmitter emitter) {
        Set<SseEmitter> set = emitters.get(videoId);
        if (set != null) {
            set.remove(emitter);
            if (set.isEmpty()) {
                emitters.remove(videoId);
            }
            log.info("SSE取消订阅: videoId={}", videoId);
        }
    }
}
