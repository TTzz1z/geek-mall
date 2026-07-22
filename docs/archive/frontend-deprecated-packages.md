# 前端废弃 workspace 包说明（已归档）

> 归档日期：2026-07-22。下列包源码仍保留在 `frontend/packages/`，本次只合并历史说明，不删除源码。

| 包 | 原说明 | 当前处理 |
|---|---|---|
| `@geek-mall/shared-utils` | Stage 4 / P3-05 标记为 deprecated；当时未被 `frontend/apps/*` 导入 | 保留源码，后续应在完整回归后决定删除或复用 |
| `@geek-mall/shared-icons` | Stage 4 / P3-05 标记为 deprecated；建议使用应用内图标或 `@phosphor-icons/vue` | 保留源码 |
| `@geek-mall/business-enums` | Stage 4 / P3-05 标记为 deprecated；领域枚举已迁往 API types 或服务契约 | 保留源码 |

2026-07-22 的仓库扫描未发现应用对这三个包的 import，但它们仍被 `pnpm-workspace.yaml` 的 `packages/*` 覆盖，因此把删除源码列为技术债，而不是在文件清理中直接移除。
