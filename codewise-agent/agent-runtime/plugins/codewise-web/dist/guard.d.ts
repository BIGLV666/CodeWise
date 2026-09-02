/**
 * SSRF 守卫：模型可控 URL 的 host 校验。
 *
 * 安全约束（部署硬性要求）：
 * - 仅允许 http/https；发请求前校验 host；
 * - 拒绝 localhost、环回、私有和保留地址（IPv4/IPv6，含 DNS 解析结果与
 *   IPv4-mapped / NAT64 / 6to4 等内嵌 IPv4 的变体）。
 *
 * 校验时机：在把请求交给官方 HTTP fetch provider 之前、对 URL 的 hostname
 * 做一次 DNS 解析并逐地址比对黑名单；解析失败（含 NXDOMAIN）一律拒绝
 * （fail-closed）。官方 provider 自身只做同源重定向 + 长度/凭据校验，不做
 * 内网防护，因此本守卫是 CodeWise 部署里模型可控抓取的唯一内网边界。
 *
 * 已知残差：同源重定向内，同一 hostname 在后续连接时可能被 DNS 重绑定到
 * 私有地址（TOCTOU）。要彻底闭合需在传输层固定解析结果（undici 自定义
 * dispatcher），属后续加固项；当前实现已满足“发请求前校验 host”的约束。
 */
/** 可注入的 DNS 解析函数，便于测试替身；返回全部地址（含 family）。 */
export interface HostLookup {
    (hostname: string): Promise<{
        address: string;
        family: number;
    }[]>;
}
/** 判断一个 IPv4 地址是否落在黑名单区间内。 */
export declare function isBlockedIpv4(ip: string): boolean;
/** 判断一个 IPv6 地址是否属于环回/私有/链路本地/保留/组播等禁止段。 */
export declare function isBlockedIpv6(ip: string): boolean;
/** 按 `node:net` 的判定结果分发到 IPv4/IPv6 黑名单检查。 */
export declare function isBlockedAddress(address: string): boolean;
/** hostname 层面的快速失败：`localhost`（任意大小写）与显式回环字面量。 */
export declare function isLoopbackHostname(hostname: string): boolean;
/** 校验模型可控 URL 的可抓取形态（http/https、无内嵌凭据、限长）。 */
export declare function parseFetchableUrl(raw: string, maxUrlLength?: number): URL;
/**
 * 校验 hostname 是否可公开访问：解析全部地址，任一命中黑名单或解析失败
 * 即抛 `WEB_BLOCKED_URL`（fail-closed）。默认使用 `dns.promises.lookup`。
 */
export declare function assertPublicHost(hostname: string, lookupImpl?: HostLookup): Promise<void>;
