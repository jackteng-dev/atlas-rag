import type { StepDefinition } from '../api/types'

export const json = (value: unknown) => JSON.stringify(value, null, 2) ?? 'null'
export function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null
}
export const stepLabels: Record<StepDefinition['type'], string> = {
  RETRIEVE: '⌕ 檢索', EXTRACT: '▤ 擷取', RULE: '✓ 規則', LLM: '◇ 模型',
  APPROVAL: '☑ 人工審核', TOOL: '⚒ 工具', OUTPUT: '↗ 輸出',
}
export const templateLabels = { KNOWLEDGE_QA: '知識問答', DOCUMENT_COMPARE: '文件比對', READONLY_TOOL: '唯讀工具' }
export const lines = (value: string) => value.split(/\r?\n/).map(s => s.trim()).filter(Boolean)