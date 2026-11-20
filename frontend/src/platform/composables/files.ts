export function fileExtension(filename: string): string {
  return /\.([^.]+)$/.exec(filename)?.[1]?.toLowerCase() ?? ''
}

export function fileSize(bytes: number | null | undefined): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return '—'
  return bytes >= 1024 * 1024 ? (bytes / (1024 * 1024)).toFixed(1) + ' MB' : (bytes / 1024).toFixed(1) + ' KB'
}
