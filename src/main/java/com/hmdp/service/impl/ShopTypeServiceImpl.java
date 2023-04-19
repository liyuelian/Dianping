package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE;

/**
 * 服务实现类
 *
 * @author 李
 * @version 1.0
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopList() {
        //查询redis中有没有店铺类型缓存
        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE);
        //如果有，则将其转为对象类型，并返回给客户端
        if (StrUtil.isBlank(shopTypeJson)) {
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }
        //如果redis中没有缓存，到DB中查询
        //如果DB中没有查到，返回错误信息
        List<ShopType> list = query().orderByAsc("sort").list();
        if (list == null) {
            return Result.fail("查询不到店铺类型！");
        }

        //如果DB查到了数据
        //将数据存入Redis中(转为json类型存入)
        stringRedisTemplate.opsForValue()
                .set(CACHE_SHOP_TYPE, JSONUtil.toJsonStr(list));
        //并返回给客户端
        return Result.ok(list);
    }
}
