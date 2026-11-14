<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { PhPaperPlaneRight as Send, PhSparkle as Sparkle, PhWarning as Warning } from '@phosphor-icons/vue'
import { mallApi } from '../api'
import { productCover } from '../catalog/covers'

type ChatItem = {
  role: 'user' | 'assistant' | 'system'
  content: string
  agent?: string
  tools?: Array<Record<string, unknown>>
  citations?: Array<Record<string, unknown>>
  confirmations?: Array<Record<string, unknown>>
  products?: ProductCard[]
  degraded?: boolean
  state?: 'streaming' | 'completed' | 'incomplete' | 'cancelled'
  requestId?: string
  requestText?: string
  turnId?: string
}

type ProductCard = {
  id: number
  title: string
  subtitle?: string
  coverUrl: string
  minPrice?: number
  shopName?: string
  brandName?: string
  sellerLabel?: string
}

const input = ref('预算8000左右要一台办公笔记本')
const loading = ref(false)
const conversationNo = ref('')
const items = ref<ChatItem[]>([
  { role: 'system', content: 'AI 可推荐商品、对比参数与协助选购；下单与支付请在商城页面完成。' }
])
const streamLog = ref<string[]>([])
const listEl = ref<HTMLElement | null>(null)

const apiBase = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

function num(v: unknown): number | undefined {
  const n = Number(v)
  return Number.isFinite(n) ? n : undefined
}

function asRecord(v: unknown): Record<string, unknown> | null {
  return v && typeof v === 'object' ? (v as Record<string, unknown>) : null
}

function toProductCard(raw: Record<string, unknown>): ProductCard | null {
  const id = num(raw.id ?? raw.spuId)
  if (!id) return null
  const title = String(raw.title || raw.name || `商品 #${id}`)
  return {
    id,
    title,
    subtitle: raw.subtitle ? String(raw.subtitle) : undefined,
    coverUrl: productCover(id, raw.coverUrl ? String(raw.coverUrl) : undefined),
    minPrice: num(raw.minPrice ?? raw.price ?? raw.salePrice),
    shopName: raw.shopName ? String(raw.shopName) : undefined,
    brandName: raw.brandName ? String(raw.brandName) : undefined,
    sellerLabel: raw.sellerLabel ? String(raw.sellerLabel) : undefined
  }
}

function collectFromList(list: unknown, out: Map<number, ProductCard>) {
  if (!Array.isArray(list)) return
  for (const row of list) {
    const rec = asRecord(row)
    if (!rec) continue
    const detail = asRecord(rec.detail)
    const card = toProductCard(detail || rec)
    if (card) out.set(card.id, card)
  }
}

/** Only commerce tools may feed product cards — never RAG citations (those pollute relevance). */
const PRODUCT_CARD_TOOLS = new Set([
  'search_products',
  'compare_products',
  'get_product_detail'
])

function extractProducts(tools?: Array<Record<string, unknown>>) {
  const out = new Map<number, ProductCard>()
  for (const tool of tools || []) {
    const name = String(tool.toolName || '')
    if (!PRODUCT_CARD_TOOLS.has(name)) continue
    const result = asRecord(tool.result) || tool
    if (name === 'search_products') {
      const data = asRecord(result.data)
      if (data) collectFromList(data.items, out)
      continue
    }
    if (name === 'compare_products') {
      collectFromList(result.items, out)
      continue
    }
    if (name === 'get_product_detail') {
      const data = asRecord(result.data)
      const card = data ? toProductCard(data) : null
      if (card) out.set(card.id, card)
    }
  }
  return [...out.values()].slice(0, 6)
}

function refreshProducts(assistant: ChatItem) {
  assistant.products = extractProducts(assistant.tools)
}

async function ensureLogin() {
  try {
    await mallApi.request('/api/v1/auth/session')
  } catch {
    /* token store refresh may fail */
  }
}

