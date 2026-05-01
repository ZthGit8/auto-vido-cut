package com.vide.autovidocut.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vide.autovidocut.config.AppPaths;
import com.vide.autovidocut.engine.FFmpegEngine;
import com.vide.autovidocut.mapper.MaterialMapper;
import com.vide.autovidocut.mapper.ProjectMapper;
import com.vide.autovidocut.model.dto.UploadResult;
import com.vide.autovidocut.model.dto.VideoMeta;
import com.vide.autovidocut.model.entity.Material;
import com.vide.autovidocut.model.entity.Project;
import com.vide.autovidocut.model.enums.ProjectStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    private final ProjectMapper projectMapper;
    private final MaterialMapper materialMapper;
    private final FFmpegEngine ffmpegEngine;
    private final AppPaths appPaths;

    public Project createProject(String name, String promotionGoal) {
        var project = new Project();
        project.setName(name);
        project.setPromotionGoal(promotionGoal);
        project.setStatus(ProjectStatus.CREATED);
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        projectMapper.insert(project);
        log.info("项目创建成功: id={}, name={}", project.getId(), name);
        return project;
    }

    public UploadResult uploadMaterial(String projectId, byte[] fileBytes,
                                       String originalFileName, String contentType) throws IOException, InterruptedException {

        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }

        String ext = getExtension(originalFileName);
        Path destPath = Path.of(appPaths.getUploads(),
                projectId + "_" + System.currentTimeMillis() + ext);

        Files.createDirectories(destPath.getParent());
        Files.write(destPath, fileBytes);

        Material material = new Material();
        material.setProjectId(projectId);
        material.setFileName(originalFileName);
        material.setFilePath(destPath.toString());
        material.setFileSize((long) fileBytes.length);
        material.setCreatedAt(LocalDateTime.now());

        boolean isVideo = contentType != null && contentType.startsWith("video/");
        material.setMediaType(isVideo ? "VIDEO" : "IMAGE");

        if (isVideo) {
            try {
                VideoMeta meta = ffmpegEngine.probe(destPath.toString());
                material.setDuration(meta.duration());
                material.setWidth(meta.width());
                material.setHeight(meta.height());
                material.setCodec(meta.videoCodec());
            } catch (Exception e) {
                log.warn("提取元数据失败: {}，继续保存", e.getMessage());
            }
        }

        materialMapper.insert(material);
        log.info("素材上传成功: id={}, fileName={}", material.getId(), originalFileName);

        return new UploadResult(
                material.getId(),
                originalFileName,
                material.getDuration() != null ? material.getDuration() : 0,
                material.getWidth() != null ? material.getWidth() : 0,
                material.getHeight() != null ? material.getHeight() : 0,
                material.getCodec()
        );
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot) : "";
    }
}