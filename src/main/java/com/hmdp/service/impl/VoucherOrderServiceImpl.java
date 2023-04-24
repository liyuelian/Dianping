package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 服务实现类
 *
 * @author 李
 * @version 1.0
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;

    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        //根据id查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("该优惠券不存在，请刷新！");
        }
        //判断秒杀券是否在有效时间内
        //若不在有效期，则返回异常结果
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        //若在有效期，判断库存是否充足
        if (voucher.getStock() < 1) {//库存不足
            return Result.fail("秒杀券库存不足！");
        }
        //库存充足，则扣减库存（操作秒杀券表）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")//set stock = stock -1
                //where voucher_id =? and stock>0
                .gt("stock", 0).eq("voucher_id", voucherId).update();
        if (!success) {//操作失败
            return Result.fail("秒杀券库存不足！");
        }
        //扣减库存成功，则创建订单，返回订单id
        VoucherOrder voucherOrder = new VoucherOrder();
        //设置订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //设置用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        //设置代金券id
        voucherOrder.setVoucherId(voucherId);

        //将订单写入数据库（操作优惠券订单表）
        this.save(voucherOrder);

        //返回订单id
        return Result.ok(orderId);
    }
}
