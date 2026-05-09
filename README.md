# 视频AI分析平台

基于大模型的视频内容理解与分析平台。支持大文件分片上传、FFmpeg 视频转码、AI 音频转写/关键帧分析/摘要生成，以及流式 AI 智能问答。

---

## 功能特性

### 视频上传
- **分片上传** — 5MB 分片并发直传 MinIO，最大支持 5GB
- **断点续传** — Redis Bitmap 追踪分片进度，刷新页面后自动恢复
- **MD5 秒传** — 相同文件跳过上传，直接复用已有视频
- **双重去重** — MD5 文件级 + Redisson 分布式锁请求级

### 视频处理
- **FFmpeg 转码** — 自动转为浏览器可播放的 H.264 MP4
- **AI 音频转写** — 提取音轨，调用 ASR 模型转为文字
- **AI 关键帧分析** — 均匀提取关键帧，视觉模型描述画面内容
- **AI 摘要生成** — 综合转写文本与画面描述，生成摘要和标签

### 视频播放
- HTML5 `<video>` 原生播放器，支持进度条拖动
- MinIO 预签名 URL，原生支持 Range 请求

### AI 智能问答
- **Markdown 渲染** — 代码块、表格、列表格式化显示
- **上下文记忆** — Redis 会话窗口，20 轮多轮对话
- **视频内容注入** — 当前视频的音频转写文本自动注入会话上下文

### 实时通知
- **SSE 状态推送** — 转码完成后前端自动刷新，弹窗通知

---

## 技术栈

| 层 | 技术 |
|------|------|
| 前端 | Vue 3 (Composition API), Vite 5, Element Plus, Pinia, Vue Router 4 |
| 后端 | Spring Boot 3.2, MyBatis-Plus 3.5, Sa-Token |
| 数据库 | MySQL 8.0 |
| 缓存/锁 | Redis 7 + Redisson |
| 消息队列 | Apache RocketMQ 5.1 |
| 对象存储 | MinIO (S3 兼容) |
| AI 模型 | 硅基流动 (Qwen2.5-72B, SenseVoiceSmall, PaddleOCR-VL) |
| 视频处理 | FFmpeg / FFprobe |

---

## 快速开始

### 环境要求

- JDK 17+
- Node.js 18+
- Docker & Docker Compose
- Maven 3.8+
- FFmpeg & FFprobe（系统已安装或通过配置指定路径）

### 1. 启动基础设施

```bash
cd video-ai-platform
docker-compose up -d
```

| 服务 | 端口 | 访问地址 |
|------|------|---------|
| MySQL | 3307 → 3306 | root / root123 |
| Redis | 6379 | — |
| RocketMQ NameServer | 9876 | — |
| RocketMQ Broker | 10911, 10909 | — |
| MinIO API | 9000 | — |
| MinIO Console | 9001 | http://localhost:9001 (minioadmin / minioadmin123) |

数据库 `video_ai` 和 MinIO bucket `video-ai` 自动创建。

### 2. 配置 AI API Key

```bash
# 注册 https://siliconflow.cn 获取 API Key
export AI_API_KEY=sk-your-key-here
```

或在 `backend/src/main/resources/application.yml` 中修改 `ai.api-key`。

### 3. 启动后端

```bash
cd backend
mvn spring-boot:run
# 启动在 http://localhost:8080/api
```

Swagger 文档：http://localhost:8080/api/doc.html

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
# 启动在 http://localhost:5173
```

### 5. 停止

```bash
docker-compose down          # 保留数据
docker-compose down -v       # 删除数据卷
```

---

## 项目结构

```
video-ai-platform/
├── docker-compose.yml              # MySQL / Redis / RocketMQ / MinIO
├── backend/
│   ├── pom.xml
│   └── src/main/
│       ├── resources/
│       │   ├── application.yml     # 全部配置（支持环境变量覆盖）
│       │   └── schema.sql          # 数据库 DDL
│       └── java/com/videoai/
│           ├── VideoAiApplication.java
│           ├── config/             # CORS, Redis, MinIO, MyBatisPlus, SaToken
│           ├── controller/         # Video, Upload, Ai, Sse
│           ├── service/            # 业务服务 + FFmpeg + MinIO + SSE
│           ├── service/impl/       # UploadServiceImpl, AiServiceImpl
│           ├── entity/             # Video, UploadTask, AiConversation
│           ├── mapper/             # MyBatis-Plus Mapper
│           ├── dto/ / vo/          # 数据传输对象 + 响应对象
│           ├── mq/                 # RocketMQ Producer + Consumer
│           ├── event/              # Spring 事件（视频合并完成）
│           ├── aspect/             # 限流切面
│           ├── exception/          # 全局异常处理
│           └── util/               # 指数退避重试
└── frontend/
    ├── vite.config.js              # 开发代理 → localhost:8080
    └── src/
        ├── api/                    # Axios 封装 (video, upload, ai)
        ├── utils/upload.js         # ChunkUploader (MD5 + 预签名直传)
        ├── views/                  # Home, Upload, Videos, VideoDetail
        ├── components/             # AiChat (流式 SSE + Markdown 渲染)
        └── router/                 # Vue Router 路由
