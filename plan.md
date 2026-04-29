# Java 版视频智能剪辑 —— 技术方案

> 参考项目：`superAIAutoCutVideo`（Python/FastAPI/FFmpeg）
> 目标技术栈：Spring AI + JDK 21 + Maven + FFmpeg

---

## 目录

1. [项目概述](#1-项目概述)
2. [参考项目技术栈分析](#2-参考项目技术栈分析)
3. [功能拆解与技术映射](#3-功能拆解与技术映射)
4. [系统架构设计](#4-系统架构设计)
5. [FFmpeg 可行性深度分析（重点）](#5-ffmpeg-可行性深度分析重点)
6. [Maven 依赖清单](#6-maven-依赖清单)
7. [关键代码设计](#7-关键代码设计)
8. [全流程编排设计](#8-全流程编排设计)
9. [实施路线图](#9-实施路线图)

---

## 1. 项目概述

### 1.1 目标

构建一个 Java 后端的视频智能剪辑服务，核心功能：
- 用户上传多段视频/图片素材 + 输入推广目标
- AI 自动生成脚本和分镜描述
- AI 自动理解素材内容并建立文案库
- 自动合成视频（裁剪 → 拼接 → 转场 → 字幕 → BGM）

### 1.2 第一版功能范围

| 功能 | 输入 | 输出 |
|------|------|------|
| 素材上传 | 多段 mp4/mov/avi + 推广目标文本 | 素材元数据 + 存储路径 |
| AI生成脚本与分镜 | 素材描述 + 推广目标 | JSON（文案 + 分镜时间线 + 对应素材） |
| 素材理解与文案库 | 视频帧/图片 | 结构化画面描述 + 文案标签 |
| 视频自动合成 | 素材 + 分镜脚本 | 成品 MP4（含转场、字幕、BGM） |

### 1.3 不做/第二版再做

- ASR 语音识别字幕提取（第一版直接由 LLM 生成字幕文本）
- TTS 配音生成（第一版仅做 BGM 背景音乐）
- TransNetV2 场景边界自动检测
- 剪映草稿导出

---

## 2. 参考项目技术栈分析

### 2.1 现有 Python 项目结构

```
backend/
├── main.py                    # FastAPI + WebSocket 入口
├── modules/
│   ├── ai/                    # 多 Provider AI 抽象层
│   │   ├── base.py            # 抽象基类 (chat/stream/test)
│   │   └── providers/         # qwen/deepseek/doubao/... 实现
│   ├── video_processor.py     # FFmpeg 全部操作封装
│   ├── audio_normalizer.py    # FFmpeg loudnorm 响度归一化
│   ├── subtitle_utils.py      # SRT 解析/格式化
│   └── ...
├── services/
│   ├── ai_service.py          # AI 调用统一入口
│   ├── video_generation_service.py  # 视频生成全流程
│   ├── generate_copywriting_service.py  # 文案生成
│   ├── vision_frame_analysis_service.py  # 视觉帧分析
│   └── jianying_draft_service.py  # 剪映草稿导出
└── routes/                    # HTTP + WS 路由
```

### 2.2 核心技术对应关系

| Python 技术 | 作用 | Java 对应 |
|-------------|------|-----------|
| FastAPI + Uvicorn | HTTP/WS 服务 | Spring Boot 3.x + WebSocket |
| asyncio | 异步编排 | JDK 21 Virtual Threads |
| httpx.AsyncClient | HTTP 客户端 | RestClient / WebClient |
| `ai/base.py` 多 Provider 抽象 | AI 调用 | Spring AI `ChatModel` 接口 |
| `ffmpeg` subprocess 调用 | 视频全部操作 | `ProcessBuilder` / `Runtime.exec()` |
| OpenCV + PIL | 帧截取/图像处理 | JavaCV (FFmpegFrameGrabber) 或 FFmpeg 直接截帧 |
| Moondream / 多模态 LLM | 图像理解 | Spring AI + GPT-4V / Qwen-VL |
| JSON 结构化输出 | 脚本+分镜 | Spring AI `BeanOutputConverter` |

### 2.3 关键洞察

> **视频合成的本质是构建正确的 FFmpeg 命令行参数字符串，而非编写视频处理代码。**

Python 项目的 [video_processor.py](file:///d:/other/py/superAIAutoCutVideo/backend/modules/video_processor.py) 中所有视频操作（裁剪、拼接、配音替换、响度归一化）都是通过 `asyncio.create_subprocess_exec` 调用 `ffmpeg` / `ffprobe` 命令行完成的，Java 端需要做的仅仅是：

1. 用 `ProcessBuilder` 执行 FFmpeg 命令
2. 解析 `ffprobe` 的 JSON 输出来获取元数据
3. 管理子进程生命周期（启动、监听、超时、取消）

---

## 3. 功能拆解与技术映射

### 3.1 功能1：用户视频素材上传和推广目标

```
前端 → POST /api/upload (multipart/form-data) → Spring Boot
                                                ├── 秒传检测 (MD5)
                                                ├── 存储到本地磁盘 /uploads/
                                                ├── ffprobe 提取元数据
                                                └── 返回素材ID + 元数据
```

**技术点：**
- `MultipartFile` 接收，大文件配合前端分片上传
- `ffprobe -v quiet -print_format json -show_format -show_streams <path>` 提取元数据
- 推广目标：文本字段存入 Project `promotion_goal`

**数据库模型：**

```sql
project: id, name, promotion_goal, status, created_at, updated_at
material: id, project_id, file_path, file_name, duration, width, height, 
          codec, ai_description, created_at
```

### 3.2 功能2：AI 生成脚本与分镜

这是 **Spring AI 发挥最大价值的功能**。

```
                    ┌────────────────────────────┐
┌─────────┐         │  Prompt 模板引擎            │
│ 素材列表 │────────▶│  (16+ 种风格模板)           │──────▶ LLM ──────▶ 结构化JSON
│ 推广目标 │         │  Spring AI PromptTemplate  │        ChatModel    │  BeanOutput
│ 素材描述 │         └────────────────────────────┘        (Spring AI)  │  Converter
└─────────┘                                                             ▼
                                                              ScriptResult {
                                                                copywriting: String,
                                                                storyboards: [
                                                                  { narration, materialRef,
                                                                    startTime, endTime,
                                                                    transition, subtitle }
                                                                ]
                                                              }
```

**Prompt 模板（类似项目 `modules/prompts/common/` 下的 16 种风格）：**

```markdown
你是一个专业的短视频脚本策划师。

## 推广目标
{promotionGoal}

## 可用素材
{@materials}

## 要求
1. 生成一段 {style} 风格的解说文案，字数约 {wordCount} 字
2. 为每句文案分配对应素材和时间范围
3. 指定相邻片段间的转场效果

## 输出格式（严格JSON）
{
  "copywriting": "完整文案...",
  "storyboards": [
    {
      "index": 1,
      "narration": "本段的旁白文案",
      "materialRef": "素材文件名或ID",
      "startTime": 0.0,
      "endTime": 3.5,
      "transition": "fade",
      "subtitle": "显示的字幕文本"
    }
  ]
}
```

**指定 Spring AI 结构化输出：**

```java
@Autowired
private ChatModel chatModel;

public ScriptResult generateScript(List<MaterialInfo> materials, String goal) {
    var prompt = new Prompt(scriptTemplate,
            Map.of("goal", goal, "materials", toJson(materials)));
    
    // 方案A: BeanOutputConverter (推荐)
    var converter = new BeanOutputConverter<>(ScriptResult.class);
    var userMessage = new UserMessage(prompt.getContents() 
            + "\n" + converter.getFormat());
    var response = chatModel.call(new Prompt(userMessage)).getResult();
    return converter.convert(response.getOutput().getText());
    
    // 方案B: @Tool 方式
}
```

### 3.3 功能3：素材理解与文案库

**流程：**

```
视频素材 ──▶ ffmpeg 截帧（每3秒1帧）──▶ 多模态LLM分析 ──▶ 结构化文案描述
图片素材 ──▶ 直接送入多模态LLM ────────▶ 结构化文案描述
```

**FFmpeg 截帧命令：**

```bash
# 每3秒截取一帧，输出为 frame_001.jpg, frame_002.jpg ...
ffmpeg -i input.mp4 -vf "fps=1/3" -q:v 2 frames/frame_%03d.jpg
```

**多模态调用（Spring AI）：**

```java
public String analyzeFrame(byte[] imageBytes, String context) {
    var userMessage = new UserMessage(
        "请描述这个画面的内容、色调、人物、情绪。" + context,
        List.of(new Media(MimeTypeUtils.IMAGE_JPEG, imageBytes))
    );
    var response = chatModel.call(new Prompt(userMessage));
    return response.getResult().getOutput().getText();
}
```

**文案库数据结构：**

```java
public record MaterialLibrary(
    List<MaterialDesc> materials
) {}

public record MaterialDesc(
    String materialId,
    String type,          // "video" | "image"
    String overallDesc,   // 整体描述
    List<FrameDesc> keyFrames,  // 关键帧描述
    List<String> keywords,      // 关键词标签
    String suggestedUsage       // 建议用途（开头/高潮/结尾）
) {}

public record FrameDesc(
    double timestamp,
    String visualContent,
    String mood,
    String colorTone
) {}
```

### 3.4 功能4：视频自动合成

这是技术复杂度最高的部分，详见 [第 5 节 FFmpeg 深度分析](#5-ffmpeg-可行性深度分析重点)。

**整体流程：**

```
分镜脚本 ──▶ 阶段1: 裁剪片段 ──▶ 阶段2: 拼接+转场 ──▶ 阶段3: 字幕叠加 ──▶ 阶段4: BGM混音 ──▶ 输出
              ffmpeg -ss -t         ffmpeg xfade         ffmpeg subtitles    ffmpeg amix
              -c copy              filter_complex        /drawtext            volume
```

---

## 4. 系统架构设计

### 4.1 分层架构

```
┌─────────────────────────────────────────────────────────┐
│                    Controller Layer                      │
│  UploadController │ ProjectController │ GenerateController│
│  (REST)           │ (REST)            │ (REST + WS)      │
├─────────────────────────────────────────────────────────┤
│                    Service Layer                         │
│  ┌───────────────┬──────────────┬────────────────────┐  │
│  │ AIService     │ VisionService│ VideoEditService   │  │
│  │ (Spring AI)   │ (多模态识别) │ (FFmpeg封装)       │  │
│  └───────┬───────┴──────┬───────┴─────────┬──────────┘  │
│  ┌───────┴──────────────┴─────────────────┴──────────┐  │
│  │          PipelineOrchestrator                      │  │
│  │  (全流程编排 + 进度推送 + 任务取消)                  │  │
│  └────────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────┤
│                    Engine Layer                           │
│  ┌──────────────────────────┬────────────────────────┐  │
│  │ FFmpegEngine             │ ProcessManager         │  │
│  │ - probe() 元数据         │ - 子进程生命周期管理    │  │
│  │ - cut() 裁剪             │ - 超时控制             │  │
│  │ - concat() 拼接          │ - 取消信号              │  │
│  │ - composite() 合成       │ - 进度解析              │  │
│  │ - subtitle() 字幕叠加    │                        │  │
│  │ - mixAudio() 音频混合    │                        │  │
│  └──────────────────────────┴────────────────────────┘  │
├─────────────────────────────────────────────────────────┤
│                    Data Layer                             │
│  H2 嵌入式数据库 (开发) / PostgreSQL (生产)               │
│  + 本地文件系统 (uploads/, outputs/, tmp/)               │
└─────────────────────────────────────────────────────────┘
```

### 4.2 包结构

```
com.example.videocut
├── VideoCutApplication.java
├── controller/
│   ├── UploadController.java
│   ├── ProjectController.java
│   └── GenerateController.java
├── service/
│   ├── AIService.java
│   ├── VisionService.java
│   ├── VideoEditService.java
│   ├── PipelineOrchestrator.java
│   └── WebSocketService.java
├── engine/
│   ├── FFmpegEngine.java
│   ├── FFmpegCommand.java        # 命令构建器（Builder模式）
│   └── ProcessManager.java       # 子进程管理
├── model/
│   ├── entity/
│   │   ├── Project.java
│   │   └── Material.java
│   ├── dto/
│   │   ├── ScriptResult.java
│   │   ├── Storyboard.java
│   │   ├── MaterialDesc.java
│   │   └── VideoMeta.java
│   └── enums/
│       ├── ProjectStatus.java
│       └── TransitionType.java
├── config/
│   ├── AsyncConfig.java          # 虚拟线程配置
│   ├── WebSocketConfig.java
│   └── AppPaths.java             # 路径管理
└── util/
    ├── SubtitleUtils.java        # SRT 格式化
    └── JsonUtils.java
```

### 4.3 关键技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 视频处理 | FFmpeg 命令行（ProcessBuilder） | 与原项目一致，功能最完整 |
| AI 调用 | Spring AI ChatModel | 统一接口，轻松切换 Provider |
| 帧截取 | FFmpeg 命令行 | 比 JavaCV 更稳定，无需 native 库 |
| 异步模型 | JDK 21 Virtual Threads | 天然支持，无需 reactive 范式 |
| 数据库 | H2（嵌入式） | 第一版无需外部依赖 |
| WebSocket | Spring WebSocket + STOMP | 标准方案，进度实时推送 |

---

## 5. FFmpeg 可行性深度分析（重点）

### 5.1 核心结论

> **FFmpeg 命令行方案完全可行。** 原 Python 项目已验证：视频裁剪、拼接、配音替换、响度归一化全部通过 `ffmpeg` 子进程调用实现。Java 端仅需 `ProcessBuilder` 执行命令即可，无需任何 Java 视频处理库。

### 5.2 各操作 FFmpeg 命令详解

#### 5.2.1 元数据提取（ffprobe）

```bash
# 提取视频/音频流的完整元数据（JSON 格式）
ffprobe -v quiet -print_format json -show_format -show_streams input.mp4
```

**返回示例：**
```json
{
  "streams": [
    {
      "index": 0, "codec_name": "h264", "codec_type": "video",
      "width": 1920, "height": 1080, "r_frame_rate": "30/1",
      "pix_fmt": "yuv420p", "duration": "120.500000"
    },
    {
      "index": 1, "codec_name": "aac", "codec_type": "audio",
      "sample_rate": "48000", "channels": 2, "duration": "120.480000"
    }
  ],
  "format": {
    "format_name": "mov,mp4,m4a,3gp,3g2,mj2",
    "duration": "120.500000", "size": "52428800"
  }
}
```

**Java 实现关键点：**
```java
public VideoMeta probe(String videoPath) {
    var cmd = List.of("ffprobe", "-v", "quiet",
        "-print_format", "json",
        "-show_format", "-show_streams", videoPath);
    var proc = new ProcessBuilder(cmd).start();
    String json = new String(proc.getInputStream().readAllBytes());
    // Jackson 解析 JSON → VideoMeta POJO
}
```

#### 5.2.2 视频裁剪（cut）

**原项目核心逻辑**（[video_processor.py:L213-L330](file:///d:/other/py/superAIAutoCutVideo/backend/modules/video_processor.py#L213-L330)）：

```
策略1: -c copy（秒级速度，无损，关键帧对齐）
  失败 → 策略2: 重新编码（libx264/h264_nvenc，保证精确）
```

```bash
# 策略1: 流拷贝（最快，结果在最近关键帧上对齐）
ffmpeg -hide_banner -loglevel error \
  -ss 10.5 -t 5.0 -i input.mp4 \
  -c copy -y output_clip.mp4

# 策略2: 重编码（精确帧级裁剪，慢但保真）
ffmpeg -hide_banner -loglevel error \
  -i input.mp4 -ss 10.5 -t 5.0 \
  -c:v libx264 -preset superfast -crf 18 -pix_fmt yuv420p \
  -c:a aac -b:a 128k -ar 48000 \
  -movflags +faststart -y output_clip.mp4
```

**关键点：**
- `-ss` 放在 `-i` **前面**是快速 seek（跳过解码），但可能不准
- `-ss` 放在 `-i` **后面**是解码后 seek，精确但慢
- 原项目先用 copy 快速模式，失败再重编码回退
- NVENC 硬件加速：`h264_nvenc`（NVIDIA GPU），`h264_qsv`（Intel GPU）

#### 5.2.3 视频拼接（concat）

**原项目三级拼接策略**（[video_processor.py:L430-L620](file:///d:/other/py/superAIAutoCutVideo/backend/modules/video_processor.py#L430-L620)）：

```
条件判断:
  ├── 所有片段编码参数完全一致 → concat demuxer (文件列表, -c copy)
  ├── H.264 + MP4 + AAC → TS intermediate + concat (mpegts中间文件)
  └── 参数不一致 → concat filter (filter_complex, 强制重编码)
```

**方法1：concat demuxer（最快，"文件列表法"）**
```bash
# concat_list.txt 内容:
# file 'clip_1.mp4'
# file 'clip_2.mp4'
# file 'clip_3.mp4'

ffmpeg -f concat -safe 0 -i concat_list.txt -c copy -movflags +faststart -y output.mp4
```
**条件：** 所有片段编码器、分辨率、帧率、像素格式必须完全一致。

**方法2：TS intermediate（中速）**
```bash
# 步骤1: 每个片段转成 mpegts 容器
ffmpeg -i clip_1.mp4 -c copy -bsf:v h264_mp4toannexb -f mpegts -y clip_1.ts
ffmpeg -i clip_2.mp4 -c copy -bsf:v h264_mp4toannexb -f mpegts -y clip_2.ts
# 步骤2: concat TS 文件
ffmpeg -f concat -safe 0 -i ts_list.txt -c copy -bsf:a aac_adtstoasc -y output.mp4
```
**条件：** H.264/H.265 + AAC 编码。

**方法3：concat filter（慢但万能）**
```bash
ffmpeg -i clip_1.mp4 -i clip_2.mp4 -i clip_3.mp4 \
  -filter_complex "[0:v][0:a][1:v][1:a][2:v][2:a]concat=n=3:v=1:a=1[v][a]" \
  -map "[v]" -map "[a]" -c:v libx264 -preset superfast -crf 18 \
  -c:a aac -b:a 192k -y output.mp4
```
**条件：** 任何片段都能拼接，但必定重编码。

**Java 实现建议：** 第一版直接用 **concat filter**（最可靠），后续优化加自动策略选择。

#### 5.2.4 转场特效（xfade）—— 第一版核心亮点

FFmpeg 内置 **100+ 种转场效果**，通过 `xfade` 滤镜实现。这是区别于简单拼接的关键。

```bash
# 两段视频间添加 0.5秒 淡入淡出（fade）
ffmpeg -i clip1.mp4 -i clip2.mp4 \
  -filter_complex "
    [0:v]settb=AVTB,fps=30[v0];
    [1:v]settb=AVTB,fps=30[v1];
    [v0][v1]xfade=transition=fade:duration=0.5:offset=2.5
  " -c:v libx264 -preset superfast -crf 18 -y output.mp4
```

**多段拼接 + 全部转场（filter_complex 构建）：**

```bash
# 3段视频，每段间 0.5s fade 转场
ffmpeg \
  -i clip1.mp4 -i clip2.mp4 -i clip3.mp4 \
  -filter_complex "
    [0:v]settb=AVTB,fps=30,setpts=PTS-STARTPTS[v0];
    [1:v]settb=AVTB,fps=30,setpts=PTS-STARTPTS[v1];
    [2:v]settb=AVTB,fps=30,setpts=PTS-STARTPTS[v2];
    [0:a]asetpts=PTS-STARTPTS[a0];
    [1:a]asetpts=PTS-STARTPTS[a1];
    [2:a]asetpts=PTS-STARTPTS[a2];
    [v0][v1]xfade=transition=fade:duration=0.5:offset=2.5[v01];
    [v01][v2]xfade=transition=fade:duration=0.5:offset=5.0[v];
    [a0][a1]acrossfade=d=0.5[a01];
    [a01][a2]acrossfade=d=0.5[a]
  " \
  -map "[v]" -map "[a]" \
  -c:v libx264 -preset superfast -crf 18 \
  -c:a aac -b:a 192k -y output.mp4
```

**常用转场效果表：**

| FFmpeg transition 名称 | 视觉效果 | 适合场景 |
|------------------------|----------|---------|
| `fade` | 淡入淡出 | 通用，温柔过渡 |
| `fadeblack` | 先黑后亮 | 段落切换 |
| `fadewhite` | 先白后亮 | 闪光过渡 |
| `dissolve` | 溶解叠加 | 画面叠加过渡 |
| `slideright` | 右推 | 快节奏视频 |
| `slideleft` | 左推 | 快节奏视频 |
| `slideup` | 上推 | 创意过渡 |
| `slidedown` | 下推 | 创意过渡 |
| `wiperight` | 右擦除 | 产品展示 |
| `wipeleft` | 左擦除 | 产品展示 |
| `circleopen` | 圆形展开 | 创意转场 |
| `circleclose` | 圆形收缩 | 创意转场 |
| `zoompan` | 缩放平移 | 画面聚焦 |
| `pixelize` | 像素化过渡 | 科技感 |
| `hlslice` | 水平切片 | 动感过渡 |
| `rectcrop` | 矩形裁剪 | 创意转场 |

**完整列表获取：**
```bash
ffmpeg -h filter=xfade   # 查看 xfade 支持的全部 transition 参数
```

#### 5.2.5 字幕叠加

**方式1：SRT 字幕烧录（推荐，内置支持）**
```bash
ffmpeg -i video.mp4 \
  -vf "subtitles=subtitle.srt:force_style='FontSize=24,PrimaryColour=&H00FFFFFF,Alignment=2,MarginV=50'" \
  -c:a copy -y output.mp4
```

**方式2：drawtext 逐帧绘制（灵活但需逐条写出）**
```bash
ffmpeg -i video.mp4 \
  -vf "drawtext=text='这是字幕':fontsize=24:fontcolor=white:x=(w-text_w)/2:y=h-th-50:enable='between(t,1,3)'" \
  -c:a copy -y output.mp4
```

**方式3：ASS 高级字幕（含样式、特效、位置控制）**
```bash
ffmpeg -i video.mp4 -vf "ass=subtitle.ass" -c:a copy -y output.mp4
```

**第一版推荐：** SRT 格式。先在 Java 代码中生成 SRT 文件，再用 FFmpeg 烧录。

**SRT 生成示例：**
```java
public String generateSRT(List<Storyboard> storyboards, double cumulativeOffset) {
    StringBuilder sb = new StringBuilder();
    int idx = 1;
    double currentTime = 0;
    for (var sb : storyboards) {
        if (sb.subtitle() == null || sb.subtitle().isBlank()) continue;
        double dur = sb.endTime() - sb.startTime();
        sb.append(idx++).append("\n");
        sb.append(formatSrtTime(currentTime)).append(" --> ")
          .append(formatSrtTime(currentTime + dur)).append("\n");
        sb.append(sb.subtitle()).append("\n\n");
        currentTime += dur;
    }
    return sb.toString();
}

private String formatSrtTime(double seconds) {
    int h = (int)(seconds / 3600);
    int m = (int)((seconds % 3600) / 60);
    int s = (int)(seconds % 60);
    int ms = (int)((seconds - (int)seconds) * 1000);
    return String.format("%02d:%02d:%02d,%03d", h, m, s, ms);
}
```

#### 5.2.6 背景音乐混音（BGM）

```bash
# 叠加背景音乐：原视频音量不变，BGM 音量降到 30%
# 如果 BGM 比视频短则循环
ffmpeg -i video.mp4 -stream_loop -1 -i bgm.mp3 \
  -filter_complex "[1:a]volume=0.3[bgm];[0:a][bgm]amix=inputs=2:duration=first:dropout_transition=2" \
  -c:v copy -shortest -y output.mp4
```

**关键参数说明：**
- `-stream_loop -1`：BGM 循环播放
- `volume=0.3`：BGM 降到 30% 音量
- `amix=inputs=2:duration=first`：混合两路音频，以第一路（原视频）时长为准
- `-shortest`：以最短流为准截断
- `dropout_transition=2`：混音结束时的淡出过渡时间

#### 5.2.7 响度归一化（可选，来自原项目的 loudnorm）

```bash
# 两遍法实现 EBU R128 响度归一化
# 第一遍：分析
ffmpeg -i input.mp4 -af "loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json" -f null -
# 返回: { "input_i": "-23.5", "input_tp": "-3.2", "input_lra": "8.0", "target_offset": "7.5" }

# 第二遍：应用
ffmpeg -i input.mp4 -af "loudnorm=I=-16:TP=-1.5:LRA=11:measured_I=-23.5:measured_TP=-3.2:measured_LRA=8.0:measured_thresh=-33.5:offset=7.5:linear=true" output.mp4
```

### 5.3 Java 端 FFmpeg 调用的工程化设计

#### 5.3.1 ProcessBuilder 封装

```java
public class FFmpegEngine {

    private static final Path FFMPEG = Path.of("ffmpeg");  // 或绝对路径
    private static final Path FFPROBE = Path.of("ffprobe");
    private static final long DEFAULT_TIMEOUT_SEC = 600;  // 10分钟

    /**
     * 通用 FFmpeg 命令执行
     */
    public FFmpegResult execute(List<String> args, Duration timeout,
                                 Consumer<Double> onProgress,
                                 CancellationToken cancelToken) 
            throws InterruptedException, TimeoutException, CancellationException {
        
        List<String> cmd = new ArrayList<>();
        cmd.add(FFMPEG.toString());
        cmd.addAll(args);
        
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);  // stderr 用于进度
        
        Process proc = pb.start();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        
        // 注册取消回调
        if (cancelToken != null) {
            cancelToken.onCancel(() -> {
                cancelled.set(true);
                proc.destroyForcibly();
            });
        }
        
        // 异步读取 stderr 来解析进度
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> {
            try (var reader = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                    // 解析进度信息：out_time_ms=1234567
                    if (onProgress != null && line.startsWith("out_time_ms=")) {
                        long ms = Long.parseLong(line.split("=")[1]);
                        onProgress.accept(ms / 1_000_000.0);  // 秒
                    }
                }
                return sb.toString();
            }
        });
        
        // 等待进程结束（带超时）
        boolean finished = proc.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new TimeoutException("FFmpeg 执行超时");
        }
        
        String stderr = stderrFuture.get(5, TimeUnit.SECONDS);
        
        return new FFmpegResult(proc.exitValue(), stderr);
    }
    
    public record FFmpegResult(int exitCode, String stderr) {
        public boolean isSuccess() { return exitCode == 0; }
    }
}
```

#### 5.3.2 命令构建器（Builder 模式）

```java
public class FFmpegCommand {
    private final List<String> args = new ArrayList<>();
    
    public static FFmpegCommand builder() { return new FFmpegCommand(); }
    
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
    
    public FFmpegCommand progress() {
        args.addAll(List.of("-progress", "pipe:1"));
        return this;
    }
    
    public FFmpegCommand loglevel(String level) {
        args.addAll(List.of("-hide_banner", "-loglevel", level));
        return this;
    }
    
    public List<String> build() { return List.copyOf(args); }
}
```

#### 5.3.3 操作示例

```java
// 裁剪
var cutCmd = FFmpegCommand.builder()
    .loglevel("error")
    .seek(10.5)
    .duration(5.0)
    .input("input.mp4")
    .copyCodec()
    .overwrite()
    .output("output.mp4")
    .build();
ffmpegEngine.execute(cutCmd, Duration.ofMinutes(2), null, null);

// 拼接（含转场）
String filterComplex = buildXfadeFilter(clips, "fade", 0.5);
var concatCmd = FFmpegCommand.builder()
    .loglevel("error")
    .input("clip1.mp4").input("clip2.mp4")
    .filterComplex(filterComplex)
    .map("[v]").map("[a]")
    .videoCodec("libx264")
    .audioCodec("aac")
    .overwrite()
    .output("final.mp4")
    .build();
```

### 5.4 第一版适用的场景边界

| 场景 | 能否处理 | 备注 |
|------|---------|------|
| 素材分辨率一致（如都是 1080p） | ✅ 完美 | 最简单的场景 |
| 素材分辨率不一致 | ✅ 可处理 | filter_complex 中统一缩放到目标分辨率 |
| 素材帧率不一致 | ✅ 可处理 | `fps=30` 统一帧率 |
| 素材编码格式不一致 | ✅ 可处理 | 使用 concat filter 重编码 |
| 只有图片素材 | ✅ 可处理 | 图片转成固定时长视频片段再用 xfade |
| 图片+视频混合 | ✅ 可处理 | 同上，图片转视频帧 |
| 素材无音频 | ✅ 可处理 | 静音填充或用 BGM 覆盖 |
| 素材含多音轨 | ⚠️ 部分 | 只取第一音轨 |
| 超高分辨率素材（4K+） | ⚠️ 性能 | 建议统一缩放到 1080p |
| 100+ 素材片段 | ⚠️ 性能 | 分批拼接，中间文件复用 |

### 5.5 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| FFmpeg 未安装 | 服务无法启动 | 启动时检测 ffmpeg/ffprobe 是否在 PATH 中 |
| 大文件 OOM | 服务崩溃 | 子进程方式执行，JVM 不加载视频到内存 |
| 编码参数不兼容 | copy 模式失败 | 三级回退策略（copy→TS→重编码） |
| 处理超时 | 请求卡死 | 设置硬超时（如 20分钟），超时强制 kill |
| 中途取消 | 残留临时文件 | finally 块清理 tmp 目录 |
| 不同平台兼容 | Windows/Linux 差异 | CREATE_NO_WINDOW flag / 统一使用 ProcessBuilder |

---

## 6. Maven 依赖清单

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.2</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>video-cut</artifactId>
    <version>0.1.0</version>
    <name>VideoCut</name>

    <properties>
        <java.version>21</java.version>
        <spring-ai.version>1.0.0-M6</spring-ai.version>
    </properties>

    <dependencies>
        <!-- ===== Spring Boot ===== -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-websocket</artifactId>
        </dependency>

        <!-- ===== Spring AI ===== -->
        <!-- OpenAI 兼容接口（通义千问/豆包/DeepSeek 均可） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
        </dependency>

        <!-- ===== 数据 ===== -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- ===== JSON ===== -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>

        <!-- ===== 工具 ===== -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- ===== 测试 ===== -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <repositories>
        <repository>
            <id>spring-milestones</id>
            <name>Spring Milestones</name>
            <url>https://repo.spring.io/milestone</url>
        </repository>
    </repositories>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

**application.yml：**

```yaml
spring:
  threads.virtual.enabled: true
  ai:
    openai:
      api-key: ${QWEN_API_KEY}
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      chat.options.model: qwen-max
      chat.options.temperature: 0.7

  datasource:
    url: jdbc:h2:file:./data/videocut
    driver-class-name: org.h2.Driver

  jpa:
    hibernate.ddl-auto: update
    show-sql: false

  servlet:
    multipart:
      max-file-size: 2048MB
      max-request-size: 2048MB

app:
  paths:
    uploads: ./uploads
    outputs: ./outputs
    tmp: ./tmp
  ffmpeg:
    path: ffmpeg
    timeout-minutes: 20
```

---

## 7. 关键代码设计

### 7.1 核心数据模型

```java
@Entity
@Table(name = "project")
@Data
public class Project {
    @Id
    private String id;
    private String name;
    
    @Column(length = 2000)
    private String promotionGoal;     // 推广目标
    
    @Enumerated(EnumType.STRING)
    private ProjectStatus status;     // CREATED, ANALYZING, SCRIPTING, COMPOSITING, DONE, FAILED
    
    @Column(length = 4000)
    private String scriptJson;        // 脚本+分镜 JSON (ScriptResult)
    
    private String outputVideoPath;   // 最终输出视频路径
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    @OneToMany(mappedBy = "project", cascade = ALL, fetch = LAZY)
    private List<Material> materials;
    
    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        createdAt = updatedAt = LocalDateTime.now();
    }
}

@Entity
@Table(name = "material")
@Data
public class Material {
    @Id
    private String id;
    
    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "project_id")
    private Project project;
    
    private String filePath;          // 本地存储路径
    private String fileName;
    private String mediaType;         // VIDEO, IMAGE
    private Double duration;
    private Integer width;
    private Integer height;
    private String codec;
    
    @Column(length = 2000)
    private String aiDescription;     // AI 素材理解结果
}

// 脚本+分镜（LLM 结构化输出目标）
public record ScriptResult(
    String copywriting,
    List<Storyboard> storyboards
) {}

public record Storyboard(
    int index,
    String narration,       // 这段的旁白
    String materialRef,     // 对应素材文件名
    double startTime,
    double endTime,
    String transition,      // fade, dissolve, slideright, wipeleft, none...
    String subtitle         // 字幕文本
) {}

// 素材理解结果
public record MaterialLibrary(
    List<MaterialDesc> materials
) {}

public record MaterialDesc(
    String materialId,
    String type,
    String overallDesc,           // 整体描述
    List<FrameDesc> keyFrames,    // 关键帧
    List<String> keywords,
    String suggestedUsage         // opening, body, climax, ending
) {}

public record FrameDesc(
    double timestamp,
    String visualContent,
    String mood,
    String colorTone
) {}

public record VideoMeta(
    double duration,
    int width,
    int height,
    String videoCodec,
    String audioCodec,
    double frameRate,
    long fileSize
) {}
```

### 7.2 AI 服务

```java
@Service
public class AIService {

    private final ChatModel chatModel;

    public AIService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 生成脚本+分镜（结构化输出）
     */
    public ScriptResult generateScript(String promptTemplate,
                                        Map<String, Object> params) {
        var template = new PromptTemplate(promptTemplate);
        var prompt = template.create(params);

        var converter = new BeanOutputConverter<>(ScriptResult.class);
        var augmentedPrompt = new Prompt(
            new UserMessage(prompt.getContents()
                + "\n\n" + converter.getFormat())
        );

        var response = chatModel.call(augmentedPrompt);
        return converter.convert(response.getResult().getOutput().getText());
    }

    /**
     * 素材理解：多模态分析图片内容
     */
    public String analyzeVisual(byte[] imageBytes, String context) {
        var userMessage = new UserMessage(
            "请描述这个画面的内容、色调、情绪、人物等。" + context,
            List.of(new Media(MimeTypeUtils.IMAGE_JPEG, imageBytes))
        );
        var response = chatModel.call(new Prompt(userMessage));
        return response.getResult().getOutput().getText();
    }
}
```

### 7.3 FFmpeg 引擎核心代码

```java
@Component
public class FFmpegEngine {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(20);
    private static final int WIN_NO_WINDOW = 0x08000000; // CREATE_NO_WINDOW

    public record FFmpegResult(int exitCode, String stderr) {
        public boolean ok() { return exitCode == 0; }
    }

    // ==================== 元数据 ====================

    public VideoMeta probe(String videoPath) throws Exception {
        var cmd = List.of("ffprobe", "-v", "quiet",
            "-print_format", "json",
            "-show_format", "-show_streams", videoPath);

        var proc = start(cmd, null);
        String json = new String(proc.getInputStream().readAllBytes());
        proc.waitFor(30, TimeUnit.SECONDS);

        // 解析 JSON → VideoMeta
        var root = new ObjectMapper().readTree(json);
        var streams = root.get("streams");
        var format = root.get("format");

        double duration = format.get("duration").asDouble();
        var videoStream = findStream(streams, "video");
        var audioStream = findStream(streams, "audio");

        return new VideoMeta(
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

    public Path captureFrames(String videoPath, int intervalSec, Path outputDir) throws Exception {
        outputDir.toFile().mkdirs();
        String fps = "1/" + intervalSec; // 每 intervalSec 秒 1 帧

        var cmd = List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
            "-i", videoPath,
            "-vf", "fps=" + fps,
            "-q:v", "2",
            "-y", outputDir.resolve("frame_%04d.jpg").toString());

        var result = execute(cmd, DEFAULT_TIMEOUT, null, null);
        if (!result.ok()) throw new RuntimeException("截帧失败: " + result.stderr());
        return outputDir;
    }

    // ==================== 视频裁剪 ====================

    public Path cut(String input, double start, double duration, Path output) throws Exception {
        // 先尝试 copy 模式
        var copyCmd = List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
            "-ss", String.valueOf(start),
            "-t", String.valueOf(duration),
            "-i", input,
            "-c", "copy", "-y", output.toString());

        var result = execute(copyCmd, Duration.ofMinutes(2), null, null);
        if (result.ok()) return output;

        // 回退：重编码
        log.warn("copy 模式裁剪失败，使用重编码回退: {}", result.stderr());
        var reencodeCmd = List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
            "-i", input,
            "-ss", String.valueOf(start),
            "-t", String.valueOf(duration),
            "-c:v", "libx264", "-preset", "superfast", "-crf", "18",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "128k", "-ar", "48000",
            "-movflags", "+faststart", "-y", output.toString());

        result = execute(reencodeCmd, Duration.ofMinutes(10), null, null);
        if (!result.ok()) throw new RuntimeException("裁剪失败: " + result.stderr());
        return output;
    }

    // ==================== 视频合成（拼接 + 转场 + 字幕 + BGM） ====================

    public Path composite(List<ClipSpec> clips,
                           Path subtitlePath,
                           Path bgmPath,
                           Path output) throws Exception {

        String filter = buildCompositeFilter(clips, subtitlePath, bgmPath);

        var cmd = new ArrayList<>(List.of(
            "ffmpeg", "-hide_banner", "-loglevel", "error"));
        for (var clip : clips) cmd.addAll(List.of("-i", clip.path().toString()));
        if (bgmPath != null) cmd.addAll(List.of("-stream_loop", "-1", "-i", bgmPath.toString()));
        cmd.addAll(List.of("-filter_complex", filter));
        cmd.addAll(List.of("-map", "[v]", "-map", "[a]"));
        cmd.addAll(List.of("-c:v", "libx264", "-preset", "medium", "-crf", "18",
            "-pix_fmt", "yuv420p"));
        cmd.addAll(List.of("-c:a", "aac", "-b:a", "192k", "-ar", "48000"));
        cmd.addAll(List.of("-movflags", "+faststart", "-y", output.toString()));

        var result = execute(cmd, Duration.ofMinutes(20), null, null);
        if (!result.ok()) throw new RuntimeException("合成失败: " + result.stderr());
        return output;
    }

    /**
     * 构建 filter_complex 字符串（核心）
     */
    private String buildCompositeFilter(List<ClipSpec> clips,
                                         Path subtitlePath, Path bgmPath) {
        int n = clips.size();
        var sb = new StringBuilder();
        double cumulativeTime = 0;
        double totalDur = clips.stream().mapToDouble(ClipSpec::duration).sum();

        // 步骤1: 设置各段视频与音频流的标签
        for (int i = 0; i < n; i++) {
            sb.append(String.format("[%d:v]settb=AVTB,fps=30,setpts=PTS-STARTPTS[v%d];", i, i));
            sb.append(String.format("[%d:a]asetpts=PTS-STARTPTS[a%d];", i, i));
        }

        // 步骤2: 串联 xfade（视频）+ acrossfade（音频）
        // [v0][v1]xfade=transition=fade:duration=0.5:offset=2.5[v01]
        String prevV = "v0";
        String prevA = "a0";
        for (int i = 1; i < n; i++) {
            double prevDur = clips.get(i - 1).duration();
            cumulativeTime += prevDur;
            double offset = cumulativeTime - 0.5; // 转场在片段末尾开始

            String transition = clips.get(i).transition();
            if ("none".equals(transition)) {
                transition = "fade";
            }
            String nextV = (i == n - 1) ? "v" : "v0" + i;
            String nextA = (i == n - 1) ? "a_premix" : "a0" + i;

            sb.append(String.format(
                "[%s][v%d]xfade=transition=%s:duration=0.5:offset=%.3f[%s];",
                prevV, i, transition, offset, nextV));
            sb.append(String.format(
                "[%s][a%d]acrossfade=d=0.5[%s];", prevA, i, nextA));
            prevV = nextV;
            prevA = nextA;
        }
        cumulativeTime += clips.get(n - 1).duration();

        // 步骤3: 音频处理——BGM 混音或直通
        if (bgmPath != null) {
            int bgmIndex = n; // BGM 在输入列表中的索引
            sb.append(String.format(
                "[%d:a]volume=0.25[bgm];[%s][bgm]amix=inputs=2:duration=first:"
                + "dropout_transition=2[a];", bgmIndex, prevA));
        } else {
            sb.append(String.format("[%s]anull[a];", prevA));
        }

        // 步骤4: 字幕叠加（如果提供了字幕文件）
        if (subtitlePath != null) {
            String subPath = subtitlePath.toAbsolutePath().toString()
                .replace("\\", "/").replace(":", "\\\\:");
            sb.append(String.format(
                "[v]subtitles='%s':force_style='FontSize=24,"
                + "PrimaryColour=&H00FFFFFF,Alignment=2,MarginV=60'[v];", subPath));
        }

        return sb.toString();
    }

    // ==================== 子进程管理 ====================

    private FFmpegResult execute(List<String> cmd, Duration timeout,
                                  Consumer<Double> onProgress,
                                  CancellationToken cancelToken) throws Exception {
        var pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            pb.redirectInput(ProcessBuilder.Redirect.PIPE); // 避免终端交互
        }

        Process proc = pb.start();
        AtomicBoolean terminated = new AtomicBoolean(false);

        // 取消处理
        if (cancelToken != null) {
            cancelToken.onCancel(() -> {
                terminated.set(true);
                proc.destroyForcibly();
            });
        }

        // 异步读取 stderr
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> {
            StringBuilder sb = new StringBuilder();
            try (var r = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream()))) {
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
            } catch (IOException e) { /* 进程被 kill 时可能抛异常 */ }
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

    private Process start(List<String> cmd, Path workDir) throws IOException {
        var pb = new ProcessBuilder(cmd);
        if (workDir != null) pb.directory(workDir.toFile());
        return pb.start();
    }

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

/**
 * 片段规格：用于 composite 方法的输入
 */
public record ClipSpec(
    String path,
    double duration,
    String transition  // fade, dissolve, slideright, wipeleft, none...
) {}
```

---

## 8. 全流程编排设计

### 8.1 PipelineOrchestrator

```java
@Service
public class PipelineOrchestrator {

    private final ProjectRepository projectRepo;
    private final MaterialRepository materialRepo;
    private final AIService aiService;
    private final FFmpegEngine ffmpeg;
    private final SimpMessagingTemplate ws; // WebSocket 进度推送

    @Value("${app.paths.uploads}")
    private String uploadsDir;
    @Value("${app.paths.outputs}")
    private String outputsDir;
    @Value("${app.paths.tmp}")
    private String tmpDir;

    /**
     * 一键生成：上传完成后的全流程入口
     * 在虚拟线程中执行（@Async）
     */
    @Async
    public void execute(String projectId, CancellationToken cancelToken) {
        var project = projectRepo.findById(projectId).orElseThrow();
        var materials = materialRepo.findByProjectId(projectId);
        var tmp = Path.of(tmpDir, "gen_" + projectId);

        try {
            // ===== 阶段1：素材理解 (10%-25%) =====
            updateStatus(project, ANALYZING);
            pushProgress(projectId, "analyze", "素材分析中...", 10);
            analyzeMaterials(materials, tmp);

            // ===== 阶段2：AI生成脚本+分镜 (25%-50%) =====
            updateStatus(project, SCRIPTING);
            pushProgress(projectId, "script", "生成脚本中...", 25);
            var script = generateScript(project, materials);

            // ===== 阶段3：视频合成 (50%-100%) =====
            updateStatus(project, COMPOSITING);
            pushProgress(projectId, "composite", "视频合成中...", 50);
            var output = compositeVideo(script, tmp, projectId);

            // ===== 完成 =====
            project.setScriptJson(toJson(script));
            project.setOutputVideoPath(output.toString());
            project.setStatus(DONE);
            projectRepo.save(project);
            pushProgress(projectId, "done", "视频生成完成!", 100);

        } catch (CancellationException e) {
            project.setStatus(CANCELLED);
            projectRepo.save(project);
            pushProgress(projectId, "cancelled", "已取消", 0);
        } catch (Exception e) {
            log.error("视频生成失败: projectId={}", projectId, e);
            project.setStatus(FAILED);
            projectRepo.save(project);
            pushProgress(projectId, "error", "生成失败: " + e.getMessage(), 0);
        } finally {
            cleanup(tmp);
        }
    }

    // ===== 素材理解 =====
    private void analyzeMaterials(List<Material> materials, Path tmp) throws Exception {
        int total = materials.size();
        for (int i = 0; i < total; i++) {
            var mat = materials.get(i);
            if ("IMAGE".equals(mat.getMediaType())) {
                byte[] imgBytes = Files.readAllBytes(Path.of(mat.getFilePath()));
                String desc = aiService.analyzeVisual(imgBytes, "这是一段推广素材。");
                mat.setAiDescription(desc);
            } else {
                // 视频：截帧 + 多模态分析
                Path framesDir = tmp.resolve("frames_" + mat.getId());
                ffmpeg.captureFrames(mat.getFilePath(), 3, framesDir);
                // 取前3帧分析
                var frameFiles = Files.list(framesDir).sorted().limit(3).toList();
                List<String> frameDescs = new ArrayList<>();
                for (var f : frameFiles) {
                    byte[] imgBytes = Files.readAllBytes(f);
                    frameDescs.add(aiService.analyzeVisual(imgBytes, ""));
                }
                mat.setAiDescription(String.join(" | ", frameDescs));
            }
            materialRepo.save(mat);
            pushProgress(mat.getProject().getId(), "analyze",
                "分析素材 " + (i+1) + "/" + total, 10.0 + (i+1)*15.0/total);
        }
    }

    // ===== 脚本生成 =====
    private ScriptResult generateScript(Project project, List<Material> materials) {
        String promptTemplate = loadPromptTemplate("script_generation.md");
        return aiService.generateScript(promptTemplate, Map.of(
            "promotionGoal", project.getPromotionGoal(),
            "materials", toMaterialJson(materials),
            "style", "viral",
            "wordCount", "200"
        ));
    }

    // ===== 视频合成 =====
    private Path compositeVideo(ScriptResult script, Path tmp, String projectId) throws Exception {
        double cumulativeTime = 0;
        List<ClipSpec> clipSpecs = new ArrayList<>();
        List<String> subtitles = new ArrayList<>();

        for (int i = 0; i < script.storyboards().size(); i++) {
            var sb = script.storyboards().get(i);
            double dur = sb.endTime() - sb.startTime();
            Path clipOutput = tmp.resolve("clip_" + i + ".mp4");
            ffmpeg.cut(sb.materialRef(), sb.startTime(), dur, clipOutput);
            clipSpecs.add(new ClipSpec(clipOutput.toString(), dur,
                sb.transition() != null ? sb.transition() : "fade"));

            // 收集字幕
            if (sb.subtitle() != null && !sb.subtitle().isBlank()) {
                subtitles.add(formatSrtEntry(i+1, cumulativeTime,
                    cumulativeTime + dur, sb.subtitle()));
            }
            cumulativeTime += dur;
        }

        // 生成 SRT 字幕文件
        Path srtPath = tmp.resolve("subtitle.srt");
        Files.writeString(srtPath, String.join("\n", subtitles));

        // 合成
        Path outputDir = Path.of(outputsDir, projectId);
        outputDir.toFile().mkdirs();
        Path output = outputDir.resolve("final_" + System.currentTimeMillis() + ".mp4");

        return ffmpeg.composite(clipSpecs, srtPath, null, output);
    }

    // ===== 辅助方法 =====
    private void pushProgress(String projectId, String phase, String msg, double pct) {
        ws.convertAndSend("/topic/progress/" + projectId,
            new ProgressMessage(phase, msg, pct, Instant.now().toString()));
    }

    private void updateStatus(Project p, ProjectStatus s) {
        p.setStatus(s);
        projectRepo.save(p);
    }

    private void cleanup(Path tmp) {
        try {
            if (Files.exists(tmp)) {
                try (var s = Files.walk(tmp)) {
                    s.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
                }
            }
        } catch (Exception ignored) {}
    }

    private String formatSrtEntry(int idx, double start, double end, String text) {
        return String.format("%d\n%s --> %s\n%s\n",
            idx, formatSrtTime(start), formatSrtTime(end), text);
    }
    private String formatSrtTime(double s) {
        int h = (int)(s/3600), m = (int)((s%3600)/60);
        int sec = (int)(s%60), ms = (int)((s-(int)s)*1000);
        return String.format("%02d:%02d:%02d,%03d", h, m, sec, ms);
    }
}
```

### 8.2 全流程时序图

```
User                Controller          Orchestrator        FFmpeg              LLM
 │                      │                     │                 │                  │
 │ POST /api/projects    │                     │                 │                  │
 │ (materials + goal)    │                     │                 │                  │
 │──────────────────────▶│                     │                 │                  │
 │                      │  async execute()     │                 │                  │
 │  202 Accepted        │────────────────────▶│                 │                  │
 │◀──────────────────────│                     │                 │                  │
 │                      │                     │                 │                  │
 │   WebSocket connect   │                     │                 │                  │
 │◀═══╗                │                     │                 │                  │
 │    ║ progress 10%    │                     │  probe()        │                  │
 │    ║ "素材分析中..."  │                     │────────────────▶│                  │
 │    ║                 │                     │◀────────────────│                  │
 │    ║                 │                     │  captureFrames()│                  │
 │    ║                 │                     │────────────────▶│                  │
 │    ║                 │                     │◀── frames ─────│                  │
 │    ║                 │                     │  analyzeVisual()│                  │
 │    ║                 │                     │─────────────────────────────▶│
 │    ║                 │                     │◀── descriptions ─────────────│
 │    ║ progress 25%    │                     │                 │                  │
 │    ║ "生成脚本中..."  │                     │                 │                  │
 │    ║                 │                     │  generateScript()                │
 │    ║                 │                     │─────────────────────────────▶│
 │    ║                 │                     │◀── ScriptResult ─────────────│
 │    ║ progress 50%    │                     │                 │                  │
 │    ║ "视频合成中..."  │                     │                 │                  │
 │    ║                 │                     │  cut() × N      │                  │
 │    ║                 │                     │────────────────▶│                  │
 │    ║                 │                     │◀── clips ───────│                  │
 │    ║                 │                     │  composite()    │                  │
 │    ║                 │                     │  (xfade+字幕)    │                  │
 │    ║                 │                     │────────────────▶│                  │
 │    ║ progress 100%   │                     │◀── output.mp4 ─│                  │
 │    ║ "完成!"         │                     │                 │                  │
 │◀═══╝                │                     │                 │                  │
```

---

## 9. 实施路线图

### 第一版迭代计划

```
Phase 0: 环境搭建 (1个sprint)
├── Spring Boot 3.4 项目初始化
├── Maven 依赖配置
├── H2 数据库 + JPA 配置
├── FFmpeg 安装 + 路径检测 + probe 验证
└── WebSocket 基础配置

Phase 1: 素材上传 (1个sprint)
├── MultipartFile 上传接口
├── ffprobe 元数据提取
├── Material 数据模型 + CRUD
├── 本地文件存储管理
└── Project 数据模型

Phase 2: AI 脚本生成 (1-2个sprint)     ◀── Spring AI 核心
├── Spring AI ChatModel 配置与连通性测试
├── Prompt 模板加载 (classpath .md 文件)
├── BeanOutputConverter 结构化输出验证
├── ScriptResult + Storyboard 反序列化
└── 16+ 风格模板适配（选3-5个核心模板）

Phase 3: 素材理解 (1个sprint)
├── FFmpeg 帧截取
├── Spring AI 多模态调用
├── 图片/视频分帧分析
└── MaterialDesc 文案库构建

Phase 4: 视频合成 (2-3个sprint)        ◀── FFmpeg 核心
├── FFmpegEngine 基础: probe + execute 封装
├── 视频裁剪: cut() + copy/重编码双模式
├── 视频拼接: concat filter + xfade 串联
├── 字幕生成: SRT 文件生成 + subtitles 烧录
├── BGM 混音: amix + volume + stream_loop
├── 全流程测试: 2段素材 → 脚本 → 合成 → 播放验证

Phase 5: 全流程编排 (1个sprint)
├── PipelineOrchestrator @Async 实现
├── WebSocket 实时进度推送
├── CancellationToken 取消机制
├── 临时文件清理
└── 异常处理 + 状态回滚

Phase 6: 集成测试与优化 (1个sprint)
├── 端到端测试（3段素材 + 推广目标 → 成品视频）
├── 分辨率不一致场景
├── 图片 + 视频混合场景
├── 超时场景 / 取消场景
└── 性能优化（编码器选择、中间文件复用）
```

### 里程碑

| 里程碑 | 可交付物 | 验收标准 |
|--------|---------|---------|
| M1: 视频可剪 | `/api/upload` + probe 元数据 | 上传一段视频，返回时长/分辨率 |
| M2: AI 能写 | `/api/generate/script` | 输入素材描述+目标，返回有效分镜 JSON |
| M3: AI 能看 | 素材分析接口 | 上传图片，返回画面描述文案 |
| M4: 能拼视频 | `/api/generate/video` | 两段素材 + 手动分镜 → 含 fade 转场的 mp4 |
| M5: 全自动 | 一键生成接口 | 上传素材+目标 → 全自动 → 成品视频 |

---

## 附录 A：FFmpeg 安装与验证

**Windows：**
```powershell
winget install Gyan.FFmpeg
ffmpeg -version
```

**Linux/Mac：**
```bash
brew install ffmpeg        # Mac
sudo apt install ffmpeg    # Ubuntu
ffmpeg -version
```

**打包分发**：将 `ffmpeg.exe` / `ffprobe.exe` 放入项目的 `bin/` 目录，启动时通过 `System.getProperty("user.dir")` 定位。

---

## 附录 B：xfade 完整转场效果列表

可通过 `ffmpeg -h filter=xfade` 查看，常用列表：

```
fade, fadeblack, fadewhite, dissolve, pixelize, 
slideright, slideleft, slideup, slidedown, 
wiperight, wipeleft, wipeup, wipedown, 
circleopen, circleclose, rectcrop, 
distance, smoothleft, smoothright, smoothup, smoothdown, 
hlslice, hrslice, vuslice, vdslice, 
radial, zoomin, squeezeh, squeezev, 
horzopen, horzclose, vertopen, vertclose, 
diagtl, diagtr, diagbl, diagbr
```

---

## 附录 C：与原项目的主要差异

| 维度 | Python 原项目 | Java 版本 |
|------|--------------|-----------|
| ASR 字幕提取 | FunASR / Bcut / Whisper | ❌ 第一版不做，LLM 直接生成字幕 |
| TTS 配音 | Edge-TTS / Qwen / VoxCPM | ❌ 第一版不做，仅 BGM |
| 场景检测 | TransNetV2 (TF/PyTorch) | ❌ 第一版不做 |
| 剪映导出 | jianying_draft_service | ❌ 第一版不做 |
| 视频转场 | 无（仅硬切） | ✅ **新增**，xfade 100+ 效果 |
| BGM | 无 | ✅ **新增**，amix 混音 |
| 字幕烧录 | 剪映草稿中处理 | ✅ **新增**，FFmpeg subtitles 直接烧录 |
| 异步模型 | asyncio | ✅ JDK 21 Virtual Threads |
| AI 调用 | 自建多 Provider 抽象层 | ✅ Spring AI（标准化接口） |
