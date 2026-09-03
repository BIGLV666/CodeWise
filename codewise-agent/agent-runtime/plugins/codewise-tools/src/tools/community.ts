/**
 * 社区工具族：帖子信息流/搜索/热榜、帖子与评论的增删改、显式点赞、「我的内容」。
 *
 * 对应 service-community 的 agent 专用接口（/api/community/agent*），与网页端分离成类。
 * 关键差异：公开信息流支持 latest 降序游标；点赞是显式终态（幂等，避免重试震荡）；
 * 发帖/发评论的 requestId 由客户端生成，重复请求显式返回 duplicate=true；
 * 「我的内容」正文已被服务端截断。发帖/改帖会进入待审核（status=0），公开不可见。
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

/** 公开信息流/列表单页上限（与 Java 侧一致）。 */
const MAX_PAGE_SIZE = 100

/** 帖子列表项（HomePostVo）投影：Java 驼峰 → snake_case，丢弃头像等无关字段。 */
function projectPost(data: Record<string, JsonValue>): Record<string, JsonValue> {
  return {
    post_id: data['postId'] ?? null,
    post_title: data['postTitle'] ?? null,
    tags: data['tags'] ?? null,
    user_id: data['userId'] ?? null,
    user_name: data['userName'] ?? null,
    like_count: data['likeCount'] ?? null,
    comment_count: data['commentCount'] ?? null,
    create_time: data['createTime'] ?? null,
    update_time: data['updateTime'] ?? null,
  }
}

/** 评论（CommentVo）投影。 */
function projectComment(data: Record<string, JsonValue>): Record<string, JsonValue> {
  return {
    comment_id: data['commentId'] ?? null,
    comment: data['comment'] ?? null,
    user_id: data['userId'] ?? null,
    user_name: data['userName'] ?? null,
    post_id: data['postId'] ?? null,
    root_comment_id: data['rootCommentId'] ?? null,
    reply_user_id: data['replyUserId'] ?? null,
    reply_user_name: data['replyUserName'] ?? null,
    like_count: data['likeCount'] ?? null,
    is_like: data['isLike'] ?? null,
    create_time: data['createTime'] ?? null,
  }
}

/** 游标分页结构投影。 */
function projectPage(
  data: Record<string, JsonValue>,
  mapRecord: (r: Record<string, JsonValue>) => Record<string, JsonValue>,
): Record<string, JsonValue> {
  const rawRecords = data['records']
  const records = Array.isArray(rawRecords)
    ? (rawRecords as Record<string, JsonValue>[]).map(mapRecord)
    : []
  return {
    records,
    next_cursor: data['nextCursor'] ?? null,
    has_next: data['hasNext'] ?? null,
    total: data['total'] ?? null,
  }
}

/** 校验 pageSize 并钳制到 1-100。 */
function normalizePageSize(value: unknown): number {
  if (value === undefined || value === null) {
    return 20
  }
  const n = Number(value)
  if (!Number.isInteger(n) || n < 1 || n > MAX_PAGE_SIZE) {
    throw new GatewayError(`page_size 必须是 1-${MAX_PAGE_SIZE} 之间的整数`)
  }
  return n
}

/** 浏览公开帖子信息流（仅审核通过的帖子，latest 从最新往回翻）。 */
export function listCommunityPosts(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'list_community_posts',
    description: '浏览社区公开帖子（仅审核通过的帖子），游标分页。'
      + 'order=latest 从最新往回翻（默认），oldest 从最旧往前翻。',
    parameters: {
      last_id: { type: 'integer', description: '翻页游标：上一页返回的 next_cursor。' },
      page_size: { type: 'integer', description: '每页条数，1-100，默认 20。' },
      order: { type: 'string', enum: ['latest', 'oldest'], description: '排序：latest 最新在前（默认），oldest 最旧在前。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const params: Record<string, string | number> = { pageSize: normalizePageSize(args.page_size) }
      if (args.last_id !== undefined) {
        params['lastId'] = requirePositiveId(args.last_id, '翻页游标 last_id')
      }
      const order = args.order ?? 'latest'
      if (order !== 'latest' && order !== 'oldest') {
        throw new GatewayError('order 仅支持 latest / oldest')
      }
      params['order'] = order
      const data = await gateway.request('GET', '/api/community/agent/posts', { params, signal: exec.signal })
      return projectPage(requireObject(data, '帖子列表'), projectPost)
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.list_community_posts}（${args.order ?? 'latest'}）`,
      kind: 'read',
    }),
  })
}

/** 搜索帖子（关键词或标签二选一）。 */
export function searchCommunityPosts(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'search_community_posts',
    description: '搜索社区帖子：按标题关键词模糊搜索，或按完整标签名搜索（二者必须且只能提供一个）。',
    parameters: {
      keyword: { type: 'string', description: '标题关键词（≤50 字符），与 tag 二选一。' },
      tag: { type: 'string', description: '完整标签名，与 keyword 二选一。' },
      limit: { type: 'integer', description: '返回条数，1-100，默认 20。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'array', items: { type: 'object', additionalProperties: true } },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const keyword = typeof args.keyword === 'string' ? args.keyword.trim() : ''
      const tag = typeof args.tag === 'string' ? args.tag.trim() : ''
      if (Boolean(keyword) === Boolean(tag)) {
        throw new GatewayError('keyword 与 tag 必须且只能提供一个')
      }
      const params: Record<string, string | number> = { limit: normalizePageSize(args.limit) }
      if (keyword) params['keyword'] = keyword
      if (tag) params['tag'] = tag
      const data = await gateway.request('GET', '/api/community/agent/posts/search', { params, signal: exec.signal })
      return requireArray(data, '搜索结果').map(projectPost)
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.search_community_posts}：${args.keyword ?? args.tag ?? ''}`,
      kind: 'search',
    }),
  })
}

