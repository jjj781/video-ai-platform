<template>
  <div class="videos-page">
    <div class="page-header">
      <h2>视频管理</h2>
      <el-input v-model="keyword" placeholder="搜索视频..." clearable style="width: 300px"
        @clear="loadVideos" @keyup.enter="loadVideos">
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
    </div>

    <el-row :gutter="20">
      <el-col :span="6" v-for="video in videos" :key="video.id">
        <el-card class="video-card" shadow="hover" @click="router.push(`/video/${video.id}`)">
          <div class="video-cover">
            <el-icon :size="48" color="#c0c4cc"><Film /></el-icon>
            <el-tag class="status-tag" :type="statusType(video.status)" size="small">
              {{ statusText(video.status) }}
            </el-tag>
          </div>
          <div class="video-info">
            <h4>{{ video.title }}</h4>
            <p class="video-meta">
              <span>{{ formatSize(video.fileSize) }}</span>
              <span>{{ formatDate(video.createdAt) }}</span>
            </p>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <div v-if="videos.length === 0 && !loading" class="empty">
      <el-empty description="暂无视频">
        <el-button type="primary" @click="router.push('/upload')">上传视频</el-button>
      </el-empty>
    </div>

    <div class="pagination" v-if="total > pageSize">
      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next"
        @current-change="loadVideos"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getVideoList } from '@/api/video'

const router = useRouter()
const videos = ref([])
const keyword = ref('')
const currentPage = ref(1)
const pageSize = 12
const total = ref(0)
const loading = ref(false)

const statusMap = {
  0: { text: '上传中', type: 'info' },
  1: { text: '待转码', type: 'warning' },
  2: { text: '转码中', type: 'warning' },
  3: { text: '已就绪', type: 'success' },
  4: { text: '转码失败', type: 'danger' }
}

const statusText = (s) => statusMap[s]?.text || '未知'
const statusType = (s) => statusMap[s]?.type || 'info'

const loadVideos = async () => {
  loading.value = true
  try {
    const res = await getVideoList({ page: currentPage.value, size: pageSize, keyword: keyword.value })
    videos.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

const formatSize = (bytes) => {
  if (!bytes) return '-'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

const formatDate = (d) => d ? new Date(d).toLocaleDateString('zh-CN') : '-'

onMounted(loadVideos)
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.video-card {
  cursor: pointer;
  margin-bottom: 20px;
  transition: transform 0.2s;
}

.video-card:hover {
  transform: translateY(-4px);
}

.video-cover {
  height: 120px;
  background: #f5f7fa;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  border-radius: 4px;
}

.status-tag {
  position: absolute;
  top: 8px;
  right: 8px;
}

.video-info h4 {
  margin: 12px 0 8px;
  font-size: 14px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.video-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #909399;
}

.empty {
  padding: 60px 0;
}

.pagination {
  margin-top: 24px;
  display: flex;
  justify-content: center;
}
</style>
