/**
 * 收藏夹工具族：收藏夹 CRUD、批量增删题目、跨夹移动、题目定位。
 *
 * 对应 service-review 的 agent 专用接口（/api/review/agent/favorites*）。
 * 返回均为瘦身 VO：收藏夹不含 userId 与 questionIds 明细（只有数量），
 * 题目条目不含题干——完整题干用 get_question_details 批量按需取。
 * 批量写操作（增/删/移）由后端在一次请求内完成去重与校验并返回分项明细。
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

/** 后端批量接口单次上限（与 Java 侧 MAX_BATCH_SIZE 一致）。 */
const MAX_BATCH = 50

/** 收藏夹瘦身 VO 投影：Java 驼峰 → snake_case。 */
function projectFolder(data: Record<string, JsonValue>): Record<string, JsonValue> {
  return {
    favorites_id: data['favoritesId'] ?? null,
    favorites_name: data['favoritesName'] ?? null,
    favorites_type: data['favoritesType'] ?? null,
    favorites_content: data['favoritesContent'] ?? null,
    question_count: data['questionCount'] ?? null,
    create_time: data['createTime'] ?? null,
    update_time: data['updateTime'] ?? null,
  }
}

/** 收藏夹题目瘦身投影（不含题干/样例）。 */
function projectBrief(data: Record<string, JsonValue>): Record<string, JsonValue> {
  return {
    question_id: data['questionId'] ?? null,
    title: data['title'] ?? null,
    difficulty: data['difficulty'] ?? null,
    tags: data['tags'] ?? null,
    total_submit: data['totalSubmit'] ?? null,
    total_ac: data['totalAc'] ?? null,
    pass_rate: data['passRate'] ?? null,
  }
}

/** 批量 ID 入参卫生：数组、去重、非空、上限、正整数。 */
function normalizeIds(value: unknown, label: string, max: number): number[] {
  if (!Array.isArray(value)) {
    throw new GatewayError(`${label}必须是整数列表`)
  }
  const distinct = [...new Set(value)]
  if (distinct.length === 0) {
    throw new GatewayError(`${label}不能为空`)
  }
  if (distinct.length > max) {
    throw new GatewayError(`一次最多操作 ${max} 个${label}`)
  }
  return distinct.map(id => requirePositiveId(Number(id), label))
}

/** 列出当前用户的所有收藏夹。 */
export function listFavoriteFolders(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'list_favorite_folders',
    description: '列出当前用户的所有收藏夹，返回名称/类型/简介/题目数量（不含题目明细）。',
    parameters: {},
    timeoutMs: 20_000,
    output: {
      schema: { type: 'array', items: { type: 'object', additionalProperties: true } },
      render: (_args, value) => renderJson(value),
    },
    execute: async (_args, exec) => {
      const data = await gateway.request('GET', '/api/review/agent/favorites', { signal: exec.signal })
      return requireArray(data, '收藏夹列表').map(projectFolder)
    },
    presentCall: () => ({ card: 'generic', title: TOOL_TITLES.list_favorite_folders, kind: 'read' }),
  })
}

/** 列出收藏夹内的题目（瘦身条目）。 */
export function getFavoriteQuestions(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'get_favorite_questions',
    description: '列出指定收藏夹内的题目，返回题目 ID/标题/难度/标签/通过统计（不含题干；'
      + '需要完整题干时用 get_question_details 按需查询）。',
    parameters: {
      favorite_id: { type: 'integer', required: true, description: '收藏夹 ID。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'array', items: { type: 'object', additionalProperties: true } },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const favoriteId = requirePositiveId(args.favorite_id, '收藏夹 ID')
      const data = await gateway.request('GET', '/api/review/agent/favorites/questions', {
        params: { favoriteId },
        signal: exec.signal,
      })
      return requireArray(data, '收藏夹题目列表').map(projectBrief)
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.get_favorite_questions} #${args.favorite_id}`,
      kind: 'read',
    }),
  })
}

