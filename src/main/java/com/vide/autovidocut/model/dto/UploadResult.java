package com.vide.autovidocut.model.dto;

public record UploadResult(
        String materialId,
        String fileName,
        double duration,
        int width,
        int height,
        String codec
) {}