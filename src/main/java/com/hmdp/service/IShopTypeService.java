package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 服务类接口
 *
 * @author 李
 * @version 1.0
 */
public interface IShopTypeService extends IService<ShopType> {

    Result queryShopList();
}
