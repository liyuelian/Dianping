package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author 李
 * @version 1.0
 */
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient() {
        //配置
        Config config = new Config();
        //redis单节点模式，设置redis服务器的地址，端口，密码
        config.useSingleServer().setAddress("redis://127.0.0.1:6379").setPassword("123456");
        //创建RedissonClient对象
        return Redisson.create(config);
    }
}
