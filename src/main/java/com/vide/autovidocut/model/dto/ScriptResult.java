package com.vide.autovidocut.model.dto;

import java.util.List;

public record ScriptResult(
        String copywriting,
        List<Storyboard> storyboards
) {}