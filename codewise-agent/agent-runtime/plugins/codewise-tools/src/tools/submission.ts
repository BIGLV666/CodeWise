/**
 * 提交记录工具：最近列表、单条详情、批量详情。
 *
 * 列表接口刻意不返回代码与 userId（与旧版 JavaClient 的投影一致），
 * 代码只在详情接口返回，控制上下文体积。
 */

import { defineTool, type JsonValue } from '@deepseek-ai/dsh-tools'
import {
  GatewayError,
  requireObject,
  type CodeWiseGateway,
  projectSubmission,
  requirePositiveId,
} from '../gateway.js'
import { TOOL_TITLES, renderJson } from '../render.js'

/** 单条提交记录查询并投影；详情接口携带代码。 */
async function fetchSubmission(
  gateway: CodeWiseGateway,
  submitRecordId: number,
  signal: AbortSignal,
): Promise<Record<string, JsonValue>> {
  const data = await gateway.request('GET', '/api/question/getsubmitrecordbyid', {
    params: { submitRecordId },
    signal,
  })
  return projectSubmission(requireObject(data, '提交记录'), true)
}

/** 查询当前用户最近的提交记录（分页游标透传）。 */
export function getRecentSubmissions(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'get_recent_submissions',
    description: '查询当前用户最近的提交记录，包含通过和未通过的提交（不含代码）。',
    parameters: {
      limit: { type: 'integer', description: '返回条数，1-20，默认 10。' },
      last_id: { type: 'integer', description: '翻页游标：上一页返回的 next_cursor。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          records: {
            type: 'array',
            required: true,
            items: { type: 'object', additionalProperties: true },
          },
          next_cursor: { type: 'integer' },
          has_next: { type: 'boolean' },
          total: { type: 'integer' },
        },
      },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec): Promise<{
      records: Record<string, JsonValue>[]
      next_cursor?: number
      has_next?: boolean
      total?: number
    }> => {
      const limit = args.limit ?? 10
      const params: Record<string, string | number> = {
        pageSize: Math.min(Math.max(Math.floor(limit), 1), 20),
      }
      if (args.last_id !== undefined) {
        params['lastId'] = requirePositiveId(args.last_id, '翻页游标 last_id')
      }
      const data = await gateway.request('GET', '/api/question/getsubmitrecordsbyuserid', {
        params,
        signal: exec.signal,
      })
      const source = requireObject(data, '提交记录')
      const rawRecords = source['records']
      const records = Array.isArray(rawRecords)
        ? rawRecords
            .filter(
              (item): item is Record<string, JsonValue> =>
                typeof item === 'object' && item !== null && !Array.isArray(item),
            )
            .map(item => projectSubmission(item, false))
        : []
      const result: {
        records: Record<string, JsonValue>[]
        next_cursor?: number
        has_next?: boolean
        total?: number
      } = { records }
      // 只有类型正确的游标字段才透传，避免把畸形数据喂给模型。
      if (typeof source['nextCursor'] === 'number') result['next_cursor'] = source['nextCursor']
      if (typeof source['hasNext'] === 'boolean') result['has_next'] = source['hasNext']
      if (typeof source['total'] === 'number') result['total'] = source['total']
      return result
    },
    presentCall: () => ({ card: 'generic', title: TOOL_TITLES.get_recent_submissions, kind: 'search' }),
  })
}

/** 按提交记录 ID 查询详情（含代码）。 */
export function getSubmissionById(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'get_submission_by_id',
    description: '根据提交记录 ID 查询当前用户的一条提交记录，包含提交代码和判题结果。',
    parameters: {
      submit_record_id: { type: 'integer', required: true, description: '提交记录 ID。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const id = requirePositiveId(args.submit_record_id, '提交记录 ID')
      return fetchSubmission(gateway, id, exec.signal)
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.get_submission_by_id} #${args.submit_record_id}`,
      kind: 'read',
    }),
  })
}

/** 批量查询提交详情（一次最多 10 条，自动去重）。 */
export function getSubmissionsByIds(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'get_submissions_by_ids',
    description: '批量查询当前用户的提交记录，包含提交代码和判题结果，一次最多查询 10 条。',
    parameters: {
      submit_record_ids: {
        type: 'array',
        required: true,
        description: '提交记录 ID 列表，最多 10 个。',
        items: { type: 'integer' },
      },
    },
    timeoutMs: 30_000,
    output: {
      schema: {
        type: 'array',
        items: { type: 'object', additionalProperties: true },
      },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const distinct = [...new Set(args.submit_record_ids)]
      if (distinct.length === 0) {
        throw new GatewayError('提交记录 ID 列表不能为空')
      }
      if (distinct.length > 10) {
        throw new GatewayError('一次最多查询 10 条提交记录')
      }
      for (const id of distinct) {
        requirePositiveId(id, '提交记录 ID')
      }
      const data = await gateway.request('POST', '/api/question/getsubmitrecordsbyids', {
        json: distinct,
        signal: exec.signal,
      })
      if (!Array.isArray(data)) {
        throw new GatewayError('CodeWise 服务返回了无效的提交记录列表')
      }
      return data
        .filter(
          (item): item is Record<string, JsonValue> =>
            typeof item === 'object' && item !== null && !Array.isArray(item),
        )
        .map(item => projectSubmission(item, true))
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.get_submissions_by_ids} × ${args.submit_record_ids.length}`,
      kind: 'read',
    }),
  })
}
