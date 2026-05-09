<template>
  <div class="video-detail" v-if="video">
    <el-row :gutter="24">
      <!-- 左侧：视频信息 -->
      <el-col :span="16">
        <el-card>
          <div class="video-player">
            <video
              v-if="video.status === 3 && playUrl"
              :src="playUrl"
              controls
              class="video-tag"
              preload="metadata"
            >
              您的浏览器不支持视频播放
            </video>
            <template v-else>
              <el-icon :size="80" color="#c0c4cc"><Film /></el-icon>
              <p v-if="video.status === 2">视频转码中，请稍候...</p>
              <p v-else-if="video.status === 4">视频转码失败</p>
              <p v-else-if="video.status !== 3">视频处理中...</p>
            </template>
          </div>

          <h2 class="video-title">{{ video.title }}</h2>
          <p class="video-desc" v-if="video.description">{{ video.description }}</p>

          <el-divider />

          <el-descriptions :column="2" border>
            <el-descriptions-item label="文件名">{{ video.originalFilename }}</el-descriptions-item>
            <el-descriptions-item label="文件大小">{{ formatSize(video.fileSize) }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="statusType(video.status)">{{ statusText(video.status) }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="上传时间">{{ formatDate(video.createdAt) }}</el-descriptions-item>
          </el-descriptions>

          <div v-if="video.summary" class="ai-summary">
            <h4><el-icon><MagicStick /></el-icon> AI摘要</h4>
            <p>{{ video.summary }}</p>
          </div>

          <div v-if="videoTags.length" class="ai-tags">
            <el-tag v-for="tag in videoTags" :key="tag" type="info" class="tag-item">{{ tag }}</el-tag>
          </div>
        </el-card>
      </el-col>

      <!-- 右侧：AI问答 -->
      <el-col :span="8">
        <AiChat :video-id="video.id" />
      </el-col>
    </el-row>
  </div>

  <div v-else-if="loading" class="loading">
    <el-icon class="is-loading" :size="40"><Loading /></el-icon>
    <p>加载中...</p>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { getVideoDetail, getPlayUrl } from '@/api/video'
import AiChat from '@/components/AiChat.vue'
import { ElMessage, ElNotification } from 'element-plus'

const route = useRoute()
const video = ref(null)
const playUrl = ref('')
const loading = ref(true)
let eventSource = null

const videoTags = computed(() => {
  if (!video.value?.tags) return []
  try { return JSON.parse(video.value.tags) } catch { return [] }
})

const statusMap = {
  0: { text: '上传中', type: 'info' },
  1: { text: '待转码', type: 'warning' },
  2: { text: '转码中', type: 'warning' },
  3: { text: '已就绪', type: 'success' },
  4: { text: '转码失败', type: 'danger' }
}

const statusText = (s) => statusMap[s]?.text || '未知'
const statusType = (s) => statusMap[s]?.type || 'info'

const formatSize = (bytes) => {
  if (!bytes) return '-'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

const formatDate = (d) => d ? new Date(d).toLocaleString('zh-CN') : '-'

const loadVideo = async () => {
  try {
    const res = await getVideoDetail(route.params.id)
    video.value = res.data
    if (video.value?.status === 3) {
      try {
        const urlRes = await getPlayUrl(route.params.id)
        playUrl.value = urlRes.data.url
      } catch {
        // 播放URL加载失败，不影响页面展示
      }
    }
  } catch {
    ElMessage.error('视频加载失败')
  }
}

const isTerminalStatus = (s) => s === 3 || s === 4

const connectSSE = () => {
  if (eventSource) return
  eventSource = new EventSource(`/api/sse/subscribe/${route.params.id}`)

  eventSource.addEventListener('status-change', async (e) => {
    const data = JSON.parse(e.data)
    if (data.status === 3) {
      ElNotification({ title: '转码完成', message: '视频已就绪，可以观看了', type: 'success' })
    } else if (data.status === 4) {
      ElNotification({ title: '转码失败', message: '视频转码失败，请稍后重试', type: 'error' })
    }
    await loadVideo()
    if (isTerminalStatus(data.status)) {
      eventSource?.close()
      eventSource = null
    }
  })

  eventSource.onerror = () => {}
}

onMounted(async () => {
  try {
    await loadVideo()
    if (video.value && !isTerminalStatus(video.value.status)) {
      connectSSE()
    }
  } catch {
    ElMessage.error('视频加载失败')
  } finally {
    loading.value = false
  }
})

onUnmounted(() => {
  eventSource?.close()
  eventSource = null
})
</script>

<style scoped>
.video-player {
  min-height: 360px;
  background: #1a1a2e;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #909399;
  overflow: hidden;
}

.video-tag {
  width: 100%;
  height: 100%;
  object-fit: contain;
  outline: none;
}

.video-title {
  margin: 16px 0 8px;
  font-size: 20px;
}

.video-desc {
  color: #606266;
  margin-bottom: 16px;
}

.ai-summary {
  margin-top: 20px;
  padding: 16px;
  background: #f0f9ff;
  border-radius: 8px;
  border-left: 4px solid #409eff;
}

.ai-summary h4 {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  color: #409eff;
}

.ai-tags {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.loading {
  text-align: center;
  padding: 80px 0;
  color: #909399;
}
</style>
