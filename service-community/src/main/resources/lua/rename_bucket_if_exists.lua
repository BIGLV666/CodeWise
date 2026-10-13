if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end

return redis.call('RENAMENX', KEYS[1], KEYS[2])
