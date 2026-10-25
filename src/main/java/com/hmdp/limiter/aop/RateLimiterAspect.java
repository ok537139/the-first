package com.hmdp.limiter.aop;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.limiter.annotation.RateLimiter;
import com.hmdp.limiter.exception.RateLimitException;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;

/**
     【滑动窗口限流】-2
     限流切面：拦截所有加了自定义限流注解的接口方法
        获取到自定义注解上的参数:  限流key前缀、时间窗口大小、时间窗口内允许的请求数、限流提示信息、限流维度
        执行限流Lua脚本(限流实现算法：滑动窗口限流)
        一个方法可以叠加多条规则（比如全局 + 按用户），按声明顺序依次判断
 */
@Slf4j
@Aspect
@Component
public class RateLimiterAspect {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 限流Lua脚本
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT;

    static {
        SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>();
        SLIDING_WINDOW_SCRIPT.setLocation(new ClassPathResource("limiter.lua"));
        SLIDING_WINDOW_SCRIPT.setResultType(Long.class);
    }

    // 前置拦截 注解了rateLimiter的方法（加了多个 @RateLimiter 时，编译器会把它们包装进 @RateLimiters）
    @Before("@annotation(com.hmdp.limiter.annotation.RateLimiter) || @annotation(com.hmdp.limiter.annotation.RateLimiters)")
    public void doBefore(JoinPoint point) {
        Method method = ((MethodSignature) point.getSignature()).getMethod();
        Set<RateLimiter> rules = AnnotatedElementUtils.findMergedRepeatableAnnotations(method, RateLimiter.class);
        for (RateLimiter rateLimiter : rules) {
            // 构建完整的限流key
            String fullKey = buildRateLimitKey(method, rateLimiter);
            // 执行限流脚本
            Long result = executeSlidingWindowScript(fullKey, (long) rateLimiter.window(), (long) rateLimiter.limit());
            // 如果返回0表示被限流
            if (result != null && result == 0) {
                throw new RateLimitException(rateLimiter.message());
            }
        }
    }

    /**
     * 执行滑动窗口限流脚本
     *
     * @param key    限流key
     * @param window 时间窗口（秒）
     * @param limit  限制请求数量
     * @return 当前窗口内请求数量计数（如果被限流返回0）
     */
    public Long executeSlidingWindowScript(String key, Long window, Long limit) {
        log.debug("key:{}, window:{}, limit:{}", key, window, limit);
        // 时间由脚本内的 Redis TIME 获取，多实例共用同一个时钟；member 用唯一 id，同一毫秒的请求不会互相覆盖
        return stringRedisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                Collections.singletonList(key),
                window.toString(), limit.toString(), IdUtil.fastSimpleUUID()
        );
    }

    /**
     * 构建限流key
     */
    private String buildRateLimitKey(Method method, RateLimiter rateLimiter) {
        StringBuilder keyBuilder = new StringBuilder(rateLimiter.key());

        // key默认 添加类名和方法名
        keyBuilder.append(method.getDeclaringClass().getName())
                .append(":")
                .append(method.getName());

        // 根据限流类型添加额外维度（为什么要这样构建？可以先看lua脚本的写法，限流的本质是利用了Redis的ZSet数据类型的一个key，因此key的构建决定了限流的维度）
        switch (rateLimiter.type()) {
            case IP:
                // 如果是按IP限流，那么在key的拼接上需要加上 ip 信息
                keyBuilder.append(":ip:").append(getClientIp());
                break;
            case USER:
                // 按用户限流；未登录的请求没有用户 id，退化为按 IP 限流，避免所有匿名请求共用一个计数器
                UserDTO user = UserHolder.getUser();
                if (user != null) {
                    keyBuilder.append(":user:").append(user.getId());
                } else {
                    keyBuilder.append(":ip:").append(getClientIp());
                }
                break;
            case METHOD:
            default:
                // 方法级限流使用默认key（默认key已带上类名和方法名  ）
                break;
        }

        return keyBuilder.toString();
    }

    /**
     * 获取客户端IP。
     * X-Forwarded-For 等请求头客户端可以随意伪造，只有请求来自本机的反向代理（如 Nginx、Vite 开发代理）时才信任，
     * 并且只取第一个地址（多级代理时格式为 "client, proxy1, proxy2"）。
     */
    private String getClientIp() {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        for (String header : new String[]{"X-Real-IP", "X-Forwarded-For"}) {
            String value = request.getHeader(header);
            if (StrUtil.isNotBlank(value) && !"unknown".equalsIgnoreCase(value)) {
                return value.split(",")[0].trim();
            }
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        return "127.0.0.1".equals(remoteAddr) || "0:0:0:0:0:0:0:1".equals(remoteAddr) || "::1".equals(remoteAddr);
    }
}
