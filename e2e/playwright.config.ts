import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  timeout: 120_000,
  retries: 0,
  reporter: [
    ['list'],
    ['json', { outputFile: process.env.E2E_JSON_REPORT || 'reports/playwright-api-results.json' }]
  ],
  projects: [
    { name: 'api', testMatch: /api-core-flow\.spec\.ts/ }
  ],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:8080'
  }
})
