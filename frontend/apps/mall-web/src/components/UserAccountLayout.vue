<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { accountNavGroups, accountTitle } from '../account-nav'
import { userApi } from '../api/user'

const route = useRoute()
const title = computed(() => accountTitle(route.path))
const crumbs = computed(() => [
  { to: '/', label: '首页' },
  { to: '/user', label: '个人中心' },
  ...(route.path === '/user' ? [] : [{ to: route.path, label: title.value }])
])

const sideValues = ref({ points: null as number | null, coupons: null as number | null, messages: null as number | null })

async function loadSideValues() {
  try {
    const [member, coupons, messages] = await Promise.all([
      userApi.member().catch(() => null),
      userApi.myCoupons().catch(() => []),
      userApi.messages().catch(() => [])
    ])
    sideValues.value = {
      points: member?.pointBalance ?? 0,
      coupons: coupons.length,
      messages: messages.length
    }
  } catch {
    /* guest / unauth pages still render shell */
  }
}

function isActive(match?: RegExp, to?: string) {
  if (match) return match.test(route.path)
  return route.path === to
}

function sideValue(key?: 'points' | 'coupons' | 'messages') {
  if (!key) return null
  const v = sideValues.value[key]
  if (v == null) return null
  if (key === 'points') return String(v)
  return String(v)
}

onMounted(loadSideValues)
watch(() => route.path, loadSideValues)
</script>

<template>
  <div class="account-shell">
    <nav class="account-crumb" aria-label="面包屑">
      <template v-for="(c, i) in crumbs" :key="c.to + c.label">
        <RouterLink v-if="i < crumbs.length - 1" :to="c.to">{{ c.label }}</RouterLink>
        <span v-else>{{ c.label }}</span>
        <i v-if="i < crumbs.length - 1">/</i>
      </template>
    </nav>

    <div class="account-frame">
      <aside class="account-side">
        <section v-for="group in accountNavGroups" :key="group.title" class="account-side-group">
          <p>{{ group.title }}</p>
          <RouterLink
            v-for="item in group.items"
            :key="item.to"
            :to="item.to"
            :class="{ active: isActive(item.match, item.to) }"
          >
            <component :is="item.icon" :size="18" weight="regular" />
            <span>{{ item.label }}</span>
            <em v-if="sideValue(item.valueKey) != null">{{ sideValue(item.valueKey) }}</em>
          </RouterLink>
        </section>
      </aside>

      <div class="account-main">
        <RouterView />
      </div>
    </div>
  </div>
</template>
