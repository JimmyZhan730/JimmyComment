package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 该拦截器只判断有没有用户
 */

public class LoginInterceptor implements HandlerInterceptor {

//    // 这里不能使用@AutoWired自动注入对象，因为LoginInterceptor这个类是由我们手动new的，而不是springboot的注解自动创建的
//    // 没有交给Spring容器管理，因此springboot无法帮我们自动注入，StringRedisTemplate就需要手动使用构造函数注入
//    private StringRedisTemplate stringRedisTemplate;
//
//    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
//        this.stringRedisTemplate = stringRedisTemplate;
//    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 判断是否需要拦截(ThreadLocal中是否有用户)
        if (UserHolder.getUser() == null) {
            // 没有，需要拦截，设置状态码
            response.setStatus(401);
            return false;
        }
        // 有，放行
        return true;
    }
}
