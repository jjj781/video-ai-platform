package com.videoai.service;

import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.videoai.config.AiConfig;
import com.videoai.entity.Video;
import com.videoai.util.RetryUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoAnalysisService {

    private final AiConfig aiConfig;
    private final MinioService minioService;
    private final FfmpegService ffmpegService;

    private static final String TEMP_DIR_PREFIX = "video-ai-analysis-";

    public record AnalysisResult(String summary, String tags, String transcript) {
        public static AnalysisResult fallback(Video video) {
            String name = video.getOriginalFilename() != null
                    ? video.getOriginalFilename().split("\\.")[0]
                    : "未知视频";
            return new AnalysisResult(
                    "视频「" + name + "」已完成转码分析，AI内容分析暂时不可用。",
                    "[\"视频\"]",
                    null
            );
        }
    }

    /**
     * 完整的AI视频分析流水线
     */
    public AnalysisResult analyze(Video video) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory(TEMP_DIR_PREFIX + video.getId() + "-");
            log.info("开始AI视频分析: videoId={}, ossKey={}", video.getId(), video.getOssKey());

            String transcript = null;
            String visualDescription = null;

            // Stage 1: 下载视频并提取关键帧
            Path videoPath = downloadVideo(video, tempDir);
            List<Path> keyframes = extractKeyframes(videoPath, tempDir);

            // Stage 2: 提取音频并转写
            if (videoPath != null) {
                transcript = transcribeAudio(videoPath, tempDir);
            }

            // Stage 3: 分析关键帧
            if (keyframes != null && !keyframes.isEmpty()) {
                visualDescription = analyzeKeyframes(video.getId(), keyframes);
            }

            // 如果两者都失败，直接返回降级方案
            if (transcript == null && visualDescription == null) {
                log.warn("ASR和Vision分析均失败，使用降级方案: videoId={}", video.getId());
                return AnalysisResult.fallback(video);
            }

            // Stage 4: 综合生成摘要和标签
            return synthesize(video, transcript, visualDescription);

        } catch (Exception e) {
            log.error("AI视频分析异常: videoId={}", video.getId(), e);
            return AnalysisResult.fallback(video);
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    // ──────────────────── Pipeline stages ────────────────────

    private Path downloadVideo(Video video, Path tempDir) {
        try {
            String ossKey = video.getOssKey();
            String filename = video.getOriginalFilename();
            String ext = "";
            if (filename != null && filename.contains(".")) {
                ext = filename.substring(filename.lastIndexOf('.'));
            }
            Path videoPath = tempDir.resolve("video" + ext);
            minioService.downloadObject(ossKey, videoPath);
            return videoPath;
        } catch (Exception e) {
            log.error("视频下载失败: videoId={}, ossKey={}", video.getId(), video.getOssKey(), e);
            return null;
        }
    }

    private List<Path> extractKeyframes(Path videoPath, Path tempDir) {
        try {
            Path frameDir = tempDir.resolve("frames");
            int count = aiConfig.getKeyframeCount();
            List<Path> frames = ffmpegService.extractKeyframes(videoPath, frameDir, count);
            if (frames.isEmpty()) {
                log.warn("关键帧提取为空");
                return null;
            }
            return frames;
        } catch (Exception e) {
            log.error("关键帧提取失败: videoPath={}", videoPath, e);
            return null;
        }
    }

    private String transcribeAudio(Path videoPath, Path tempDir) {
        try {
            Path audioPath = ffmpegService.extractAudio(videoPath, tempDir);
            if (audioPath == null) {
                log.warn("音频提取失败，跳过ASR");
                return null;
            }
            return callAsr(audioPath);
        } catch (Exception e) {
            log.error("ASR转写失败", e);
            return null;
        }
    }

    private String analyzeKeyframes(Long videoId, List<Path> keyframePaths) {
        try {
            List<String> imageUrls = uploadKeyframes(videoId, keyframePaths);
            if (imageUrls.isEmpty()) {
                return null;
            }
            String result = callVision(imageUrls);
            deleteFrameObjects(videoId, keyframePaths.size());
            return result;
        } catch (Exception e) {
            log.error("Vision分析失败: videoId={}", videoId, e);
            return null;
        }
    }

    // ──────────────────── MinIO helpers ────────────────────

    private List<String> uploadKeyframes(Long videoId, List<Path> keyframePaths) {
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < keyframePaths.size(); i++) {
            try {
                String key = "analysis/" + videoId + "/frames/frame_" + String.format("%03d", i + 1) + ".png";
                long size = Files.size(keyframePaths.get(i));
                try (FileInputStream fis = new FileInputStream(keyframePaths.get(i).toFile())) {
                    minioService.uploadObject(key, fis, size, "image/png");
                }
                String url = minioService.getPresignedDownloadUrl(key);
                urls.add(url);
                log.info("关键帧上传成功: key={}", key);
            } catch (Exception e) {
                log.warn("关键帧上传失败: index={}", i, e);
            }
        }
        return urls;
    }

    private void deleteFrameObjects(Long videoId, int count) {
        for (int i = 0; i < count; i++) {
            String key = "analysis/" + videoId + "/frames/frame_" + String.format("%03d", i + 1) + ".png";
            minioService.deleteObject(key);
        }
    }

    // ──────────────────── AI API calls ────────────────────

    private String callAsr(Path audioPath) {
        return RetryUtil.retry(() -> {
            HttpResponse response = HttpRequest.post(aiConfig.getAsrUrl())
                    .header(Header.AUTHORIZATION, "Bearer " + aiConfig.getApiKey())
                    .form("file", audioPath.toFile())
                    .form("model", aiConfig.getAsrModel())
                    .form("response_format", "json")
                    .timeout(120000)
                    .execute();

            if (!response.isOk()) {
                throw new RuntimeException("ASR API调用失败: " + response.getStatus() + " " + response.body());
            }

            JSONObject json = JSONUtil.parseObj(response.body());
            String text = json.getStr("text", "");
            if (text.isBlank()) {
                log.warn("ASR返回空文本");
            }
            log.info("ASR完成: text_len={}", text.length());
            return text;
        });
    }

    private String callVision(List<String> imageUrls) {
        return RetryUtil.retry(() -> {
            JSONArray contentBlocks = new JSONArray();
            contentBlocks.add(new JSONObject()
                    .set("type", "text")
                    .set("text", "请用中文描述以下视频关键帧的画面内容，包括场景、人物、动作、物体、文字等。请按时间顺序简洁描述每一帧的画面。"));
            for (String url : imageUrls) {
                contentBlocks.add(new JSONObject()
                        .set("type", "image_url")
                        .set("image_url", new JSONObject().set("url", url)));
            }

            JSONArray messages = new JSONArray();
            messages.add(new JSONObject()
                    .set("role", "user")
                    .set("content", contentBlocks));

            JSONObject body = new JSONObject();
            body.set("model", aiConfig.getVisionModel());
            body.set("messages", messages);
            body.set("max_tokens", aiConfig.getVisionMaxTokens());
            body.set("temperature", aiConfig.getVisionTemperature());

            HttpResponse response = HttpRequest.post(aiConfig.getApiUrl())
                    .header(Header.AUTHORIZATION, "Bearer " + aiConfig.getApiKey())
                    .header(Header.CONTENT_TYPE, "application/json")
                    .body(body.toString())
                    .timeout(120000)
                    .execute();

            if (!response.isOk()) {
                throw new RuntimeException("Vision API调用失败: " + response.getStatus() + " " + response.body());
            }

            JSONObject respJson = JSONUtil.parseObj(response.body());
            JSONArray choices = respJson.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("Vision API返回空响应");
            }

            String content = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getStr("content");
            log.info("Vision分析完成: content_len={}", content != null ? content.length() : 0);
            return content;
        });
    }

    private AnalysisResult synthesize(Video video, String transcript, String visualDescription) {
        return RetryUtil.retry(() -> {
            StringBuilder prompt = new StringBuilder();
            prompt.append("你是一个专业的视频内容分析助手。请根据以下信息生成视频的AI摘要和标签：\n\n");
            prompt.append("视频标题：").append(video.getTitle() != null ? video.getTitle() : "无").append("\n");
            prompt.append("文件名：").append(video.getOriginalFilename() != null ? video.getOriginalFilename() : "无").append("\n\n");

            if (transcript != null && !transcript.isBlank()) {
                prompt.append("音频转写内容：\n").append(transcript).append("\n\n");
            } else {
                prompt.append("（音频转写不可用）\n\n");
            }

            if (visualDescription != null && !visualDescription.isBlank()) {
                prompt.append("关键帧画面描述：\n").append(visualDescription).append("\n\n");
            } else {
                prompt.append("（画面分析不可用）\n\n");
            }

            prompt.append("请以JSON格式返回，包含以下字段：\n");
            prompt.append("- summary: 一段200字以内的中文视频内容摘要\n");
            prompt.append("- tags: 一个包含3-8个中文标签的JSON字符串数组，例如 [\"科技\",\"教程\",\"AI\"]\n\n");
            prompt.append("只返回JSON，不要包含其他文字。");

            JSONArray messages = new JSONArray();
            messages.add(new JSONObject()
                    .set("role", "user")
                    .set("content", prompt.toString()));

            JSONObject body = new JSONObject();
            body.set("model", aiConfig.getModel());
            body.set("messages", messages);
            body.set("max_tokens", aiConfig.getMaxTokens());
            body.set("temperature", aiConfig.getTemperature());
            body.set("response_format", new JSONObject().set("type", "json_object"));

            HttpResponse response = HttpRequest.post(aiConfig.getApiUrl())
                    .header(Header.AUTHORIZATION, "Bearer " + aiConfig.getApiKey())
                    .header(Header.CONTENT_TYPE, "application/json")
                    .body(body.toString())
                    .timeout(120000)
                    .execute();

            if (!response.isOk()) {
                throw new RuntimeException("Synthesis API调用失败: " + response.getStatus() + " " + response.body());
            }

            JSONObject respJson = JSONUtil.parseObj(response.body());
            JSONArray choices = respJson.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("Synthesis API返回空响应");
            }

            String content = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getStr("content");
            log.info("摘要合成完成: videoId={}, response_len={}", video.getId(), content != null ? content.length() : 0);

            return parseAnalysisResult(video, content, transcript);
        });
    }

    private AnalysisResult parseAnalysisResult(Video video, String content, String transcript) {
        try {
            JSONObject json = JSONUtil.parseObj(content);
            String summary = json.getStr("summary", "");
            Object tagsObj = json.get("tags");

            String tags;
            if (tagsObj instanceof JSONArray) {
                tags = ((JSONArray) tagsObj).toString();
            } else if (tagsObj instanceof String) {
                tags = (String) tagsObj;
            } else {
                tags = "[\"视频\"]";
            }

            return new AnalysisResult(summary, tags, transcript);

        } catch (Exception e) {
            log.warn("解析合成结果JSON失败，使用降级方案: videoId={}", video.getId(), e);
            return AnalysisResult.fallback(video);
        }
    }

    // ──────────────────── Temp file cleanup ────────────────────

    private void cleanupTempDir(Path tempDir) {
        if (tempDir == null) return;
        try (Stream<Path> stream = Files.walk(tempDir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            log.warn("清理临时目录失败: {}", tempDir, e);
        }
    }
}
