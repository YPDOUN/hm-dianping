package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdGenerator;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private IVoucherOrderService proxy;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdGenerator redisIdGenerator;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
    }

    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init() {
        //Bean初始化完成后，异步开启任务，监听队列
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {

        String queueName = "stream.orders";
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单消息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAM stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 判断队列中是否获取消息成功
                    if (list == null || list.isEmpty()) {
                        //失败则再次尝试
                        continue;
                    }
                    // 解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 保存订单消息
                    proxy.createVoucherOrder(voucherOrder);
                    // 确认pending队列 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.info("保存数据失败!", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取消息队列中的pending-list消息 XREADGROUP GROUP g1 c1 COUNT 1 STREAM stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 判断pending-list中是否有未确认的消息
                    if (list == null || list.isEmpty()) {
                        //没有则结束
                        break;
                    }
                    // 有则确认消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 保存订单消息
                    proxy.save(voucherOrder);
                    // 确认pending队列 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.info("处理pending-list订单异常!", e);
                }
            }
        }
    }
    /**
     * 创建阻塞队列以及保存订单数据
     */
    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable {
        public void run() {
            while (true) {
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    save(voucherOrder);
                } catch (InterruptedException e) {
                    log.info("保存数据失败!");
                }
            }
        }
    }*/

    /*
        stream消息队列实现异步创建订单
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdGenerator.nextId("order");

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId));
        // 1.判断库存是否充足以及用户是否下过单
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足！" : "您已下过单，请勿重复下单！");
        }

        return Result.ok(orderId);
    }

    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucher) {
        seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucher.getVoucherId())
                .update();
        save(voucher);
    }

    /*
        基于阻塞队列实现异步创建订单
     */
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString());
        // 1.判断库存是否充足以及用户是否下过单
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足！" : "您已下过单，请勿重复下单！");
        }
        // 2.有购买资格，将下单信息放入阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.1 订单id
        long orderId = redisIdGenerator.nextId("order");
        voucherOrder.setId(orderId);
        // 2.2 用户id
        voucherOrder.setUserId(userId);
        // 2.3 代金券id
        voucherOrder.setVoucherId(voucherId);
        // 2.4 放入阻塞队列
        orderTasks.add(voucherOrder);

        return Result.ok(orderId);
    }*/

   /* public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券是否存在
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在！");
        }

        // 2.判断优惠券是否在有效时间内
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("抢购时间还未开始！");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("抢购时间已经结束！");
        }

        // 3.判断库存数量是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("优惠券已抢光");
        }

        // 获取当前线程的用户id
        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:voucher:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:voucher:" + userId);
        // 判断是否成功获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 不超过，代表当前用户已经有线程在下单
            return Result.fail("下单失败，请勿重复下单！");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }
        *//* synchronized (userId.toString().intern()) {
            // 获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }*//*
    }*/

    /*@Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 一人一单
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("单次活动仅能购买一次，您已购买！");
        }
        // 4.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();

        if (!success) {
            // 扣减失败
            return Result.fail("优惠券已抢光");
        }
        // 5.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdGenerator.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);
    }*/
}
