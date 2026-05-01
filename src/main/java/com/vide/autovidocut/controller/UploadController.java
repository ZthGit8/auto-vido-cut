package com.vide.autovidocut.controller;

import com.vide.autovidocut.model.dto.CreateProjectRequest;
import com.vide.autovidocut.model.dto.UploadResult;
import com.vide.autovidocut.model.entity.Project;
import com.vide.autovidocut.service.UploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;

    @PostMapping("/projects")
    public ResponseEntity<Project> createProject(@RequestBody CreateProjectRequest request) {
        Project project = uploadService.createProject(request.name(), request.promotionGoal());
        return ResponseEntity.ok(project);
    }

    @PostMapping("/projects/{projectId}/materials")
    public ResponseEntity<UploadResult> uploadMaterial(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file) throws IOException, InterruptedException {

        UploadResult result = uploadService.uploadMaterial(
                projectId, file.getBytes(), file.getOriginalFilename(), file.getContentType());
        return ResponseEntity.ok(result);
    }
}