<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import type { AuthPrincipal, TokenPair } from '@geek-mall/api-types'
import { clearTokens, isLoggedIn, mobileApi, setTokens } from '../../api'

const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const principal = ref<AuthPrincipal | null>(null)

const menus = [
  { title: '我的订单', url: '/pages/orders/orders' },
  { title: '收藏', url: '/pages/favorites/favorites' },
  { title: '足迹', url: '/pages/history/history' },
  { title: '优惠券', url: '/pages/coupons/coupons' },
  { title: '会员', url: '/pages/member/member' },
  { title: '消息', url: '/pages/messages/messages' },
  { title: '收货地址', url: '/pages/address/address' },
  { title: '售后', url: '/pages/aftersales/aftersales' }
]

async function refreshSession() {
  if (!isLoggedIn()) {
    principal.value = null
    return
  }
  try {
    principal.value = await mobileApi.request<AuthPrincipal>('/api/v1/auth/session')
  } catch {
    principal.value = null
  }
}

async function login() {
  loading.value = true
  error.value = ''
  try {
    if (!username.value.trim() || !password.value) throw new Error('请输入用户名和密码')
    const tokens = await mobileApi.request<TokenPair>('/api/v1/auth/login/password', {
      method: 'POST',
      body: { username: username.value.trim(), password: password.value }
    })
    setTokens(tokens)
    principal.value = tokens.principal
    password.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败'
  } finally {
    loading.value = false
  }
}

function logout() {
  clearTokens()
  principal.value = null
}

function go(url: string) {
  uni.navigateTo({ url })
}

onShow(refreshSession)
</script>

<template>
  <view class="page user-page">
    <view v-if="principal" class="profile">
      <view class="avatar">{{ (principal.username || 'G').slice(0, 1).toUpperCase() }}</view>
      <view class="profile-meta">
        <text class="name">{{ principal.username }}</text>
        <text class="muted">极客账号 · ID {{ principal.userId }}</text>
      </view>
    </view>

    <view v-if="principal" class="card menu-card">
      <view v-for="m in menus" :key="m.url" class="menu-row" @click="go(m.url)">
        <text>{{ m.title }}</text>
        <text class="arrow">›</text>
      </view>
      <view class="btn-ghost" @click="logout">退出登录</view>
    </view>

    <view v-else class="card login-card">
      <text class="h">登录极客账号</text>
      <input v-model="username" class="input" placeholder="用户名" />
      <input v-model="password" class="input" password placeholder="密码" />
      <text v-if="error" class="warn" style="display:block;margin-top:12rpx">{{ error }}</text>
      <view class="btn-primary brand" :class="{ disabled: loading }" @click="login">{{ loading ? '登录中…' : '登录' }}</view>
    </view>
  </view>
</template>

<style scoped>
.user-page { padding-top: 8rpx; }
.profile {
  margin: 20rpx 24rpx 8rpx;
  padding: 36rpx 28rpx;
  display: flex;
  align-items: center;
  border-radius: 28rpx;
  background: linear-gradient(135deg, #1b1e21, #31363d);
  color: #fff;
}
.avatar {
  width: 96rpx;
  height: 96rpx;
  margin-right: 22rpx;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #ff6a2b;
  font-size: 40rpx;
  font-weight: 800;
}
.profile-meta { display: flex; flex-direction: column; gap: 8rpx; }
.name { font-size: 36rpx; font-weight: 800; }
.profile .muted { color: rgba(255, 255, 255, 0.65); }
.h { font-size: 40rpx; font-weight: 800; }
.input {
  margin-top: 16rpx;
  padding: 0 24rpx;
  min-height: 84rpx;
  background: #f3f5f7;
  border-radius: 16rpx;
}
.menu-card { padding: 8rpx 8rpx 24rpx; }
.menu-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 92rpx;
  padding: 0 20rpx;
  border-bottom: 1px solid #eef1f3;
  font-size: 28rpx;
  font-weight: 600;
}
.menu-row:last-of-type { border-bottom: 0; }
.arrow { color: #b0b6be; font-size: 36rpx; }
.btn-ghost {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 84rpx;
  margin: 20rpx 16rpx 0;
  border-radius: 999rpx;
  background: #f3f5f7;
  color: #17191c;
  font-weight: 650;
}
</style>
