/**
 * 学习计划（进度计划追踪）工具族：今日计划、active 列表、详情、日历概览、周报，
 * 以及批量创建/更新/删除。
 *
 * 对应 service-review 的 agent 专用接口（/api/review/agent/progress*）与周报接口
 * （/api/review/progress/report/week）。状态机单向：0-未开始 → 1-进行中 →（2-已完成 或 3-已过期）；
 * 已过期只能删除或新建，不能逆向改期；开始时间仅「未开始」时可改，备注/反思任何状态可改。
 */

import { defineTool, type JsonValue } from '@deepseek-ai/dsh-tools'
import {
  GatewayError,
  requireArray,
  requireObject,
  type CodeWiseGateway,
  requirePositiveId,
} from '../gateway.js'
import { TOOL_TITLES, renderJson } from '../render.js'

/** 单批最多创建/删除的计划条数上限（与 Java 侧 MAX_PROGRESS_BATCH 一致）。 */
const MAX_BATCH = 200
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/

/** 状态码 → 中文，供工具输出便于模型阅读。 */
const STATUS_TEXT: Record<number, string> = { 0: '未开始', 1: '进行中', 2: '已完成', 3: '已过期' }

/** 计划瘦身条目投影。 */
function projectPlan(data: Record<string, JsonValue>): Record<string, JsonValue> {
  const status = typeof data['status'] === 'number' ? (data['status'] as number) : -1
  return {
    progress_id: data['progressId'] ?? null,
    question_id: data['questionId'] ?? null,
    title: data['title'] ?? null,
    difficulty: data['difficulty'] ?? null,
    begin_time: data['beginTime'] ?? null,
    status,
    status_text: STATUS_TEXT[status] ?? '未知',
    notes_content: data['notesContent'] ?? null,
    summary_content: data['summaryContent'] ?? null,
  }
}

/** 校验日期字符串格式（YYYY-MM-DD）。 */
function requireDate(value: unknown, label: string): string {
  if (typeof value !== 'string' || !DATE_RE.test(value)) {
    throw new GatewayError(`${label} 必须是 YYYY-MM-DD 格式`)
  }
  return value
}

/** 校验并规整计划 ID 列表。 */
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

/** 今天要做的计划。 */
export function getTodayPlan(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'get_today_plan',
    description: '查询当前用户今天要做的学习计划（含题目标题/难度与状态，自动懒刷新过期/进行中）。',
    parameters: {},
    timeoutMs: 20_000,
    output: {
      schema: { type: 'array', items: { type: 'object', additionalProperties: true } },
      render: (_args, value) => renderJson(value),
    },
    execute: async (_args, exec) => {
      const data = await gateway.request('GET', '/api/review/agent/progress/today', { signal: exec.signal })
      return requireArray(data, '今日计划').map(projectPlan)
    },
    presentCall: () => ({ card: 'generic', title: TOOL_TITLES.get_today_plan, kind: 'read' }),
  })
}

/** 未开始 + 进行中的计划（接下来要做）。 */
export function listStudyPlan(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'list_study_plan',
    description: '查询当前用户未开始 + 进行中的学习计划（即「接下来要做」），按日期升序。',
    parameters: {},
    timeoutMs: 20_000,
    output: {
      schema: { type: 'array', items: { type: 'object', additionalProperties: true } },
      render: (_args, value) => renderJson(value),
    },
    execute: async (_args, exec) => {
      const data = await gateway.request('GET', '/api/review/agent/progress/list', { signal: exec.signal })
      return requireArray(data, '学习计划').map(projectPlan)
    },
    presentCall: () => ({ card: 'generic', title: TOOL_TITLES.list_study_plan, kind: 'read' }),
  })
}

/** 单条计划详情。 */
export function getPlanDetail(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'get_plan_detail',
    description: '查询单条学习计划详情（含完整备注、反思总结与关联提交记录）。',
    parameters: {
      progress_id: { type: 'integer', required: true, description: '计划 ID。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const progressId = requirePositiveId(args.progress_id, '计划 ID')
      const data = await gateway.request('GET', '/api/review/agent/progress/detail', {
        params: { progressId },
        signal: exec.signal,
      })
      return requireObject(data, '计划详情')
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.get_plan_detail} #${args.progress_id}`,
      kind: 'read',
    }),
  })
}

/** 日历概览。 */
export function getPlanCalendar(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'get_plan_calendar',
    description: '查询学习计划的日历概览：day/week/month 逐日计划题数与完成数（缺日期补零）。',
    parameters: {
      range: { type: 'string', enum: ['day', 'week', 'month'], description: '聚合粒度，默认 week。' },
      date: { type: 'string', description: '锚点日期 YYYY-MM-DD，默认今天。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'array', items: { type: 'object', additionalProperties: true } },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const range = args.range ?? 'week'
      if (!['day', 'week', 'month'].includes(range)) {
        throw new GatewayError('range 仅支持 day / week / month')
      }
      const params: Record<string, string | number> = { range }
      if (args.date !== undefined) {
        params['date'] = requireDate(args.date, 'date')
      }
      const data = await gateway.request('GET', '/api/review/agent/progress/calendar', { params, signal: exec.signal })
      return requireArray(data, '日历概览').map(item => ({
        date: item['date'] ?? null,
        total: item['total'] ?? null,
        completed: item['completed'] ?? null,
      }))
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.get_plan_calendar}（${args.range ?? 'week'}）`,
      kind: 'read',
    }),
  })
}

/** 周报。 */
export function getWeeklyReport(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'get_weekly_report',
    description: '获取某自然周的学习周报：本周计划/完成/进行中/过期计数、逐日分布，以及已完成条目（含反思总结）。'
      + '用于向用户汇报学习进展或组织总结。',
    parameters: {
      date: { type: 'string', description: '锚点日期 YYYY-MM-DD（取该日期所在周），默认今天。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const params: Record<string, string | number> = {}
      if (args.date !== undefined) {
        params['date'] = requireDate(args.date, 'date')
      }
      const data = await gateway.request('GET', '/api/review/progress/report/week', { params, signal: exec.signal })
      const report = requireObject(data, '周报')
      return {
        start_date: report['startDate'] ?? null,
        end_date: report['endDate'] ?? null,
        total_planned: report['totalPlanned'] ?? null,
        completed_count: report['completedCount'] ?? null,
        in_progress_count: report['inProgressCount'] ?? null,
        expired_count: report['expiredCount'] ?? null,
        daily: Array.isArray(report['daily'])
          ? (report['daily'] as Record<string, JsonValue>[]).map(d => ({
              date: d['date'] ?? null, total: d['total'] ?? null, completed: d['completed'] ?? null,
            }))
          : [],
        completed_items: Array.isArray(report['completedItems'])
          ? (report['completedItems'] as Record<string, JsonValue>[]).map(i => ({
              question_id: i['questionId'] ?? null,
              title: i['title'] ?? null,
              summary_content: i['summaryContent'] ?? null,
            }))
          : [],
      }
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.get_weekly_report}${args.date ? `（${args.date}）` : ''}`,
      kind: 'read',
    }),
  })
}

