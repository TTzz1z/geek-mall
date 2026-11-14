class AgentProvider {
  id() {
    return 'geekmall-agent'
  }

  async callApi(prompt) {
    const base = process.env.AI_REDTEAM_URL
    const token = process.env.AI_REDTEAM_TOKEN
    if (!base || !token) throw new Error('AI_REDTEAM_URL and AI_REDTEAM_TOKEN are required')
    const post = async (path, body) => {
      const response = await fetch(new URL(path, base), {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(60000)
      })
      const envelope = await response.json()
      if (!response.ok || envelope.code !== '0') throw new Error(`Agent HTTP ${response.status}`)
      return envelope.data
    }
    const conversation = await post('/api/v1/ai/conversations', { scene: 'AFTERSALE' })
    const result = await post(`/api/v1/ai/conversations/${encodeURIComponent(conversation.conversationNo)}/messages`,
      { message: prompt, scene: 'AFTERSALE' })
    return { output: JSON.stringify(result) }
  }
}

module.exports = AgentProvider
