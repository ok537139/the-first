package com.hmdp.config;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.cache.ShopBloomFilter;
import com.hmdp.service.consistency.ShopCacheSyncService;
import com.hmdp.service.impl.VoucherServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupDataInitializer {
    private final ISeckillVoucherService seckillVoucherService;
    private final IVoucherOrderService voucherOrderService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ShopBloomFilter shopBloomFilter;
    private final ShopCacheSyncService shopSync;
    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpRedisIndexes() {
        try { shopBloomFilter.init(); }
        catch (RuntimeException e) { log.warn("Bloom warmup unavailable; lookups fail open",e); }
        // Only enqueue IDs in bounded pages. Workers read current rows under lock,
        // so startup cannot overwrite a concurrent shop edit with an old snapshot.
        long shopCursor=0;
        int shops=0;
        while (true) {
            List<Long> ids=jdbc.queryForList("SELECT id FROM tb_shop WHERE id>? ORDER BY id LIMIT 500",Long.class,shopCursor);
            if (ids.isEmpty()) break;
            for(Long id:ids) shopSync.markPending(id);
            shops+=ids.size(); shopCursor=ids.get(ids.size()-1);
        }
        shopSync.retryPending();
        int vouchers=0,orders=0;
        try {
            long cursor=0;
            while(true) {
                List<SeckillVoucher> page=seckillVoucherService.query().gt("voucher_id",cursor)
                        .orderByAsc("voucher_id").last("LIMIT 500").list();
                if(page.isEmpty()) break;
                for(SeckillVoucher voucher:page) {
                    stringRedisTemplate.opsForValue().setIfAbsent(SECKILL_STOCK_KEY+voucher.getVoucherId(),voucher.getStock().toString());
                    Map<String,String> time=VoucherServiceImpl.seckillTime(voucher.getBeginTime(),voucher.getEndTime());
                    if(!time.isEmpty()) stringRedisTemplate.opsForHash().putAll(SECKILL_TIME_KEY+voucher.getVoucherId(),time);
                }
                vouchers+=page.size(); cursor=page.get(page.size()-1).getVoucherId();
            }
            cursor=0;
            while(true) {
                List<VoucherOrder> page=voucherOrderService.query().select("id","user_id","voucher_id")
                        .gt("id",cursor).orderByAsc("id").last("LIMIT 500").list();
                if(page.isEmpty()) break;
                for(VoucherOrder order:page)
                    stringRedisTemplate.opsForSet().add(SECKILL_ORDER_KEY+order.getVoucherId(),order.getUserId().toString());
                orders+=page.size(); cursor=page.get(page.size()-1).getId();
            }
        } catch(RuntimeException e) { log.error("Seckill warmup incomplete; restore Redis before accepting seckill traffic",e); throw e; }
        // Follows and Feed load only for the requested user/author; no global fanout on startup.
        log.info("Redis warmup: {} shop sync tasks, {} vouchers, {} orders; follows/feed rebuild on demand",shops,vouchers,orders);
    }
}
