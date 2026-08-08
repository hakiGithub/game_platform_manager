/**
 * L4D2 地图中心 API
 *
 * 复用 @/api/request 模块（base 已为 /api/plugin/l4d2），
 * 与项目其他 api 模块保持一致。
 */
import { get, post } from './request'

// 地图列表查询参数
export interface MapCenterQuery {
  source?: string      // ORANGE / WORKSHOP
  keyword?: string     // 搜索关键词
  mode?: string        // 游戏模式
  page?: number
  size?: number
  sort?: string        // 排序字段
}

// 下载链接
export interface DownloadLink {
  channel: string
  shareUrl: string
  accessCode: string
}

// 地图信息
export interface MapCenterVO {
  id: string
  sourceId: string
  source: string
  sourceUrl: string
  titleCn: string
  titleEn: string
  fileSize: string
  fileSizeBytes: number
  gameModes: string[]
  chapterCount: number
  mapType: string
  author: string
  mapDate: string
  vpkFileName: string
  starRating: number
  platform: string
  language: string
  license: string
  description: string
  mapCommands: string[]
  thumbnailUrl: string
  screenshotUrls: string[]
  downloadLinks: DownloadLink[]
  viewCount: number
  sourceUpdateDate: string
  crawlTime: number
}

// 爬取状态
export interface CrawlStatusVO {
  taskId: string
  taskType: string
  source: string
  status: string
  totalPages: number
  processedPages: number
  totalMaps: number
  newMaps: number
  updatedMaps: number
  skippedMaps: number
  failedMaps: number
  startTime: number
  endTime: number
  errorMessage: string
}

// 分页结果
export interface PageResult<T> {
  records: T[]
  current: number
  size: number
  total: number
  pages: number
}

// 获取地图列表
export function getMapList(params: MapCenterQuery) {
  return get<PageResult<MapCenterVO>>('/map-center/maps', params)
}

// 获取地图详情
export function getMapDetail(sourceId: string) {
  return get<MapCenterVO>(`/map-center/maps/${encodeURIComponent(sourceId)}`)
}

// 触发爬取
export function triggerCrawl(type: string) {
  return post<string>('/map-center/crawl', { type })
}

// 获取爬取状态
export function getCrawlStatus() {
  return get<CrawlStatusVO>('/map-center/crawl/status')
}

export default {
  getMapList,
  getMapDetail,
  triggerCrawl,
  getCrawlStatus,
}
