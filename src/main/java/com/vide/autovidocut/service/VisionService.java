package com.vide.autovidocut.service;

import com.vide.autovidocut.engine.FFmpegEngine;
import com.vide.autovidocut.mapper.MaterialMapper;
import com.vide.autovidocut.model.dto.FrameDesc;
import com.vide.autovidocut.model.dto.MaterialDesc;
import com.vide.autovidocut.model.entity.Material;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisionService {

    private final FFmpegEngine ffmpegEngine;
    private final MaterialMapper materialMapper;
    private final ChatClient.Builder chatClientBuilder;

    /**
     * Analyze material content — extracts frames for video, directly analyzes images.
     */
    public String analyzeMaterial(Material material, Path tmpDir) throws IOException, InterruptedException {
        if ("IMAGE".equals(material.getMediaType())) {
            byte[] imageBytes = Files.readAllBytes(Path.of(material.getFilePath()));
            return analyzeImage(imageBytes, "请描述这个画面的内容、色调、情绪、人物等，用于视频剪辑素材分析。");
        } else {
            // Video: capture frames every 3 seconds
            Path framesDir = tmpDir.resolve("frames_" + material.getId());
            ffmpegEngine.captureFrames(material.getFilePath(), 3, framesDir);

            var frameFiles = Files.list(framesDir).sorted().limit(3).toList();
            if (frameFiles.isEmpty()) return "";

            StringBuilder desc = new StringBuilder();
            for (int i = 0; i < frameFiles.size(); i++) {
                byte[] imgBytes = Files.readAllBytes(frameFiles.get(i));
                String frameDesc = analyzeImage(imgBytes,
                        "这是视频第" + (i * 3) + "秒的画面，请简要描述。");
                desc.append(frameDesc);
                if (i < frameFiles.size() - 1) desc.append(" | ");
            }
            return desc.toString();
        }
    }

    private String analyzeImage(byte[] imageBytes, String prompt) {
        var chatClient = chatClientBuilder.build();
        return chatClient.prompt()
                .user(user -> user.text(prompt))
                .call()
                .content();
    }
}