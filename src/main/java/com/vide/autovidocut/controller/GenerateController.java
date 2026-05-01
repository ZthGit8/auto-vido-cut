package com.vide.autovidocut.controller;

import com.vide.autovidocut.service.PipelineOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GenerateController {

    private final PipelineOrchestrator orchestrator;

    @PostMapping("/generate/{projectId}")
    public ResponseEntity<Map<String, String>> generate(@PathVariable String projectId) {
        orchestrator.execute(projectId);
        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "projectId", projectId,
                "message", "视频生成已启动，可通过 WebSocket 接收进度"
        ));
    }
}