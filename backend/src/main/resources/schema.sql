-- 视频AI分析平台 数据库Schema
CREATE DATABASE IF NOT EXISTS video_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE video_ai;

-- 视频表
CREATE TABLE t_video (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    title           VARCHAR(255) NOT NULL COMMENT '视频标题',
    description     TEXT COMMENT '视频描述',
    user_id         BIGINT NOT NULL COMMENT '上传用户ID',
    oss_key         VARCHAR(512) COMMENT 'OSS对象键',
    original_filename VARCHAR(255) COMMENT '原始文件名',
    file_size       BIGINT COMMENT '文件大小(bytes)',
    file_md5        VARCHAR(32) COMMENT '文件MD5',
    duration        INT COMMENT '视频时长(秒)',
    cover_url       VARCHAR(512) COMMENT '视频封面URL',
    status          TINYINT NOT NULL DEFAULT 0 COMMENT '状态: 0-上传中, 1-待转码, 2-转码中, 3-已就绪, 4-转码失败, 5-已删除',
    transcoded_key  VARCHAR(512) COMMENT '转码后OSS Key',
    summary         TEXT COMMENT 'AI内容摘要',
    transcript      MEDIUMTEXT COMMENT '音频转写内容',
    tags            JSON COMMENT 'AI分析标签',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_md5 (file_md5),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB COMMENT='视频表';

-- 分片上传任务表
CREATE TABLE t_upload_task (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id              VARCHAR(64) NOT NULL UNIQUE COMMENT '任务唯一标识',
    video_id             BIGINT COMMENT '关联视频ID',
    user_id              BIGINT NOT NULL COMMENT '上传用户ID',
    upload_id            VARCHAR(128) COMMENT 'OSS Upload ID',
    filename             VARCHAR(255) NOT NULL COMMENT '文件名',
    file_size            BIGINT NOT NULL COMMENT '文件大小',
    file_md5             VARCHAR(32) NOT NULL COMMENT '文件MD5',
    chunk_size           BIGINT NOT NULL COMMENT '分片大小',
    total_chunks         INT NOT NULL COMMENT '总分片数',
    completed_chunks     INT NOT NULL DEFAULT 0 COMMENT '已完成分片数',
    completed_chunk_indexes JSON COMMENT '已完成分片索引',
    status               TINYINT NOT NULL DEFAULT 0 COMMENT '状态: 0-进行中, 1-已完成, 2-已失败, 3-已取消',
    created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_task_id (task_id),
    INDEX idx_user_id (user_id),
    INDEX idx_md5 (file_md5),
    INDEX idx_status (status)
) ENGINE=InnoDB COMMENT='分片上传任务表';

-- AI对话记录表
CREATE TABLE t_ai_conversation (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    video_id        BIGINT COMMENT '关联视频ID',
    user_id         BIGINT NOT NULL COMMENT '用户ID',
    user_message    TEXT NOT NULL COMMENT '用户消息',
    ai_response     TEXT COMMENT 'AI回复',
    function_calls  JSON COMMENT 'Function Calling调用详情',
    token_used      INT COMMENT '消耗Token数',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_user_id (user_id),
    INDEX idx_video_id (video_id)
) ENGINE=InnoDB COMMENT='AI对话记录表';

-- 转码失败记录表（死信队列消费者写入）
CREATE TABLE t_transcode_failure (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    video_id        BIGINT NOT NULL COMMENT '视频ID',
    oss_key         VARCHAR(512) COMMENT 'OSS对象键',
    fail_reason     TEXT COMMENT '失败原因',
    retry_count     INT DEFAULT 0 COMMENT '已重试次数',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_video_id (video_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB COMMENT='转码失败记录表';
