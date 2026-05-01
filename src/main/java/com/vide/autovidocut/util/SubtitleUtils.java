package com.vide.autovidocut.util;

import com.vide.autovidocut.model.dto.Storyboard;

import java.util.List;

public final class SubtitleUtils {

    private SubtitleUtils() {}

    /**
     * Generate SRT file content from storyboards.
     */
    public static String generateSRT(List<Storyboard> storyboards) {
        StringBuilder sb = new StringBuilder();
        double currentTime = 0;
        int idx = 1;
        for (var storyboard : storyboards) {
            if (storyboard.subtitle() == null || storyboard.subtitle().isBlank()) {
                currentTime += storyboard.endTime() - storyboard.startTime();
                continue;
            }
            double dur = storyboard.endTime() - storyboard.startTime();
            sb.append(idx++).append("\n");
            sb.append(formatSrtTime(currentTime)).append(" --> ")
                    .append(formatSrtTime(currentTime + dur)).append("\n");
            sb.append(storyboard.subtitle()).append("\n\n");
            currentTime += dur;
        }
        return sb.toString();
    }

    static String formatSrtTime(double seconds) {
        int h = (int) (seconds / 3600);
        int m = (int) ((seconds % 3600) / 60);
        int s = (int) (seconds % 60);
        int ms = (int) ((seconds - (int) seconds) * 1000);
        return String.format("%02d:%02d:%02d,%03d", h, m, s, ms);
    }
}