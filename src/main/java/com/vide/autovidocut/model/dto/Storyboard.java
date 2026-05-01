package com.vide.autovidocut.model.dto;

public record Storyboard(
        int index,
        String narration,
        String materialRef,
        double startTime,
        double endTime,
        String transition,
        String subtitle
) {}