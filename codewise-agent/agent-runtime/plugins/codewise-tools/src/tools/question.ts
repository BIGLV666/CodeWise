/**
 * 题目工具：搜索与详情查询。
 */

import { defineTool } from '@deepseek-ai/dsh-tools'
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
