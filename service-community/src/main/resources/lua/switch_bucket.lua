local current = redis.call('GET', KEYS[1])
if not current then
    current = '0'
end

local current_number = tonumber(current)
if current_number ~= 0 and current_number ~= 1 then
    current_number = 0
end

local next_bucket = 1 - current_number
redis.call('SET', KEYS[1], next_bucket)
return current_number
