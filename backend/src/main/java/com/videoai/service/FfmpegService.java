package com.videoai.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.videoai.config.AiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class FfmpegService {

    private final AiConfig aiConfig;

    public record VideoMetadata(long durationSeconds, int width, int height, String codecName) {}

    /**
     * 从视频中提取N个均匀分布的关键帧
     */
    public List<Path> extractKeyframes(Path videoPath, Path outputDir, int count) {
        VideoMetadata meta = probeVideo(videoPath);
        if (meta.durationSeconds() <= 0) {
            log.warn("无法获取视频时长，使用默认采样: videoPath={}", videoPath);
            return extractKeyframesByTime(videoPath, outputDir, count, 5);
        }
        return extractKeyframesByTime(videoPath, outputDir, count, meta.durationSeconds());
    }

    private List<Path> extractKeyframesByTime(Path videoPath, Path outputDir, int count, long durationSeconds) {
        String outputPattern = outputDir.resolve("frame_%03d.png").toString();
        double interval = durationSeconds / (double) (count + 1);

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException("创建输出目录失败: " + outputDir, e);
        }

        List<String> cmd = List.of(
                resolveFfmpegPath(),
                "-i", videoPath.toString(),
                "-vf", "fps=1/" + String.format("%.1f", interval),
                "-frames:v", String.valueOf(count),
                "-q:v", "2",
                "-y",
                outputPattern
        );

        log.info("提取关键帧: count={}, interval={}s, cmd={}", count, String.format("%.1f", interval), String.join(" ", cmd));
        executeCommand(cmd, aiConfig.getFfmpegTimeoutSeconds());

        List<Path> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Path frame = outputDir.resolve(String.format("frame_%03d.png", i + 1));
            if (Files.exists(frame)) {
                frames.add(frame);
            }
        }
        log.info("成功提取 {} 个关键帧", frames.size());
        return frames;
    }

    /**
     * 从视频中提取音轨为MP3
     */
    public Path extractAudio(Path videoPath, Path outputDir) {
        Path audioPath = outputDir.resolve("audio.mp3");
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException("创建输出目录失败: " + outputDir, e);
        }

        List<String> cmd = List.of(
                resolveFfmpegPath(),
                "-i", videoPath.toString(),
                "-vn",
                "-acodec", "libmp3lame",
                "-q:a", "2",
                "-y",
                audioPath.toString()
        );

        log.info("提取音轨: cmd={}", String.join(" ", cmd));
        executeCommand(cmd, aiConfig.getFfmpegTimeoutSeconds());

        if (Files.exists(audioPath) && audioPath.toFile().length() > 0) {
            log.info("音轨提取成功: size={}bytes", audioPath.toFile().length());
            return audioPath;
        }
        return null;
    }

    /**
     * 使用ffprobe获取视频元数据
     */
    public VideoMetadata probeVideo(Path videoPath) {
        List<String> cmd = List.of(
                resolveFfprobePath(),
                "-v", "quiet",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                videoPath.toString()
        );

        String output = executeCommand(cmd, 30);
        try {
            JSONObject json = JSONUtil.parseObj(output);

            long duration = 0;
            String formatDuration = json.getJSONObject("format").getStr("duration");
            if (formatDuration != null) {
                duration = (long) Double.parseDouble(formatDuration);
            }

            int width = 0;
            int height = 0;
            String codecName = null;
            var streams = json.getJSONArray("streams");
            if (streams != null) {
                for (int i = 0; i < streams.size(); i++) {
                    JSONObject stream = streams.getJSONObject(i);
                    if ("video".equals(stream.getStr("codec_type"))) {
                        width = stream.getInt("width", 0);
                        height = stream.getInt("height", 0);
                        codecName = stream.getStr("codec_name");
                        break;
                    }
                }
            }

            return new VideoMetadata(duration, width, height, codecName);

        } catch (Exception e) {
            log.warn("解析视频元数据失败，使用默认值: videoPath={}", videoPath, e);
            return new VideoMetadata(0, 0, 0, null);
        }
    }

    private String executeCommand(List<String> cmd, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            byte[] output = process.getInputStream().readAllBytes();

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("FFmpeg命令超时(" + timeoutSeconds + "s): " + String.join(" ", cmd));
            }

            String outputStr = new String(output);
            if (process.exitValue() != 0) {
                throw new RuntimeException("FFmpeg命令失败(exit=" + process.exitValue() + "): "
                        + String.join(" ", cmd) + "\n" + outputStr);
            }

            return outputStr;

        } catch (IOException e) {
            throw new RuntimeException("FFmpeg命令执行异常，请检查ffmpeg是否已安装: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("FFmpeg命令被中断", e);
        }
    }

    private String resolveFfmpegPath() {
        String path = aiConfig.getFfmpegPath();
        return (path != null && !path.isBlank()) ? path : "ffmpeg";
    }

    private String resolveFfprobePath() {
        String path = aiConfig.getFfprobePath();
        return (path != null && !path.isBlank()) ? path : "ffprobe";
    }

    /**
     * 转码视频为浏览器可播放的MP4格式 (H.264 + AAC)
     */
    public Path transcodeToMp4(Path sourcePath, Path outputDir) {
        Path outputPath = outputDir.resolve("transcoded.mp4");
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException("创建输出目录失败: " + outputDir, e);
        }

        List<String> cmd = List.of(
                resolveFfmpegPath(),
                "-i", sourcePath.toString(),
                "-c:v", "libx264",
                "-preset", "fast",
                "-crf", "23",
                "-c:a", "aac",
                "-b:a", "128k",
                "-movflags", "+faststart",
                "-y",
                outputPath.toString()
        );

        log.info("视频转码: source={}, cmd={}", sourcePath, String.join(" ", cmd));
        executeCommand(cmd, aiConfig.getFfmpegTimeoutSeconds());

        if (Files.exists(outputPath) && outputPath.toFile().length() > 0) {
            log.info("视频转码完成: output={}, size={}bytes", outputPath, outputPath.toFile().length());
            return outputPath;
        }
        throw new RuntimeException("转码输出文件为空");
    }
}