async function ensureConversation() {
  if (conversationNo.value) return
  const data = await mallApi.request<{ conversationNo: string }>('/api/v1/ai/conversations', {
    method: 'POST',
    body: JSON.stringify({ scene: 'GUIDE', context: { page: '/ai-shopping' } })
  })
  conversationNo.value = data.conversationNo
}

function parseSseBlocks(buffer: string): { events: Array<{ event: string; data: unknown }>; rest: string } {
  const parts = buffer.split('\n\n')
  const rest = parts.pop() ?? ''
  const events: Array<{ event: string; data: unknown }> = []
  for (const block of parts) {
    if (!block.trim()) continue
    let event = 'message'
    const dataLines: string[] = []
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
    }
    const raw = dataLines.join('\n')
    let data: unknown = raw
    try {
      data = JSON.parse(raw)
    } catch {
      /* keep raw */
    }
    events.push({ event, data })
  }
  return { events, rest }
}

let activeStream: AbortController | undefined
let activeTurnId = ''

function cancelStream() {
  if (!activeStream) return
  if (activeTurnId) {
    void mallApi.request(`/api/v1/ai/conversations/${conversationNo.value}/turns/${activeTurnId}`,
      { method: 'DELETE' }).catch(() => {})
  }
  activeStream.abort()
}

onUnmounted(cancelStream)

async function abandon(assistant: ChatItem) {
  if (!assistant.turnId) return
  try {
    const result = await mallApi.request<{ status: string; content?: string }>(
      `/api/v1/ai/conversations/${conversationNo.value}/turns/${assistant.turnId}`, { method: 'DELETE' })
    assistant.state = result.status === 'COMPLETED' ? 'completed' : 'cancelled'
    if (result.content) assistant.content = result.content
  } catch (error) {
    items.value.push({ role: 'system', content: error instanceof Error ? error.message : '暂时无法放弃请求，请重试' })
  }
}

