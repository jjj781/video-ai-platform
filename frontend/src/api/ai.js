import request from './index'

/**
 * AI对话
 */
export function chat(data) {
  return request.post('/ai/chat', data)
}
