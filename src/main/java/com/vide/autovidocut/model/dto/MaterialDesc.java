package com.vide.autovidocut.model.dto;

import java.util.List;

public record MaterialDesc(
        String materialId,
        String type,
        String overallDesc,
        List<FrameDesc> keyFrames,
        List<String> keywords,
        String suggestedUsage
) {}