local current = redis.call('GET', KEYS[1])
if not current then
    current = '0'
end

local next_bucket = (tonumber(current) + 1) % 2
redis.call('SET', KEYS[1], next_bucket)
return tonumber(current)
