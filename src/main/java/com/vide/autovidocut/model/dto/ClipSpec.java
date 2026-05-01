package com.vide.autovidocut.model.dto;

public record ClipSpec(
        String path,
        double duration,
        String transition
) {}