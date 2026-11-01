package com.hmdp.config;

import com.hmdp.dto.Result;
import com.hmdp.limiter.exception.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    // 被限流：返回 429，前端和监控可以区分"被限流"和"服务出错"
    @ExceptionHandler(RateLimitException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Result handleRateLimitException(RateLimitException e) {
        return Result.fail(e.getMessage());
    }

    // 全局异常处理捕捉，记录完整日志；异常信息可能包含 SQL、类名等内部细节，不返回给前端
    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常，请稍后重试");
    }
}
