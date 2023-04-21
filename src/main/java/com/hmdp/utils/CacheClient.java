package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author 李
 * @version 1.0
 * 封装redis工具类
 */
@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 将任意Java对象序列化为json，并存储在string类型的key中，并且可以设置TTL过期时间
     *
     * @param key   缓存的key值
     * @param value 缓存的value值
     * @param time  过期时间值
     * @param unit  过期的时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue()
                .set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 将任意Java对象序列化为json，并存储在string类型的key中，
     * 并且可以设置逻辑过期时间，用户处理缓存击穿问题（针对热点key）
     *
     * @param key   缓存的key值
     * @param value 缓存的value值
     * @param time  过期时间值
     * @param unit  过期的时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        //逻辑过期时间=当前时间+指定的时间
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     *
     * @param keyPrefix  查询的key值的前缀
     * @param id         查询的key值的后缀
     * @param type       要转换的Class类型
     * @param dbFallback 传入的函数
     * @param time       过期时间值
     * @param unit       时间单位
     * @param <R>        泛型
     * @param <ID>       泛型
     * @return 返回指定的类型对象
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断json是否存在
        if (StrUtil.isNotBlank(json)) {
            //存在，转为java对象并返回
            return JSONUtil.toBean(json, type);
        }
        //判断是否为""，如果是，说明该key是为了解决缓存穿透设置的空值
        if ("".equals(json)) {
            //返回错误信息
            return null;
        }
        //不存在，根据id查询数据库——使用函数式编程
        R r = dbFallback.apply(id);
        if (r == null) {//说明数据库中没有该数据
            //缓存空值，应对缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //r存在，则将其写入redis
        this.set(key, r, time, unit);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            Executors.newFixedThreadPool(10);

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题（针对热点key）
     * @param keyPrefix  查询的key值的前缀
     * @param id         查询的key值的后缀
     * @param type       要转换的Class类型
     * @param dbFallback 传入的函数
     * @param time       过期时间值
     * @param unit       时间单位
     * @param <R>        泛型
     * @param <ID>       泛型
     * @return 返回指定的类型对象
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,
                                            Function<ID, R> dbFallback, Long time,
                                            TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //这里不再考虑缓存穿透问题，因为key永不过期
        if (StrUtil.isBlank(json)) {
            //如果未命中,说明不是热点key,直接返回null
            return null;
        }
        //如果命中
        //先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否逻辑过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回信息
            return r;
        }
        //过期，获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {//成功获取互斥锁
            //开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    //先查询数据库
                    R apply = dbFallback.apply(id);
                    //再存入reids缓存
                    this.setWithLogicalExpire(key, apply, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                //释放互斥锁
                unLock(lockKey);
            });
        }
        //如果未获取互斥锁，直接返回旧数据
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
