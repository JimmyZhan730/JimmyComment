package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;


/**
 * 该拦截器置于LoginInterceptor之前，形成拦截器的责任链模式
 * 该拦截器中只关注刷新token有效期，因此不管你是不是有token，都放行，交给LoginInterceptor去拦截未登录的用户
 * 只有查到了有该用户登录，就刷新token
 *
 *
 * 加入这个拦截器的意义在于：
 *      如果有的用户只在首页操作，首页的请求是不被登录拦截器拦截的，因此token不会刷新
 *      因此将该拦截器置于登录拦截器之前，拦截所有请求，不作登录拦截，只刷新token
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取请求头中的token
//        HttpSession session = request.getSession();
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            // 拦截登录交由下一个拦截器去处理
            return true;
        }
        // 2. 基于token，获取在redis中的用户
        String key  = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        // 3.判断用户是否存在
        if (userMap.isEmpty()) {
            // 拦截登录交由下一个拦截器去处理
            return true;
        }
        // 5.将查询到的hash数据转为UserDTO（数据脱敏）
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 6.存在，保存用户信息到 ThreadLocal
        UserHolder.saveUser(userDTO);
        // 7.刷新token有效期
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 8.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 在LocalHost中创建的用户最后要移除
        UserHolder.removeUser();
    }
}
