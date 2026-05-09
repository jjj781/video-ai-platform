<template>
  <el-card class="ai-chat">
    <template #header>
      <div class="chat-header">
        <span><el-icon><ChatDotRound /></el-icon> AI智能问答</span>
      </div>
    </template>

    <div class="messages" ref="messagesRef">
      <div v-if="messages.length === 0" class="empty-chat">
        <el-icon :size="40" color="#dcdfe6"><ChatDotRound /></el-icon>
        <p>向AI助手提问关于这个视频的问题</p>
      </div>

      <div v-for="(msg, i) in messages" :key="i" :class="['message', msg.role]">
        <div
          class="message-content"
          :class="{ 'md-content': msg.rendered }"
          v-html="msg.rendered ? renderMarkdown(msg.content) : escapeHtml(msg.content)"
        ></div>
      </div>

      <div v-if="loading" class="message assistant">
        <div class="message-content typing">
          <span class="dot"></span>
          <span class="dot"></span>
          <span class="dot"></span>
        </div>
      </div>
    </div>

    <div class="input-area">
      <el-input
        v-model="input"
        placeholder="输入你的问题..."
        :rows="2"
        type="textarea"
        resize="none"
        @keydown.enter.ctrl="sendMessage"
        :disabled="loading"
      />
      <el-button type="primary" @click="sendMessage" :loading="loading" :disabled="!input.trim()">
        <el-icon><Promotion /></el-icon>
        发送
      </el-button>
    </div>
    <div class="input-tip">Ctrl + Enter 发送</div>
  </el-card>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import { marked } from 'marked'

// 配置marked：链接在新标签打开
marked.setOptions({ breaks: true, gfm: true })

const props = defineProps({
  videoId: { type: Number, default: null }
})

const messages = ref([])
const input = ref('')
const loading = ref(false)
const conversationId = ref(null)
const messagesRef = ref(null)

const escapeHtml = (text) => {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

const renderMarkdown = (text) => {
  return marked.parse(text)
}

const sendMessage = async () => {
  const text = input.value.trim()
  if (!text || loading.value) return

  messages.value.push({ role: 'user', content: text, rendered: true })
  input.value = ''
  loading.value = true

  await nextTick()
  scrollToBottom()

  // 添加空的assistant消息槽位，用于流式填充
  const assistantIdx = messages.value.length
  messages.value.push({ role: 'assistant', content: '', rendered: false })

  try {
    const response = await fetch('/api/ai/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        conversationId: conversationId.value,
        videoId: props.videoId,
        message: text
      })
    })

    if (!response.ok) throw new Error('HTTP ' + response.status)

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let currentEvent = 'token'

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop()

      for (const line of lines) {
        if (line.startsWith('event:')) {
          currentEvent = line.substring(6).trim()
        } else if (line.startsWith('data:')) {
          const payload = line.substring(5)
          if (currentEvent === 'token' && payload) {
            messages.value[assistantIdx].content += payload
            await nextTick()
            scrollToBottom()
          } else if (currentEvent === 'meta') {
            try {
              const meta = JSON.parse(payload.trim())
              if (meta.conversationId) {
                conversationId.value = meta.conversationId
              }
            } catch {}
          } else if (currentEvent === 'error') {
            if (!messages.value[assistantIdx].content) {
              messages.value[assistantIdx].content = payload.trim() || 'AI服务暂时不可用'
            }
          } else if (currentEvent === 'done') {
            messages.value[assistantIdx].rendered = true
          }
        }
        if (line === '') {
          currentEvent = 'token'
        }
      }
    }

  } catch {
    if (!messages.value[assistantIdx].content) {
      messages.value[assistantIdx].content = '抱歉，AI服务暂时不可用，请稍后重试。'
    }
  } finally {
    loading.value = false
    messages.value[assistantIdx].rendered = true
    await nextTick()
    scrollToBottom()
  }
}

const scrollToBottom = () => {
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  }
}
</script>

<style scoped>
.ai-chat {
  position: sticky;
  top: 80px;
}

.chat-header {
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
}

.messages {
  height: 400px;
  overflow-y: auto;
  padding: 12px 0;
}

.empty-chat {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #c0c4cc;
}

.empty-chat p {
  margin-top: 12px;
  font-size: 14px;
}

.message {
  margin-bottom: 12px;
  display: flex;
}

.message.user {
  justify-content: flex-end;
}

.message.user .message-content {
  background: #409eff;
  color: white;
  border-radius: 12px 12px 4px 12px;
}

.message.assistant .message-content {
  background: #f4f4f5;
  color: #303133;
  border-radius: 12px 12px 12px 4px;
}

.message-content {
  max-width: 85%;
  padding: 10px 14px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
  white-space: pre-wrap;
}

.typing {
  display: flex;
  gap: 4px;
  align-items: center;
}

.dot {
  width: 6px;
  height: 6px;
  background: #c0c4cc;
  border-radius: 50%;
  animation: bounce 1.4s infinite;
}

.dot:nth-child(2) { animation-delay: 0.2s; }
.dot:nth-child(3) { animation-delay: 0.4s; }

@keyframes bounce {
  0%, 80%, 100% { transform: translateY(0); }
  40% { transform: translateY(-8px); }
}

.input-area {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.input-area .el-button {
  align-self: flex-end;
}

.input-tip {
  font-size: 11px;
  color: #c0c4cc;
  margin-top: 4px;
}

/* Markdown 渲染样式 */
.md-content {
  white-space: normal;
}

.md-content :deep(p) {
  margin: 0 0 8px 0;
}

.md-content :deep(p:last-child) {
  margin-bottom: 0;
}

.md-content :deep(ul),
.md-content :deep(ol) {
  margin: 4px 0;
  padding-left: 20px;
}

.md-content :deep(li) {
  margin-bottom: 2px;
}

.md-content :deep(code) {
  background: rgba(0, 0, 0, 0.06);
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 13px;
  font-family: 'Courier New', monospace;
}

.md-content :deep(pre) {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
  margin: 8px 0;
  font-size: 13px;
  line-height: 1.5;
}

.md-content :deep(pre code) {
  background: transparent;
  padding: 0;
  color: inherit;
  font-size: inherit;
}

.md-content :deep(blockquote) {
  border-left: 3px solid #409eff;
  margin: 8px 0;
  padding: 4px 12px;
  color: #606266;
  background: rgba(64, 158, 255, 0.05);
}

.md-content :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
  width: 100%;
}

.md-content :deep(th),
.md-content :deep(td) {
  border: 1px solid #dcdfe6;
  padding: 6px 10px;
  text-align: left;
  font-size: 13px;
}

.md-content :deep(th) {
  background: #f5f7fa;
  font-weight: 600;
}

.md-content :deep(strong) {
  font-weight: 600;
}

.md-content :deep(a) {
  color: #409eff;
}

.md-content :deep(hr) {
  border: none;
  border-top: 1px solid #dcdfe6;
  margin: 12px 0;
}
</style>
