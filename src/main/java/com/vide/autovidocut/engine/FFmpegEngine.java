package com.vide.autovidocut.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vide.autovidocut.model.dto.ClipSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
@Component
public class FFmpegEngine {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${app.ffmpeg.timeout-minutes:20}")
    private long timeoutMinutes;

    // ==================== 元数据 ====================

    public com.vide.autovidocut.model.dto.VideoMeta probe(String videoPath) throws IOException, InterruptedException {
        var cmd = List.of("ffprobe", "-v", "quiet",
                "-print_format", "json",
                "-show_format", "-show_streams", videoPath);

        log.debug("执行 ffprobe: {}", String.join(" ", cmd));
        var proc = new ProcessBuilder(cmd).start();
        String json = new String(proc.getInputStream().readAllBytes());
        proc.waitFor(30, TimeUnit.SECONDS);

        var root = mapper.readTree(json);
        var streams = root.get("streams");
        var format = root.get("format");

        double duration = format.get("duration").asDouble();
        var videoStream = findStream(streams, "video");
        var audioStream = findStream(streams, "audio");

        return new com.vide.autovidocut.model.dto.VideoMeta(
                duration,
                videoStream != null ? videoStream.get("width").asInt() : 0,
                videoStream != null ? videoStream.get("height").asInt() : 0,
                videoStream != null ? videoStream.get("codec_name").asText() : "unknown",
                audioStream != null ? audioStream.get("codec_name").asText() : "none",
                parseFrameRate(videoStream),
                format.get("size").asLong()
        );
    }

    // ==================== 帧截取 ====================

    public Path captureFrames(String videoPath, int intervalSec, Path outputDir) throws IOException, InterruptedException {
        Files.createDirectories(outputDir);
        String fps = "1/" + intervalSec;

        var cmd = List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
                "-i", videoPath,
                "-vf", "fps=" + fps,
                "-q:v", "2",
                "-y", outputDir.resolve("frame_%04d.jpg").toString());

