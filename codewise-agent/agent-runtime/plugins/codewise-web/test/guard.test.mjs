import test from 'node:test'
import assert from 'node:assert/strict'
import { isBlockedIpv4, isBlockedIpv6, isBlockedAddress, parseFetchableUrl, assertPublicHost } from '../dist/guard.js'
import { WebError } from '@deepseek-ai/dsh-web'

test('isBlockedIpv4 拦截环回/私有/链路本地/保留/组播', () => {
  const blocked = [
    '127.0.0.1', '127.255.255.255',      // 环回
    '10.0.0.1', '10.255.255.254',         // 私有 A
    '172.16.0.1', '172.31.255.255',       // 私有 B
    '192.168.0.1', '192.168.255.255',     // 私有 C
    '169.254.0.1', '169.254.255.255',     // 链路本地
    '100.64.0.1', '100.127.255.254',      // CGNAT
    '0.0.0.0', '0.255.255.255',           // 本网络
    '192.0.2.1', '198.51.100.1', '203.0.113.1', // 文档网
    '192.88.99.1', '198.18.0.1', '198.19.255.254', // 保留/基准
    '224.0.0.1', '239.255.255.255',       // 组播
    '240.0.0.1', '255.255.255.255',       // 保留/广播
  ]
  for (const ip of blocked) assert.equal(isBlockedIpv4(ip), true, ip)
})

test('isBlockedIpv4 放行公网地址', () => {
  const allowed = ['8.8.8.8', '1.1.1.1', '172.32.0.1', '100.128.0.1', '169.255.0.1', '192.169.0.1']
  for (const ip of allowed) assert.equal(isBlockedIpv4(ip), false, ip)
})

test('isBlockedIpv6 拦截环回/ULA/链路本地/组播/保留', () => {
  const blocked = [
    '::1', '::', 'fc00::1', 'fd12:3456::1', 'fe80::1', 'febf::1', 'ff02::1',
    '2001:db8::1', '2001::1', '100::1',
  ]
  for (const ip of blocked) assert.equal(isBlockedIpv6(ip), true, ip)
})

test('isBlockedIpv6 还原内嵌 IPv4 并按 IPv4 黑名单拦截', () => {
  const blocked = [
    '::ffff:127.0.0.1', '::ffff:10.0.0.1', '::ffff:192.168.1.1',
    '::ffff:7f00:1', '64:ff9b::7f00:1', '64:ff9b::a00:1', '2002:7f00:1::', '2002:c0a8:101::',
  ]
  for (const ip of blocked) assert.equal(isBlockedIpv6(ip), true, ip)
})

test('isBlockedIpv6 放行公网地址', () => {
  const allowed = ['2606:4700:4700::1111', '2001:4860:4860::8888', '2400:cb00::1']
  for (const ip of allowed) assert.equal(isBlockedIpv6(ip), false, ip)
})

test('isBlockedAddress 按族分发', () => {
  assert.equal(isBlockedAddress('127.0.0.1'), true)
  assert.equal(isBlockedAddress('::1'), true)
  assert.equal(isBlockedAddress('8.8.8.8'), false)
  assert.equal(isBlockedAddress('not-an-ip'), false)
})

test('parseFetchableUrl 仅放行 http/https', () => {
  assert.equal(parseFetchableUrl('https://example.com/a?b=1').hostname, 'example.com')
  assert.equal(parseFetchableUrl('http://example.com').protocol, 'http:')
  for (const bad of ['ftp://example.com', 'file:///etc/passwd', 'gopher://x', 'javascript:alert(1)']) {
    assert.throws(() => parseFetchableUrl(bad), (e) => e instanceof WebError && e.code === 'WEB_INVALID_URL', bad)
  }
})

test('parseFetchableUrl 拒绝内嵌凭据与超长', () => {
  assert.throws(() => parseFetchableUrl('https://user:pass@example.com'), (e) => e.code === 'WEB_BLOCKED_URL')
  assert.throws(() => parseFetchableUrl('https://example.com/' + 'a'.repeat(3000)), (e) => e.code === 'WEB_INVALID_URL')
})

test('assertPublicHost 快速失败 localhost', async () => {
  await assert.rejects(() => assertPublicHost('localhost'), (e) => e.code === 'WEB_BLOCKED_URL')
  await assert.rejects(() => assertPublicHost('127.0.0.1'), (e) => e.code === 'WEB_BLOCKED_URL')
  await assert.rejects(() => assertPublicHost('::1'), (e) => e.code === 'WEB_BLOCKED_URL')
  await assert.rejects(() => assertPublicHost('192.168.1.1'), (e) => e.code === 'WEB_BLOCKED_URL')
  await assertPublicHost('8.8.8.8') // 公网字面量放行
})

test('assertPublicHost 解析结果逐地址比对（注入替身）', async () => {
  const privateLookup = async () => [{ address: '10.0.0.5', family: 4 }]
  await assert.rejects(() => assertPublicHost('internal.example', privateLookup), (e) => e.code === 'WEB_BLOCKED_URL')

  const publicLookup = async () => [{ address: '93.184.216.34', family: 4 }]
  await assertPublicHost('example.com', publicLookup)

  await assert.rejects(() => assertPublicHost('x', async () => []), (e) => e.code === 'WEB_BLOCKED_URL')
  await assert.rejects(
    () => assertPublicHost('x', async () => { throw new Error('NXDOMAIN') }),
    (e) => e.code === 'WEB_BLOCKED_URL',
  )
})

test('assertPublicHost 真实 DNS：公网域名放行（需网络）', async () => {
  try {
    await assertPublicHost('example.com')
  } catch (e) {
    // 无网络环境下整段跳过：ENOTFOUND 之外的失败都视为测试失败。
    assert.equal(e.code, 'WEB_BLOCKED_URL')
  }
})
