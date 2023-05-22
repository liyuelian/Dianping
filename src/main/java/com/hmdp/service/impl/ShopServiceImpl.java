package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop =
        //        cacheClient.queryWithPassThrough
        //                (CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //缓存击穿方案（互斥锁解决）
        //Shop shop = queryWithMutex(id);

        //缓存击穿方案（逻辑过期）
        Shop shop = cacheClient.queryWithLogicalExpire
                (CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    //public Shop queryWithMutex(Long id) {
    //    String key = CACHE_SHOP_KEY + id;
    //    //从redis中查询商铺缓存
    //    String shopJson = stringRedisTemplate.opsForValue().get(key);
    //    //判断缓存是否命中
    //    if (StrUtil.isNotBlank(shopJson)) {
    //        //命中，直接返回商铺信息
    //        return JSONUtil.toBean(shopJson, Shop.class);
    //    }
    //    //判断命中的是否是redis的空值（缓存击穿解决）
    //    if (shopJson != null) {
    //        return null;
    //    }
    //    //未命中，尝试获取互斥锁
    //    String lockKey = "lock:shop:" + id;
    //    boolean isLock = false;
    //    Shop shop = null;
    //    try {
    //        //获取互斥锁
    //        isLock = tryLock(lockKey);
    //        //判断是否获取成功
    //        if (!isLock) {//失败
    //            //等待并重试
    //            Thread.sleep(50);
    //            //直到缓存命中，或者获取到锁
    //            return queryWithMutex(id);
    //        }
    //        //获取锁成功，开始重建缓存
    //        //根据id查询数据库，判断商铺是否存在数据库中
    //        shop = getById(id);
    //        //模拟重建缓存的延迟-----------
    //        Thread.sleep(200);
    //        if (shop == null) {
    //            //不存在，防止缓存穿透，将空值存入redis，TTL设置为2min
    //            stringRedisTemplate.opsForValue().set(key, "",
    //                    CACHE_NULL_TTL, TimeUnit.MINUTES);
    //            //返回错误信息
    //            return null;
    //        }
    //        //存在，则将商铺数据写入redis中
    //        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
    //                CACHE_SHOP_TTL, TimeUnit.MINUTES);
    //    } catch (InterruptedException e) {
    //        throw new RuntimeException(e);
    //    } finally {
    //        //释放互斥锁
    //        unLock(lockKey);
    //    }
    //    //返回从缓存或数据库中查到的数据
    //    return shop;
    //}
    //
    //private boolean tryLock(String key) {
    //    Boolean flag = stringRedisTemplate.opsForValue()
    //            .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
    //    return BooleanUtil.isTrue(flag);
    //}
    //
    //private void unLock(String key) {
    //    stringRedisTemplate.delete(key);
    //}

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

    @Override
    public Result queryShopByTypeId(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要按照坐标查询
        if (x == null || y == null) {//不按照坐标查询
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        //2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        //3.查询redis，按照距离排序、分页，结果：shopId，distance
        String key = SHOP_GEO_KEY + typeId;
        //GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDIST
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs
                                .newGeoSearchArgs().includeDistance().limit(end)
                );

        //4.解析出id
        if (results == null) {
            return Result.ok();
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        //4.1因为stringRedisTemplate.opsForGeo().search 只能从0开始查询到end条，
        // 所以接下来需要进行截取：from-end 部分
        if (list.size() <= from) {
            //没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        list.stream().skip(from).forEach(result -> {
            //4.2获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //4.3获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });

        //5.根据id查询店铺Shop
        String idStr = StrUtil.join(",", ids);
        //从数据库中查询店铺详细信息
        List<Shop> shops =
                query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        //设置距离信息
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //6.返回
        return Result.ok(shops);
    }

    //public void saveShop2Redis(Long id, Long expireSeconds) {
    //    //查询店铺数据
    //    Shop shop = getById(id);
    //    //封装逻辑过期时间
    //    RedisData redisData = new RedisData();
    //    redisData.setData(shop);
    //    //逻辑过期时间=当前时间+expireSeconds秒
    //    redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
    //    //写入redis
    //    stringRedisTemplate.opsForValue()
    //            .set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    //}
}
