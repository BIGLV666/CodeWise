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
  get_question_details: '批量查询题目详情',
  get_all_review: '查询复习计划',
  update_review_config: '更新复习配置',
  list_favorite_folders: '查询收藏夹列表',
  get_favorite_questions: '查询收藏夹题目',
  create_favorite_folder: '创建收藏夹',
  update_favorite_folder: '更新收藏夹',
  delete_favorite_folder: '删除收藏夹',
  add_questions_to_favorite: '收藏题目',
  remove_questions_from_favorite: '取消收藏',
  move_questions_to_favorite: '移动收藏题目',
  find_question_in_favorites: '定位收藏题目',
  list_community_posts: '浏览社区帖子',
  search_community_posts: '搜索社区帖子',
  get_community_hot_posts: '社区热榜',
  get_community_post: '查看帖子详情',
  create_community_post: '发布帖子',
  update_community_post: '编辑帖子',
  delete_community_post: '删除帖子',
  list_post_comments: '查看评论',
  create_community_comment: '发表评论',
  delete_community_comment: '删除评论',
  like_community_target: '点赞/取消点赞',
  list_my_community_content: '我的社区内容',
  get_today_plan: '今日计划',
  list_study_plan: '查询学习计划',
  get_plan_detail: '查看计划详情',
  get_plan_calendar: '计划日历概览',
  get_weekly_report: '生成周报',
  create_study_plan: '创建学习计划',
  update_study_plan: '更新学习计划',
  delete_study_plan: '删除学习计划',
  list_note_folders: '查询笔记文件夹',
  create_note_folder: '新建笔记文件夹',
  rename_note_folder: '重命名文件夹',
  delete_note_folder: '删除文件夹',
  list_notes: '查询笔记列表',
  get_note: '查看笔记',
  create_note: '新建笔记',
  update_note: '更新笔记',
  move_note: '移动笔记',
  delete_note: '删除笔记',
} as const
