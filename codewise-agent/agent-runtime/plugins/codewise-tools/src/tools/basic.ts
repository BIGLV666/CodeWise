/**
 * 基础工具：当前时间与用户信息。
 */

import { defineTool } from '@deepseek-ai/dsh-tools'
import { requireObject, type CodeWiseGateway } from '../gateway.js'
import { TOOL_TITLES, renderJson } from '../render.js'

/** 服务器当前时间。无网络依赖，供模型做时间相关推理。 */
export function getCurrentTime() {
  return defineTool({
    name: 'get_current_time',
    description: '获取服务器当前时间。',
    parameters: {},
    timeoutMs: 3_000,
    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          time: { type: 'string', required: true, description: 'YYYY-MM-DD HH:mm:ss 格式。' },
        },
      },
      render: (_args, value) => renderJson(value),
    },
    execute: async () => {
      const now = new Date()
      const pad = (n: number) => String(n).padStart(2, '0')
      const time =
        `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ` +
        `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
      return { time }
    },
    presentCall: () => ({ card: 'generic', title: TOOL_TITLES.get_current_time, kind: 'other' }),
  })
}

/** 当前登录用户的信息（token 决定身份，模型无法查询他人）。 */
export function getUserInfo(gateway: CodeWiseGateway) {
  return defineTool({
    name: 'get_user_info',
    description: '查询当前用户信息。',
    parameters: {},
    timeoutMs: 20_000,
    output: {
      schema: { type: 'object', additionalProperties: true },
      render: (_args, value) => renderJson(value),
    },
    execute: async (_args, exec) => {
      const data = await gateway.request('GET', '/api/user/getuserbyid', { signal: exec.signal })
      return requireObject(data, '用户信息')
    },
    presentCall: () => ({ card: 'generic', title: TOOL_TITLES.get_user_info, kind: 'read' }),
  })
}
