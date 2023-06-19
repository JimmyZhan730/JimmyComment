package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 解决缓存击穿问题方法之二  ——  逻辑过期时间
 *
 *
 */

@Data
public class RedisData {
    private LocalDateTime expireTime;
    // 为了避免修改原有的业务逻辑，我们把 RedisData定义成一个容器，其中data属性为Object，随便存什么数据都行
    private Object data;
}
