package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author 李
 * @version 1.0
 */
@Component
public class RedisIdWorker {
    //开始时间戳(1970-01-01T00:00:00到2022-01-01T00:00:00的秒数)
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    //序列号的位数
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //public static void main(String[] args) {
    //    //开始时间
    //    LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
    //    //得到1970-01-01T00:00:00Z.到指定时间为止的具体秒数
    //    long second = time.toEpochSecond(ZoneOffset.UTC);
    //    System.out.println(second);//1640995200L
    //}

    public long nextId(String keyPrefix) {
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        //开始时间到当前时间的 时间戳
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        //2.生成序列号(keyPrefix代表业务前缀)
        /*
         * Redis的 Incr命令将 key 中储存的数字值增1，如果key不存在，那么key的值会先被初始化为0，然后再执行INCR操作。
         * 根据这个特性，我们每一天拼接不同的日期，当做key。也就是说同一天下单采用相同的key，不同天下单采用不同的key
         * 这种方法不仅可以防止订单号使用完（redis的的自增最多可以有2^64位，我们采取其中32位作计数器），
         * 还可以根据不同的日期，统计该天的订单数量
         */
        //2.1获取当前的日期（精确到天）
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2做自增长
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        //3.拼接并返回
        //将时间戳左移32位，空出来的右边32位使用count填充，共64位
        return timeStamp << COUNT_BITS | count;
    }
}

