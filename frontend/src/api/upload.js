import request from './index'

/**
 * 初始化分片上传
 */
export function initUpload(data) {
  return request.post('/upload/init', data)
}

/**
 * 获取分片预签名上传URL (前端直传MinIO)
 */
export function getPresignedUrl(taskId, chunkIndex) {
  return request.get(`/upload/presigned-url/${taskId}/${chunkIndex}`)
}

/**
 * 分片上传完成回调 (通知后端更新进度)
 */
export function chunkCallback(taskId, chunkIndex) {
  return request.post(`/upload/chunk-callback/${taskId}/${chunkIndex}`)
}

/**
 * 服务端代理上传（兜底方案）
 */
export function uploadChunk(taskId, chunkIndex, formData) {
  return request.post(`/upload/chunk/${taskId}/${chunkIndex}`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000
  })
}

/**
 * 查询上传进度（断点续传）
 */
export function getUploadProgress(taskId) {
  return request.get(`/upload/progress/${taskId}`)
}

/**
 * 获取文件访问URL
 */
export function getFileUrl(objectKey) {
  return request.get('/upload/file-url', { params: { objectKey } })
}
