package com.vide.autovidocut.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class FFmpegConfig {

    @Value("${app.ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @PostConstruct
    void verifyFFmpeg() {
        try {
            Process proc = new ProcessBuilder(ffmpegPath, "-version")
                    .redirectErrorStream(true)
                    .start();
            boolean ok = proc.waitFor(10, TimeUnit.SECONDS);
            String output = new String(proc.getInputStream().readAllBytes());
            if (ok && proc.exitValue() == 0) {
                String firstLine = output.lines().findFirst().orElse("");
                log.info("FFmpeg 检测成功: {}", firstLine);
            } else {
                log.warn("FFmpeg 未就绪，请确认 ffmpeg 已安装并在 PATH 中");
            }
        } catch (IOException | InterruptedException e) {
            log.warn("FFmpeg 检测失败: {}", e.getMessage());
        }
    }
}