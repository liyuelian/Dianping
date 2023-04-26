package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @author 李
 * @version 1.0
 */
public class SimpleRedisLock implements ILock {
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识(使用UUID+线程id)
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁（使用UUID+线程id 作为value，"lock:"+业务name 作为key）
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);//防止空指针
    }

    //泛型类型是返回型类型
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    //使用静态常量和静态代码块，类一加载就初始化脚本
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public void unLock() {
        //调用lua脚本
        //public <T> T execute(RedisScript<T> script, List<K> keys, Object... args) {
        //    return this.scriptExecutor.execute(script, keys, args);
        //}
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),//锁的线程标识
                ID_PREFIX + Thread.currentThread().getId());//当前线程标识
    }

    //@Override
    //public void unLock() {
    //    //获取线程标识
    //    String threadId = ID_PREFIX + Thread.currentThread().getId();
    //    //获取redis锁的标识
    //    String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
    //    //判断标识是否一致
    //    if (threadId.equals(id)) {
    //        //一致，则释放锁
    //        stringRedisTemplate.delete(KEY_PREFIX + name);
    //    }
    //}
}
