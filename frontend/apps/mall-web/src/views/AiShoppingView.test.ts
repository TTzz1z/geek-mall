import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
Object.defineProperty(HTMLElement.prototype, 'scrollTo', { value: vi.fn(), configurable: true })

import AiShoppingView from './AiShoppingView.vue'

vi.mock('../api', () => ({ mallApi: { request: vi.fn(async (path: string) => path.endsWith('/conversations') ? { conversationNo: 'C1' } : {}) } }))

function stream(events: string) {
  return new Response(new ReadableStream({ start(controller) {
    controller.enqueue(new TextEncoder().encode(events))
    controller.close()
  } }), { status: 200 })
}

afterEach(() => vi.unstubAllGlobals())
beforeEach(() => vi.stubGlobal('localStorage', { getItem: vi.fn(() => null) }))

describe('AI stream completion', () => {
  it('keeps partial output visibly incomplete and retries with the same intent key', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(stream('event: message.delta\ndata: {"delta":"部分回答"}\n\n'))
      .mockResolvedValueOnce(stream('event: message.complete\ndata: {"content":"完整回答"}\n\n'))
    vi.stubGlobal('fetch', fetch)
    const wrapper = mount(AiShoppingView, { global: { stubs: { RouterLink: true } } })
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('部分回答')
    expect(wrapper.text()).toContain('回答未完成')
    const retry = wrapper.findAll('button').find(button => button.text().includes('重试'))!
    expect(retry).toBeDefined()
    await retry.trigger('click')
    await flushPromises()
    expect(JSON.parse(fetch.mock.calls[0][1].body).requestId).toEqual(JSON.parse(fetch.mock.calls[1][1].body).requestId)
    expect(wrapper.text()).toContain('完整回答')
    expect(wrapper.text()).not.toContain('回答未完成')
    wrapper.unmount()
  })

  it('accepts completion before a late transport failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(stream('event: message.complete\ndata: {"content":"已完成"}\n\n')))
    const wrapper = mount(AiShoppingView, { global: { stubs: { RouterLink: true } } })
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.text()).not.toContain('回答未完成')
    wrapper.unmount()
  })
})
