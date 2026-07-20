<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { PhMapPin as MapPin } from '@phosphor-icons/vue'
import { addressApi, type UserAddress } from '../api/phase4'

const emptyForm = () => ({
  receiver: '',
  mobile: '',
  regionCode: '310000',
  regionName: '上海市',
  detail: '',
  isDefault: false
})

const form = reactive(emptyForm())
const addresses = ref<UserAddress[]>([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const showForm = ref(false)
const editingId = ref<number | null>(null)

const formTitle = computed(() => (editingId.value ? '编辑收货地址' : '新增收货地址'))
const submitLabel = computed(() => (editingId.value ? '保存修改' : '保存地址'))

function isDefaultFlag(value: unknown) {
  return value === true || value === 1 || value === '1'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    addresses.value = await addressApi.list()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function resetForm() {
  Object.assign(form, emptyForm())
  editingId.value = null
}

function openCreate() {
  resetForm()
  form.isDefault = addresses.value.length === 0
  showForm.value = true
}

function openEdit(row: UserAddress) {
  editingId.value = row.id
  Object.assign(form, {
    receiver: row.receiver || '',
    mobile: row.mobile || '',
    regionCode: row.regionCode || '310000',
    regionName: row.regionName || '',
    detail: row.detail || '',
    isDefault: isDefaultFlag(row.isDefault)
  })
  showForm.value = true
}

function closeForm() {
  showForm.value = false
  resetForm()
}

async function save() {
  if (saving.value) return
  saving.value = true
  error.value = ''
  try {
    const payload = {
      receiver: form.receiver.trim(),
      mobile: form.mobile.trim(),
      regionCode: form.regionCode.trim(),
      regionName: form.regionName.trim(),
      detail: form.detail.trim(),
      isDefault: !!form.isDefault
    }
    if (editingId.value) {
      await addressApi.update(editingId.value, payload)
    } else {
      await addressApi.create(payload)
    }
    closeForm()
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    saving.value = false
  }
}

async function setDefault(id: number) {
  error.value = ''
  try {
    await addressApi.setDefault(id)
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '设置默认失败'
  }
}

async function remove(row: UserAddress) {
  if (!window.confirm(`确认删除「${row.receiver}」的收货地址？`)) return
  error.value = ''
  try {
    await addressApi.remove(row.id)
    if (editingId.value === row.id) closeForm()
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '删除失败'
  }
}

onMounted(load)
</script>

<template>
  <div class="account-panel">
    <header class="panel-head">
      <div>
        <h1>我的收货地址</h1>
        <p class="panel-tip">提示：结算时可直接使用默认地址，也可在此预先维护。</p>
      </div>
      <button type="button" class="account-cta ghost" @click="showForm ? closeForm() : openCreate()">
        {{ showForm ? '收起' : '新增地址' }}
      </button>
    </header>

    <div v-if="loading" class="account-empty">加载中…</div>
    <div v-else-if="error && !addresses.length" class="account-empty">{{ error }}</div>
    <template v-else>
      <p v-if="error" class="panel-tip" style="color:#c2410c;margin-bottom:12px;">{{ error }}</p>

      <form v-if="showForm" class="user-form account-form" @submit.prevent="save">
        <p class="panel-tip" style="grid-column:1/-1;margin:0;">{{ formTitle }}</p>
        <label>收货人<input v-model="form.receiver" required maxlength="32" /></label>
        <label>手机号<input v-model="form.mobile" required maxlength="20" /></label>
        <label>地区编码<input v-model="form.regionCode" required maxlength="16" /></label>
        <label>地区名称<input v-model="form.regionName" required maxlength="64" /></label>
        <label class="wide">详细地址<input v-model="form.detail" required maxlength="200" /></label>
        <label class="wide address-default-check">
          <input v-model="form.isDefault" type="checkbox" />
          设为默认收货地址
        </label>
        <div class="address-form-actions">
          <button type="button" class="account-cta ghost" @click="closeForm">取消</button>
          <button type="submit" class="account-cta" :disabled="saving">
            {{ saving ? '保存中…' : submitLabel }}
          </button>
        </div>
      </form>

      <div v-if="!addresses.length" class="account-empty soft">
        <MapPin :size="48" weight="thin" />
        <h3>暂无收货地址</h3>
        <p>添加后可在结算时一键选用</p>
        <button type="button" class="account-cta" @click="openCreate">新增地址</button>
      </div>

      <div v-else class="address-cards">
        <article v-for="a in addresses" :key="a.id">
          <header>
            <strong>{{ a.receiver }} {{ a.mobile }}</strong>
            <em v-if="isDefaultFlag(a.isDefault)">默认</em>
          </header>
          <p>{{ a.regionName }} {{ a.detail }}</p>
          <footer class="address-card-actions">
            <button type="button" class="text-link" @click="openEdit(a)">编辑</button>
            <button type="button" class="text-link danger" @click="remove(a)">删除</button>
            <button
              v-if="!isDefaultFlag(a.isDefault)"
              type="button"
              class="text-link"
              @click="setDefault(a.id)"
            >
              设为默认
            </button>
          </footer>
        </article>
      </div>
    </template>
  </div>
</template>