/** 批量创建计划（幂等）。 */
export function createStudyPlan(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'create_study_plan',
    description: '批量创建学习计划：为若干题目分别安排「哪天做」并写备注（一次调用，最多 200 题）。'
      + '同题已存在计划时该题记为 skipped 而非报错；计划只能从今天或未来开始。'
      + '仅当用户明确要求制定/安排学习计划时才可调用。',
    parameters: {
      items: {
        type: 'array',
        required: true,
        description: '计划条目列表。',
        items: {
          type: 'object',
          additionalProperties: false,
          properties: {
            question_id: { type: 'integer', required: true, description: '题目 ID。' },
            begin_time: { type: 'string', required: true, description: '哪天做，YYYY-MM-DD，不得早于今天。' },
            notes: { type: 'string', description: '计划备注，可选。' },
          },
        },
      },
    },
    timeoutMs: 30_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec): Promise<Record<string, JsonValue>> => {
      const items = Array.isArray(args.items) ? (args.items as Record<string, JsonValue>[]) : []
      if (items.length === 0) {
        throw new GatewayError('计划条目不能为空')
      }
      if (items.length > MAX_BATCH) {
        throw new GatewayError(`一次最多创建 ${MAX_BATCH} 条计划`)
      }
      const body = items.map(item => {
        const questionId = requirePositiveId(Number(item['question_id']), '题目 ID')
        const beginTime = requireDate(item['begin_time'], '开始时间')
        const bodyItem: Record<string, JsonValue> = { questionId, beginTime }
        const notes = item['notes']
        if (typeof notes === 'string' && notes.trim()) {
          bodyItem['notesContent'] = notes.trim()
        }
        return bodyItem
      })
      const data = await gateway.request('POST', '/api/review/agent/progress/create', {
        json: body,
        signal: exec.signal,
      })
      return requireObject(data, '创建结果')
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.create_study_plan} × ${Array.isArray(args.items) ? args.items.length : 0}`,
      kind: 'edit',
    }),
  })
}

/** 更新计划（备注/反思任何状态可改；开始时间仅未开始可改）。 */
export function updateStudyPlan(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'update_study_plan',
    description: '更新学习计划：备注/反思总结任何状态都可改；开始时间仅「未开始」的计划可改（进行中/已完成/已过期锁定，已过期只能删除或新建）。'
      + '仅当用户明确要求修改计划时才可调用。',
    parameters: {
      progress_id: { type: 'integer', required: true, description: '计划 ID。' },
      begin_time: { type: 'string', description: '新开始日期 YYYY-MM-DD（仅未开始可改）。' },
      notes: { type: 'string', description: '新计划备注，可选。' },
      summary: { type: 'string', description: '新反思总结，可选。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const progressId = requirePositiveId(args.progress_id, '计划 ID')
      const params: Record<string, string | number> = { progressId }
      if (args.begin_time !== undefined) {
        params['beginTime'] = requireDate(args.begin_time, '开始时间')
      }
      if (typeof args.notes === 'string' && args.notes.trim()) {
        params['notesContent'] = args.notes.trim()
      }
      if (typeof args.summary === 'string' && args.summary.trim()) {
        params['summaryContent'] = args.summary.trim()
      }
      const data = await gateway.request('POST', '/api/review/agent/progress/update', { params, signal: exec.signal })
      if (typeof data !== 'object' || data === null || Array.isArray(data)) {
        return { success: true }
      }
      return data
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.update_study_plan} #${args.progress_id}`,
      kind: 'edit',
    }),
  })
}

/** 批量删除计划。 */
export function deleteStudyPlan(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'delete_study_plan',
    description: '批量删除当前用户的学习计划（最多 200 条）。仅当用户明确要求删除计划时才可调用。',
    parameters: {
      progress_ids: {
        type: 'array',
        required: true,
        description: '计划 ID 列表，1-200 个。',
        items: { type: 'integer' },
      },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const ids = normalizeIds(args.progress_ids, '计划 ID', MAX_BATCH)
      const data = await gateway.request('POST', '/api/review/agent/progress/delete', {
        params: { progressIds: ids.join(',') },
        signal: exec.signal,
      })
      return requireObject(data, '删除结果')
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.delete_study_plan} × ${args.progress_ids.length}`,
      kind: 'edit',
    }),
  })
}
