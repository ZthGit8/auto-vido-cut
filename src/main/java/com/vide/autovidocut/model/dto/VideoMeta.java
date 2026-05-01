package com.vide.autovidocut.model.dto;

public record VideoMeta(
        double duration,
        int width,
        int height,
        String videoCodec,
        String audioCodec,
        double frameRate,
        long fileSize
) {}