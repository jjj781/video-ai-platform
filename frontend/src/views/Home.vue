<template>
  <div class="home">
    <div class="hero">
      <h1>视频AI分析平台</h1>
      <p>基于大模型的全链路视频内容理解与分析</p>
      <div class="actions">
        <el-button type="primary" size="large" @click="router.push('/upload')">
          <el-icon><Upload /></el-icon>
          上传视频
        </el-button>
        <el-button size="large" @click="router.push('/videos')">
          <el-icon><Film /></el-icon>
          浏览视频
        </el-button>
      </div>
    </div>

    <div class="features">
      <el-row :gutter="24">
        <el-col :span="8" v-for="feature in features" :key="feature.title">
          <el-card class="feature-card" shadow="hover">
            <el-icon :size="40" :color="feature.color">
              <component :is="feature.icon" />
            </el-icon>
            <h3>{{ feature.title }}</h3>
            <p>{{ feature.desc }}</p>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()

const features = [
  { icon: 'Upload', title: '分片上传与断点续传', desc: '5MB分片并发上传，支持断点续传，大文件上传稳定可靠', color: '#409eff' },
  { icon: 'Lightning', title: '全链路异步化', desc: 'RocketMQ异步解耦，上传响应50ms以内，吞吐量大幅提升', color: '#e6a23c' },
  { icon: 'Lock', title: '双重去重防并发', desc: 'MD5+Redisson分布式锁拦截重复提交，数据库状态机防重复消费', color: '#67c23a' },
  { icon: 'Cpu', title: 'AI智能问答', desc: '接入硅基流动大模型，Function Calling精准查询视频数据', color: '#f56c6c' },
  { icon: 'Shield', title: '限流与容错', desc: 'Redis令牌桶限流，指数退避重试，保障系统高可用', color: '#909399' },
  { icon: 'Connection', title: '存储直传架构', desc: 'STS临时凭证直传OSS，消除业务服务器带宽瓶颈', color: '#b37feb' },
]
</script>

<style scoped>
.home {
  max-width: 900px;
  margin: 0 auto;
}

.hero {
  text-align: center;
  padding: 60px 0 40px;
}

.hero h1 {
  font-size: 36px;
  color: #303133;
  margin-bottom: 12px;
}

.hero p {
  font-size: 16px;
  color: #606266;
  margin-bottom: 32px;
}

.actions {
  display: flex;
  gap: 16px;
  justify-content: center;
}

.features {
  margin-top: 20px;
}

.feature-card {
  text-align: center;
  padding: 24px 16px;
  margin-bottom: 16px;
  transition: transform 0.2s;
}

.feature-card:hover {
  transform: translateY(-4px);
}

.feature-card h3 {
  margin: 16px 0 8px;
  font-size: 16px;
  color: #303133;
}

.feature-card p {
  font-size: 13px;
  color: #909399;
  line-height: 1.6;
}
</style>
