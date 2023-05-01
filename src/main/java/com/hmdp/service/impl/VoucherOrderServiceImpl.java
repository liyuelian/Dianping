package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 服务实现类
 *
 * @author 李
 * @version 1.0
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    //类一加载就初始化脚本
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //在当前类初始化完毕之后就执行
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //获取消息队列中的消息
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //3.如果成功，将信息转为对象
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.创建订单
                    handleVoucherOrder(voucherOrder);
                    //5.消息的ACK确认 SACK stream.orders g1 消息id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    //1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //获取失败，说明pending-list中没有消息，退出循坏
                        break;
                    }
                    //3.如果成功，将信息转为对象
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.创建订单
                    handleVoucherOrder(voucherOrder);
                    //5.消息的ACK确认 SACK stream.orders g1 消息id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list异常", e);
                    try {
                        Thread.sleep(20);//休眠20ms
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

    //阻塞队列：当一个线程尝试从队列中获取元素时，如果队列中没有元素，那么该线程就会被阻塞，直到队列中有元素，线程才会被唤醒并获取元素
    //private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    //执行异步操作，从阻塞队列中获取订单
    //private class VoucherOrderHandler implements Runnable {
    //    @Override
    //    public void run() {
    //        while (true) {
    //            try {
    //                //1.获取队列中的订单信息
    //                /* take()--获取和删除阻塞对列中的头部，如果需要则等待直到元素可用
    //                           (因此不必担心这里的死循环会增加cpu的负担) */
    //                VoucherOrder voucherOrder = orderTasks.take();
    //                //2.创建订单
    //                handleVoucherOrder(voucherOrder);
    //            } catch (Exception e) {
    //                log.error("处理订单异常", e);
    //            }
    //        }
    //    }
    //}

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户（因为目前的是线程池对象，不是主线程，不能使用UserHolder从ThreadLocal中获取用户id）
        Long userId = voucherOrder.getUserId();
        //创建锁对象，指定锁的名称
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁（可重入锁）
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if (!isLock) {
            //获取锁失败
            log.error("不允许重复下单");
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本-判断购买资格，发送信息到stream.order消息队列
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        //2.判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            //不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //4.向客户返回订单id
        return Result.ok(orderId);
    }

    //@Override
    //public Result seckillVoucher(Long voucherId) {
    //    //获取用户id
    //    Long userId = UserHolder.getUser().getId();
    //    //1.执行lua脚本
    //    Long result = stringRedisTemplate.execute(
    //            SECKILL_SCRIPT,
    //            Collections.emptyList(),
    //            voucherId.toString(),
    //            userId.toString()
    //    );
    //    //2.判断脚本执行结果是否为0
    //    int r = result.intValue();
    //    if (r != 0) {
    //        //2.1如果不为0，代表没有购买资格
    //        return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
    //    }
    //    //2.2如果为0，代表有购买资格，将下单信息保存到阻塞对列中
    //    VoucherOrder voucherOrder = new VoucherOrder();
    //    //设置订单id
    //    long orderId = redisIdWorker.nextId("order");
    //    voucherOrder.setId(orderId);
    //    //设置用户id
    //    voucherOrder.setUserId(userId);
    //    //设置秒杀券id
    //    voucherOrder.setVoucherId(voucherId);
    //    //将上述信息保存到阻塞队列
    //    orderTasks.add(voucherOrder);
    //    //3.获取代理对象
    //    proxy = (IVoucherOrderService) AopContext.currentProxy();
    //
    //    //4.返回订单id
    //    return Result.ok(0);
    //}

    /*
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

        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        //获取锁（可重入锁），指定锁的名称
        RLock lock = redissonClient.getLock("order:" + userId);
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if (!isLock) {//获取锁失败
            //直接返回错误，不阻塞
            return Result.fail("不允许重复下单！");
        }
        try {
            //获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            //这里应该先获取锁，然后提交createVoucherOrder()的事务，再释放锁，才能确保线程是安全的
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }
    }
    */

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userId = voucherOrder.getUserId();
        //查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {//说明已经该用户已经对该优惠券下过单了
            log.error("用户已经购买过一次!");
            return;
        }
        //库存充足，则扣减库存（操作秒杀券表）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")//set stock = stock -1
                //where voucher_id =? and stock>0
                .gt("stock", 0).eq("voucher_id", voucherOrder.getVoucherId()).update();
        if (!success) {//操作失败
            log.error("秒杀券库存不足!");
            return;
        }
        //将订单写入数据库（操作优惠券订单表）
        save(voucherOrder);
    }
}
