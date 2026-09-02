/**
 * 工具输出的模型可见渲染。
 *
 * dsh 管线中 canonical value 只在执行期存在，真正持久化并交给模型的是
 * ``output.render`` 产出的文本块。这里统一做 JSON 序列化并限制长度，
 * 防止一次大查询把模型上下文撑爆（超长部分显式标注截断）。
 */

const MAX_RENDER_CHARS = 12_000

export function renderJson(value: unknown): Array<{ type: 'text'; text: string }> {
  const text = JSON.stringify(value) ?? 'null'
  if (text.length <= MAX_RENDER_CHARS) {
    return [{ type: 'text', text }]
  }
  const truncated = text.slice(0, MAX_RENDER_CHARS)
  return [
    {
      type: 'text',
      text: `${truncated}\n…[结果过长已截断，原始长度 ${text.length} 字符。请缩小查询范围后重试]`,
    },
  ]
}

/** 工具调用卡片的通用 Chinese 标题（前端复刻 dsh UI 时直接展示）。 */
export const TOOL_TITLES = {
  get_current_time: '获取当前时间',
  get_recent_submissions: '查询最近提交',
  get_submission_by_id: '查询提交详情',
  get_submissions_by_ids: '批量查询提交详情',
  get_user_info: '查询用户信息',
  search_question: '搜索题目',
  get_review_record: '查询复习记录',
  get_user_review_config: '查询复习配置',
  get_question_by_id: '查询题目详情',
  get_all_review: '查询复习计划',
  update_review_config: '更新复习配置',
} as const
