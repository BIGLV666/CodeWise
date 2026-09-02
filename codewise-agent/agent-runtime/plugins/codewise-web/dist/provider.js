/**
 * 受控 HTTP fetch provider：把官方 `HttpFetchProvider` 包一层 SSRF 守卫。
 *
 * 官方 provider 负责 URL 卫生（同源重定向、长度/凭据、字节上限、charset
 * 解码），但它不做内网防护。本 provider 在把请求交给它之前，先对 URL 的
 * hostname 做 DNS 解析 + 黑名单校验（见 guard.ts），命中环回/私有/保留地址
 * 即抛结构化 `WebError(WEB_BLOCKED_URL)`。
 */
import { HttpFetchProvider } from '@deepseek-ai/dsh-web-fetch-http';
import { assertPublicHost, parseFetchableUrl } from './guard.js';
/** 注册到 ctx.web 的稳定 id（官方 http provider 的占位，CodeWise 只用守卫版）。 */
export const GUARDED_FETCH_PROVIDER_ID = 'http';
/** 带 SSRF 守卫的 HTTP(S) fetch provider。 */
export class GuardedHttpFetchProvider {
    id = GUARDED_FETCH_PROVIDER_ID;
    inner;
    lookupImpl;
    constructor(limits, lookupImpl) {
        this.inner = new HttpFetchProvider(limits);
        this.lookupImpl = lookupImpl;
    }
    /** 匿名公网抓取无凭据前提，恒可用。 */
    available() {
        return true;
    }
    async fetch(request, signal) {
        // 守卫先于任何网络访问：仅 http/https、无内嵌凭据、host 公网可达。
        const url = parseFetchableUrl(request.url);
        await assertPublicHost(url.hostname, this.lookupImpl);
        return this.inner.fetch(request, signal);
    }
}
