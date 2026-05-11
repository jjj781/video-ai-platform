<template>
  <div class="upload-page">
    <h2>上传视频</h2>
    <p class="subtitle">支持大文件分片上传，自动断点续传，最大支持5GB</p>

    <el-card class="upload-card">
      <!-- 上传表单 -->
      <div v-if="!uploading && !uploadResult">
        <el-form :model="form" label-width="80px">
          <el-form-item label="视频标题">
            <el-input v-model="form.title" placeholder="请输入视频标题（可选）" />
          </el-form-item>
          <el-form-item label="视频描述">
            <el-input v-model="form.description" type="textarea" :rows="3" placeholder="请输入视频描述（可选）" />
          </el-form-item>
        </el-form>

        <el-upload
          class="upload-area"
          drag
          :auto-upload="false"
          :on-change="handleFileChange"
          :show-file-list="false"
          accept="video/*"
        >
          <el-icon :size="60" color="#c0c4cc"><Upload /></el-icon>
          <div class="el-upload__text">
            将视频文件拖拽到此处，或 <em>点击选择</em>
          </div>
          <template #tip>
            <div class="el-upload__tip">支持 MP4/AVI/MOV/MKV 等常见视频格式</div>
          </template>
        </el-upload>

        <div v-if="selectedFile" class="file-info">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="文件名">{{ selectedFile.name }}</el-descriptions-item>
            <el-descriptions-item label="文件大小">{{ formatSize(selectedFile.size) }}</el-descriptions-item>
            <el-descriptions-item label="分片数">{{ Math.ceil(selectedFile.size / (5 * 1024 * 1024)) }}</el-descriptions-item>
            <el-descriptions-item label="分片大小">5 MB</el-descriptions-item>
          </el-descriptions>

          <div class="upload-actions">
            <el-button type="primary" @click="startUpload" :loading="false">
              <el-icon><Upload /></el-icon>
              开始上传
            </el-button>
          </div>
        </div>
      </div>

      <!-- 上传进度 -->
      <div v-if="uploading" class="upload-progress">
        <div class="progress-header">
          <span>{{ progressData.message }}</span>
          <div class="progress-actions">
            <el-button v-if="!isPaused" size="small" @click="pauseUpload">
              <el-icon><VideoPause /></el-icon> 暂停
            </el-button>
            <el-button v-else size="small" type="warning" @click="resumeUpload">
              <el-icon><VideoPlay /></el-icon> 继续
            </el-button>
            <el-button size="small" type="danger" @click="cancelUpload">
              <el-icon><Close /></el-icon> 取消
            </el-button>
          </div>
        </div>

        <el-progress
          :percentage="progressData.percent"
          :status="progressData.phase === 'error' ? 'exception' : undefined"
          :stroke-width="20"
          :text-inside="true"
        />

        <div class="progress-detail">
          <el-tag v-if="progressData.phase === 'hash'" type="info">计算哈希</el-tag>
          <el-tag v-else-if="progressData.phase === 'init'" type="warning">初始化</el-tag>
          <el-tag v-else-if="progressData.phase === 'upload'" type="primary">
            分片 {{ progressData.uploadedChunks || 0 }}/{{ progressData.totalChunks || 0 }}
          </el-tag>
        </div>
      </div>

      <!-- 上传结果 -->
      <div v-if="uploadResult" class="upload-result">
        <el-result icon="success" title="上传成功" sub-title="视频已提交异步转码，转码完成后即可观看">
          <template #extra>
            <el-button type="primary" @click="goToVideo">查看视频</el-button>
            <el-button @click="resetUpload">继续上传</el-button>
          </template>
        </el-result>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ChunkUploader } from '@/utils/upload'
import { ElMessage } from 'element-plus'

const router = useRouter()

const form = reactive({ title: '', description: '' })
const selectedFile = ref(null)
const uploading = ref(false)
const isPaused = ref(false)
const uploadResult = ref(null)
const progressData = reactive({
  phase: 'idle',
  percent: 0,
  message: '',
  uploadedChunks: 0,
  totalChunks: 0
})

let uploader = null

const handleFileChange = (file) => {
  selectedFile.value = file.raw
}

const startUpload = () => {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择视频文件')
    return
  }

  uploading.value = true
  isPaused.value = false

  uploader = new ChunkUploader(selectedFile.value, {
    onProgress: (data) => {
      Object.assign(progressData, data)
    },
    onComplete: (result) => {
      uploadResult.value = result
      uploading.value = false
      ElMessage.success('视频上传成功！')
    },
    onError: (error) => {
      uploading.value = false
      ElMessage.error('上传失败: ' + error.message)
    }
  })

  uploader.start(form.title, form.description)
}

const pauseUpload = () => {
  uploader?.pause()
  isPaused.value = true
}

const resumeUpload = () => {
  uploader?.resume()
  isPaused.value = false
}

const cancelUpload = () => {
  uploader?.cancel()
  uploading.value = false
  ElMessage.info('上传已取消')
}

const goToVideo = () => {
  if (uploadResult.value?.videoId) {
    router.push(`/video/${uploadResult.value.videoId}`)
  } else {
    ElMessage.error('无法获取视频ID，请稍后重试')
  }
}

const resetUpload = () => {
  selectedFile.value = null
  uploadResult.value = null
  uploading.value = false
  form.title = ''
  form.description = ''
}

const formatSize = (bytes) => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}
</script>

<style scoped>
.upload-page {
  max-width: 700px;
  margin: 0 auto;
}

.upload-page h2 {
  margin-bottom: 8px;
  color: #303133;
}

.subtitle {
  color: #909399;
  margin-bottom: 24px;
}

.upload-area {
  width: 100%;
  margin: 16px 0;
}

.file-info {
  margin-top: 16px;
}

.upload-actions {
  margin-top: 16px;
  text-align: center;
}

.upload-progress {
  padding: 20px 0;
}

.progress-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.progress-actions {
  display: flex;
  gap: 8px;
}

.progress-detail {
  margin-top: 12px;
  text-align: center;
}

.upload-result {
  padding: 20px 0;
}
</style>
