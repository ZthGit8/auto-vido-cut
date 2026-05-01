# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

基于 Java 的视频智能剪辑后端服务。用户上传多段视频/图片素材 + 推广目标，AI 自动生成脚本和分镜，FFmpeg 自动合成成品视频（裁剪 → 拼接 → 转场 → 字幕 → BGM）。

详细技术方案见 `plan.md`。

## 构建与运行

```bash
# 构建
./mvnw clean package -DskipTests

# 运行
./mvnw spring-boot:run

# 运行测试
./mvnw test

# 运行单个测试
./mvnw test -Dtest=YourTestClass
```

服务端口：`http://localhost:8080`

## 技术栈

| 领域 | 技术 |
|------|------|
| 框架 | Spring Boot 3.3.2, JDK 21 |
| AI | Spring AI 1.1.4 + Alibaba DashScope (`spring-ai-alibaba-starter-dashscope`) |
| ORM | MyBatis-Plus 3.5.7 |
| 数据库 | MySQL + Druid 连接池 |
| 视频处理 | FFmpeg / ffprobe (ProcessBuilder 子进程调用) |
| 异步 | JDK 21 Virtual Threads |
| 构建 | Maven, `mvnw` wrapper, WAR 打包 |

## 包结构 (规划)

```
com.vide.autovidocut
├── AutoVidoCutApplication.java
├── controller/       # UploadController, ProjectController, GenerateController
├── service/          # AIService, VisionService, VideoEditService, PipelineOrchestrator
├── engine/           # FFmpegEngine, FFmpegCommand (Builder模式), ProcessManager
├── model/
│   ├── entity/       # Project, Material (MyBatis-Plus Entity)
│   ├── dto/          # ScriptResult, Storyboard, MaterialDesc, VideoMeta
│   └── enums/        # ProjectStatus, TransitionType
├── config/           # AsyncConfig, WebSocketConfig, AppPaths
└── util/             # SubtitleUtils, JsonUtils
```

## 核心架构决策

1. **视频处理 = 构建 FFmpeg 命令行字符串**。所有操作（裁剪、拼接、字幕、BGM）通过 `ProcessBuilder` 调用 `ffmpeg`/`ffprobe` 完成，JVM 不加载视频到内存。参考 `plan.md` 第 5 节完整 FFmpeg 命令。

2. **Spring AI 是 AI 调用的统一入口**。使用 `ChatModel` 接口 + `BeanOutputConverter` 实现结构化 JSON 输出。Prompt 模板存放在 classpath 下（`plan.md` 第 3.2 节）。

3. **视频剪辑三级回退策略**：优先 `-c copy`（无损快速）→ TS intermediate（中速）→ concat filter 重编码（万能）。

4. **转场效果**：使用 FFmpeg `xfade` 滤镜，支持 100+ 内建转场效果（fade, dissolve, slideright 等），是区别于简单拼接的核心功能。

5. **第一版范围**：不做 ASR 语音识别、TTS 配音、TransNetV2 场景检测、剪映草稿导出。LLM 直接生成字幕文本，仅 BGM 背景音乐。

6. **字幕**：Java 端生成 SRT 文件，FFmpeg `subtitles` 滤镜烧录到视频。

## FFmpeg 前置要求

启动前确保 `ffmpeg` 和 `ffprobe` 在系统 PATH 中：
```bash
ffmpeg -version
ffprobe -version

# Windows 安装: winget install Gyan.FFmpeg
# Linux: sudo apt install ffmpeg
```

## 实施阶段

按 `plan.md` 第 9 节路线图，当前处于 **Phase 0（环境搭建）**：项目已初始化，依赖已配置，下一步是实现素材上传和 ffprobe 元数据提取。