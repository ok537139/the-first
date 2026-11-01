package com.hmdp.service.consistency;

import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.cache.ShopBloomFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Service
public class ShopCacheSyncService {
    private final JdbcTemplate jdbc;
    private final ShopMapper shops;
    private final StringRedisTemplate redis;
    private final ShopBloomFilter bloom;
    private final ConsistencyProperties config;
    private final TransactionTemplate tx;
    private final DefaultRedisScript<Long> syncScript;

    public ShopCacheSyncService(JdbcTemplate jdbc, ShopMapper shops, StringRedisTemplate redis,
            ShopBloomFilter bloom, ConsistencyProperties config, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.shops = shops; this.redis = redis; this.bloom = bloom; this.config = config;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        syncScript = new DefaultRedisScript<>();
        syncScript.setLocation(new ClassPathResource("shop_sync.lua"));
        syncScript.setResultType(Long.class);
    }

    /** Must be called in the same transaction as the shop write. */
    public void markPending(Long id) {
        jdbc.update("UPDATE tb_shop SET cache_sync_stage=1, cache_retry_at=NOW(3) WHERE id=?", id);
    }

    public void trySync(Long id) {
        try {
            tx.execute(status -> {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT cache_sync_stage,cache_geo_type_id FROM tb_shop WHERE id=? AND cache_sync_stage IN (1,2) AND cache_retry_at<=NOW(3) FOR UPDATE", id);
                if (rows.isEmpty()) return null;
                Map<String, Object> row = rows.get(0);
                int stage = ((Number) row.get("cache_sync_stage")).intValue();
                Shop shop = shops.selectById(id);
                redis.execute(syncScript, Arrays.asList(CACHE_SHOP_KEY + id, "shop:geo:types"),
                        id.toString(), SHOP_GEO_KEY, row.get("cache_geo_type_id").toString(), shop.getTypeId().toString(),
                        shop.getX() == null ? "" : shop.getX().toString(), shop.getY() == null ? "" : shop.getY().toString());
                bloom.add(id);
                // Stage 2 is durable: a restart between the two deletions cannot lose it.
                jdbc.update("UPDATE tb_shop SET cache_sync_stage=?,cache_geo_type_id=?,cache_retry_at=TIMESTAMPADD(MICROSECOND,?,NOW(3)) WHERE id=?",
                        stage == 1 ? 2 : 0, shop.getTypeId(),
                        Math.max(1, config.getDoubleDeleteDelayMs()) * 1000L, id);
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Shop cache sync deferred, shop={}", id, e);
            try { jdbc.update("UPDATE tb_shop SET cache_retry_at=TIMESTAMPADD(SECOND,?,NOW(3)) WHERE id=? AND cache_sync_stage<>0",
                    config.getRetrySeconds(), id);
            } catch (RuntimeException retryError) { log.warn("Could not postpone shop task {}", id, retryError); }
        }
    }

    @Scheduled(fixedDelayString = "${hmdp.consistency.worker-delay-ms:1000}")
    public void retryPending() {
        try {
            for (int stage = 1; stage <= 2; stage++) {
                List<Long> ids = jdbc.queryForList(
                        "SELECT id FROM tb_shop WHERE cache_sync_stage=? AND cache_retry_at<=NOW(3) ORDER BY cache_retry_at,id LIMIT ?",
                        Long.class, stage, config.getBatchSize());
                ids.forEach(this::trySync);
            }
        } catch (RuntimeException e) { log.warn("Shop cache recovery will retry", e); }
    }
}
