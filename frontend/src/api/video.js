import request from './index'

/**
 * 分页查询视频列表
 */
export function getVideoList(params) {
  return request.get('/video/list', { params })
}

/**
 * 获取视频详情
 */
export function getVideoDetail(id) {
  return request.get(`/video/${id}`)
}

/**
 * 删除视频
 */
export function deleteVideo(id) {
  return request.delete(`/video/${id}`)
}

/**
 * 获取视频播放URL
 */
export function getPlayUrl(id) {
  return request.get(`/video/${id}/play-url`)
}
