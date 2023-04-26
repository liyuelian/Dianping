--这里的KEYS[1]是redis锁的key
--redis.call('get',KEYS[1])就是锁中的线程标识（value）
--ARGV[1]是当前线程标识

-- 比较线程标识与锁中的标识是否一致
if(redis.call('get',KEYS[1]) == ARGV[1]) then
    --一致，则删除锁
    return redis.call('del',KEYS[1])
end
--不一致则直接返回
return 0