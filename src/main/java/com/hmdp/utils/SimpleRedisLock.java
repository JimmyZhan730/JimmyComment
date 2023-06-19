package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 基于setnx实现的分布式锁
 * 并使用Lua脚本解决多条redis指令的原子性的问题
 *
 * 存在的问题：
 *      不可重入：在一个方法A内调用另一个方法B，B在等A释放锁，A在等B返回结果执行业务，造成死锁
 *      不可重试：目前的分布式只能尝试一次
 *      超时释放：在加锁时增加了过期时间，这样的我们可以防止死锁。
 *              虽然我们采用了lua表达式防止删锁的时候误删别人的锁，但是在卡顿时间超过TTL的情况下，自己是没有锁住的，仍然有安全隐患
 *      主从一致性：如果Redis提供了主从集群，当我们向集群写数据时，主机需要异步的将数据同步给从机。
 *                而在同步过去之前主机就宕机了情况下，就会出现死锁问题。
 *
 * 替换为 Redisson分布式锁
 */

public class SimpleRedisLock implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;
    // 因为这个类没有交给springboot管理，因此stringRedisTemplate需要使用构造器注入
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁(SET NX EX)
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        // 自动拆箱：有空指针的风险
        // return success;
        return Boolean.TRUE.equals(success);
    }

    // lua脚本实现事物原子性
    @Override
    public void unlock() {
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }
    /*@Override
    public void unlock() {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁中的标示
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 判断标示是否一致
        if(threadId.equals(id)) {
            // 释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}
