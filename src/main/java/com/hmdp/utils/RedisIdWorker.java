package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 基于Redis自增策略生成全局唯一ID
 *      ID：时间戳 + 计数器
 *      每天一个key，方便统计，也可以避免数据量过大
 *
 * 生成全局唯一ID的策略，如：
 *      UUID（十六进制字符串，不友好）
 *      Redis自增
 *      snowflake算法（对时钟依赖较高）
 *      数据库自增
 */
@Component
public class RedisIdWorker {
    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

//    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 这里用@Resource或者用构造函数注入都行
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 获取优惠券的id，使用时间戳 + 序列号拼接而成
     *
     * @param keyPrefix 业务前缀，区分不同的业务，不同业务之间的id隔离开来
     * @return
     */
    public long nextId(String keyPrefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC); // 获取当前的秒数
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1.获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长(使用date来分隔每天的key，避免数据存不下，也可以带来统计效果)
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // +会变成字符串拼接，我们要的是数字拼接，所以使用位运算，再把count拼在低位，使用或运算即可，遇1得1
        // 3. 拼接并返回
        return timestamp << COUNT_BITS | count;
    }
}