```

---

## 核心流程

### 上传 → 转码 → 播放

```
选择文件
  → SparkMD5 分片计算 MD5
  → POST /api/upload/init (去重检查 + 创建上传任务)
  → 并发 GET 预签名 URL → PUT 直传 MinIO
  → POST /api/upload/chunk-callback (Redis Lua 原子进度追踪)
  → 全部分片完成 → composeObject 合并
  → @Transactional 更新视频状态 → 发布 VideoMergedEvent
  → 事务提交 → @TransactionalEventListener → MQ 发送转码消息
  → FFmpeg 转码为 H.264 MP4 → 上传 MinIO
  → AI 分析 (ASR + Vision + 摘要生成)
  → status=3, SSE 通知前端
  → 前端自动加载播放 URL → <video> 播放
```

### AI 问答（流式 SSE）

```
用户发送消息
  → POST /api/ai/chat/stream
  → 非流式调用 LLM (Function Calling 判断)
    → 如有工具调用 → 执行 → 结果加入上下文
  → 流式调用 LLM (stream=true)
  → SSE 逐 token 推送到前端
    event: token / data: 你好
    event: token / data: ，请问...
  → 前端实时追加文本 (流式中纯文本, 完成后 Markdown 渲染)
```

---

## API 概览

### 视频管理 `/api/video`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/video/list` | 分页列表 (page, size, keyword, status) |
| GET | `/video/{id}` | 视频详情 |
| GET | `/video/{id}/play-url` | 获取播放 URL (预签名) |
| DELETE | `/video/{id}` | 逻辑删除 |

### 上传 `/api/upload`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/upload/init` | 初始化分片上传 |
| GET | `/upload/presigned-url/{taskId}/{chunkIndex}` | 获取分片预签名上传 URL |
| POST | `/upload/chunk-callback/{taskId}/{chunkIndex}` | 分片上传完成回调 |
| POST | `/upload/chunk/{taskId}/{chunkIndex}` | 服务端代理上传（兜底） |
| GET | `/upload/progress/{taskId}` | 查询上传进度（断点续传） |
| GET | `/upload/file-url` | 获取文件访问 URL |

### AI 问答 `/api/ai`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/ai/chat` | AI 对话（阻塞式，返回完整结果） |
| POST | `/ai/chat/stream` | AI 对话（SSE 流式输出） |

### 实时通知 `/api/sse`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/sse/subscribe/{videoId}` | 订阅视频状态变更事件 |

---

## 配置说明

关键配置项（`application.yml`），均支持环境变量覆盖：

| 配置 | 环境变量 | 默认值 | 说明 |
|------|---------|--------|------|
| 数据库密码 | `DB_PASSWORD` | `root123` | MySQL 连接密码 |
| AI API Key | `AI_API_KEY` | — | 硅基流动 API Key（必填） |
| AI 模型 | `ai.model` | `Qwen/Qwen2.5-72B-Instruct` | 对话模型 |
| ASR 模型 | `ai.asr-model` | `FunAudioLLM/SenseVoiceSmall` | 语音转文字模型 |
| FFmpeg 路径 | `FFMPEG_PATH` | `ffmpeg` | 可指定自定义安装路径 |
| MinIO 地址 | `MINIO_ENDPOINT` | `http://localhost:9000` | 对象存储地址 |
| RocketMQ NS | `ROCKETMQ_NAMESRV` | `localhost:9876` | NameServer 地址 |
| Redis 地址 | `REDIS_HOST` | `localhost` | Redis 服务器地址 |
| 上传分片大小 | `upload.chunk-size` | `5242880` | 5 MB |
| 最大文件大小 | `upload.max-file-size` | `5368709120` | 5 GB |

---

## 数据库表

| 表 | 说明 |
|------|------|
| `t_video` | 视频记录（含摘要、转写文本、标签、转码状态） |
| `t_upload_task` | 分片上传任务（断点续传进度） |
| `t_ai_conversation` | AI 对话历史记录 |

---

## 线程池配置

| 线程池 | 用途 | Core/Max |
|--------|------|---------|
| `sseExecutor` | SSE 流式聊天异步处理 | 4 / 8 |
| `transcodeExecutor` | 视频转码异步执行 | 2 / 4 |