/** 创建收藏夹（客户端生成幂等键，返回含新 ID 的完整信息）。 */
export function createFavoriteFolder(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'create_favorite_folder',
    description: '为当前用户创建一个收藏夹，返回新收藏夹 ID 及完整信息。'
      + 'favorites_type 与 favorites_content 可选；仅当用户明确要求创建收藏夹时才可调用。',
    parameters: {
      favorites_name: { type: 'string', required: true, description: '收藏夹名称，1-255 字符。' },
      favorites_type: { type: 'string', description: '收藏夹类型标签，可选，≤255 字符。' },
      favorites_content: { type: 'string', description: '收藏夹简介，可选，≤255 字符。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec): Promise<Record<string, JsonValue>> => {
      const name = String(args.favorites_name ?? '').trim()
      if (!name) {
        throw new GatewayError('收藏夹名称不能为空')
      }
      if (name.length > 255) {
        throw new GatewayError('收藏夹名称长度不能超过 255 字符')
      }
      // 幂等键由客户端生成：网络重试时复用同一 requestId，后端 3 分钟窗口内不会重复建夹
      const body: Record<string, JsonValue> = { favoritesName: name, requestId: randomUUID() }
      const type = typeof args.favorites_type === 'string' ? args.favorites_type.trim() : ''
      const content = typeof args.favorites_content === 'string' ? args.favorites_content.trim() : ''
      if (type) body['favoritesType'] = type
      if (content) body['favoritesContent'] = content
      const data = await gateway.request('POST', '/api/review/agent/favorites', {
        json: body,
        signal: exec.signal,
      })
      return projectFolder(requireObject(data, '新建收藏夹'))
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.create_favorite_folder}：${String(args.favorites_name ?? '').trim() || '(空)'}`,
      kind: 'edit',
    }),
  })
}

/** 更新收藏夹名称/类型/简介。 */
export function updateFavoriteFolder(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'update_favorite_folder',
    description: '更新收藏夹的名称/类型/简介（至少提供一个字段），不影响收藏夹内的题目；'
      + '仅当用户明确要求修改收藏夹信息时才可调用。',
    parameters: {
      favorites_id: { type: 'integer', required: true, description: '收藏夹 ID。' },
      favorites_name: { type: 'string', description: '新名称，可选。' },
      favorites_type: { type: 'string', description: '新类型标签，可选。' },
      favorites_content: { type: 'string', description: '新简介，可选。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec): Promise<Record<string, JsonValue>> => {
      const favoritesId = requirePositiveId(args.favorites_id, '收藏夹 ID')
      const body: Record<string, JsonValue> = { favoritesId }
      let provided = 0
      for (const [argKey, bodyKey] of [
        ['favorites_name', 'favoritesName'],
        ['favorites_type', 'favoritesType'],
        ['favorites_content', 'favoritesContent'],
      ] as const) {
        const value = args[argKey]
        if (typeof value === 'string' && value.trim()) {
          body[bodyKey] = value.trim()
          provided++
        } else if (typeof value === 'string') {
          throw new GatewayError(`${argKey} 不能为空白字符串`)
        }
      }
      if (provided === 0) {
        throw new GatewayError('至少提供一个要更新的字段（名称/类型/简介）')
      }
      const data = await gateway.request('PUT', '/api/review/agent/favorites', {
        json: body,
        signal: exec.signal,
      })
      return projectFolder(requireObject(data, '更新后的收藏夹'))
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.update_favorite_folder} #${args.favorites_id}`,
      kind: 'edit',
    }),
  })
}

/** 删除收藏夹（不可恢复）。 */
export function deleteFavoriteFolder(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'delete_favorite_folder',
    description: '删除当前用户的指定收藏夹（不可恢复，仅解除收藏关系，不影响题目本身）；'
      + '仅当用户明确要求删除收藏夹时才可调用。',
    parameters: {
      favorite_id: { type: 'integer', required: true, description: '收藏夹 ID。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const favoriteId = requirePositiveId(args.favorite_id, '收藏夹 ID')
      const data = await gateway.request('DELETE', '/api/review/agent/favorites', {
        params: { favoriteId },
        signal: exec.signal,
      })
      if (typeof data !== 'object' || data === null || Array.isArray(data)) {
        return { success: true }
      }
      return data
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.delete_favorite_folder} #${args.favorite_id}`,
      kind: 'edit',
    }),
  })
}

/** 批量收藏题目。 */
export function addQuestionsToFavorite(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'add_questions_to_favorite',
    description: '把一批题目加入指定收藏夹（一次最多 50 题，自动去重）。'
      + '服务端会过滤不存在或不可见的题目并在结果中分项说明；仅当用户明确要求收藏时才可调用。',
    parameters: {
      favorite_id: { type: 'integer', required: true, description: '收藏夹 ID。' },
      question_ids: {
        type: 'array',
        required: true,
        description: '题目 ID 列表，去重后 1-50 个。',
        items: { type: 'integer' },
      },
    },
    timeoutMs: 30_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec): Promise<Record<string, JsonValue>> => {
      const favoriteId = requirePositiveId(args.favorite_id, '收藏夹 ID')
      const questionIds = normalizeIds(args.question_ids, '题目 ID', MAX_BATCH)
      const data = await gateway.request('POST', '/api/review/agent/favorites/questions', {
        json: { favoriteId, questionIds },
        signal: exec.signal,
      })
      return requireObject(data, '批量收藏结果')
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.add_questions_to_favorite} × ${args.question_ids.length} → #${args.favorite_id}`,
      kind: 'edit',
    }),
  })
}

