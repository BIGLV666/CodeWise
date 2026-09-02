/**
 * codewise-web：CodeWise web 抓取守卫插件（dsh cordis 插件）。
 *
 * 在 ctx.web 上注册唯一一个带 SSRF 守卫的 fetch provider（id ``http``），
 * 供 ``@deepseek-ai/dsh-tool-web`` 的 ``web_fetch`` 工具使用。与官方
 * ``@deepseek-ai/dsh-web-fetch-http`` 插件的区别只有一点：每次抓取前先做
 * host 校验（仅 http/https，拒绝环回/私有/保留地址），官方 provider 本身
 * 不做内网防护，因此 CodeWise 组合里只挂本插件、不挂官方 fetch 插件。
 *
 * 传输/大小限制沿用官方默认值，可在 cordis.yml 里逐项覆盖。
 */
import z from '@deepseek-ai/schemastery';
import { DEFAULT_USER_AGENT } from '@deepseek-ai/dsh-web-fetch-http';
import { GuardedHttpFetchProvider } from './provider.js';
export { GUARDED_FETCH_PROVIDER_ID, GuardedHttpFetchProvider } from './provider.js';
/** Cordis 插件名，供 loader 诊断。 */
export const name = 'codewise-web';
/** 本插件注册到的能力缝隙。 */
export const inject = ['web'];
export const Config = z.object({
    maxUrlLength: z.number().default(2048),
    maxResponseBytes: z.number().default(5_000_000),
    maxBodyChars: z.number().default(100_000),
    timeoutMs: z.number().default(30_000),
    maxRedirects: z.number().default(5),
    userAgent: z.string().default(DEFAULT_USER_AGENT),
});
/** Node 定时器最大延迟：更大的 timeoutMs 会被强制为 1ms，故在配置期拒绝。 */
const MAX_NODE_TIMER_DELAY_MS = 2_147_483_647;
function assertPositiveFinite(field, value) {
    if (!Number.isFinite(value) || value <= 0) {
        throw new Error(`codewise-web: ${field} 必须是正有限数`);
    }
}
function assertNonNegativeInteger(field, value) {
    if (!Number.isInteger(value) || value < 0) {
        throw new Error(`codewise-web: ${field} 必须是非负整数`);
    }
}
/** 注册带守卫的 fetch provider 到 ctx.web。 */
export function apply(ctx, config) {
    const resolved = config;
    assertPositiveFinite('maxUrlLength', resolved.maxUrlLength);
    assertPositiveFinite('maxResponseBytes', resolved.maxResponseBytes);
    assertPositiveFinite('maxBodyChars', resolved.maxBodyChars);
    assertPositiveFinite('timeoutMs', resolved.timeoutMs);
    if (resolved.timeoutMs > MAX_NODE_TIMER_DELAY_MS) {
        throw new Error(`codewise-web: timeoutMs 不得超过 ${MAX_NODE_TIMER_DELAY_MS}`);
    }
    assertNonNegativeInteger('maxRedirects', resolved.maxRedirects);
    const limits = {
        maxUrlLength: resolved.maxUrlLength,
        maxResponseBytes: resolved.maxResponseBytes,
        maxBodyChars: resolved.maxBodyChars,
        timeoutMs: resolved.timeoutMs,
        maxRedirects: resolved.maxRedirects,
        userAgent: resolved.userAgent,
    };
    ctx.web.registerFetchProvider(new GuardedHttpFetchProvider(limits));
}
