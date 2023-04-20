package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop>
        implements IShopService {
    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //缓存击穿方案（互斥锁解决）
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            //命中，直接返回商铺信息
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否是redis的空值（缓存击穿解决）
        if (shopJson != null) {
            return null;
        }
        //未命中，尝试获取互斥锁
        String lockKey = "lock:shop:" + id;
        boolean isLock = false;
        Shop shop = null;
        try {
            //获取互斥锁
            isLock = tryLock(lockKey);
            //判断是否获取成功
            if (!isLock) {//失败
                //等待并重试
                Thread.sleep(50);
                //直到缓存命中，或者获取到锁
                return queryWithMutex(id);
            }
            //获取锁成功，开始重建缓存
            //根据id查询数据库，判断商铺是否存在数据库中
            shop = getById(id);
            //模拟重建缓存的延迟-----------
            Thread.sleep(200);
            if (shop == null) {
                //不存在，防止缓存穿透，将空值存入redis，TTL设置为2min
                stringRedisTemplate.opsForValue().set(key, "",
                        CACHE_NULL_TTL, TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //存在，则将商铺数据写入redis中
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
                    CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(lockKey);
        }
        //返回从缓存或数据库中查到的数据
        return shop;
    }

    //缓存穿透方案
//    public Shop queryWithPassThrough(Long id) {
//        String key = CACHE_SHOP_KEY + id;
//        //1.从redis中查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //2.判断缓存是否命中
//        if (StrUtil.isNotBlank(shopJson)) {
//            //2.1若命中，直接返回商铺信息
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        //判断命中的是否是redis的空值
//        if (shopJson != null) {
//            return null;
//        }
//        //2.2未命中，根据id查询数据库，判断商铺是否存在数据库中
//        Shop shop = getById(id);
//        if (shop == null) {
//            //2.2.1不存在，防止缓存穿透，将空值存入redis，TTL设置为2min
//            stringRedisTemplate.opsForValue().set(key, "",
//                    CACHE_NULL_TTL, TimeUnit.MINUTES);
//            //返回错误信息
//            return null;
//        }
//        //2.2.2存在，则将商铺数据写入redis中
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
//                CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return shop;
//    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
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