async function send(retry?: ChatItem) {
  const text = retry?.requestText ?? input.value.trim()
  if (!text || loading.value) return
  loading.value = true
  const stream = new AbortController()
  activeStream = stream
  if (!retry) items.value.push({ role: 'user', content: text })
  input.value = ''
  streamLog.value = []

  const assistant: ChatItem = retry ?? {
    role: 'assistant',
    content: '',
    tools: [],
    citations: [],
    confirmations: [],
    products: [],
    requestId: crypto.randomUUID(),
    requestText: text
  }
  assistant.state = 'streaming'
  assistant.content = ''
  if (!retry) items.value.push(assistant)
  let completed = false

  try {
    await ensureConversation()
    const token = localStorage.getItem('geek.accessToken')
    const response = await fetch(
      `${apiBase}/api/v1/ai/conversations/${conversationNo.value}/stream`,
      {
        method: 'POST',
        signal: stream.signal,
        body: JSON.stringify({ message: text, requestId: assistant.requestId }),
        headers: {
          Accept: 'text/event-stream',
          'Content-Type': 'application/json',
          'X-Request-Id': assistant.requestId!,
          'X-Client-Type': 'WEB',
          ...(token ? { Authorization: `Bearer ${token}` } : {})
        }
      }
    )
    if (!response.ok || !response.body) {
      throw new Error(`SSE 失败 HTTP ${response.status}`)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      const parsed = parseSseBlocks(buf)
      buf = parsed.rest
      for (const e of parsed.events) {
        streamLog.value.push(e.event)
        const data = (e.data && typeof e.data === 'object' ? e.data : {}) as Record<string, unknown>
        if (e.event === 'turn.accepted') {
          activeTurnId = String(data.turnId ?? '')
          assistant.turnId = activeTurnId
        } else if (e.event === 'turn.cancelled') {
          assistant.state = 'cancelled'
          throw new Error(String(data.message ?? '生成已取消'))
        } else if (e.event === 'turn.running') {
          assistant.turnId = String(data.turnId ?? '')
          throw new Error('原请求仍在执行，请稍后重试获取结果')
        } else if (e.event === 'message.start') {
          assistant.agent = String(data.agent ?? '')
        } else if (e.event === 'message.delta') {
          assistant.content += String(data.delta ?? '')
        } else if (e.event === 'message.reset') {
          assistant.content = String(data.content ?? '')
          assistant.degraded = Boolean(data.degraded)
        } else if (e.event === 'tool.call' || e.event === 'tool.result') {
          assistant.tools = [...(assistant.tools ?? []), data]
          refreshProducts(assistant)
        } else if (e.event === 'citation') {
          assistant.citations = [...(assistant.citations ?? []), data]
          refreshProducts(assistant)
        } else if (e.event === 'confirmation.required') {
          assistant.confirmations = [...(assistant.confirmations ?? []), data]
        } else if (e.event === 'message.complete') {
          completed = true
          assistant.state = 'completed'
          if (data.content) assistant.content = String(data.content)
          if (data.agent) assistant.agent = String(data.agent)
          if (Array.isArray(data.tools)) assistant.tools = data.tools as Array<Record<string, unknown>>
          if (Array.isArray(data.citations)) assistant.citations = data.citations as Array<Record<string, unknown>>
          if (Array.isArray(data.confirmations)) {
            assistant.confirmations = data.confirmations as Array<Record<string, unknown>>
          }
          assistant.degraded = Boolean(data.degraded)
          refreshProducts(assistant)
        } else if (e.event === 'error') {
          throw new Error(String(data.message ?? 'AI 流式失败'))
        }
      }
      await nextTick()
      listEl.value?.scrollTo({ top: listEl.value.scrollHeight, behavior: 'smooth' })
    }
    if (!completed) throw new Error('连接已结束，回答未完成，请重试')
    if (!assistant.content) assistant.content = '（无内容）'
    refreshProducts(assistant)
  } catch (e) {
    if (completed) return
    assistant.state = stream.signal.aborted || assistant.state === 'cancelled' ? 'cancelled' : 'incomplete'
    if (!assistant.content) assistant.content = stream.signal.aborted ? '已取消生成' :
      e instanceof Error ? e.message : 'AI 请求失败，请稍后重试'
  } finally {
    activeStream = undefined
    activeTurnId = ''
    loading.value = false
    await nextTick()
    listEl.value?.scrollTo({ top: listEl.value.scrollHeight, behavior: 'smooth' })
  }
}

const pendingApprovals = reactive(new Set<string>())

async function confirm(approval: Record<string, unknown>) {
  const token = String(approval.confirmationToken)
  if (pendingApprovals.has(token)) return
  pendingApprovals.add(token)
  const path = `/api/v1/ai/tool-calls/${encodeURIComponent(token)}`
  try {
    let data = await mallApi.request<{ message: string; status: string }>(`${path}/confirm`, { method: 'POST' })
    for (let attempt = 0; data.status === 'CONFIRMED' && attempt < 8; attempt++) {
      await new Promise(resolve => window.setTimeout(resolve, 750))
      data = await mallApi.request<{ message: string; status: string }>(path)
    }
    approval.status = data.status
    approval.message = data.message
    items.value.push({ role: 'system', content: data.message })
  } catch (e) {
    items.value.push({ role: 'system', content: e instanceof Error ? e.message : '确认失败' })
  } finally {
    pendingApprovals.delete(token)
  }
}

const hasStream = computed(() => streamLog.value.length > 0)

onMounted(async () => {
  try {
    await ensureLogin()
  } catch {
    /* ignore */
  }
})
</script>

