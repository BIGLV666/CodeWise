/**
 * 个人笔记工具族：一级文件夹（分类）+ 其下 Markdown 笔记的增删改查与移动。
 *
 * 对应 service-review 的 agent 专用接口（/api/review/agent/note*），与网页端 NoteController
 * 等价（query 参数化以适配静态路径白名单）。笔记正文为 Markdown（mediumtext），
 * 列表走游标分页并返回纯文本预览，完整正文走详情接口；内容类型创建后不可变。
 */

import { defineTool, type JsonValue } from '@deepseek-ai/dsh-tools'
import { randomUUID } from 'node:crypto'
import {
  GatewayError,
  requireArray,
  requireObject,
  type CodeWiseGateway,
  requirePositiveId,
} from '../gateway.js'
import { TOOL_TITLES, renderJson } from '../render.js'

/** 笔记文件夹 VO 投影。 */
function projectFolder(data: Record<string, JsonValue>): Record<string, JsonValue> {
  return {
    folder_id: data['folderId'] ?? null,
    folder_name: data['folderName'] ?? null,
    note_count: data['noteCount'] ?? null,
    create_time: data['createTime'] ?? null,
    update_time: data['updateTime'] ?? null,
  }
}

/** 笔记列表项投影（不含全文）。 */
function projectBrief(data: Record<string, JsonValue>): Record<string, JsonValue> {
  return {
    note_id: data['noteId'] ?? null,
    title: data['title'] ?? null,
    folder_id: data['folderId'] ?? null,
    content_type: data['contentType'] ?? null,
    preview: data['preview'] ?? null,
    create_time: data['createTime'] ?? null,
    update_time: data['updateTime'] ?? null,
  }
}

/** 列出我的笔记文件夹。 */
export function listNoteFolders(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'list_note_folders',
    description: '列出当前用户的笔记文件夹（一级分类，含各文件夹笔记数）。',
    parameters: {},
    timeoutMs: 20_000,
    output: {
      schema: { type: 'array', items: { type: 'object', additionalProperties: true } },
      render: (_args, value) => renderJson(value),
    },
    execute: async (_args, exec) => {
      const data = await gateway.request('GET', '/api/review/agent/note/folders', { signal: exec.signal })
      return requireArray(data, '笔记文件夹').map(projectFolder)
    },
    presentCall: () => ({ card: 'generic', title: TOOL_TITLES.list_note_folders, kind: 'read' }),
  })
}

/** 创建笔记文件夹。 */
export function createNoteFolder(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'create_note_folder',
    description: '为当前用户创建一个笔记文件夹；仅当用户明确要求新建文件夹时才可调用。',
    parameters: {
      folder_name: { type: 'string', required: true, description: '文件夹名称，1-255 字符。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec): Promise<Record<string, JsonValue>> => {
      const name = String(args.folder_name ?? '').trim()
      if (!name) throw new GatewayError('文件夹名称不能为空')
      if (name.length > 255) throw new GatewayError('文件夹名称长度不能超过 255 字符')
      const data = await gateway.request('POST', '/api/review/agent/note/folders', {
        json: { folderName: name, requestId: randomUUID() },
        signal: exec.signal,
      })
      return projectFolder(requireObject(data, '新建文件夹'))
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.create_note_folder}：${String(args.folder_name ?? '').trim()}`,
      kind: 'edit',
    }),
  })
}

/** 重命名文件夹。 */
export function renameNoteFolder(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'rename_note_folder',
    description: '重命名当前用户的笔记文件夹；仅当用户明确要求时调用。',
    parameters: {
      folder_id: { type: 'integer', required: true, description: '文件夹 ID。' },
      folder_name: { type: 'string', required: true, description: '新名称，1-255 字符。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec): Promise<Record<string, JsonValue>> => {
      const folderId = requirePositiveId(args.folder_id, '文件夹 ID')
      const name = String(args.folder_name ?? '').trim()
      if (!name) throw new GatewayError('文件夹名称不能为空')
      if (name.length > 255) throw new GatewayError('文件夹名称长度不能超过 255 字符')
      const data = await gateway.request('PUT', '/api/review/agent/note/folders', {
        params: { folderId },
        json: { folderName: name },
        signal: exec.signal,
      })
      return projectFolder(requireObject(data, '重命名后的文件夹'))
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.rename_note_folder} #${args.folder_id}`,
      kind: 'edit',
    }),
  })
}

/** 删除文件夹（级联）。 */
export function deleteNoteFolder(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'delete_note_folder',
    description: '删除当前用户的笔记文件夹（会级联删除其下全部笔记，不可恢复）；仅当用户明确要求时才可调用。',
    parameters: {
      folder_id: { type: 'integer', required: true, description: '文件夹 ID。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const folderId = requirePositiveId(args.folder_id, '文件夹 ID')
      const data = await gateway.request('DELETE', '/api/review/agent/note/folders', {
        params: { folderId },
        signal: exec.signal,
      })
      if (typeof data !== 'object' || data === null || Array.isArray(data)) {
        return { success: true }
      }
      return data
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.delete_note_folder} #${args.folder_id}`,
      kind: 'edit',
    }),
  })
}

