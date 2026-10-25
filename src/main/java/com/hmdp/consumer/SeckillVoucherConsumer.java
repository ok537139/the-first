package com.hmdp.consumer;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static com.hmdp.config.KafkaConfig.SECKILL_ORDER_TOPIC;

@Slf4j
@Component
public class SeckillVoucherConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 【秒杀链路消息队列使用】-3
     * Kafka 监听消费秒杀订单消息。
     * concurrency 是消费线程数，超过分区数的线程会空闲。
     * 异常原样抛出，交给 KafkaConfig 中的错误处理器重试，重试用尽后进入死信 topic 并退回资格。
     */
    @KafkaListener(topics = SECKILL_ORDER_TOPIC, concurrency = "${hmdp.kafka.seckill.concurrency:3}")
    public void processMessage(VoucherOrder order) {
        try {
            if (!voucherOrderService.createVoucherOrder(order)) {
                // MySQL 已经没有库存，订单已回滚：退回资格但不加回库存。
                // 退回失败会抛异常，消息重试时订单仍然建不成，会再退一次
                voucherOrderService.releaseSeckill(order, false);
            }
        } catch (RuntimeException e) {
            log.warn("秒杀订单消费异常，等待重试: orderId={}, 原因={}", order.getId(), e.getMessage());
            throw e;
        }
    }
}