        var proc = new ProcessBuilder(cmd).start();
        String stderr = new String(proc.getErrorStream().readAllBytes());
        int exitCode = proc.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("截帧失败: " + stderr);
        }
        return outputDir;
    }

    // ==================== 视频裁剪 ====================

    /**
     * Cut a video segment. Tries stream copy first, falls back to re-encode.
     */
    public Path cut(String input, double start, double duration, Path output) throws Exception {
        // 策略1: stream copy (fast)
        var copyCmd = List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
                "-ss", String.valueOf(start),
                "-t", String.valueOf(duration),
                "-i", input,
                "-c", "copy", "-y", output.toString());

        var result = execute(copyCmd, Duration.ofMinutes(2), null);
        if (result.ok()) return output;

        // 策略2: re-encode (precise)
        log.warn("copy 模式裁剪失败，使用重编码回退: {}", result.stderr());
        var reencodeCmd = List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
                "-i", input,
                "-ss", String.valueOf(start),
                "-t", String.valueOf(duration),
                "-c:v", "libx264", "-preset", "superfast", "-crf", "18",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "128k", "-ar", "48000",
                "-movflags", "+faststart", "-y", output.toString());

        result = execute(reencodeCmd, Duration.ofMinutes(10), null);
        if (!result.ok()) throw new RuntimeException("裁剪失败: " + result.stderr());
        return output;
    }

    // ==================== 视频合成 ====================

    /**
     * Composite clips with xfade transitions, optional subtitles and BGM.
     */
    public Path composite(List<ClipSpec> clips, Path subtitlePath, Path bgmPath, Path output) throws Exception {
        String filter = buildCompositeFilter(clips, subtitlePath, bgmPath);

        var cmd = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error"));
        for (var clip : clips) {
            cmd.addAll(List.of("-i", clip.path()));
        }
        if (bgmPath != null) {
            cmd.addAll(List.of("-stream_loop", "-1", "-i", bgmPath.toString()));
        }
        cmd.addAll(List.of("-filter_complex", filter));
        cmd.addAll(List.of("-map", "[v]", "-map", "[a]"));
        cmd.addAll(List.of("-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p"));
        cmd.addAll(List.of("-c:a", "aac", "-b:a", "192k", "-ar", "48000"));
        cmd.addAll(List.of("-movflags", "+faststart", "-y", output.toString()));

        var result = execute(cmd, Duration.ofMinutes(timeoutMinutes), null);
        if (!result.ok()) throw new RuntimeException("合成失败: " + result.stderr());
        return output;
    }

    private String buildCompositeFilter(List<ClipSpec> clips, Path subtitlePath, Path bgmPath) {
        int n = clips.size();
        var sb = new StringBuilder();

        // Step 1: label video and audio streams
        for (int i = 0; i < n; i++) {
            sb.append(String.format("[%d:v]settb=AVTB,fps=30,setpts=PTS-STARTPTS[v%d];", i, i));
            sb.append(String.format("[%d:a]asetpts=PTS-STARTPTS[a%d];", i, i));
        }

        // Step 2: chain xfade (video) + acrossfade (audio)
        String prevV = "v0";
        String prevA = "a0";
        double cumulativeTime = 0;
        for (int i = 1; i < n; i++) {
            double prevDur = clips.get(i - 1).duration();
            cumulativeTime += prevDur;
            double offset = cumulativeTime - 0.5;

            String transition = clips.get(i).transition();
            if (transition == null || "none".equals(transition)) {
                transition = "fade";
            }
            String nextV = (i == n - 1) ? "v" : "v0" + i;
            String nextA = (i == n - 1) ? "a_premix" : "a0" + i;

            sb.append(String.format("[%s][v%d]xfade=transition=%s:duration=0.5:offset=%.3f[%s];",
                    prevV, i, transition, offset, nextV));
            sb.append(String.format("[%s][a%d]acrossfade=d=0.5[%s];", prevA, i, nextA));
            prevV = nextV;
            prevA = nextA;
        }

        // Step 3: audio — BGM mix or passthrough
        if (bgmPath != null) {
            int bgmIndex = n;
            sb.append(String.format("[%d:a]volume=0.25[bgm];[%s][bgm]amix=inputs=2:duration=first:dropout_transition=2[a];",
                    bgmIndex, prevA));
        } else {
            sb.append(String.format("[%s]anull[a];", prevA));
        }

        // Step 4: subtitle burn-in
        if (subtitlePath != null) {
            String subPath = subtitlePath.toAbsolutePath().toString()
                    .replace("\\", "/").replace(":", "\\\\:");
            sb.append(String.format("[v]subtitles='%s':force_style='FontSize=24,"
                    + "PrimaryColour=&H00FFFFFF,Alignment=2,MarginV=60'[v];", subPath));
        }

        return sb.toString();
    }

    // ==================== 通用子进程执行 ====================

    public record FFmpegResult(int exitCode, String stderr) {
        public boolean ok() { return exitCode == 0; }
    }

    FFmpegResult execute(List<String> cmd, Duration timeout, Consumer<Double> onProgress) throws Exception {
        var pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        Process proc = pb.start();
        AtomicBoolean terminated = new AtomicBoolean(false);

        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> {
            StringBuilder sb = new StringBuilder();
            try (var r = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append("\n");
                    if (onProgress != null && line.startsWith("out_time_ms=")) {
                        try {
                            long ms = Long.parseLong(line.split("=")[1]);
                            onProgress.accept(ms / 1_000_000.0);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (IOException e) { /* process killed */ }
            return sb.toString();
        });

        boolean finished = proc.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        if (!finished && !terminated.get()) {
            proc.destroyForcibly();
            throw new TimeoutException("FFmpeg 执行超时 (" + timeout.toMinutes() + "分钟)");
        }

        String stderr = stderrFuture.get(10, TimeUnit.SECONDS);
        return new FFmpegResult(proc.exitValue(), stderr);
    }

    // ==================== helpers ====================

    private JsonNode findStream(JsonNode streams, String codecType) {
        if (streams == null) return null;
        for (var s : streams) {
            if (codecType.equals(s.get("codec_type").asText())) return s;
        }
        return null;
    }

    private double parseFrameRate(JsonNode stream) {
        if (stream == null) return 0;
        var fr = stream.get("r_frame_rate");
        if (fr == null) return 0;
        String[] parts = fr.asText().split("/");
        return Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
    }
}