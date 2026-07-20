# Playwright API smoke (Stage 4)

Isolated from app packages. Requires Gateway at `http://127.0.0.1:8080` and seeded `e2e_tester`.

```bash
cd e2e
pnpm install
pnpm test
```

Env overrides: `E2E_BASE_URL`, `E2E_USERNAME`, `E2E_PASSWORD`.