<template>
  <section class="ai-page">
    <header>
      <div>
        <p>GEEK AI</p>
        <h1>智能导购与受控助手</h1>
        <span>支持流式回答与商品推荐；下单与支付请在商城页面完成。</span>
      </div>
      <RouterLink to="/search">去搜索页</RouterLink>
    </header>

    <div class="ai-shell">
      <div ref="listEl" class="messages">
        <article v-for="(m, idx) in items" :key="idx" :class="m.role">
          <strong v-if="m.role === 'assistant'">{{ m.agent || 'ASSISTANT' }}</strong>
          <strong v-else-if="m.role === 'user'">我</strong>
          <strong v-else>系统</strong>
          <p>{{ m.content }}</p>
          <div v-if="m.state === 'incomplete' || m.state === 'cancelled'" role="status">
            {{ m.state === 'cancelled' ? '已取消，回答未完成' : '回答未完成' }}
            <button v-if="m.state === 'incomplete'" type="button" :disabled="loading" @click="send(m)">重试</button>
            <button v-if="m.state === 'incomplete' && m.turnId" type="button" :disabled="loading" @click="abandon(m)">放弃此请求</button>
          </div>
          <div v-if="m.degraded" class="badge warn"><Warning :size="14" /> 模型已降级</div>

          <div v-if="m.products?.length" class="ai-product-rail" aria-label="推荐商品">
            <RouterLink
              v-for="p in m.products"
              :key="p.id"
              class="ai-product-card"
              :to="`/product/${p.id}`"
            >
              <img :src="p.coverUrl" :alt="p.title" loading="lazy" />
              <div>
                <small>{{ p.brandName || p.sellerLabel || '推荐' }}</small>
                <strong>{{ p.title }}</strong>
                <span v-if="p.subtitle">{{ p.subtitle }}</span>
                <em v-if="p.minPrice != null">¥{{ Number(p.minPrice).toLocaleString() }}</em>
                <em v-else>查看详情</em>
              </div>
            </RouterLink>
          </div>

          <ul v-if="m.tools?.length" class="meta">
            <li v-for="(t, i) in m.tools" :key="i">
              工具 {{ t.toolName }} · {{ t.status || 'CALL' }}
              <small v-if="t.reason">{{ t.reason }}</small>
            </li>
          </ul>
          <ul v-if="m.citations?.length && !m.products?.length" class="cite">
            <li v-for="(c, i) in m.citations" :key="i">
              引用 {{ c.documentTitle }} / {{ c.section }} · score {{ c.score }}
              <small>{{ c.snippet }}</small>
            </li>
          </ul>
          <div v-if="m.confirmations?.length" class="confirm">
            <div v-for="c in m.confirmations" :key="String(c.confirmationToken)">
              <dl v-if="c.arguments">
                <template v-for="(value, key) in (c.arguments as Record<string, unknown>)" :key="key">
                  <dt>{{ { orderNo: '订单号', addressId: '地址编号', reason: '原因' }[String(key)] ?? key }}</dt>
                  <dd>{{ value }}</dd>
                </template>
              </dl>
              <button v-if="!c.status || c.status === 'PENDING' || c.status === 'CONFIRMED'" type="button"
                      :disabled="pendingApprovals.has(String(c.confirmationToken))" @click="confirm(c)">
                {{ c.status === 'CONFIRMED' ? '查询执行结果' : (c.toolName === 'modify_address_draft' ? '确认生成改地址草稿' : '确认生成取消订单草稿') }}
              </button>
              <p v-else>{{ c.message }}</p>
            </div>
          </div>
        </article>
      </div>

      <aside>
        <h2><Sparkle :size="18" /> 过程提示</h2>
        <ul>
          <li v-for="(e, i) in streamLog" :key="i">{{ e }}</li>
          <li v-if="!hasStream">发送后这里会显示检索与回答进度</li>
        </ul>
        <p class="tip">会话号：{{ conversationNo || '未创建' }}</p>
      </aside>
    </div>

    <form class="composer" @submit.prevent="send()">
      <textarea v-model="input" rows="2" placeholder="例如：8000 元内轻薄办公本 / NovaBuds 和 Buds Lite 怎么选" />
      <button v-if="loading" type="button" @click="cancelStream">停止生成</button>
      <button v-else type="submit"><Send :size="18" /> 发送</button>
    </form>
  </section>
