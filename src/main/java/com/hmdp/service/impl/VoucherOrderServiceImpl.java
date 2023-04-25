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
import org.springframework.aop.framework.AopContext;
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

        Long userId = UserHolder.getUser().getId();

        //即使是同一个userId,在不同线程中调用toString得到的是不同的字符串对象，synchronized无法锁定
        //因此这里还要使用intern()方法：
        //调用intern()时，如果常量池中已经包含一个等于这个String对象（由equals(Object)方法确定）的字符串，
        //则返回池中的字符串。否则将此String对象添加到常量池中并返回该String对象的引用
        synchronized (userId.toString().intern()) {
            //spring声明式事务的原理，是通过aop的动态代理实现的，获取到这个动态代理，让动态代理去调用方法
            IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
            //这里应该先获取锁，然后提交createVoucherOrder()的事务，再释放锁，才能确保线程是安全的
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单
        Long userId = UserHolder.getUser().getId();
        //查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {//说明已经该用户已经对该优惠券下过单了
            return Result.fail("用户已经购买过一次！");
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
        //Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        //设置代金券id
        voucherOrder.setVoucherId(voucherId);
        //将订单写入数据库（操作优惠券订单表）
        save(voucherOrder);

        return Result.ok(orderId);
    }
}
