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

import { isIP } from 'node:net'
import { lookup as nodeLookup } from 'node:dns/promises'
import { WebError } from '@deepseek-ai/dsh-web'

/** 可注入的 DNS 解析函数，便于测试替身；返回全部地址（含 family）。 */
export interface HostLookup {
  (hostname: string): Promise<{ address: string; family: number }[]>
}

/** 默认解析器：`dns.promises.lookup` 必须传 `{ all: true }` 才返回数组。 */
const defaultLookup: HostLookup = (hostname) => nodeLookup(hostname, { all: true })

/** 单条 IPv4 黑名单区间，闭区间，按无符号 32 位整数比较。 */
type Ipv4Range = readonly [start: number, end: number]

/** IPv4 黑名单：环回、私有、链路本地、CGNAT、保留/文档/组播/广播。 */
const IPV4_BLOCKED_RANGES: readonly Ipv4Range[] = [
  [0x00000000, 0x00ffffff], // 0.0.0.0/8 本网络
  [0x0a000000, 0x0affffff], // 10.0.0.0/8 私有
  [0x64400000, 0x647fffff], // 100.64.0.0/10 CGNAT
  [0x7f000000, 0x7fffffff], // 127.0.0.0/8 环回
  [0xa9fe0000, 0xa9feffff], // 169.254.0.0/16 链路本地
  [0xac100000, 0xac1fffff], // 172.16.0.0/12 私有
  [0xc0000000, 0xc00000ff], // 192.0.0.0/24 IETF 协议分配
  [0xc0000200, 0xc00002ff], // 192.0.2.0/24 TEST-NET-1
  [0xc0586300, 0xc05863ff], // 192.88.99.0/24 6to4 中继（废弃/保留）
  [0xc0a80000, 0xc0a8ffff], // 192.168.0.0/16 私有
  [0xc6120000, 0xc613ffff], // 198.18.0.0/15 基准测试
  [0xc6336400, 0xc63364ff], // 198.51.100.0/24 TEST-NET-2
  [0xcb007100, 0xcb0071ff], // 203.0.113.0/24 TEST-NET-3
  [0xe0000000, 0xefffffff], // 224.0.0.0/4 组播
  [0xf0000000, 0xffffffff], // 240.0.0.0/4 保留（含 255.255.255.255）
]

/** 把 IPv4 点分十进制字符串转成无符号整数；非法输入返回 NaN。 */
function ipv4ToInt(ip: string): number {
  const parts = ip.split('.')
  if (parts.length !== 4) return Number.NaN
  let value = 0
  for (const part of parts) {
    if (!/^\d{1,3}$/.test(part)) return Number.NaN
    const octet = Number(part)
    if (octet > 255) return Number.NaN
    value = value * 256 + octet
  }
  return value >>> 0
}

/** 判断一个 IPv4 地址是否落在黑名单区间内。 */
export function isBlockedIpv4(ip: string): boolean {
  const value = ipv4ToInt(ip)
  if (Number.isNaN(value)) return false
  return IPV4_BLOCKED_RANGES.some(([start, end]) => value >= start && value <= end)
}

/** 把两个 16 位十六进制组（如 "7f00:0001"）还原成点分四段；失败返回空串。 */
function hexGroupPairToIpv4(pair: string): string {
  const groups = pair.split(':')
  if (groups.length !== 2) return ''
  const a = parseInt(groups[0]!, 16)
  const b = parseInt(groups[1]!, 16)
  if (Number.isNaN(a) || Number.isNaN(b)) return ''
  return `${(a >> 8) & 0xff}.${a & 0xff}.${(b >> 8) & 0xff}.${b & 0xff}`
}

/**
 * 从 IPv6 中还原内嵌 IPv4 的点分四段：
 * - IPv4-mapped 点分形式 `::ffff:127.0.0.1`；
 * - IPv4-mapped 十六进制形式 `::ffff:7f00:1`；
 * - NAT64 `64:ff9b::7f00:1`（64:ff9b::/96 末 32 位为 IPv4）；
 * - 6to4 `2002:7f00:1::`（2002::/16 后 32 位为 IPv4）。
 * 无法识别返回 null。
 */
