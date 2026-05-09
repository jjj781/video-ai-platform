package com.videoai.event;

/**
 * 视频分片合并完成事件
 * 在事务提交后由监听器异步发送转码消息，解决DB+MQ双写一致性问题
 */
public record VideoMergedEvent(Long videoId, String ossKey, String filename, Long fileSize) {}
