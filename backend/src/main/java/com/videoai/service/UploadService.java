package com.videoai.service;

import com.videoai.dto.UploadInitDTO;
import com.videoai.vo.UploadInitVO;

import java.io.InputStream;

public interface UploadService {

    /**
     * 初始化分片上传
     */
    UploadInitVO initUpload(UploadInitDTO dto);

    /**
     * 服务端代理上传单个分片
     */
    void uploadChunk(String taskId, int chunkIndex, InputStream inputStream);

    /**
     * 生成单个分片的预签名上传URL (前端直传MinIO)
     */
    String getChunkPresignedUrl(String taskId, int chunkIndex);

    /**
     * 更新分片上传进度 (前端直传MinIO后回调)
     */
    void updateChunkProgress(String taskId, int chunkIndex);

    /**
     * 查询上传进度（断点续传）
     */
    UploadInitVO getUploadProgress(String taskId);
}
