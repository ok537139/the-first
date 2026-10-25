package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.cache.VoucherListCacheService;
import com.hmdp.utils.TransactionUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_TIME_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private VoucherListCacheService voucherListCacheService;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 【秒杀优惠券信息多级缓存改造】查询优惠券信息（L1 Caffeine + L2 Redis）
        List<Voucher> cached = voucherListCacheService.getVoucherByShopId(shopId);
        // 动静分离：标题、规则、时间等静态信息走多级缓存，库存实时从 Redis 读取
        return Result.ok(fillRealtimeStock(cached));
    }

    /**
     * 用 Redis 中的实时库存覆盖缓存里的库存。
     * 缓存里的对象是多个请求共享的，先复制再修改，不能直接改缓存中的对象。
     */
    private List<Voucher> fillRealtimeStock(List<Voucher> cached) {
        List<Voucher> vouchers = cached.stream()
                .map(v -> BeanUtil.copyProperties(v, Voucher.class))
                .collect(Collectors.toList());
        if (vouchers.isEmpty()) {
            return vouchers;
        }
        List<String> keys = vouchers.stream().map(v -> SECKILL_STOCK_KEY + v.getId()).collect(Collectors.toList());
        List<String> stocks = stringRedisTemplate.opsForValue().multiGet(keys);
        if (stocks == null) {
            return vouchers;
        }
        for (int i = 0; i < vouchers.size(); i++) {
            String stock = stocks.get(i);
            if (stock != null) {
                vouchers.get(i).setStock(Math.max(Integer.parseInt(stock), 0));
            }
        }
        return vouchers;
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        // 秒杀脚本从 Redis 读取库存和活动时间，在事务提交前写入：
        // Redis 写失败会抛异常让事务回滚，接口如实返回失败，不会出现"券建好了却一直提示活动不存在"，
        // 也不会因为接口报错、用户重试而建出重复的券。
        // 反过来事务提交失败时 Redis 会留下这张券的 key，但券不在列表里；就算有人按 id 抢，
        // 消费者扣 MySQL 库存会失败，订单回滚并退回资格
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
        Map<String, String> time = seckillTime(voucher.getBeginTime(), voucher.getEndTime());
        if (!time.isEmpty()) {
            stringRedisTemplate.opsForHash().putAll(SECKILL_TIME_KEY + voucher.getId(), time);
        }
        // 列表缓存必须在提交后清理（提交前清理的话，期间的读请求会把旧列表写回缓存）。
        // 这里的异常会抛给调用方，但数据已经提交，所以只记日志：清理失败只是新券要等缓存过期才出现在列表里
        TransactionUtils.afterCommit(() -> {
            try {
                voucherListCacheService.evict(voucher.getShopId());
            } catch (RuntimeException e) {
                log.warn("清理店铺券列表缓存失败，等待缓存过期: shopId={}", voucher.getShopId(), e);
            }
        });
    }

    /**
     * 活动时间转成毫秒时间戳，供 seckill.lua 判断
     */
    public static Map<String, String> seckillTime(LocalDateTime begin, LocalDateTime end) {
        Map<String, String> time = new HashMap<>(2);
        if (begin != null) {
            time.put("begin", String.valueOf(begin.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
        }
        if (end != null) {
            time.put("end", String.valueOf(end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
        }
        return time;
    }
}
