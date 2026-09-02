/**
 * 复习工具：复习记录、复习配置（读 + 唯一的写操作）、复习计划。
 */

import { defineTool, type JsonValue } from '@deepseek-ai/dsh-tools'
import { GatewayError, requireArray, requireObject, type CodeWiseGateway, requirePositiveId } from '../gateway.js'
import { TOOL_TITLES, renderJson } from '../render.js'

/** 把投影键从 Java 驼峰映射到工具的 snake_case 输出。 */
function projectReviewRecord(data: Record<string, JsonValue>): Record<string, JsonValue> {
  return {
    review_record_id: data['reviewRecordId'] ?? null,
    all_question_ids: data['allQuestionIds'] ?? null,
    pending_review_question_ids: data['pendingReviewQuestionIds'] ?? null,
    completed_review_question_ids: data['completedReviewQuestionIds'] ?? null,
    create_time: data['createTime'] ?? null,
    review_days: data['reviewDays'] ?? null,
    all_questionid_and_title: data['allQuestionTitles'] ?? null,
    ac_question_ids: data['acQuestionIds'] ?? null,
    review_date: data['reviewDate'] ?? null,
  }
}

/** 查询指定日期的复习记录。 */
export function getReviewRecord(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'get_review_record',
    description: '查询指定日期的复习记录。',
    parameters: {
      day: {
        type: 'string',
        required: true,
        description: '日期，格式 YYYY-MM-DD。',
      },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(args.day)) {
        throw new GatewayError('day 必须是 YYYY-MM-DD 格式')
      }
      const data = await gateway.request('GET', '/api/review/review/reviewrecord', {
        params: { day: args.day },
        signal: exec.signal,
      })
      return projectReviewRecord(requireObject(data, '复习记录'))
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.get_review_record} ${args.day}`,
      kind: 'read',
    }),
  })
}

/** 查询当前用户的复习配置。 */
export function getUserReviewConfig(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'get_user_review_config',
    description: '查询当前用户的复习配置。',
    parameters: {},
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (_args, exec) => {
      const data = await gateway.request('GET', '/api/review/review/config', {
        signal: exec.signal,
      })
      return requireObject(data, '复习配置')
    },
    presentCall: () => ({ card: 'generic', title: TOOL_TITLES.get_user_review_config, kind: 'read' }),
  })
}

/** 查询当前用户复习计划中的所有题目。 */
export function getAllReview(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'get_all_review',
    description: '查询当前用户复习计划中的所有题目。',
    parameters: {},
    timeoutMs: 20_000,
    output: {
      schema: { type: 'array', items: { type: 'object', additionalProperties: true } },
      render: (_args, value) => renderJson(value),
    },
    execute: async (_args, exec) => {
      const data = await gateway.request('GET', '/api/review/review/allreview', {
        signal: exec.signal,
      })
      return requireArray(data, '复习计划')
    },
    presentCall: () => ({ card: 'generic', title: TOOL_TITLES.get_all_review, kind: 'read' }),
  })
}

/**
 * 更新复习配置（本插件唯一的写工具）。
 *
 * 仅当用户明确要求修改配置时才可调用。当前默认放行并记录审计；
 * 后续接入 dsh 审批（pre-execute 瀑布 / permission-presets）时无需改动本定义。
 */
export function updateReviewConfig(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'update_review_config',
    description:
      '更新当前用户的复习配置，仅当用户明确要求修改配置时才可调用。'
      + 'reviewCount=每日复习题目数量；enableAutoReview=1 启用/0 禁用自动复习；'
      + 'countCompileError=1 编译失败计入复习/0 不计入（OJ 场景建议开启）；'
      + 'minEasinessFactor=SM-2 最低难度因子（官方下限 1.3）；'
      + 'initialEasinessFactor=初始难度因子（新题默认 2.5）；'
      + 'masteredIntervalDays=掌握判定间隔天数。',
    parameters: {
      reviewCount: { type: 'integer', required: true, description: '每日复习题目数量，正整数。' },
      enableAutoReview: { type: 'integer', required: true, enum: [0, 1], description: '1 启用自动复习，0 禁用。' },
      countCompileError: { type: 'integer', required: true, enum: [0, 1], description: '1 编译失败计入一次复习，0 不计入。' },
      minEasinessFactor: { type: 'number', required: true, description: '最低难度因子 EF，建议不低于 1.3。' },
      initialEasinessFactor: { type: 'number', required: true, description: '初始难度因子 EF，新题默认 2.5。' },
      masteredIntervalDays: { type: 'integer', required: true, description: '掌握判定间隔天数，正整数。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec): Promise<Record<string, JsonValue>> => {
      requirePositiveId(args.reviewCount, 'reviewCount')
      requirePositiveId(args.masteredIntervalDays, 'masteredIntervalDays')
      for (const [label, value] of [
        ['minEasinessFactor', args.minEasinessFactor],
        ['initialEasinessFactor', args.initialEasinessFactor],
      ] as const) {
        if (!Number.isFinite(value) || value <= 0) {
          throw new GatewayError(`${label} 必须为正数`)
        }
      }
      const data = await gateway.request('PUT', '/api/review/review/config', {
        json: {
          reviewCount: args.reviewCount,
          enableAutoReview: args.enableAutoReview,
          countCompileError: args.countCompileError,
          minEasinessFactor: args.minEasinessFactor,
          initialEasinessFactor: args.initialEasinessFactor,
          masteredIntervalDays: args.masteredIntervalDays,
        },
        signal: exec.signal,
      })
      // Java 侧可能返回 null data 表示无返回体，此时给模型一个明确成功值。
      if (typeof data !== 'object' || data === null || Array.isArray(data)) {
        return { success: true }
      }
      return data
    },
    presentCall: () => ({ card: 'generic', title: TOOL_TITLES.update_review_config, kind: 'edit' }),
  })
}
