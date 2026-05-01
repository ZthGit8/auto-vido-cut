package com.vide.autovidocut.model.dto;

public record ProgressMessage(
        String phase,
        String message,
        double percent,
        String timestamp
) {}