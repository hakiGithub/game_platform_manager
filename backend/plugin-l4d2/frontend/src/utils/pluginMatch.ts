/**
 * 插件名称匹配（ADR-0022 已安装名称匹配）
 *
 * 内置/远端市场条目与本地已安装插件按"归一化名称"精确相等判定：
 * 去 .smx 后缀、忽略大小写、忽略下划线/连字符/空格差异。
 */

/** 归一化插件名：小写、去掉 .smx 后缀、去掉 _ - 空格 */
export function normalizePluginName(name: string): string {
  if (!name) return ''
  let n = name.trim().toLowerCase()
  if (n.endsWith('.smx')) n = n.slice(0, -4)
  return n.replace(/[\s_-]+/g, '')
}

/** 判断市场条目（name 或 fileName）是否已在已安装列表中 */
export function isPluginInstalled(
  marketName: string,
  installedNames: Array<string>
): boolean {
  const target = normalizePluginName(marketName)
  if (!target) return false
  return installedNames.some(installed => normalizePluginName(installed) === target)
}
