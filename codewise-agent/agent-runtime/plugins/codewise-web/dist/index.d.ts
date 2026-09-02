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
import type { Context } from '@deepseek-ai/cordis';
import z from '@deepseek-ai/schemastery';
export { GUARDED_FETCH_PROVIDER_ID, GuardedHttpFetchProvider } from './provider.js';
/** Cordis 插件名，供 loader 诊断。 */
export declare const name = "codewise-web";
/** 本插件注册到的能力缝隙。 */
export declare const inject: string[];
/** 插件配置：传输/大小限制与 User-Agent（全部有默认值，镜像官方 fetch 插件）。 */
export interface Config {
    /** 请求 URL 最大长度。 */
    maxUrlLength?: number;
    /** 响应体最大字节数。 */
    maxResponseBytes?: number;
    /** 解码后正文最大字符数。 */
    maxBodyChars?: number;
    /** 单次抓取超时（毫秒）。 */
    timeoutMs?: number;
    /** 最多跟随的同源重定向跳数。 */
    maxRedirects?: number;
    /** 每次请求携带的 User-Agent。 */
    userAgent?: string;
}
export declare const Config: z<Config>;
/** 注册带守卫的 fetch provider 到 ctx.web。 */
export declare function apply(ctx: Context, config: Config): void;
