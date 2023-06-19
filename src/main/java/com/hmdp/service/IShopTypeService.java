package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author Jimmy
 * @since 2023-05-31
 */
public interface IShopTypeService extends IService<ShopType> {

    Result queryTypeLists();

}
