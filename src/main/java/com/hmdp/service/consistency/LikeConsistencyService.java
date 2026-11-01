package com.hmdp.service.consistency;

import com.hmdp.dto.Result;
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
import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.ReturnType;

/** Redis owns identities; MySQL stores an eventually synchronized absolute count. */
@Slf4j
@Service
public class LikeConsistencyService {
    private static final String DIRTY = "likes:dirty";
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ConsistencyProperties config;
    private final TransactionTemplate tx;
    private final DefaultRedisScript<List> change = script("like_state.lua", List.class);
    private final DefaultRedisScript<List> snapshot = script("like_snapshot.lua", List.class);
    private final DefaultRedisScript<Long> ack = script("like_ack.lua", Long.class);

    public LikeConsistencyService(JdbcTemplate jdbc, StringRedisTemplate redis, ConsistencyProperties config,
                                  PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.redis = redis; this.config = config;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static <T> DefaultRedisScript<T> script(String file, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(file)); script.setResultType(type); return script;
    }
    private String table(boolean comment) { return comment ? "tb_blog_comments" : "tb_blog"; }
    private String members(boolean comment, Long id) { return (comment ? "blog:comment:liked:" : "blog:liked:") + id; }
    private String meta(boolean comment, Long id) { return "likes:meta:" + token(comment, id); }
    private String token(boolean comment, Long id) { return (comment ? "comment:" : "blog:") + id; }

    public Result setLiked(boolean comment, Long id, Long userId, Boolean liked) {
        if (id == null || liked == null) return Result.fail("请明确指定 liked=true 或 false");
        try {
            // 普通读，不开事务、不加行锁：只用来判断目标是否存在、是否第一次接入新版点赞。
            // 如果在请求里 SELECT ... FOR UPDATE，热门笔记的所有点赞会排队等同一行锁并占住数据库连接。
            // 并发由 Lua 保证（名单、计数、版本一次改完），计数写回 MySQL 由后台 sync() 加锁完成。
            // 读完之后评论被删除也没关系：后台同步看到 status=3 会清理这次写入的 Redis 数据。
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT liked,like_initialized" +
                    (comment ? ",status" : "") + " FROM " + table(comment) + " WHERE id=?", id);
            if (rows.isEmpty() || (comment && (rows.get(0).get("status") == null || ((Number) rows.get(0).get("status")).intValue() != 0)))
                return Result.fail(comment ? "评论不存在" : "笔记不存在");
            Map<String, Object> row = rows.get(0);
            boolean initialized = ((Number) row.get("like_initialized")).intValue() == 1;
            List<?> result = redis.execute(change, Arrays.asList(members(comment,id),meta(comment,id),DIRTY),
                    userId.toString(), liked ? "1" : "0", Long.toString(System.currentTimeMillis()), token(comment,id),
                    initialized ? "0" : "1", row.get("liked") == null ? "0" : row.get("liked").toString());
            if (result == null || ((Number) result.get(0)).longValue() < 0)
                return Result.fail("点赞记录缺失，请先恢复 Redis 备份后重试");
            // 只有第一次点赞时写一次数据库，之后不会再执行
            if (!initialized)
                jdbc.update("UPDATE " + table(comment) + " SET like_initialized=1 WHERE id=? AND like_initialized=0", id);
            Map<String, Object> response = new HashMap<>();
            response.put("liked", ((Number) result.get(0)).longValue());
            response.put("isLike", liked);
            return Result.ok(response);
        } catch (RuntimeException e) {
            log.warn("Like request incomplete, target={}", token(comment,id), e);
            return Result.fail("点赞服务暂不可用，请重试相同操作");
        }
    }

    /** Batch list enrichment: one pipeline, retain durable counts if Redis is unavailable. */
    public Map<Long,long[]> readMany(boolean comment, List<Long> ids, Long userId) {
        Map<Long,long[]> states = new HashMap<>();
        if (ids.isEmpty()) return states;
        try {
            byte[] script = snapshot.getScriptAsString().getBytes(StandardCharsets.UTF_8);
            byte[] user = (userId == null ? "" : userId.toString()).getBytes(StandardCharsets.UTF_8);
            List<Object> results = redis.executePipelined((RedisCallback<Object>) connection -> {
                for (Long id : ids) {
                    byte[] key = members(comment,id).getBytes(StandardCharsets.UTF_8);
                    connection.eval(script,ReturnType.MULTI,2,key,meta(comment,id).getBytes(StandardCharsets.UTF_8),user);
                    // Existing installations may have membership but no new metadata yet.
                    connection.zScore(key,user);
                }
                return null;
            });
            for (int i=0;i<ids.size();i++) {
                Object value=results.get(i*2);
                if (value instanceof List && ((List<?>)value).size()==3) {
                    List<?> state=(List<?>)value;
                    states.put(ids.get(i),new long[]{((Number)state.get(1)).longValue(),((Number)state.get(2)).longValue()});
                } else {
                    states.put(ids.get(i),new long[]{-1,userId!=null && results.get(i*2+1)!=null?1:0});
                }
            }
        } catch (RuntimeException e) { states.clear(); }
        return states;
    }

    private void sync(String target) {
        boolean comment = target.startsWith("comment:");
        Long id = Long.valueOf(target.substring(target.indexOf(':') + 1));
        Long version = tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT id" + (comment ? ",status" : "") +
                    " FROM " + table(comment) + " WHERE id=? FOR UPDATE", id);
            if (rows.isEmpty() || (comment && rows.get(0).get("status") != null && ((Number) rows.get(0).get("status")).intValue() == 3)) {
                cleanup(comment,id); return null;
            }
            List<?> value = redis.execute(snapshot, Arrays.asList(members(comment,id),meta(comment,id)), "");
            if (value == null || value.size() != 3) throw new IllegalStateException("Like identities need backup recovery: " + target);
            jdbc.update("UPDATE " + table(comment) + " SET liked=?,like_initialized=1 WHERE id=?", value.get(1), id);
            return ((Number) value.get(0)).longValue();
        });
        // DB commit precedes ack; a newer Redis version must remain pending.
        if (version != null) redis.execute(ack, Arrays.asList(meta(comment,id),DIRTY), version.toString(),target);
    }

    private void cleanup(boolean comment, Long id) {
        redis.delete(Arrays.asList(members(comment,id),meta(comment,id)));
        redis.opsForZSet().remove(DIRTY,token(comment,id));
        if (comment) jdbc.update("UPDATE tb_blog_comments SET like_cleanup_pending=0 WHERE id=? AND status<>0", id);
    }

    @Scheduled(fixedDelayString = "${hmdp.consistency.like-sync-delay-ms:1000}")
    public void syncPending() {
        try {
            Set<String> targets = redis.opsForZSet().rangeByScore(DIRTY,0,System.currentTimeMillis(),0,config.getBatchSize());
            if (targets != null) for (String target : targets) {
                try { sync(target); } catch (RuntimeException e) {
                    log.warn("Like count sync deferred, target={}", target, e);
                    redis.opsForZSet().add(DIRTY,target,System.currentTimeMillis() + config.getRetrySeconds()*1000L);
                }
            }
            List<Long> deleted = jdbc.queryForList("SELECT id FROM tb_blog_comments WHERE like_cleanup_pending=1 ORDER BY id LIMIT ?",
                    Long.class,config.getBatchSize());
            for (Long id : deleted) tx.execute(status -> { cleanup(true,id); return null; });
        } catch (RuntimeException e) { log.warn("Like recovery will retry", e); }
    }
}
