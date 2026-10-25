package com.hmdp.config;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 【秒杀链路消息队列使用】-1
 *      1. 秒杀订单 topic 和死信 topic：分区数决定消费并行度的上限
 *      2. 消费失败：间隔重试，重试用尽后转发到死信 topic（原 topic 名 + .DLT），不阻塞后续消息也不丢消息
 *      3. 反序列化失败的"毒丸"消息由 ErrorHandlingDeserializer 转成异常，不重试，直接进死信（见 application.yaml）
 */
@Slf4j
@Configuration
public class KafkaConfig {

    public static final String SECKILL_ORDER_TOPIC = "seckill-voucher-order";

    /**
     * 分区数。topic 已存在且分区更少时，启动时 KafkaAdmin 会自动增加分区。
     */
    @Value("${hmdp.kafka.seckill.partitions:3}")
    private int partitions;
    /**
     * 副本数。本地单节点只能为 1，生产环境建议 3，并配置 min.insync.replicas=2。
     */
    @Value("${hmdp.kafka.seckill.replicas:1}")
    private short replicas;
    /**
     * 两次重试之间的等待时间（毫秒）。
     */
    @Value("${kafka.backoff.interval:1000}")
    private Long interval;
    /**
     * 首次失败后的最大重试次数。
     */
    @Value("${kafka.backoff.max_failure:3}")
    private Long maxAttempts;

    @Resource
    private KafkaProperties kafkaProperties;

    /**
     * 在监听容器启动前创建 topic，已存在但分区更少时扩容。
     * 死信 topic 的分区数不能少于原 topic：DeadLetterPublishingRecoverer 默认投递到同编号的分区。
     * 不用 NewTopic Bean 交给 KafkaAdmin 创建：项目中 spring-kafka 2.9 与 kafka-clients 2.6 版本不匹配，
     * KafkaAdmin 检查分区时会调用 kafka-clients 3.x 才有的方法，启动报 NoSuchMethodError。
     */
    @PostConstruct
    public void createTopics() {
        try (AdminClient admin = AdminClient.create(kafkaProperties.buildAdminProperties())) {
            ensureTopic(admin, SECKILL_ORDER_TOPIC);
            ensureTopic(admin, SECKILL_ORDER_TOPIC + ".DLT");
        } catch (Exception e) {
            // Kafka 暂时不可用时不阻止应用启动，topic 会在首次使用时按 broker 默认配置自动创建
            log.warn("初始化 Kafka topic 失败: {}", e.toString());
        }
    }

    private void ensureTopic(AdminClient admin, String topic) throws Exception {
        Set<String> existing = admin.listTopics().names().get(10, TimeUnit.SECONDS);
        if (!existing.contains(topic)) {
            admin.createTopics(Collections.singleton(new NewTopic(topic, partitions, replicas)))
                    .all().get(10, TimeUnit.SECONDS);
            log.info("创建 topic {}，分区数 {}", topic, partitions);
            return;
        }
        int current = admin.describeTopics(Collections.singleton(topic)).all()
                .get(10, TimeUnit.SECONDS).get(topic).partitions().size();
        if (current < partitions) {
            admin.createPartitions(Collections.singletonMap(topic, NewPartitions.increaseTo(partitions)))
                    .all().get(10, TimeUnit.SECONDS);
            log.info("topic {} 分区数 {} -> {}", topic, current, partitions);
        }
    }

    /**
     * Spring Boot 2.3 的自动配置不会装配 spring-kafka 2.8 新增的 DefaultErrorHandler，
     * 这里声明同名工厂替换自动配置的那个，并沿用 application.yaml 中的消费者配置。
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate,
            IVoucherOrderService voucherOrderService) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);

        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(kafkaTemplate);
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            // 重试用尽：转发死信 topic 并告警，由人工或补偿任务处理
            log.error("秒杀订单消费失败，转入死信 topic: partition={}, offset={}, 原因={}",
                    record.partition(), record.offset(), exception.getMessage());
            deadLetter.accept(record, exception);
            // 订单最终没建成，退回用户的资格和库存，否则用户一直显示"重复下单"。
            // 死信只用于排查，退回之后不要原样重放。反序列化失败的消息没有内容，只能人工处理。
            // 这一步抛异常时消息会重新投递：重试成功就正常建单，失败会再走到这里
            Object value = record.value();
            if (SECKILL_ORDER_TOPIC.equals(record.topic()) && value instanceof VoucherOrder) {
                VoucherOrder order = (VoucherOrder) value;
                boolean created = voucherOrderService.query()
                        .eq("user_id", order.getUserId()).eq("voucher_id", order.getVoucherId()).count() > 0;
                if (!created) {
                    voucherOrderService.releaseSeckill(order, true);
                }
            }
        };
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(interval, maxAttempts)));
        return factory;
    }
}