</template>

<style scoped>
.ai-page { max-width: 1100px; margin: 0 auto; padding: 28px 20px 64px; }
.ai-page > header { display: flex; justify-content: space-between; gap: 16px; align-items: end; margin-bottom: 20px; }
.ai-page > header p { color: #ff6a2b; letter-spacing: .12em; font-size: 12px; margin: 0 0 6px; }
.ai-page > header h1 { margin: 0 0 8px; font-size: 30px; }
.ai-page > header span { color: #64748b; }
.ai-page > header a { color: #0f172a; font-weight: 600; }
.ai-shell { display: grid; grid-template-columns: 1fr 260px; gap: 16px; }
.messages { background: #fff; border: 1px solid #e2e8f0; border-radius: 18px; min-height: 420px; max-height: 560px; overflow: auto; padding: 16px; display: grid; gap: 12px; }
.messages article { border-radius: 14px; padding: 12px 14px; background: #f8fafc; }
.messages article.user { background: #fff7ed; }
.messages article.system { background: #eff6ff; }
.messages strong { display: block; font-size: 12px; color: #64748b; margin-bottom: 6px; }
.messages p { margin: 0; white-space: pre-wrap; line-height: 1.55; }
.ai-product-rail { margin-top: 12px; display: grid; gap: 10px; }
.ai-product-card {
  display: grid;
  grid-template-columns: 88px 1fr;
  gap: 12px;
  align-items: center;
  padding: 10px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  text-decoration: none;
  color: inherit;
  transition: border-color 160ms ease, transform 160ms ease;
}
.ai-product-card:hover { border-color: #ff6a2b; transform: translateY(-1px); }
.ai-product-card img { width: 88px; height: 80px; object-fit: cover; border-radius: 8px; background: #eef1f3; }
.ai-product-card > div { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.ai-product-card small { color: #ff6a2b; font-size: 11px; font-weight: 700; }
.ai-product-card strong { margin: 0; font-size: 15px; color: #0f172a; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ai-product-card span { color: #64748b; font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ai-product-card em { font-style: normal; font-weight: 800; color: #ff6a2b; font-size: 15px; }
.meta, .cite { margin: 10px 0 0; padding-left: 16px; color: #475569; font-size: 13px; }
.meta small, .cite small { display: block; color: #94a3b8; }
.badge { display: inline-flex; gap: 6px; align-items: center; margin-top: 8px; font-size: 12px; color: #b45309; }
.confirm { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.confirm button { border: 0; background: #0f172a; color: #fff; border-radius: 999px; padding: 8px 12px; cursor: pointer; }
aside { background: #0f172a; color: #e2e8f0; border-radius: 18px; padding: 16px; }
aside h2 { display: flex; gap: 8px; align-items: center; font-size: 15px; margin: 0 0 12px; }
aside ul { margin: 0; padding-left: 16px; font-size: 13px; color: #94a3b8; }
.tip { margin-top: 16px; font-size: 12px; color: #64748b; word-break: break-all; }
.composer { margin-top: 16px; display: grid; grid-template-columns: 1fr auto; gap: 12px; }
.composer textarea { border: 1px solid #dbe3ee; border-radius: 14px; padding: 12px 14px; resize: vertical; font: inherit; }
.composer button { border: 0; border-radius: 14px; background: #ff6a2b; color: #fff; padding: 0 18px; font-weight: 700; display: inline-flex; gap: 8px; align-items: center; cursor: pointer; }
.composer button:disabled { opacity: .6; cursor: wait; }
@media (max-width: 860px) {
  .ai-shell { grid-template-columns: 1fr; }
  .composer { grid-template-columns: 1fr; }
}
</style>
