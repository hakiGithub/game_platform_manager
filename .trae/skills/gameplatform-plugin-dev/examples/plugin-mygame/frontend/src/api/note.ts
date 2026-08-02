/**
 * 笔记 API（demo）
 *
 * 路径与后端 NoteController 对齐：
 *   GET    /notes?instanceId=xxx       列表
 *   GET    /notes/{id}                 详情
 *   POST   /notes                      创建
 *   PUT    /notes/{id}                 更新（带 version 乐观锁）
 *   DELETE /notes/{id}                 删除
 */
import { get, post, put, del } from './request'

export interface NoteVO {
  id: string
  name: string
  version: number
  createTime?: string
  instanceId: number
  title: string
  content?: string
  pinned?: boolean
}

export interface CreateNoteDTO {
  instanceId: number
  title: string
  content?: string
}

export interface UpdateNoteDTO {
  version: number
  title?: string
  content?: string
  pinned?: boolean
}

export const noteApi = {
  list: (instanceId: number) => get<NoteVO[]>('/notes', { instanceId }),
  getById: (id: string) => get<NoteVO>(`/notes/${id}`),
  create: (dto: CreateNoteDTO) => post<NoteVO>('/notes', dto),
  update: (id: string, dto: UpdateNoteDTO) => put<NoteVO>(`/notes/${id}`, dto),
  delete: (id: string) => del<void>(`/notes/${id}`)
}
