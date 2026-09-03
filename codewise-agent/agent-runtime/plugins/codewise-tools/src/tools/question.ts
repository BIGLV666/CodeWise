/**
 * 题目工具：搜索、详情查询（单个与批量）。
 */

import { defineTool, type JsonValue } from '@deepseek-ai/dsh-tools'
import { GatewayError, requireArray, requireObject, type CodeWiseGateway, requirePositiveId } from '../gateway.js'
import { TOOL_TITLES, renderJson } from '../render.js'

/** 按标题或标签搜索题目。 */
export function searchQuestion(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'search_question',
    description: '按照标题或者标签搜索题目，返回题目列表。',
    parameters: {
      like_word: {
        type: 'string',
        required: true,
        description: '搜索关键词，不能为空。',
      },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'array', items: { type: 'object', additionalProperties: true } },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const keyword = args.like_word.trim()
      if (!keyword) {
        throw new GatewayError('搜索关键词不能为空')
      }
      const data = await gateway.request('GET', '/api/question/likeserach', {
        params: { likeKey: keyword },
        signal: exec.signal,
      })
      return requireArray(data, '题目列表')
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.search_question}：${args.like_word.trim() || '(空)'}`,
      kind: 'search',
    }),
  })
}

/** 按题目 ID 查询详情。 */
export function getQuestionById(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'get_question_by_id',
    description: '根据题目 ID 查询题目信息。',
    parameters: {
      question_id: { type: 'integer', required: true, description: '题目 ID。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const questionId = requirePositiveId(args.question_id, '题目 ID')
      const data = await gateway.request('GET', '/api/question/getquestionbyid', {
        params: { questionId },
        signal: exec.signal,
      })
      return requireObject(data, '题目信息')
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.get_question_by_id} #${args.question_id}`,
      kind: 'read',
    }),
  })
}

/** 批量查询题目详情（逐题做可见性判定，一次最多 10 条）。 */
export function getQuestionDetails(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'get_question_details',
    description: '按题目 ID 批量查询完整题目详情（题干/样例/限制等），一次最多 10 条。'
      + '每题独立返回状态：ok / not_found / invisible（他人私密题、下架、审核中不可见），不因个别题目失败整体报错。',
    parameters: {
      question_ids: {
        type: 'array',
        required: true,
        description: '题目 ID 列表，去重后 1-10 个。',
        items: { type: 'integer' },
      },
    },
    timeoutMs: 30_000,
    output: {
      schema: { type: 'array', items: { type: 'object', additionalProperties: true } },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const distinct = [...new Set(args.question_ids)]
      if (distinct.length === 0) {
        throw new GatewayError('题目 ID 列表不能为空')
      }
      if (distinct.length > 10) {
        throw new GatewayError('一次最多查询 10 道题目详情')
      }
      const questionIds = distinct.map(id => requirePositiveId(Number(id), '题目 ID'))
      const data = await gateway.request('POST', '/api/question/agent/detail', {
        json: { questionIds },
        signal: exec.signal,
      })
      return requireArray(data, '题目详情列表').map(item => ({
        question_id: item['questionId'] ?? null,
        state: item['state'] ?? null,
        message: item['message'] ?? null,
        // state=ok 时携带完整题目详情（含题干），保留原始字段供模型阅读
        question: (item['question'] ?? null) as Record<string, JsonValue> | null,
      }))
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.get_question_details} × ${args.question_ids.length}`,
      kind: 'read',
    }),
  })
}
