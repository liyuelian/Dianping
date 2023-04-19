package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 *  服务类
 *
 * @author 李
 * @version 1.0
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);
}
