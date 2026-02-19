-- Velocity Check Multi-Key Lua Script
-- Atomically increments multiple counters and sets expiry on first increment.
-- Returns a flat array: [count1, exceeded1, count2, exceeded2, ...]
--
-- KEYS[1..N]               = velocity keys
-- ARGV[1..N]               = window_seconds for each key
-- ARGV[N+1..2N]            = threshold for each key

local n = #KEYS
local result = {}

for i = 1, n do
    local key = KEYS[i]
    local window_seconds = tonumber(ARGV[i])
    local threshold = tonumber(ARGV[n + i])

    local count = redis.call('INCR', key)
    if count == 1 then
        redis.call('EXPIRE', key, window_seconds)
    end

    local exceeded = 0
    if count >= threshold then
        exceeded = 1
    end

    result[(i - 1) * 2 + 1] = count
    result[(i - 1) * 2 + 2] = exceeded
end

return result
