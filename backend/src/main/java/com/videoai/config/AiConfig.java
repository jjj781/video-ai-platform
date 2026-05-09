package com.videoai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai.siliconflow")
public class AiConfig {

    private String apiUrl;
    private String apiKey;
    private String model;
    private int maxTokens = 4096;
    private double temperature = 0.7;

    // ASR 语音识别
    private String asrUrl;
    private String asrModel;

    // 多模态视觉模型
    private String visionModel;
    private int visionMaxTokens = 2048;
    private double visionTemperature = 0.3;

    // FFmpeg
    private String ffmpegPath;
    private String ffprobePath;
    private int keyframeCount = 5;
    private int ffmpegTimeoutSeconds = 120;
}
