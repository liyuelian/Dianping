package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author 李
 * @version 1.0
 */
@Data
public class RedisData {
    //逻辑过期时间
    private LocalDateTime expireTime;
    //存入redis的数据
    private Object data;
}
