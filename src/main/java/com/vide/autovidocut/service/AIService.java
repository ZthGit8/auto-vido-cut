package com.vide.autovidocut.service;

import com.vide.autovidocut.model.dto.ScriptResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@Service
public class AIService {

    private final ChatClient chatClient;

    public AIService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Generate script + storyboard with structured output.
     */
    public ScriptResult generateScript(Map<String, Object> params) {
        var converter = new BeanOutputConverter<>(ScriptResult.class);

        String prompt = """
                你是一个专业的短视频脚本策划师。

                ## 推广目标
                {promotionGoal}

                ## 可用素材
                {materials}

                ## 要求
                1. 生成一段 {style} 风格的解说文案，字数约 {wordCount} 字
                2. 为每句文案分配对应素材和时间范围
                3. 指定相邻片段间的转场效果

                {format}
                """;

        var template = new PromptTemplate(prompt);
        var finalParams = new java.util.HashMap<>(params);
        finalParams.put("format", converter.getFormat());

        var message = template.createMessage(finalParams);
        String response = chatClient.prompt()
                .user(message.getText())
                .call()
                .content();

        log.debug("AI 响应: {}", response);
        return converter.convert(response);
    }
}