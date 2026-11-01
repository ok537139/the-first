package com.hmdp.utils;

import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;

public class LoginInterceptor implements HandlerInterceptor {

    /**
     * 只有查询（GET）允许匿名访问的路径。同一路径下的新增、修改接口（如 POST /shop、POST /voucher/seckill）需要登录。
     * 原来整个 /shop/**、/voucher/** 都在白名单里，未登录也能修改商铺、创建秒杀券。
     */
    private static final List<String> PUBLIC_GET_PATTERNS = Arrays.asList("/shop/**", "/voucher/**", "/shop-type/**");
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser() != null || isPublicRead(request)) {
            // 有用户，或者是公开的查询接口，则放行
            return true;
        }
        // 没有，需要拦截，设置状态码
        response.setStatus(401);
        // 拦截
        return false;
    }

    private boolean isPublicRead(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return PUBLIC_GET_PATTERNS.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }
}
