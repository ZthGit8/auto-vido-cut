package com.vide.autovidocut.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for FFmpeg command-line arguments.
 */
public class FFmpegCommand {

    private final List<String> args = new ArrayList<>();

    public static FFmpegCommand builder() {
        return new FFmpegCommand();
    }

    public FFmpegCommand input(String path) {
        args.addAll(List.of("-i", path));
        return this;
    }

    public FFmpegCommand seek(double seconds) {
        args.addAll(List.of("-ss", String.valueOf(seconds)));
        return this;
    }

    public FFmpegCommand duration(double seconds) {
        args.addAll(List.of("-t", String.valueOf(seconds)));
        return this;
    }

    public FFmpegCommand videoCodec(String codec) {
        args.addAll(List.of("-c:v", codec));
        return this;
    }

    public FFmpegCommand audioCodec(String codec) {
        args.addAll(List.of("-c:a", codec));
        return this;
    }

    public FFmpegCommand copyCodec() {
        args.addAll(List.of("-c", "copy"));
        return this;
    }

    public FFmpegCommand filterComplex(String filter) {
        args.addAll(List.of("-filter_complex", filter));
        return this;
    }

    public FFmpegCommand videoFilter(String filter) {
        args.addAll(List.of("-vf", filter));
        return this;
    }

    public FFmpegCommand map(String mapping) {
        args.addAll(List.of("-map", mapping));
        return this;
    }

    public FFmpegCommand overwrite() {
        args.add("-y");
        return this;
    }

    public FFmpegCommand output(String path) {
        args.add(path);
        return this;
    }

    public FFmpegCommand loglevel(String level) {
        args.addAll(List.of("-hide_banner", "-loglevel", level));
        return this;
    }

    public FFmpegCommand preset(String preset) {
        args.addAll(List.of("-preset", preset));
        return this;
    }

    public FFmpegCommand pixFmt(String fmt) {
        args.addAll(List.of("-pix_fmt", fmt));
        return this;
    }

    public FFmpegCommand crf(int crf) {
        args.addAll(List.of("-crf", String.valueOf(crf)));
        return this;
    }

    public FFmpegCommand audioBitrate(String bitrate) {
        args.addAll(List.of("-b:a", bitrate));
        return this;
    }

    public FFmpegCommand audioSampleRate(int rate) {
        args.addAll(List.of("-ar", String.valueOf(rate)));
        return this;
    }

    public List<String> build() {
        return List.copyOf(args);
    }
}