package com.hmdp.consumer;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
// @KafkaListener(id = "multiGroup", topics = "greeting")
public class SeckillVoucherConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 【秒杀链路消息队列使用】-3
     * Kafka 监听消费秒杀订单消息
     * 指定之前创建的containerFactory
     */
    @KafkaListener(topics = "seckill-voucher-order")
    public void processMessage(VoucherOrder order) {
        try {
            // 生成订单 扣减库存 等等操作
            voucherOrderService.createVoucherOrder(order);
        } catch (Exception e) {
            log.error("秒杀订单消费异常: orderId={}, 原因={}", order.getId(), e.getMessage());
            throw new RuntimeException("Voucher order processing failed", e);
        }
    }
}