/** 社区热榜。 */
export function getCommunityHotPosts(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'get_community_hot_posts',
    description: '获取当前热度最高的社区帖子，最多 10 条。',
    parameters: {},
    timeoutMs: 20_000,
    output: {
      schema: { type: 'array', items: { type: 'object', additionalProperties: true } },
      render: (_args, value) => renderJson(value),
    },
    execute: async (_args, exec) => {
      const data = await gateway.request('GET', '/api/community/agent/posts/hot', { signal: exec.signal })
      return requireArray(data, '热榜').map(projectPost)
    },
    presentCall: () => ({ card: 'generic', title: TOOL_TITLES.get_community_hot_posts, kind: 'read' }),
  })
}

/** 帖子详情。 */
export function getCommunityPost(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'get_community_post',
    description: '查询帖子详情：正文、标签、点赞状态、相关帖子推荐；待审核/已下架内容仅作者本人可见。',
    parameters: {
      post_id: { type: 'integer', required: true, description: '帖子 ID。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const postId = requirePositiveId(args.post_id, '帖子 ID')
      const data = await gateway.request('GET', '/api/community/agent/post', {
        params: { postId },
        signal: exec.signal,
      })
      return requireObject(data, '帖子详情')
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.get_community_post} #${args.post_id}`,
      kind: 'read',
    }),
  })
}

/** 发帖（进入待审核）。 */
export function createCommunityPost(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'create_community_post',
    description: '为当前用户发布社区帖子。新帖会进入待审核状态（status=0），管理员审核通过前仅作者本人可见；'
      + '仅当用户明确要求发帖时才可调用。',
    parameters: {
      title: { type: 'string', required: true, description: '帖子标题，1-200 字符。' },
      content: { type: 'string', required: true, description: '帖子正文，不超过 20000 字符。' },
      tags: { type: 'array', items: { type: 'string' }, description: '标签列表，可选，每个标签 <20 字符。' },
    },
    timeoutMs: 30_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec): Promise<Record<string, JsonValue>> => {
      const title = String(args.title ?? '').trim()
      const content = String(args.content ?? '')
      if (!title) throw new GatewayError('帖子标题不能为空')
      if (!content.trim()) throw new GatewayError('帖子正文不能为空')
      if (content.length > 20_000) throw new GatewayError('帖子正文长度不能超过 20000 字符')
      const body: Record<string, JsonValue> = {
        postTitle: title,
        postContent: content,
        requestId: randomUUID(),
      }
      if (Array.isArray(args.tags)) {
        body['tags'] = args.tags.filter((t: unknown) => typeof t === 'string').map((t: string) => t.trim()).filter(Boolean)
      }
      const data = await gateway.request('POST', '/api/community/agent/post', { json: body, signal: exec.signal })
      const result = requireObject(data, '发帖结果')
      return {
        duplicate: result['duplicate'] ?? false,
        post_id: result['postId'] ?? null,
        status: result['status'] ?? null,
        review_note: result['duplicate'] ? null : '帖子已创建，等待审核通过后公开可见',
      }
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.create_community_post}：${String(args.title ?? '').trim() || '(空)'}`,
      kind: 'edit',
    }),
  })
}

/** 编辑帖子（回到待审核）。 */
export function updateCommunityPost(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'update_community_post',
    description: '编辑当前用户的帖子；已发布内容编辑后会重新进入待审核，审核通过前公开不可见。'
      + '仅当用户明确要求修改自己的帖子时才可调用。',
    parameters: {
      post_id: { type: 'integer', required: true, description: '帖子 ID。' },
      title: { type: 'string', required: true, description: '新标题，1-200 字符。' },
      content: { type: 'string', required: true, description: '新正文，不超过 20000 字符。' },
      tags: { type: 'array', items: { type: 'string' }, description: '新标签列表，可选；传空数组表示清空标签。' },
    },
    timeoutMs: 30_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const postId = requirePositiveId(args.post_id, '帖子 ID')
      const title = String(args.title ?? '').trim()
      const content = String(args.content ?? '')
      if (!title) throw new GatewayError('帖子标题不能为空')
      if (!content.trim()) throw new GatewayError('帖子正文不能为空')
      if (content.length > 20_000) throw new GatewayError('帖子正文长度不能超过 20000 字符')
      const body: Record<string, JsonValue> = { postTitle: title, postContent: content }
      if (Array.isArray(args.tags)) {
        body['tags'] = args.tags.filter((t: unknown) => typeof t === 'string').map((t: string) => t.trim()).filter(Boolean)
      }
      const data = await gateway.request('PUT', '/api/community/agent/post', {
        params: { postId },
        json: body,
        signal: exec.signal,
      })
      return requireObject(data, '编辑结果')
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.update_community_post} #${args.post_id}`,
      kind: 'edit',
    }),
  })
}

