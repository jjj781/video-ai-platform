import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Home',
    component: () => import('@/views/Home.vue')
  },
  {
    path: '/upload',
    name: 'Upload',
    component: () => import('@/views/Upload.vue')
  },
  {
    path: '/videos',
    name: 'Videos',
    component: () => import('@/views/Videos.vue')
  },
  {
    path: '/video/:id',
    name: 'VideoDetail',
    component: () => import('@/views/VideoDetail.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
