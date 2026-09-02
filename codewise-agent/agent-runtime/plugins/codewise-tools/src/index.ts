/**
 * codewise-tools：CodeWise 项目工具插件（dsh cordis 插件）。
 *
 * 在 dsh 工具注册表上挂载 CodeWise 内网网关工具。插件从进程环境读取：
 *
 * - ``CODEWISE_GATEWAY_URL``：内网网关地址（http/https），由部署环境固定；
 * - ``CODEWISE_TOKEN_FILE``：会话私有 Bearer token 文件，由 Python 门面每轮刷新。
 *
 * 新增工具的方式：在 ``src/tools/`` 下编写 ``defineTool`` 定义并在本文件
 * ``apply`` 中注册；参数 schema 校验、超时、错误归一、结果截断均由 dsh
 * 工具管线提供。工具永远不接受模型提供的 URL/路径。
 */

import type { Context } from '@deepseek-ai/cordis'
import z from '@deepseek-ai/schemastery'
import { CodeWiseGateway } from './gateway.js'
import { getCurrentTime, getUserInfo } from './tools/basic.js'
import { getQuestionById, searchQuestion } from './tools/question.js'
import {
  getRecentSubmissions,
  getSubmissionById,
  getSubmissionsByIds,
} from './tools/submission.js'
import {
  getAllReview,
  getReviewRecord,
  getUserReviewConfig,
  updateReviewConfig,
} from './tools/review.js'

export const name = 'codewise-tools'

export const inject = ['tools']

/** 插件配置（当前无外部可配项，环境变量即配置面）。 */
export interface Config {}

/** schemastery 配置校验：空对象。 */
export const Config: z<Config> = z.object({})

/**
 * 注册全部 CodeWise 工具。
 *
 * @param ctx - cordis 上下文，携带工具注册表。
 * @param _config - 未使用的插件配置。
 */
export function apply(ctx: Context, _config: Config): void {
  // 网关客户端在插件加载时构造一次；加载失败（如网关地址缺失/非法）
  // 会直接让插件装载报错，运行时退出，门面据此快速暴露配置问题。
  const gateway = new CodeWiseGateway(
    process.env['CODEWISE_GATEWAY_URL'],
    process.env['CODEWISE_TOKEN_FILE'],
  )

  ctx.tools.register(getCurrentTime())
  ctx.tools.register(getUserInfo(gateway))
  ctx.tools.register(searchQuestion(gateway))
  ctx.tools.register(getQuestionById(gateway))
  ctx.tools.register(getRecentSubmissions(gateway))
  ctx.tools.register(getSubmissionById(gateway))
  ctx.tools.register(getSubmissionsByIds(gateway))
  ctx.tools.register(getReviewRecord(gateway))
  ctx.tools.register(getUserReviewConfig(gateway))
  ctx.tools.register(getAllReview(gateway))
  ctx.tools.register(updateReviewConfig(gateway))
}