/** 删除帖子（不可恢复）。 */
export function deleteCommunityPost(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'delete_community_post',
    description: '删除当前用户的帖子（硬删除、不可恢复；评论与点赞异步清理）。'
      + '仅当用户明确要求删除自己的帖子时才可调用。',
    parameters: {
      post_id: { type: 'integer', required: true, description: '帖子 ID。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const postId = requirePositiveId(args.post_id, '帖子 ID')
      const data = await gateway.request('DELETE', '/api/community/agent/post', {
        params: { postId },
        signal: exec.signal,
      })
      if (typeof data !== 'object' || data === null || Array.isArray(data)) {
        return { success: true }
      }
      return data
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.delete_community_post} #${args.post_id}`,
      kind: 'edit',
    }),
  })
}

/** 帖子评论列表。 */
export function listPostComments(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'list_post_comments',
    description: '列出社区帖子的公开评论，游标分页（含当前用户点赞状态）。'
      + '传 root_comment_id 可只看某条根评论下的回复。',
    parameters: {
      post_id: { type: 'integer', required: true, description: '帖子 ID。' },
      last_id: { type: 'integer', description: '翻页游标：上一页返回的 next_cursor。' },
      page_size: { type: 'integer', description: '每页条数，1-100，默认 20。' },
      root_comment_id: { type: 'integer', description: '只看某条根评论下的回复（可选）。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const postId = requirePositiveId(args.post_id, '帖子 ID')
      const params: Record<string, string | number> = { postId, pageSize: normalizePageSize(args.page_size) }
      if (args.last_id !== undefined) {
        params['lastId'] = requirePositiveId(args.last_id, '翻页游标 last_id')
      }
      if (args.root_comment_id !== undefined) {
        params['rootCommentId'] = requirePositiveId(args.root_comment_id, '根评论 ID')
      }
      const data = await gateway.request('GET', '/api/community/agent/comments', { params, signal: exec.signal })
      return projectPage(requireObject(data, '评论列表'), projectComment)
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.list_post_comments} #${args.post_id}`,
      kind: 'read',
    }),
  })
}

/** 发表评论。 */
export function createCommunityComment(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'create_community_comment',
    description: '在社区帖子下发表评论（即时可见）；回复某条评论时传 root_comment_id。'
      + '仅当用户明确要求评论时才可调用。',
    parameters: {
      post_id: { type: 'integer', required: true, description: '帖子 ID。' },
      comment: { type: 'string', required: true, description: '评论内容，不超过 2000 字符。' },
      root_comment_id: { type: 'integer', description: '回复某条根评论时传该评论 ID。' },
      reply_user_id: { type: 'integer', description: '被回复用户 ID，可选。' },
      reply_user_name: { type: 'string', description: '被回复用户名，可选。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec): Promise<Record<string, JsonValue>> => {
      const postId = requirePositiveId(args.post_id, '帖子 ID')
      const comment = String(args.comment ?? '')
      if (!comment.trim()) throw new GatewayError('评论内容不能为空')
      if (comment.length > 2000) throw new GatewayError('评论长度不能超过 2000 字符')
      const body: Record<string, JsonValue> = { postId, comment, requestId: randomUUID() }
      if (args.root_comment_id !== undefined) body['rootCommentId'] = requirePositiveId(args.root_comment_id, '根评论 ID')
      if (args.reply_user_id !== undefined) body['replyUserId'] = requirePositiveId(args.reply_user_id, '被回复用户 ID')
      if (typeof args.reply_user_name === 'string' && args.reply_user_name.trim()) {
        body['replyUserName'] = args.reply_user_name.trim()
      }
      const data = await gateway.request('POST', '/api/community/agent/comments', { json: body, signal: exec.signal })
      const result = requireObject(data, '评论结果')
      return {
        duplicate: result['duplicate'] ?? false,
        comment_id: result['commentId'] ?? null,
        post_id: result['postId'] ?? null,
        status: result['status'] ?? null,
      }
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.create_community_comment} #${args.post_id}`,
      kind: 'edit',
    }),
  })
}

