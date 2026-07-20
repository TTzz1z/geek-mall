<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '../session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const title = computed(() => String(route.meta.title ?? '工作台'))

const cards = [
  { label: '今日待办', value: '12', hint: '审核、发货与客服队列' },
  { label: '需关注异常', value: '3', hint: '库存预警与售后仲裁' },
  { label: '本周完成率', value: '92%', hint: '较昨日保持稳定' }
]

const rows = [
  { name: '工程基座', owner: '平台研发', status: '已完成' },
  { name: '权限策略', owner: '系统管理员', status: '验证中' },
  { name: '租户隔离', owner: '商家安全', status: '已通过' }
]

function goProducts() {
  router.push('/products')
}
</script>

<template>
  <section class="admin-home">
    <header class="home-hero-band">
      <div>
        <p>GEEK MALL CONSOLE</p>
        <h2>{{ title }}</h2>
        <span>你好，{{ session.principal?.username }}。从这里进入审核、授权、履约与首页装修。</span>
      </div>
      <button type="button" class="pill-cta" @click="goProducts">进入商品中心</button>
    </header>

    <div class="admin-metrics">
      <article v-for="card in cards" :key="card.label">
        <small>{{ card.label }}</small>
        <strong>{{ card.value }}</strong>
        <span>{{ card.hint }}</span>
      </article>
    </div>

    <article class="admin-panel">
      <header>
        <div>
          <p>WORKSTREAM</p>
          <h2>经营节奏</h2>
          <span>已接入真实路由与角色权限，按菜单继续处理业务。</span>
        </div>
        <el-button type="primary" @click="goProducts">创建 / 审核商品</el-button>
      </header>
      <el-table :data="rows" class="home-table">
        <el-table-column prop="name" label="事项" />
        <el-table-column prop="owner" label="负责人" />
        <el-table-column prop="status" label="状态" width="120" />
      </el-table>
    </article>
  </section>
</template>
