--[[
  在重建开始时复制线上排行榜，形成不可变的分数基线。

  KEYS[1]: 当前正式排行榜 ZSet。它可能在脚本执行后继续接收实时增量。
  KEYS[2]: 本次重建专用的基线 ZSet，调用方必须使用唯一 Key。

  返回值：复制的成员数量。

  该脚本在 Redis 内一次执行，复制期间不会被其他 Redis 命令插入；后续
  切换脚本通过“正式榜当前值 - 基线值”计算重建期间发生的变化。
]]
local active_ranking = KEYS[1]
local baseline_ranking = KEYS[2]

-- 清理同名残留，保证重试时基线只代表本次重建开始时的状态。
redis.call('DEL', baseline_ranking)

-- 复制全部成员及分数，而不是只复制首页，确保任意成员的删除和分数变化都可比较。
local entries = redis.call('ZRANGE', active_ranking, 0, -1, 'WITHSCORES')
for index = 1, #entries, 2 do
    redis.call('ZADD', baseline_ranking, entries[index + 1], entries[index])
end

return #entries / 2