/** 笔记列表（游标分页）。 */
export function listNotes(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'list_notes',
    description: '列出当前用户的笔记（游标分页，返回纯文本预览；folder_id 缺省查全部，指定则只看该文件夹）。',
    parameters: {
      folder_id: { type: 'integer', description: '文件夹 ID，可选（缺省查全部）。' },
      cursor: { type: 'integer', description: '翻页游标：上一页返回的 next_cursor。' },
      size: { type: 'integer', description: '每页条数，默认 20。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const params: Record<string, string | number> = {}
      if (args.folder_id !== undefined) params['folderId'] = requirePositiveId(args.folder_id, '文件夹 ID')
      if (args.cursor !== undefined) params['cursor'] = requirePositiveId(args.cursor, '游标 cursor')
      if (args.size !== undefined) params['size'] = args.size
      const data = await gateway.request('GET', '/api/review/agent/note/list', { params, signal: exec.signal })
      const page = requireObject(data, '笔记列表')
      return {
        items: Array.isArray(page['items']) ? (page['items'] as Record<string, JsonValue>[]).map(projectBrief) : [],
        next_cursor: page['nextCursor'] ?? null,
        has_next: page['hasNext'] ?? null,
        total: page['total'] ?? null,
      }
    },
    presentCall: args => ({
      card: 'generic',
      title: TOOL_TITLES.list_notes,
      kind: 'read',
    }),
  })
}

/** 笔记详情（全文）。 */
export function getNote(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'get_note',
    description: '查询单条笔记全文（含 Markdown 正文）。',
    parameters: {
      note_id: { type: 'integer', required: true, description: '笔记 ID。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const noteId = requirePositiveId(args.note_id, '笔记 ID')
      const data = await gateway.request('GET', '/api/review/agent/note/detail', {
        params: { noteId },
        signal: exec.signal,
      })
      return requireObject(data, '笔记详情')
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.get_note} #${args.note_id}`,
      kind: 'read',
    }),
  })
}

/** 创建笔记。 */
export function createNote(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'create_note',
    description: '为当前用户创建一条笔记（Markdown 正文）；title 为空时由服务端从正文推导。'
      + '仅当用户明确要求记笔记时才可调用。',
    parameters: {
      content: { type: 'string', required: true, description: '笔记正文（Markdown），必填。' },
      title: { type: 'string', description: '标题，可选；为空时从正文推导。' },
      folder_id: { type: 'integer', description: '所属文件夹 ID，可选（缺省=未分类）。' },
      type: { type: 'string', description: '内容类型标识，可选，默认 MD。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec): Promise<Record<string, JsonValue>> => {
      const content = String(args.content ?? '')
      if (!content.trim()) throw new GatewayError('笔记正文不能为空')
      const body: Record<string, JsonValue> = { content, requestId: randomUUID() }
      if (typeof args.title === 'string' && args.title.trim()) body['title'] = args.title.trim()
      if (args.folder_id !== undefined) body['folderId'] = requirePositiveId(args.folder_id, '文件夹 ID')
      if (typeof args.type === 'string' && args.type.trim()) body['type'] = args.type.trim()
      const data = await gateway.request('POST', '/api/review/agent/note', { json: body, signal: exec.signal })
      return requireObject(data, '新建笔记')
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.create_note}：${String(args.title ?? '').trim() || '(从正文推导)'}`,
      kind: 'edit',
    }),
  })
}

/** 更新笔记。 */
export function updateNote(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'update_note',
    description: '更新笔记标题/正文（内容类型创建后不可变）；仅当用户明确要求修改笔记时才可调用。',
    parameters: {
      note_id: { type: 'integer', required: true, description: '笔记 ID。' },
      title: { type: 'string', description: '新标题，可选。' },
      content: { type: 'string', description: '新正文，可选。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const noteId = requirePositiveId(args.note_id, '笔记 ID')
      const body: Record<string, JsonValue> = {}
      if (typeof args.title === 'string' && args.title.trim()) body['title'] = args.title.trim()
      if (typeof args.content === 'string' && args.content.trim()) body['content'] = args.content
      if (Object.keys(body).length === 0) throw new GatewayError('至少提供标题或正文之一')
      const data = await gateway.request('PUT', '/api/review/agent/note', {
        params: { noteId },
        json: body,
        signal: exec.signal,
      })
      return requireObject(data, '更新后的笔记')
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.update_note} #${args.note_id}`,
      kind: 'edit',
    }),
  })
}

/** 移动笔记。 */
export function moveNote(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'move_note',
    description: '把笔记移动到指定文件夹；folder_id 缺省表示移到「未分类」。仅当用户明确要求移动时才可调用。',
    parameters: {
      note_id: { type: 'integer', required: true, description: '笔记 ID。' },
      folder_id: { type: 'integer', description: '目标文件夹 ID，可选（缺省=未分类）。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const noteId = requirePositiveId(args.note_id, '笔记 ID')
      const params: Record<string, string | number> = { noteId }
      if (args.folder_id !== undefined) params['folderId'] = requirePositiveId(args.folder_id, '文件夹 ID')
      const data = await gateway.request('POST', '/api/review/agent/note/move', { params, signal: exec.signal })
      if (typeof data !== 'object' || data === null || Array.isArray(data)) {
        return { success: true }
      }
      return data
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.move_note} #${args.note_id}`,
      kind: 'edit',
    }),
  })
}

/** 删除笔记。 */
export function deleteNote(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'delete_note',
    description: '删除当前用户的一条笔记（不可恢复）；仅当用户明确要求删除时才可调用。',
    parameters: {
      note_id: { type: 'integer', required: true, description: '笔记 ID。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const noteId = requirePositiveId(args.note_id, '笔记 ID')
      const data = await gateway.request('DELETE', '/api/review/agent/note', {
        params: { noteId },
        signal: exec.signal,
      })
      if (typeof data !== 'object' || data === null || Array.isArray(data)) {
        return { success: true }
      }
      return data
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.delete_note} #${args.note_id}`,
      kind: 'edit',
    }),
  })
}
