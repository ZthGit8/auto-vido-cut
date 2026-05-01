package com.vide.autovidocut.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vide.autovidocut.config.AppPaths;
import com.vide.autovidocut.engine.FFmpegEngine;
import com.vide.autovidocut.mapper.MaterialMapper;
import com.vide.autovidocut.mapper.ProjectMapper;
import com.vide.autovidocut.model.dto.ClipSpec;
import com.vide.autovidocut.model.dto.ProgressMessage;
import com.vide.autovidocut.model.dto.ScriptResult;
import com.vide.autovidocut.model.entity.Material;
import com.vide.autovidocut.model.entity.Project;
import com.vide.autovidocut.model.enums.ProjectStatus;
import com.vide.autovidocut.util.SubtitleUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineOrchestrator {

    private final ProjectMapper projectMapper;
    private final MaterialMapper materialMapper;
    private final AIService aiService;
    private final VisionService visionService;
    private final FFmpegEngine ffmpeg;
    private final SimpMessagingTemplate ws;
    private final AppPaths appPaths;
    private final ObjectMapper objectMapper;

    @Async
    public void execute(String projectId) {
        var project = projectMapper.selectById(projectId);
        if (project == null) {
            log.error("项目不存在: {}", projectId);
            return;
        }
        var materials = materialMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Material>()
                        .eq(Material::getProjectId, projectId)
        );
        var tmp = Path.of(appPaths.getTmp(), "gen_" + projectId);

        try {
            // Phase 1: Material analysis (10%-25%)
            updateStatus(project, ProjectStatus.ANALYZING);
            pushProgress(projectId, "analyze", "素材分析中...", 10);
            analyzeMaterials(materials, tmp);

            // Phase 2: Script generation (25%-50%)
            updateStatus(project, ProjectStatus.SCRIPTING);
            pushProgress(projectId, "script", "生成脚本中...", 25);
            var script = generateScript(project, materials);

            // Phase 3: Video composition (50%-100%)
            updateStatus(project, ProjectStatus.COMPOSITING);
            pushProgress(projectId, "composite", "视频合成中...", 50);
            var output = compositeVideo(script, tmp, projectId);

            // Done
            project.setScriptJson(objectMapper.writeValueAsString(script));
            project.setOutputVideoPath(output.toString());
            project.setStatus(ProjectStatus.DONE);
            project.setUpdatedAt(LocalDateTime.now());
            projectMapper.updateById(project);
            pushProgress(projectId, "done", "视频生成完成!", 100);

        } catch (Exception e) {
            log.error("视频生成失败: projectId={}", projectId, e);
            project.setStatus(ProjectStatus.FAILED);
            project.setUpdatedAt(LocalDateTime.now());
            projectMapper.updateById(project);
            pushProgress(projectId, "error", "生成失败: " + e.getMessage(), 0);
        } finally {
            cleanup(tmp);
        }
    }

    private void analyzeMaterials(List<Material> materials, Path tmp) throws Exception {
        int total = materials.size();
        for (int i = 0; i < total; i++) {
            var mat = materials.get(i);
            String desc = visionService.analyzeMaterial(mat, tmp);
            mat.setAiDescription(desc);
            materialMapper.updateById(mat);
            pushProgress(mat.getProjectId(), "analyze",
                    "分析素材 " + (i + 1) + "/" + total,
                    10.0 + (i + 1) * 15.0 / total);
        }
    }

    private ScriptResult generateScript(Project project, List<Material> materials) throws JsonProcessingException {
        String materialsJson = objectMapper.writeValueAsString(
                materials.stream().map(m -> Map.of(
                        "id", m.getId(),
                        "fileName", m.getFileName(),
                        "type", m.getMediaType(),
                        "duration", m.getDuration() != null ? m.getDuration() : 0,
                        "description", m.getAiDescription() != null ? m.getAiDescription() : ""
                )).toList()
        );

        return aiService.generateScript(Map.of(
                "promotionGoal", project.getPromotionGoal(),
                "materials", materialsJson,
                "style", "viral",
                "wordCount", "200"
        ));
    }

    private Path compositeVideo(ScriptResult script, Path tmp, String projectId) throws Exception {
        double cumulativeTime = 0;
        List<ClipSpec> clipSpecs = new ArrayList<>();

        for (int i = 0; i < script.storyboards().size(); i++) {
            var sb = script.storyboards().get(i);
            double dur = sb.endTime() - sb.startTime();
            Path clipOutput = tmp.resolve("clip_" + i + ".mp4");
            ffmpeg.cut(sb.materialRef(), sb.startTime(), dur, clipOutput);
            clipSpecs.add(new ClipSpec(clipOutput.toString(), dur,
                    sb.transition() != null ? sb.transition() : "fade"));
            cumulativeTime += dur;
        }

        // Generate SRT subtitle file
        String srtContent = SubtitleUtils.generateSRT(script.storyboards());
        Path srtPath = tmp.resolve("subtitle.srt");
        Files.writeString(srtPath, srtContent);

        // Composite
        Path outputDir = Path.of(appPaths.getOutputs(), projectId);
        Files.createDirectories(outputDir);
        Path output = outputDir.resolve("final_" + System.currentTimeMillis() + ".mp4");

        return ffmpeg.composite(clipSpecs, srtPath, null, output);
    }

    private void pushProgress(String projectId, String phase, String msg, double pct) {
        ws.convertAndSend("/topic/progress/" + projectId,
                new ProgressMessage(phase, msg, pct, Instant.now().toString()));
    }

    private void updateStatus(Project p, ProjectStatus s) {
        p.setStatus(s);
        p.setUpdatedAt(LocalDateTime.now());
        projectMapper.updateById(p);
    }

    private void cleanup(Path tmp) {
        try {
            if (Files.exists(tmp)) {
                try (var s = Files.walk(tmp)) {
                    s.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
                }
            }
        } catch (Exception ignored) {}
    }
}