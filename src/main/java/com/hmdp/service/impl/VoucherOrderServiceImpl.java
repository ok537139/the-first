package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.hmdp.config.KafkaConfig.SECKILL_ORDER_TOPIC;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_TIME_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill_rollback.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    // seckill.lua 的返回码
    private static final long STOCK_NOT_ENOUGH = 1;
    private static final Map<Long, String> SECKILL_FAIL_MESSAGES = new HashMap<>();

    static {
        SECKILL_FAIL_MESSAGES.put(STOCK_NOT_ENOUGH, "库存不足");
        SECKILL_FAIL_MESSAGES.put(2L, "重复下单");
        SECKILL_FAIL_MESSAGES.put(3L, "活动不存在");
        SECKILL_FAIL_MESSAGES.put(4L, "活动尚未开始");
        SECKILL_FAIL_MESSAGES.put(5L, "活动已结束");
    }

    /**
     * 本地售罄标记：voucherId -> 标记失效时间（毫秒）。
     * 售罄后的请求在本机直接返回，不再访问 Redis。标记只保留很短时间，
     * 发送失败回滚、补库存后，最多等这么久就能重新放行。
     */
    private static final long SOLD_OUT_MARK_MILLIS = 1000;
    private final Map<Long, Long> soldOutUntil = new ConcurrentHashMap<>();

    @Override
    public Result seckillVoucher(Long voucherId) {
        if (isSoldOut(voucherId)) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        List<String> keys = seckillKeys(voucherId);
        // 1.执行lua脚本：判断活动时间、库存、一人一单，并预扣库存
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(keys.get(0), keys.get(1), SECKILL_TIME_KEY + voucherId),
                userId.toString(), String.valueOf(System.currentTimeMillis())
        );
        // 2.判断结果
        if (result == null || result != 0) {
            if (result != null && result == STOCK_NOT_ENOUGH) {
                soldOutUntil.put(voucherId, System.currentTimeMillis() + SOLD_OUT_MARK_MILLIS);
            }
            return Result.fail(result == null ? "抢购失败" : SECKILL_FAIL_MESSAGES.getOrDefault(result, "抢购失败"));
        }
        // 3.有购买资格后再生成订单号，抢不到的请求不必多访问一次 Redis
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);    // 订单ID
        voucherOrder.setUserId(userId); // 用户ID
        voucherOrder.setVoucherId(voucherId); // 优惠券ID

        // 4.发送Kafka消息  【秒杀链路消息队列使用】-2
        // 以 userId 为 key：同一用户的消息进入同一分区，不同用户分散到各分区并行消费
        try {
            kafkaTemplate.send(SECKILL_ORDER_TOPIC, userId.toString(), voucherOrder)
                    .addCallback(sendResult -> { }, ex -> rollbackSeckill(voucherOrder, ex));
        } catch (Exception e) {
            // 拿不到 topic 元数据等情况下 send 会直接抛异常，此时回调不会执行
            rollbackSeckill(voucherOrder, e);
            return Result.fail("系统繁忙，请稍后再试");
        }

        // 5.返回订单id（此时订单尚未落库，只代表抢到了资格，前端通过 querySeckillResult 查询最终结果）
        // 订单id超过 JS Number 的安全整数范围，按字符串返回，否则前端显示的订单号末几位会变
        return Result.ok(String.valueOf(orderId));
    }

    /**
     * 下单结果以 MySQL 订单为准：
     * 有订单 -> 成功；没有订单但用户还在 Redis 已购集合中 -> 消息还在发送或消费，处理中；
     * 两者都没有 -> 没抢过，或者抢到的资格因下单失败已经退回，可以重新抢。
     */
    @Override
    public Result querySeckillResult(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Map<String, Object> result = new HashMap<>();
        VoucherOrder order = query().eq("user_id", userId).eq("voucher_id", voucherId).one();
        if (order != null) {
            result.put("status", "SUCCESS");
            result.put("orderId", String.valueOf(order.getId()));
            return Result.ok(result);
        }
        Boolean holding = stringRedisTemplate.opsForSet().isMember(SECKILL_ORDER_KEY + voucherId, userId.toString());
        result.put("status", Boolean.TRUE.equals(holding) ? "PROCESSING" : "NONE");
        return Result.ok(result);
    }

    /**
     * 消息发送失败：把预扣的库存加回去，把用户移出已购集合，用户可以重新抢。
     */
    private void rollbackSeckill(VoucherOrder order, Throwable ex) {
        log.error("秒杀消息发送失败，回滚 Redis 预扣: orderId={}", order.getId(), ex);
        try {
            releaseSeckill(order, true);
        } catch (Exception e) {
            // 回滚也失败时只能依靠对账任务修正
            log.error("回滚 Redis 预扣失败，需要对账修正: orderId={}", order.getId(), e);
        }
    }

    @Override
    public void releaseSeckill(VoucherOrder order, boolean restoreStock) {
        stringRedisTemplate.execute(ROLLBACK_SCRIPT, seckillKeys(order.getVoucherId()),
                order.getUserId().toString(), restoreStock ? "1" : "0");
        if (restoreStock) {
            soldOutUntil.remove(order.getVoucherId());
        }
    }

    private boolean isSoldOut(Long voucherId) {
        Long until = soldOutUntil.get(voucherId);
        return until != null && until > System.currentTimeMillis();
    }

    private static List<String> seckillKeys(Long voucherId) {
        return Arrays.asList(SECKILL_STOCK_KEY + voucherId, SECKILL_ORDER_KEY + voucherId);
    }

    /**
     * Kafka 消费者调用。消息可能被重复投递，必须幂等：
     * 先插订单，重复消息（主键冲突）或同一用户重复下单（唯一索引 uk_user_voucher 冲突）在这一步被拦下；
     * 最后才更新库存这一热点行，拿到行锁后马上提交，持锁时间最短。
     */
    @Override
    @Transactional
    public boolean createVoucherOrder(VoucherOrder voucherOrder) {
        try {
            save(voucherOrder);
        } catch (DuplicateKeyException e) {
            log.warn("重复的秒杀订单消息，忽略: orderId={}, userId={}", voucherOrder.getId(), voucherOrder.getUserId());
            return true;
        }

        // 扣减库存，库存大于 0 时才允许扣减
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            // Redis 与 MySQL 库存出现偏差。回滚订单但正常返回，避免消息被无意义地重试；
            // 由调用方在事务回滚之后退回用户的资格
            log.error("MySQL 库存不足，订单回滚: orderId={}", voucherOrder.getId());
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return false;
        }
        return true;
    }
}
