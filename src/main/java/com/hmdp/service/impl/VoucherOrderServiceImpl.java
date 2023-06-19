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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 服务实现类
 *
 * @author Jimmy
 * @since 2023-05-31
 * <p>
 * <p>
 * <p>
 * 面试点（遇到的问题）：如果是将锁放在事物内，可能会导致一些问题（读取脏数据）。
 * 线程1开启事务A后获取分布式锁，此时如果锁在事物内，执行业务代码后在事务A内释放了分布式锁，还未提交
 * 这时候线程1开启了事务B获取到了线程1刚释放的分布式锁,执行查询操作时查到的数据就可能出现问题，就是修改之前的数据（因为事物A目前尚未提交）
 * 因此要把锁放在事物外进行处理。保证在事物提交之前都不会有其他线程获得锁
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    // 用于查询秒杀券的相关信息
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    // 用于注入id
    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 异步下单：1. 线程池   2. 线程任务
    // 1. 声明用于处理订单的线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 2. 线程任务
    // 内部类实现 Runnable，用户可能随时进行秒杀，因此当前这个内部类应该在类的初始化之后就应该执行（PostConstruct）
    @PostConstruct
    private void init() {
        // 这个类一初始化完毕，VoucherOrderHandler就被提交到线程池，run()方法就执行
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 获取消息队列中的消息
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 如果获取成功：解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
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
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                }
            }
        }
    }


    // 创建阻塞队列（已优化成消息队列）
    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 异步下单内部类，我们认为能够100%成功，因为经过了lua(redis)的验证，库存一定是充足的
    // 线程池的任务就是从阻塞队列中不断取出订单，并创建订单
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    // 1.获取队列中的订单信息   take(): 获取和删除该队列的头部，如果需要，则等待直到元素可用为止，在此之前一直阻塞
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单（存入到数据库中，因为用户已经拿到了秒杀订单的id，他可以通过这个id去付款了，因此异步进行的对db的写操作可以慢慢进行）
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/




    // 阻塞队列模式中处理订单信息
    /*private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 获取用户
        Long userId = voucherOrder.getUserId();
        // 2. 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3. 获取锁 (无参的默认值：失败立即返回、等待30s自动释放锁)
        boolean isLock = lock.tryLock();
        // 4. 判断是否获取锁成功
        if (!isLock) {
            // 获取锁失败，返回错误信息
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 手动释放锁
            lock.unlock();
        }
    }*/


    // 获取代理对象
    /*private IVoucherOrderService proxy;*/


    /**
     * 秒杀方法
     * 主方法：任何人来调用创建订单逻辑都会先经过该方法
     * （使用Redis提供的Stream消息队列）
     * 使用redis + lua脚本判断秒杀资格，避免直接和db对接，优化性能
     * 配合lua判断秒杀资格
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long orderId = redisIdWorker.nextId("order");   // redisIdWorker.nextId(): 时间戳 + 计数器
        // 1.执行lua脚本
        // ① 尝试判断用户有没有购买资格、库存是否充足（返回0,1,2）
        // ② 发送订单信息到消息队列
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),    // 因为这个脚本没有KEYS，因此传一个空集合（不传null）
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        // 2.判断秒杀资格（结果是否为0）
        int r = result.intValue();
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 将订单信息存入数据库的步骤将不再需要了，因为存入了redis的消息队列中
        // 3.返回订单id
        return Result.ok(orderId);
    }


    /**
     *
     * 主方法：任何人来调用创建订单逻辑都会先经过该方法
     * （使用阻塞队列）
     * 使用redis + lua脚本判断秒杀资格，避免直接和db对接，优化性能
     * @param voucherId
     * @return
     */
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本，尝试判断用户有没有购买资格、库存是否充足（返回0,1,2）
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),    // 因为这个脚本没有KEYS，因此传一个空集合（不传null）
                voucherId.toString(), userId.toString()
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 程序进行到这一步说明是有秒杀资格的
        // 2.2.为0 ，有购买资格，把下单信息保存到阻塞队列（而不是直接下单）
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.3.订单id
        long orderId = redisIdWorker.nextId("order");   // redisIdWorker.nextId(): 时间戳 + 计数器
        voucherOrder.setId(orderId);
        // 2.4.用户id
        voucherOrder.setUserId(userId);
        // 2.5.代金券id
        voucherOrder.setVoucherId(voucherId);
        // 2.6.放入阻塞队列(抢单)
        orderTasks.add(voucherOrder);


        // 3. 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();  // 拿到当前对象的代理对象
        // 4.返回订单id
        return Result.ok(orderId);
    }*/

    /**
     * 如果直接在方法上加锁，锁的是整个方法、锁的对象是this，也就是说每个用户用的都是同一把锁
     * 这样串行执行效率过低，事实上，张三和李四之间并不需要上锁，张三和张三之间锁一下就行了
     * 因此我们把锁加在userId上而不是使用方法锁、
     * <p>
     * <p>
     * 另一个问题，因为在互斥锁释放和事务提交之前的这段时间里仍然可能发生并发问题，因此要把锁的释放放在事物提交之后，但是又不能使用方法锁（见上 ↑）
     * 因此我们把锁上在函数调用上
     */

    /**
     * 创建订单 （创建步骤是：MP的save()方法）
     */

    @Transactional
    // public synchronized Result createVoucherOrder(Long voucherId) {
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5. 一人一单：因为是新插入而不是更新，没有办法判断是否修改过，因此只能使用悲观锁，无法使用乐观锁
        // 因为改成了异步的，用户id就不能通过ThreadLocal获取了
        // Long userId = UserHolder.getUser().getId();
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        // 创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if (!isLock) {
            // 获取锁失败，直接返回
            log.error("不允许重复下单");
            return;
        }


        try {
            // 5.1 查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2 判断是否存在
            if (count > 0) {
                // 该用户已经购买过了该优惠券
                log.error("单用户限购一次！");
                return;
            }

            // stock > 0，基于CAS思想解决超卖问题。版本号法，库存可以代表版本号
            // 同时对比版本号不需要前后一致，这样效率太低，只需要保证库存大于0即可扣减。
            // 6. 减扣库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")        // set stock = stock - 1
                    .eq("voucher_id", voucherId)    // where id = ?
                    .gt("stock", 0)  // and stock > 0  (CAS思想)
                    .update();
            if (!success) {
                // 扣减失败
                log.error("库存不足!");
                return;
            }

            // 7. 创建订单
            save(voucherOrder);
        } finally {
            // 8. 在finally中释放锁
            redisLock.unlock();
        }
    }


    /**
     * 对接数据库判断秒杀资格（已优化为对接redis判断）
     * @param voucherId 要让seckillVoucher表和voucher表共享id
     *                  因为数据表的设计是：
     *                  让普通券和秒杀券都放在voucher表中，让秒杀券到秒杀券专用表中扩展一些字段
     *                  因此要让秒杀券在两张表中的id相同
     * @return
     */
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2. 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        // 3. 判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        // 4. 判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();

        // 创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁 (无参的默认值：失败立即返回、等待30s自动释放锁)
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 获取锁失败，返回错误信息
            return Result.fail("不允许重复下单");
        }
        try {
            // SpringBoot 的事物是通过aop代理实现的，不使用代理对象调用的事物是没有事物功能的
            // return this.createVoucherOrder(voucherId);
            // 获取代理对象（事物）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();  // 拿到当前对象的代理对象
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 手动释放锁
            lock.unlock();
        }
    }*/
}

