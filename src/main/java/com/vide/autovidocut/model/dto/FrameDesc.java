package com.vide.autovidocut.model.dto;

public record FrameDesc(
        double timestamp,
        String visualContent,
        String mood,
        String colorTone
) {}