/** 批量取消收藏。 */
export function removeQuestionsFromFavorite(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'remove_questions_from_favorite',
    description: '从指定收藏夹移除一批题目（一次最多 50 题，自动去重）；'
      + '本就不在该收藏夹中的题目会在结果中列出；仅当用户明确要求移除时才可调用。',
    parameters: {
      favorite_id: { type: 'integer', required: true, description: '收藏夹 ID。' },
      question_ids: {
        type: 'array',
        required: true,
        description: '题目 ID 列表，去重后 1-50 个。',
        items: { type: 'integer' },
      },
    },
    timeoutMs: 30_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const favoriteId = requirePositiveId(args.favorite_id, '收藏夹 ID')
      const questionIds = normalizeIds(args.question_ids, '题目 ID', MAX_BATCH)
      const data = await gateway.request('DELETE', '/api/review/agent/favorites/questions', {
        params: { favoriteId, questionIds: questionIds.join(',') },
        signal: exec.signal,
      })
      return requireObject(data, '批量移除结果')
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.remove_questions_from_favorite} × ${args.question_ids.length} ← #${args.favorite_id}`,
      kind: 'edit',
    }),
  })
}

/** 跨收藏夹移动题目（后端行锁保证并发安全）。 */
export function moveQuestionsToFavorite(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'move_questions_to_favorite',
    description: '把一批题目从源收藏夹移动到目标收藏夹（一次最多 50 题，两个收藏夹须属于当前用户）。'
      + '不在源收藏夹或已在目标收藏夹的题目会在结果中分项说明；仅当用户明确要求移动时才可调用。',
    parameters: {
      from_favorite_id: { type: 'integer', required: true, description: '源收藏夹 ID。' },
      to_favorite_id: { type: 'integer', required: true, description: '目标收藏夹 ID，不能与源相同。' },
      question_ids: {
        type: 'array',
        required: true,
        description: '题目 ID 列表，去重后 1-50 个。',
        items: { type: 'integer' },
      },
    },
    timeoutMs: 30_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const fromFavoriteId = requirePositiveId(args.from_favorite_id, '源收藏夹 ID')
      const toFavoriteId = requirePositiveId(args.to_favorite_id, '目标收藏夹 ID')
      if (fromFavoriteId === toFavoriteId) {
        throw new GatewayError('源收藏夹与目标收藏夹相同')
      }
      const questionIds = normalizeIds(args.question_ids, '题目 ID', MAX_BATCH)
      const data = await gateway.request('POST', '/api/review/agent/favorites/move', {
        json: { fromFavoriteId, toFavoriteId, questionIds },
        signal: exec.signal,
      })
      return requireObject(data, '移动结果')
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.move_questions_to_favorite} #${args.from_favorite_id} → #${args.to_favorite_id}`,
      kind: 'edit',
    }),
  })
}

/** 定位题目被收藏在哪些收藏夹。 */
export function findQuestionInFavorites(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'find_question_in_favorites',
    description: '查询一批题目分别被收藏在当前用户的哪些收藏夹中（回答「这道题我收藏过没有/在哪个夹里」）。',
    parameters: {
      question_ids: {
        type: 'array',
        required: true,
        description: '题目 ID 列表，去重后 1-50 个。',
        items: { type: 'integer' },
      },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'array', items: { type: 'object', additionalProperties: true } },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const questionIds = normalizeIds(args.question_ids, '题目 ID', MAX_BATCH)
      const data = await gateway.request('GET', '/api/review/agent/favorites/locate', {
        params: { questionIds: questionIds.join(',') },
        signal: exec.signal,
      })
      return requireArray(data, '定位结果').map(item => ({
        question_id: item['questionId'] ?? null,
        folders: Array.isArray(item['folders'])
          ? (item['folders'] as Record<string, JsonValue>[]).map(folder => ({
              favorite_id: folder['favoriteId'] ?? null,
              favorites_name: folder['favoritesName'] ?? null,
            }))
          : [],
      }))
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.find_question_in_favorites} × ${args.question_ids.length}`,
      kind: 'search',
    }),
  })
}
