package com.hmdp.service.consistency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FollowCacheService {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ConsistencyProperties config;
    private final TransactionTemplate tx;
    private final DefaultRedisScript<Long> publish;

    public FollowCacheService(JdbcTemplate jdbc, StringRedisTemplate redis, ConsistencyProperties config,
                              PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.redis=redis; this.config=config;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        publish = new DefaultRedisScript<>();
        publish.setLocation(new ClassPathResource("follow_publish.lua")); publish.setResultType(Long.class);
    }
    public long version(Long userId) {
        List<Long> values = jdbc.queryForList("SELECT follow_version FROM tb_user WHERE id=?", Long.class,userId);
        return values.isEmpty() ? -1 : values.get(0);
    }
    public Set<Long> followedIds(Long userId) {
        long version = version(userId);
        if (version < 0) return Collections.emptySet();
        try {
            String cached = redis.opsForValue().get("follows:version:"+userId);
            if (Long.toString(version).equals(cached)) {
                Set<String> ids = redis.opsForSet().members("follows:"+userId);
                if (ids != null && ids.remove("_")) return ids.stream().map(Long::valueOf).collect(Collectors.toSet());
            }
            return rebuild(userId);
        } catch (RuntimeException e) {
            log.warn("Follow cache unavailable, reading user {} from MySQL",userId);
            return databaseIds(userId);
        }
    }
    private Set<Long> databaseIds(Long userId) {
        Set<Long> ids = new LinkedHashSet<>();
        long cursor = 0;
        while (true) {
            List<Long> page = jdbc.queryForList("SELECT follow_user_id FROM tb_follow WHERE user_id=? AND follow_user_id>? ORDER BY follow_user_id LIMIT ?",
                    Long.class,userId,cursor,config.getFanoutBatchSize());
            ids.addAll(page);
            if (page.size() < config.getFanoutBatchSize()) break;
            cursor = page.get(page.size()-1);
        }
        return ids;
    }
    private Set<Long> rebuild(Long userId) {
        return tx.execute(status -> {
            List<Long> versions = jdbc.queryForList("SELECT follow_version FROM tb_user WHERE id=? FOR UPDATE",Long.class,userId);
            if (versions.isEmpty()) return Collections.emptySet();
            Set<Long> ids = databaseIds(userId);
            String temporary = "follows:building:"+userId+":"+UUID.randomUUID();
            redis.opsForSet().add(temporary,"_");
            redis.expire(temporary,60,TimeUnit.SECONDS);
            List<String> batch = new ArrayList<>();
            for (Long id : ids) {
                batch.add(id.toString());
                if (batch.size() == config.getFanoutBatchSize()) {
                    redis.opsForSet().add(temporary,batch.toArray(new String[0])); batch.clear();
                }
            }
            if (!batch.isEmpty()) redis.opsForSet().add(temporary,batch.toArray(new String[0]));
            redis.execute(publish,Arrays.asList(temporary,"follows:"+userId,"follows:version:"+userId,"feed:ready:"+userId),
                    versions.get(0).toString(),Integer.toString(config.getCacheSeconds()));
            jdbc.update("UPDATE tb_user SET follow_sync_pending=0 WHERE id=?",userId);
            return ids;
        });
    }
    public void trySync(Long userId) {
        try { rebuild(userId); } catch (RuntimeException e) {
            log.warn("Follow cache sync deferred, user={}",userId,e);
            try { jdbc.update("UPDATE tb_user SET follow_retry_at=TIMESTAMPADD(SECOND,?,NOW(3)) WHERE id=? AND follow_sync_pending=1",
                    config.getRetrySeconds(),userId);
            } catch (RuntimeException ignored) { log.warn("Could not postpone follow task {}",userId); }
        }
    }
    @Scheduled(fixedDelayString = "${hmdp.consistency.worker-delay-ms:1000}")
    public void retryPending() {
        try {
            jdbc.queryForList("SELECT id FROM tb_user WHERE follow_sync_pending=1 AND follow_retry_at<=NOW(3) ORDER BY follow_retry_at,id LIMIT ?",
                    Long.class,config.getBatchSize()).forEach(this::trySync);
        } catch (RuntimeException e) { log.warn("Follow recovery will retry",e); }
    }
}
