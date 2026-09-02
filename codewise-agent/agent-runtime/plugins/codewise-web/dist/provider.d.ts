/**
 * 受控 HTTP fetch provider：把官方 `HttpFetchProvider` 包一层 SSRF 守卫。
 *
 * 官方 provider 负责 URL 卫生（同源重定向、长度/凭据、字节上限、charset
 * 解码），但它不做内网防护。本 provider 在把请求交给它之前，先对 URL 的
 * hostname 做 DNS 解析 + 黑名单校验（见 guard.ts），命中环回/私有/保留地址
 * 即抛结构化 `WebError(WEB_BLOCKED_URL)`。
 */
import type { WebFetchProvider, WebFetchRequest, WebFetchResult } from '@deepseek-ai/dsh-web';
import type { HttpFetchLimits } from '@deepseek-ai/dsh-web-fetch-http';
import { type HostLookup } from './guard.js';
/** 注册到 ctx.web 的稳定 id（官方 http provider 的占位，CodeWise 只用守卫版）。 */
export declare const GUARDED_FETCH_PROVIDER_ID = "http";
/** 带 SSRF 守卫的 HTTP(S) fetch provider。 */
export declare class GuardedHttpFetchProvider implements WebFetchProvider {
    readonly id = "http";
    private readonly inner;
    private readonly lookupImpl?;
    constructor(limits: HttpFetchLimits, lookupImpl?: HostLookup);
    /** 匿名公网抓取无凭据前提，恒可用。 */
    available(): boolean;
    fetch(request: WebFetchRequest, signal?: AbortSignal): Promise<WebFetchResult>;
}