function extractEmbeddedIpv4(ip: string): string | null {
  const lower = ip.toLowerCase()
  const dotted = lower.match(/^::ffff:(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})$/)
  if (dotted) return dotted[1]!
  const hexMapped = lower.match(/^::ffff:([0-9a-f]{1,4}):([0-9a-f]{1,4})$/)
  if (hexMapped) return hexGroupPairToIpv4(`${hexMapped[1]}:${hexMapped[2]}`)
  const nat64 = lower.match(/^64:ff9b::([0-9a-f]{1,4}):([0-9a-f]{1,4})$/)
  if (nat64) return hexGroupPairToIpv4(`${nat64[1]}:${nat64[2]}`)
  const sixToFour = lower.match(/^2002:([0-9a-f]{1,4}):([0-9a-f]{1,4})::/)
  if (sixToFour) return hexGroupPairToIpv4(`${sixToFour[1]}:${sixToFour[2]}`)
  return null
}

/** 判断一个 IPv6 地址是否属于环回/私有/链路本地/保留/组播等禁止段。 */
export function isBlockedIpv6(ip: string): boolean {
  // 内嵌 IPv4 的变体先还原成 IPv4 再按 IPv4 黑名单判定。
  const embedded = extractEmbeddedIpv4(ip)
  if (embedded !== null) return isBlockedIpv4(embedded)

  const lower = ip.toLowerCase()
  if (lower === '::' || lower === '::1') return true // 未指定 / 环回
  if (lower.startsWith('100:')) return true // 100::/64 丢弃前缀
  if (lower.startsWith('fc') || lower.startsWith('fd')) return true // fc00::/7 ULA
  if (lower.startsWith('fe8') || lower.startsWith('fe9') || lower.startsWith('fea') || lower.startsWith('feb')) return true // fe80::/10 链路本地
  if (lower.startsWith('ff')) return true // ff00::/8 组播
  if (lower.startsWith('2001:db8:')) return true // 2001:db8::/32 文档
  if (lower.startsWith('2001:0') || lower.startsWith('2001::')) return true // 2001::/23 保留（含 2001::/32 Teredo）
  return false
}

/** 按 `node:net` 的判定结果分发到 IPv4/IPv6 黑名单检查。 */
export function isBlockedAddress(address: string): boolean {
  const family = isIP(address)
  if (family === 4) return isBlockedIpv4(address)
  if (family === 6) return isBlockedIpv6(address)
  return false
}

/** hostname 层面的快速失败：`localhost`（任意大小写）与显式回环字面量。 */
export function isLoopbackHostname(hostname: string): boolean {
  return hostname.toLowerCase() === 'localhost'
}

/** 校验模型可控 URL 的可抓取形态（http/https、无内嵌凭据、限长）。 */
export function parseFetchableUrl(raw: string, maxUrlLength = 2048): URL {
  if (raw.length === 0 || raw.length > maxUrlLength) {
    throw new WebError(`URL 长度必须在 1..${maxUrlLength} 字节内`, 'WEB_INVALID_URL')
  }
  let url: URL
  try {
    url = new URL(raw)
  } catch (cause) {
    throw new WebError(`非法 URL：${raw}`, 'WEB_INVALID_URL', { cause })
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new WebError(`不支持的协议 "${url.protocol}"（仅允许 http/https）`, 'WEB_INVALID_URL')
  }
  if (url.username.length > 0 || url.password.length > 0) {
    throw new WebError('URL 中不允许携带凭据', 'WEB_BLOCKED_URL')
  }
  return url
}

/**
 * 校验 hostname 是否可公开访问：解析全部地址，任一命中黑名单或解析失败
 * 即抛 `WEB_BLOCKED_URL`（fail-closed）。默认使用 `dns.promises.lookup`。
 */
export async function assertPublicHost(
  hostname: string,
  lookupImpl: HostLookup = defaultLookup,
): Promise<void> {
  if (hostname.length === 0 || isLoopbackHostname(hostname)) {
    throw new WebError(`禁止访问 localhost/环回地址：${hostname}`, 'WEB_BLOCKED_URL')
  }
  // 字面量 IP 无需 DNS，直接判定。
  if (isIP(hostname) !== 0) {
    if (isBlockedAddress(hostname)) {
      throw new WebError(`禁止访问非公网地址：${hostname}`, 'WEB_BLOCKED_URL')
    }
    return
  }
  let records: { address: string; family: number }[]
  try {
    records = await lookupImpl(hostname)
  } catch (cause) {
    throw new WebError(`域名解析失败：${hostname}`, 'WEB_BLOCKED_URL', { cause })
  }
  if (records.length === 0) {
    throw new WebError(`域名无解析结果：${hostname}`, 'WEB_BLOCKED_URL')
  }
  for (const record of records) {
    if (isBlockedAddress(record.address)) {
      throw new WebError(`域名解析到非公网地址：${hostname} → ${record.address}`, 'WEB_BLOCKED_URL')
    }
  }
}