/** 删除评论。 */
export function deleteCommunityComment(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'delete_community_comment',
    description: '删除当前用户的评论（根评论会级联删除其下回复，异步清理）。'
      + '仅当用户明确要求删除自己的评论时才可调用。',
    parameters: {
      comment_id: { type: 'integer', required: true, description: '评论 ID。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const commentId = requirePositiveId(args.comment_id, '评论 ID')
      const data = await gateway.request('DELETE', '/api/community/agent/comments', {
        params: { commentId },
        signal: exec.signal,
      })
      if (typeof data !== 'object' || data === null || Array.isArray(data)) {
        return { success: true }
      }
      return data
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.delete_community_comment} #${args.comment_id}`,
      kind: 'edit',
    }),
  })
}

/** 显式点赞/取消点赞（幂等）。 */
export function likeCommunityTarget(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'like_community_target',
    description: '对帖子或评论点赞/取消点赞（幂等：声明期望终态，已处于该状态则不会重复操作）。'
      + '仅当用户明确要求点赞或取消点赞时才可调用。',
    parameters: {
      target_type: { type: 'string', required: true, enum: ['post', 'comment'], description: '目标类型。' },
      target_id: { type: 'integer', required: true, description: '帖子 ID 或评论 ID。' },
      action: { type: 'string', required: true, enum: ['like', 'unlike'], description: '期望终态：like=点赞，unlike=取消点赞。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const targetId = requirePositiveId(args.target_id, '目标 ID')
      const targetType = args.target_type === 'post' ? 'POST' : 'COMMENT'
      const action = args.action
      if (action !== 'like' && action !== 'unlike') {
        throw new GatewayError('action 仅支持 like / unlike')
      }
      const data = await gateway.request('POST', '/api/community/agent/likes', {
        json: { targetType, targetId, action },
        signal: exec.signal,
      })
      const result = requireObject(data, '点赞结果')
      return {
        liked: result['liked'] ?? null,
        changed: result['changed'] ?? null,
      }
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.like_community_target} ${args.target_type} #${args.target_id} → ${args.action}`,
      kind: 'edit',
    }),
  })
}

/** 我的社区内容（瘦身）。 */
export function listMyCommunityContent(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'list_my_community_content',
    description: '列出当前用户发布的社区内容（帖子/评论/题解），含审核状态与拒绝原因；'
      + '正文已被截断，完整帖子正文用 get_community_post 查询。',
    parameters: {
      type: { type: 'string', required: true, enum: ['post', 'comment', 'solution'], description: '内容类型。' },
      last_id: { type: 'integer', description: '翻页游标：上一页返回的 next_cursor。' },
      page_size: { type: 'integer', description: '每页条数，1-100，默认 20。' },
    },
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (args, exec) => {
      const type = String(args.type ?? '').toUpperCase()
      if (!['POST', 'COMMENT', 'SOLUTION'].includes(type)) {
        throw new GatewayError('type 仅支持 post / comment / solution')
      }
      const params: Record<string, string | number> = { type, pageSize: normalizePageSize(args.page_size) }
      if (args.last_id !== undefined) {
        params['lastId'] = requirePositiveId(args.last_id, '翻页游标 last_id')
      }
      const data = await gateway.request('GET', '/api/community/agent/mine', { params, signal: exec.signal })
      return projectPage(requireObject(data, '我的内容'), r => ({
        target_id: r['targetId'] ?? null,
        type: r['type'] ?? null,
        title: r['title'] ?? null,
        content: r['content'] ?? null,
        status: r['status'] ?? null,
        reject_reason: r['rejectReason'] ?? null,
        tags: r['tags'] ?? null,
        root_id: r['rootId'] ?? null,
        root_type: r['rootType'] ?? null,
        question_id: r['questionId'] ?? null,
        create_time: r['createTime'] ?? null,
      }))
    },
    presentCall: args => ({
      card: 'generic',
      title: `${TOOL_TITLES.list_my_community_content}（${args.type}）`,
      kind: 'read',
    }),
  })
}
