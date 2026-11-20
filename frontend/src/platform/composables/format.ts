import type { Locator } from '../api/types'
export const date = (value: string | null | undefined) => value ? new Date(value).toLocaleString('zh-TW') : '—'
export function locator(value: Locator) {
  return [value.section, value.page !== undefined ? '第 ' + value.page + ' 頁' : '', value.lineStart !== undefined ? '第 ' + value.lineStart + (value.lineEnd !== undefined ? '–' + value.lineEnd : '') + ' 行' : '', value.sheet ? '工作表 ' + value.sheet : '', value.row !== undefined ? '第 ' + value.row + ' 列' : ''].filter(Boolean).join(' · ') || '未提供位置'
}
export const orgPath = (orgId: string, path: string) => '/o/' + encodeURIComponent(orgId) + '/' + path

