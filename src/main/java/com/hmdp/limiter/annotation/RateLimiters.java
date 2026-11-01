package com.hmdp.limiter.annotation;

import java.lang.annotation.*;

/**
 * {@link RateLimiter} 的容器注解：同一方法上加多个 @RateLimiter 时由编译器自动生成，
 * 例如秒杀接口同时按全局和按用户限流。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimiters {
    RateLimiter[] value();
}
