/**
 * CodeWise 内网网关客户端（工具专属）。
 *
 * 安全边界：
 * - 目标 URL 只由部署环境决定（CODEWISE_GATEWAY_URL），工具不接受模型提供的
 *   URL 或路径；模型只能提供参数（ID、关键词等）。未来若新增“模型可控 URL”
 *   的抓取类工具，必须在该处做 SSRF 校验（仅 http/https，拒绝环回/私有/保留地址）。
 * - Bearer token 每次请求时从 CODEWISE_TOKEN_FILE 读取（门面每轮刷新），
 *   绝不缓存、不写日志。
 */

import { readFileSync } from 'node:fs'
import type { JsonValue } from '@deepseek-ai/dsh-tools'

/** Java 侧统一响应包装 Result<T>。 */
interface CodeWiseResult<T = JsonValue> {
  code?: number
  message?: string
  data?: T
}

/**
 * 工具请求路径静态白名单：与 src/tools/ 下工具的实际调用一一对应。
 * 新增工具时必须在此登记；白名单外的路径一律拒绝（fail-closed）。
 */
const ALLOWED_GATEWAY_PATHS: ReadonlySet<string> = new Set([
  '/api/user/getuserbyid',
  '/api/question/getquestionbyid',
  '/api/question/likeserach',
  '/api/question/getsubmitrecordbyid',
  '/api/question/getsubmitrecordsbyuserid',
  '/api/question/getsubmitrecordsbyids',
  '/api/question/agent/detail',
  '/api/community/agent/post',
  '/api/community/agent/posts',
  '/api/community/agent/posts/hot',
  '/api/community/agent/posts/search',
  '/api/community/agent/comments',
  '/api/community/agent/likes',
  '/api/community/agent/mine',
  '/api/review/agent/favorites',
  '/api/review/agent/favorites/questions',
  '/api/review/agent/favorites/move',
  '/api/review/agent/favorites/locate',
  '/api/review/agent/progress/today',
  '/api/review/agent/progress/list',
  '/api/review/agent/progress/detail',
  '/api/review/agent/progress/calendar',
  '/api/review/agent/progress/create',
  '/api/review/agent/progress/update',
  '/api/review/agent/progress/delete',
  '/api/review/progress/report/week',
  '/api/review/agent/note/folders',
  '/api/review/agent/note/list',
  '/api/review/agent/note/detail',
  '/api/review/agent/note/move',
  '/api/review/agent/note',
  '/api/review/review/allreview',
  '/api/review/review/config',
  '/api/review/review/reviewrecord',
])

/** 网关调用失败的统一错误；dsh 工具管线会把它归一为结构化 isError 结果。 */
export class GatewayError extends Error {}

export class CodeWiseGateway {
  private readonly baseUrl: string
  private readonly origin: string
  private readonly tokenFile: string

  constructor(baseUrl: string | undefined, tokenFile: string | undefined) {
    if (!baseUrl || !(baseUrl.startsWith('http://') || baseUrl.startsWith('https://'))) {
      throw new Error('CODEWISE_GATEWAY_URL 未配置或不是 http/https 地址')
    }
    if (!tokenFile) {
      throw new Error('CODEWISE_TOKEN_FILE 未配置（由 Python 门面按会话注入）')
    }
    this.baseUrl = baseUrl.replace(/\/+$/, '')
    this.tokenFile = tokenFile
    this.origin = new URL(this.baseUrl).origin
  }

  /** 发起一次网关调用并解包 Result<T>，失败抛 GatewayError。 */
  async request(
    method: 'GET' | 'POST' | 'PUT' | 'DELETE',
    path: string,
    options: {
      params?: Record<string, string | number>
      json?: JsonValue
      signal?: AbortSignal
    } = {},
  ): Promise<JsonValue> {
    // SSRF 防线（部署硬性要求）：目的地必须钉死在部署配置的网关 origin 上，
    // 且路径只能在静态白名单内——模型可控的只有查询参数与请求体，
    // 永远无法改变请求的主机、端口或路径。
    const url = new URL(this.baseUrl + path)
    if (url.origin !== this.origin) {
      throw new GatewayError('目标地址越界：仅允许部署配置的网关地址')
    }
    if (!ALLOWED_GATEWAY_PATHS.has(url.pathname)) {
      throw new GatewayError('路径不在工具白名单内')
    }
    for (const [key, value] of Object.entries(options.params ?? {})) {
      url.searchParams.set(key, String(value))
    }

    let response: Response
    try {
      response = await fetch(url, {
        method,
        headers: {
          Authorization: `Bearer ${this.readToken()}`,
          Accept: 'application/json',
          ...(options.json !== undefined ? { 'Content-Type': 'application/json' } : {}),
        },
        body: options.json !== undefined ? JSON.stringify(options.json) : undefined,
        signal: options.signal,
      })
    } catch (error) {
      // 网络层失败（超时/连接拒绝/中止）。中止信号直接透传给上层判断。
      if (options.signal?.aborted) throw error
      throw new GatewayError(`CodeWise 服务暂时不可用：${String(error)}`)
    }

    let result: CodeWiseResult
    try {
      result = (await response.json()) as CodeWiseResult
    } catch {
      throw new GatewayError('CodeWise 服务返回了无效数据')
    }
    if (!response.ok || result.code !== 200) {
      throw new GatewayError(result.message || 'CodeWise 服务调用失败')
    }
    return (result.data ?? null) as JsonValue
  }

  /** 每次请求都重新读取 token 文件，保证门面刷新后立即生效。 */
  private readToken(): string {
    try {
      const token = readFileSync(this.tokenFile, 'utf8').trim()
      if (!token) throw new Error('empty')
      return token
    } catch {
      throw new GatewayError('会话凭据不可用，请重新发起对话')
    }
  }
}

/** 收窄 JSON 对象；网关返回的数据形状由 Java 端契约保证。 */
export function requireObject(value: JsonValue, what: string): Record<string, JsonValue> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new GatewayError(`CodeWise 服务返回了无效的${what}`)
  }
  return value as Record<string, JsonValue>
}

/** 收窄 JSON 数组。 */
export function requireArray(value: JsonValue, what: string): Record<string, JsonValue>[] {
  if (!Array.isArray(value)) {
    throw new GatewayError(`CodeWise 服务返回了无效的${what}`)
  }
  return value.filter(
    (item): item is Record<string, JsonValue> =>
      typeof item === 'object' && item !== null && !Array.isArray(item),
  )
}

/** 提交记录列表项的统一投影（不含代码与 userId，避免污染列表上下文）。 */
export function projectSubmission(
  record: Record<string, JsonValue>,
  includeContent: boolean,
): Record<string, JsonValue> {
  const projected: Record<string, JsonValue> = {
    submit_record_id: record['submitRecordId'] ?? null,
    question_id: record['questionId'] ?? null,
    question_title: record['questionTitle'] ?? null,
    submit_time: record['submitTime'] ?? null,
    submit_status: record['submitStatus'] ?? null,
    judge_status: record['judgeStatus'] ?? null,
    time_used: record['timeUsed'] ?? null,
    memory_used: record['memoryUsed'] ?? null,
    language: record['language'] ?? null,
    submit_scene: record['submitScene'] ?? null,
  }
  if (includeContent) {
    projected['submit_content'] = record['submitContent'] ?? null
  }
  return projected
}

/** 校验正整数 ID（模型生成的参数在 schema 之外的业务约束）。 */
export function requirePositiveId(value: number, label: string): number {
  if (!Number.isInteger(value) || value <= 0) {
    throw new GatewayError(`${label} 必须为正整数`)
  }
  return value
}
