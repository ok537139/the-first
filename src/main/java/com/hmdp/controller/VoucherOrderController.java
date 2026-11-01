package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.limiter.annotation.RateLimiter;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 秒杀下单，叠加两条限流规则（阈值应以压测结果为准）：
     * 全局限流保护整体容量；按用户限流防止单个账号用脚本刷接口
     */
    @RateLimiter(key = "rate_limit:seckill:", window = 1, limit = 1000,
            message = "抢购人数过多，请稍后再试", type = RateLimiter.LimitType.METHOD)
    @RateLimiter(key = "rate_limit:seckill:", window = 1, limit = 5,
            message = "操作太频繁，请稍后再试", type = RateLimiter.LimitType.USER)
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 秒杀下单结果。下单接口只代表抢到资格，订单由 Kafka 消费者异步创建，前端抢到后轮询这个接口：
     * SUCCESS 已创建订单（带 orderId）；PROCESSING 处理中；NONE 没有资格（没抢过，或下单失败资格已退回）
     */
    @GetMapping("seckill/{id}/result")
    public Result querySeckillResult(@PathVariable("id") Long voucherId) {
        return voucherOrderService.querySeckillResult(voucherId);
    }
}
