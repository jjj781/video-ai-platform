import SparkMD5 from 'spark-md5'
import { initUpload, getPresignedUrl, chunkCallback } from '@/api/upload'

/**
 * 分片上传管理器 (基于MinIO预签名URL直传)
 * 流程：前端计算MD5 → 初始化上传 → 获取预签名URL → 直传MinIO → 回调更新进度
 * 支持：分片并发上传、断点续传、暂停/恢复
 */
export class ChunkUploader {
  /**
   * @param {File} file - 要上传的文件
   * @param {Object} options - 配置选项
   * @param {number} options.chunkSize - 分片大小 (默认5MB)
   * @param {number} options.concurrent - 并发上传数 (默认3)
   * @param {Function} options.onProgress - 进度回调
   * @param {Function} options.onComplete - 上传完成回调
   * @param {Function} options.onError - 错误回调
   */
  constructor(file, options = {}) {
    this.file = file
    this.chunkSize = options.chunkSize || 5 * 1024 * 1024 // 5MB
    this.concurrent = options.concurrent || 3
    this.onProgress = options.onProgress || (() => {})
    this.onComplete = options.onComplete || (() => {})
    this.onError = options.onError || (() => {})

    this.taskId = null
    this.uploadId = null
    this.totalChunks = 0
    this.completedChunks = new Set()
    this.uploadedBytes = 0
    this.isPaused = false
    this.isCancelled = false
  }

  /**
   * 计算文件快速哈希（采样首尾1MB + 元信息，避免大文件全量MD5耗时）
   * GB级文件仅需0.1秒，命中后再由服务端校验全量MD5确认秒传
   */
  async computeQuickHash() {
    return new Promise((resolve, reject) => {
      const spark = new SparkMD5.ArrayBuffer()
      const sampleSize = 1024 * 1024 // 1MB

      const readSlice = (offset, size) => {
        return new Promise((res, rej) => {
          const reader = new FileReader()
          reader.onload = (e) => res(e.target.result)
          reader.onerror = rej
          reader.readAsArrayBuffer(this.file.slice(offset, offset + size))
        })
      }

      Promise.resolve().then(async () => {
        try {
          // 读取首部1MB
          spark.append(await readSlice(0, sampleSize))
          // 读取尾部1MB
          if (this.file.size > sampleSize * 2) {
            spark.append(await readSlice(this.file.size - sampleSize, sampleSize))
          }
          // 混入文件元信息
          spark.append(new TextEncoder().encode(this.file.name + this.file.size))
          resolve(spark.end())
        } catch (e) {
          reject(new Error('文件哈希计算失败: ' + e.message))
        }
      })
    })
  }

  /**
   * 开始上传
   * 1. 计算快速哈希 → 2. 初始化上传 → 3. 获取预签名URL → 4. 直传MinIO → 5. 回调更新进度
   */
  async start(title, description) {
    try {
      // 1. 计算快速哈希（首尾采样，极速完成）
      this.onProgress({ phase: 'hash', percent: 0, message: '正在计算文件哈希...' })
      const fileMd5 = await this.computeQuickHash()
      this.onProgress({ phase: 'hash', percent: 100, message: '哈希计算完成' })

      // 2. 初始化上传（服务端会用quickHash+全量MD5双重验证秒传）
      this.onProgress({ phase: 'init', message: '正在初始化上传任务...' })
      const res = await initUpload({
        filename: this.file.name,
        fileSize: this.file.size,
        fileMd5: fileMd5,
        quickHash: fileMd5,
        title: title || this.file.name,
        description
      })

      this.taskId = res.data.taskId
      this.uploadId = res.data.uploadId
      this.totalChunks = res.data.totalChunks

      // 3. 断点续传：恢复已完成的分片
      if (res.data.completedChunks && res.data.completedChunks.length > 0) {
        res.data.completedChunks.forEach(idx => this.completedChunks.add(idx))
        this.uploadedBytes = this.completedChunks.size * this.chunkSize
        this.onProgress({
          phase: 'upload',
          percent: Math.round((this.completedChunks.size / this.totalChunks) * 100),
          message: `断点续传: 已完成 ${this.completedChunks.size}/${this.totalChunks} 个分片`
        })
      }

      // 4. 并发上传分片到MinIO
      await this.uploadChunks()

      // 5. 上传完成
      this.onComplete({ taskId: this.taskId, videoId: res.data.videoId })

    } catch (error) {
      this.onError(error)
    }
  }

  /**
   * 并发上传分片 (通过预签名URL直传MinIO)
   */
  async uploadChunks() {
    const pendingChunks = []
    for (let i = 0; i < this.totalChunks; i++) {
      if (!this.completedChunks.has(i)) {
        pendingChunks.push(i)
      }
    }

    // 并发控制
    const executing = new Set()
    for (const chunkIndex of pendingChunks) {
      if (this.isCancelled) break

      // 暂停等待
      while (this.isPaused) {
        await new Promise(resolve => setTimeout(resolve, 200))
      }

      const task = this.uploadSingleChunk(chunkIndex)
      executing.add(task)
      task.finally(() => executing.delete(task))

      if (executing.size >= this.concurrent) {
        await Promise.race(executing)
      }
    }

    // 等待剩余分片完成
    await Promise.all(executing)
  }

  /**
   * 上传单个分片到MinIO (预签名URL直传)
   */
  async uploadSingleChunk(chunkIndex) {
    try {
      // 1. 从后端获取该分片的预签名上传URL
      const presignedRes = await getPresignedUrl(this.taskId, chunkIndex)
      const presignedUrl = presignedRes.data.presignedUrl

      // 2. 计算分片范围
      const start = chunkIndex * this.chunkSize
      const end = Math.min(start + this.chunkSize, this.file.size)
      const chunk = this.file.slice(start, end)

      // 3. 直接PUT到MinIO (不经过业务服务器)
      const response = await fetch(presignedUrl, {
        method: 'PUT',
        body: chunk,
        headers: {
          'Content-Type': 'application/octet-stream'
        }
      })

      if (!response.ok) {
        throw new Error(`MinIO上传失败: HTTP ${response.status}`)
      }

      // 4. 回调后端更新分片进度
      await chunkCallback(this.taskId, chunkIndex)

      // 5. 更新本地进度
      this.completedChunks.add(chunkIndex)
      this.uploadedBytes += (end - start)

      const percent = Math.round((this.completedChunks.size / this.totalChunks) * 100)
      this.onProgress({
        phase: 'upload',
        percent,
        uploadedChunks: this.completedChunks.size,
        totalChunks: this.totalChunks,
        message: `上传中: ${this.completedChunks.size}/${this.totalChunks} 个分片 (${percent}%)`
      })

    } catch (error) {
      // 单个分片失败不中断整体，后续可断点续传
      console.error(`分片 ${chunkIndex} 上传失败:`, error)
    }
  }

  /**
   * 暂停上传
   */
  pause() {
    this.isPaused = true
  }

  /**
   * 恢复上传
   */
  resume() {
    this.isPaused = false
  }

  /**
   * 取消上传
   */
  cancel() {
    this.isCancelled = true
    this.isPaused = false
  }
}
