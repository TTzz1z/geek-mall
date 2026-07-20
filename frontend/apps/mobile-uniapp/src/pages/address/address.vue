<script setup lang="ts">
import { reactive, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { isLoggedIn, mobileApi } from '../../api'

const addresses = ref<any[]>([])
const loading = ref(true)
const error = ref('')
const showForm = ref(false)
const form = reactive({
  receiver: '',
  mobile: '',
  regionCode: '310000',
  regionName: '上海市',
  detail: '',
  isDefault: true
})

async function load() {
  loading.value = true
  error.value = ''
  if (!isLoggedIn()) { loading.value = false; return }
  try {
    addresses.value = await mobileApi.request('/api/v1/user/addresses')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function create() {
  try {
    await mobileApi.request('/api/v1/user/addresses', { method: 'POST', body: { ...form } })
    showForm.value = false
    Object.assign(form, { receiver: '', mobile: '', detail: '' })
    await load()
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '保存失败', icon: 'none' })
  }
}

async function setDefault(id: number) {
  await mobileApi.request(`/api/v1/user/addresses/${id}/default`, { method: 'POST' })
  await load()
}

onShow(load)
</script>

<template>
  <view class="page">
    <view v-if="!isLoggedIn()" class="empty">登录后管理地址（/api/v1/user/addresses）</view>
    <view v-else-if="loading" class="empty">加载中…</view>
    <view v-else-if="error" class="empty warn">{{ error }}</view>
    <template v-else>
      <view class="card">
        <view class="btn-primary" style="margin:0" @click="showForm = !showForm">{{ showForm ? '收起表单' : '新增地址' }}</view>
        <view v-if="showForm" class="form">
          <input v-model="form.receiver" class="input" placeholder="收货人" />
          <input v-model="form.mobile" class="input" placeholder="手机号" />
          <input v-model="form.regionCode" class="input" placeholder="地区编码" />
          <input v-model="form.regionName" class="input" placeholder="地区名称" />
          <input v-model="form.detail" class="input" placeholder="详细地址" />
          <view class="btn-primary" @click="create">保存</view>
        </view>
      </view>
      <view v-if="!addresses.length" class="empty">暂无收货地址</view>
      <view v-else v-for="a in addresses" :key="a.id" class="card">
        <text class="h">{{ a.receiver }} {{ a.mobile }} <text v-if="a.isDefault" class="tag">默认</text></text>
        <text class="muted" style="display:block;margin-top:8rpx">{{ a.regionName }} {{ a.detail }}</text>
        <view v-if="!a.isDefault" class="link" @click="setDefault(a.id)">设为默认</view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.h { font-weight: 700; }
.tag { color: #ff6a2b; font-size: 22rpx; margin-left: 8rpx; }
.input { margin-top: 12rpx; min-height: 72rpx; padding: 0 16rpx; background: #f0f2f4; border-radius: 12rpx; }
.form { margin-top: 16rpx; }
.link { margin-top: 12rpx; color: #ff6a2b; }
</style>
