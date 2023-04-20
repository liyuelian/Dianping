package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 服务实现类
 *
 * @author 李
 * @version 1.0
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;

        //1.从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            //2.1若命中，直接返回商铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //判断命中的是否是redis的空值
        if (shopJson != null) {
            return Result.fail("店铺不存在！");
        }

        //2.2未命中，根据id查询数据库，判断商铺是否存在数据库中
        Shop shop = getById(id);
        if (shop == null) {
            //2.2.1不存在，防止缓存穿透，将空值存入redis，TTL设置为2min
            stringRedisTemplate.opsForValue().set(key, "",
                    CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return Result.fail("店铺不存在！");
        }

        //2.2.2存在，则将商铺数据写入redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
                CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
