--[[
  合并重建期间的实时变化，并原子切换排行榜及帖子缓存。

  KEYS[1]: 正式排行榜 ZSet，重建期间的实时写入目标。
  KEYS[2]: 重建开始时复制的基线 ZSet。
  KEYS[3]: 根据数据库快照构建的临时排行榜 ZSet。
  KEYS[4]: 正式排行榜目标 Key，通常与 KEYS[1] 相同。
  KEYS[5]: 根据临时排行榜前 N 名构建的临时帖子 Hash。
  KEYS[6]: 正式帖子 Hash 目标 Key。

  合并规则：
  1. 正式榜中仍存在的成员，将“当前分数 - 基线分数”作为增量叠加到临时榜。
  2. 正式榜中新出现的成员直接写入临时榜，覆盖审核通过等实时新增。
  3. 基线榜中已不存在于正式榜的成员从临时榜删除，覆盖下架、删除等实时移除。
  4. 上述合并与两个 RENAME 在同一次 Lua 执行中完成，读请求不会看到脚本中间状态。

  注意：Redis Lua 脚本单线程执行；脚本运行期间其他 Redis 命令会等待，因而不会
  在“合并完成”和“Key 替换”之间插入新的排行榜写入。
]]
local active_ranking = KEYS[1]
local baseline_ranking = KEYS[2]
local temporary_ranking = KEYS[3]
local target_ranking = KEYS[4]
local temporary_cache = KEYS[5]
local target_cache = KEYS[6]

-- 将正式榜当前状态与基线比较，把重建期间发生的变化合并到临时榜。
local active_entries = redis.call('ZRANGE', active_ranking, 0, -1, 'WITHSCORES')
for index = 1, #active_entries, 2 do
    local member = active_entries[index]
    local current_score = tonumber(active_entries[index + 1])
    local baseline_score = redis.call('ZSCORE', baseline_ranking, member)
    if baseline_score then
        redis.call('ZINCRBY', temporary_ranking, current_score - tonumber(baseline_score), member)
    else
        redis.call('ZADD', temporary_ranking, current_score, member)
    end
end

-- 基线中存在、正式榜中已不存在的成员，说明其在重建期间被移除。
local baseline_entries = redis.call('ZRANGE', baseline_ranking, 0, -1)
for _, member in ipairs(baseline_entries) do
    if not redis.call('ZSCORE', active_ranking, member) then
        redis.call('ZREM', temporary_ranking, member)
    end
end

-- 临时榜为空时删除正式榜，否则用 RENAME 一次性替换正式榜。
if redis.call('EXISTS', temporary_ranking) == 1 then
    redis.call('RENAME', temporary_ranking, target_ranking)
else
    redis.call('DEL', target_ranking)
end

-- Hash 与 ZSet 使用同一批临时数据完成替换，避免继续使用旧的帖子对象缓存。
if redis.call('EXISTS', temporary_cache) == 1 then
    redis.call('RENAME', temporary_cache, target_cache)
else
    redis.call('DEL', target_cache)
end

-- 基线只服务于本次合并，切换完成后立即清理。
redis.call('DEL', baseline_ranking)
return 1
