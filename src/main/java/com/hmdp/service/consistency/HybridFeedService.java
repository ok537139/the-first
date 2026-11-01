package com.hmdp.service.consistency;

import com.hmdp.dto.ScrollResult;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.DateTimeException;
import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.ReturnType;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Delivery mode belongs to each post, so crossing the follower threshold loses no history. */
@Slf4j
@Service
public class HybridFeedService {
    private static final DateTimeFormatter FEED_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int AUTHORS_PER_QUERY = 100;
    private final JdbcTemplate jdbc;
    private final BlogMapper blogs;
    private final StringRedisTemplate redis;
    private final FollowCacheService follows;
    private final ConsistencyProperties config;
    private final TransactionTemplate tx;
    private final DefaultRedisScript<Long> add;
    private final DefaultRedisScript<List> read;

    public HybridFeedService(JdbcTemplate jdbc, BlogMapper blogs, StringRedisTemplate redis, FollowCacheService follows,
                             ConsistencyProperties config, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.blogs=blogs; this.redis=redis; this.follows=follows; this.config=config;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        add = new DefaultRedisScript<>(); add.setLocation(new ClassPathResource("feed_add.lua")); add.setResultType(Long.class);
        read = new DefaultRedisScript<>(); read.setLocation(new ClassPathResource("feed_read.lua")); read.setResultType(List.class);
    }

    /** Called inside the post transaction: content and delivery task commit together. */
    public void register(Blog blog) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT id FROM tb_follow WHERE follow_user_id=? LIMIT ?) f",
                Integer.class,blog.getUserId(),config.getBigAuthorThreshold());
        jdbc.update("UPDATE tb_blog SET feed_mode=?,feed_status=1,feed_cursor=0,feed_retry_at=NOW(3) WHERE id=?",
                count != null && count >= config.getBigAuthorThreshold() ? 1 : 0,blog.getId());
    }

    private String member(Long id, String time) {
        return String.format(Locale.ROOT,"%s:%020d",time,id);
    }
    private void add(String key, String value) {
        redis.execute(add,Collections.singletonList(key),value,Integer.toString(config.getFeedMaxSize()),
                Integer.toString(config.getCacheSeconds()*2));
    }

    private void addBatch(Map<String,List<String>> writes) {
        byte[] script=add.getScriptAsString().getBytes(StandardCharsets.UTF_8);
        byte[] max=Integer.toString(config.getFeedMaxSize()).getBytes(StandardCharsets.UTF_8);
        byte[] ttl=Integer.toString(config.getCacheSeconds()*2).getBytes(StandardCharsets.UTF_8);
        redis.executePipelined((RedisCallback<Object>) connection -> {
            for(Map.Entry<String,List<String>> entry:writes.entrySet()) {
                byte[] key=entry.getKey().getBytes(StandardCharsets.UTF_8);
                for(String value:entry.getValue()) connection.eval(script,ReturnType.INTEGER,1,
                        key,value.getBytes(StandardCharsets.UTF_8),max,ttl);
            }
            return null;
        });
    }

    private void addPosts(String key,List<Map<String,Object>> posts) {
        if(posts.isEmpty()) return;
        // 收件箱最多保留 feedMaxSize 条，排在这之后的写进去也会被立刻裁掉，不必发给 Redis
        List<String> values=posts.stream().map(post -> member(((Number)post.get("id")).longValue(),
                (String)post.get("feed_time"))).sorted(Comparator.reverseOrder())
                .limit(config.getFeedMaxSize()).collect(Collectors.toList());
        addBatch(Collections.singletonMap(key,values));
    }

    private void deliver(Long blogId) {
        tx.execute(status -> {
            List<Map<String,Object>> rows = jdbc.queryForList(
                    "SELECT id,user_id,DATE_FORMAT(create_time,'%Y%m%d%H%i%s') AS feed_time,feed_mode,feed_status,feed_cursor FROM tb_blog WHERE id=? FOR UPDATE",blogId);
            if (rows.isEmpty()) return null;
            Map<String,Object> blog=rows.get(0);
            if (((Number)blog.get("feed_status")).intValue()==0) return null;
            long author=((Number)blog.get("user_id")).longValue();
            String value=member(blogId,(String)blog.get("feed_time"));
            if (((Number)blog.get("feed_mode")).intValue()==1) {
                add("feed:outbox:"+author,value);
                jdbc.update("UPDATE tb_blog SET feed_status=0 WHERE id=?",blogId);
                return null;
            }
            List<Map<String,Object>> fans=jdbc.queryForList(
                    "SELECT id,user_id FROM tb_follow WHERE follow_user_id=? AND id>? ORDER BY id LIMIT ?",
                    author,blog.get("feed_cursor"),config.getFanoutBatchSize());
            Map<String,List<String>> writes=new HashMap<>();
            for(Map<String,Object> fan:fans) writes.put("feed:inbox:"+fan.get("user_id"),Collections.singletonList(value));
            if(!writes.isEmpty()) addBatch(writes);
            long cursor=fans.isEmpty()?((Number)blog.get("feed_cursor")).longValue():
                    ((Number)fans.get(fans.size()-1).get("id")).longValue();
            jdbc.update("UPDATE tb_blog SET feed_cursor=?,feed_status=?,feed_retry_at=NOW(3) WHERE id=?",
                    cursor,fans.size()<config.getFanoutBatchSize()?0:1,blogId);
            return null;
        });
    }

    @Scheduled(fixedDelayString="${hmdp.consistency.feed-delay-ms:1000}")
    public void deliverPending() {
        try {
            List<Long> ids=jdbc.queryForList("SELECT id FROM tb_blog WHERE feed_status=1 AND feed_retry_at<=NOW(3) ORDER BY feed_retry_at,id LIMIT ?",
                    Long.class,config.getBatchSize());
            for(Long id:ids) try { deliver(id); } catch(RuntimeException e) {
                log.warn("Feed delivery deferred, blog={}",id,e);
                jdbc.update("UPDATE tb_blog SET feed_retry_at=TIMESTAMPADD(SECOND,?,NOW(3)) WHERE id=? AND feed_status=1",
                        config.getRetrySeconds(),id);
            }
        } catch(RuntimeException e) { log.warn("Feed recovery will retry",e); }
    }

    private String recentSql(Long author, Integer mode, int limit, String cursor, List<Object> args) {
        String sql="SELECT id,user_id,DATE_FORMAT(create_time,'%Y%m%d%H%i%s') AS feed_time FROM tb_blog WHERE user_id=?";
        args.add(author);
        if(mode!=null) { sql+=" AND feed_mode=?"; args.add(mode); }
        if(cursor!=null) {
            String time=LocalDateTime.parse(cursor.substring(0,14),FEED_TIME).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            sql+=" AND (create_time<? OR (create_time=? AND id<?))";
            args.add(time); args.add(time); args.add(Long.parseLong(cursor.substring(15)));
        }
        sql+=" ORDER BY create_time DESC,id DESC LIMIT ?"; args.add(limit);
        return sql;
    }

    private List<Map<String,Object>> recent(Long author, Integer mode, int limit, String cursor) {
        return recentMany(Collections.singletonList(author),mode,limit,cursor);
    }

    /**
     * 多个作者各自最近的 limit 篇笔记。逐个作者查询的话，关注几百人就要几百次数据库往返（N+1）。
     * MySQL 5.7 没有窗口函数，这里把每个作者的子查询用 UNION ALL 拼成一条 SQL：
     * 每个子查询仍然走 (user_id, create_time, id) 索引并各自 LIMIT，不扫描作者的全部笔记；
     * 每 AUTHORS_PER_QUERY 个作者一条 SQL，往返次数降到原来的百分之一。
     */
    private List<Map<String,Object>> recentMany(Collection<Long> authors, Integer mode, int limit, String cursor) {
        List<Map<String,Object>> posts=new ArrayList<>();
        List<Long> list=new ArrayList<>(authors);
        for(int i=0;i<list.size();i+=AUTHORS_PER_QUERY) {
            List<Object> args=new ArrayList<>();
            List<String> parts=new ArrayList<>();
            for(Long author:list.subList(i,Math.min(list.size(),i+AUTHORS_PER_QUERY)))
                parts.add(recentSql(author,mode,limit,cursor,args));
            // 子查询带 ORDER BY ... LIMIT 时必须加括号，LIMIT 才只作用于这一个作者
            String sql=parts.size()==1 ? parts.get(0) : "(" + String.join(") UNION ALL (",parts) + ")";
            posts.addAll(jdbc.queryForList(sql,args.toArray()));
        }
        return posts;
    }

    private void pruneInbox(String key,Set<Long> authors) {
        List<String> existing=read(key,null,config.getFeedMaxSize());
        for(int i=0;i<existing.size();i+=100) {
            List<String> page=existing.subList(i,Math.min(i+100,existing.size()));
            List<Long> ids=page.stream().map(value->Long.valueOf(value.substring(15))).collect(Collectors.toList());
            Map<Long,Blog> stored=blogs.selectBatchIds(ids).stream().collect(Collectors.toMap(Blog::getId,b->b));
            List<String> obsolete=page.stream().filter(value -> {
                Blog blog=stored.get(Long.valueOf(value.substring(15)));
                return blog==null || !authors.contains(blog.getUserId());
            }).collect(Collectors.toList());
            if(!obsolete.isEmpty()) redis.opsForZSet().remove(key,obsolete.toArray());
        }
    }

    private void ensureInbox(Long userId, Set<Long> authors, long version) {
        String key="feed:inbox:"+userId, ready="feed:ready:"+userId;
        if(Long.toString(version).equals(redis.opsForValue().get(ready)) && redis.opsForZSet().score(key,"!") != null) return;
        // Remove obsolete members before trimming, or unfollowed authors can crowd out valid posts.
        pruneInbox(key,authors);
        // Merge instead of replace: a fanout concurrent with rebuilding must survive.
        addPosts(key,recentMany(authors,0,config.getFollowBackfillSize(),null));
        // If a concurrent follow changed, this old version cannot pass the next read.
        redis.opsForZSet().add(key,"!",0);
        redis.expire(key,config.getCacheSeconds()*2L,TimeUnit.SECONDS);
        redis.opsForValue().set(ready,Long.toString(version),config.getCacheSeconds(),TimeUnit.SECONDS);
    }

    private void ensureOutbox(Long author) {
        String key="feed:outbox:"+author, ready="feed:outbox:ready:"+author;
        if(Boolean.TRUE.equals(redis.hasKey(ready)) && redis.opsForZSet().score(key,"!") != null) return;
        addPosts(key,recent(author,1,config.getFeedMaxSize(),null));
        redis.opsForZSet().add(key,"!",0);
        redis.expire(key,config.getCacheSeconds()*2L,TimeUnit.SECONDS);
        redis.opsForValue().set(ready,"1",config.getCacheSeconds(),TimeUnit.SECONDS);
    }

    private Set<Long> pullAuthors(Set<Long> authors) {
        Set<Long> result=new HashSet<>();
        List<Long> list=new ArrayList<>(authors);
        for(int i=0;i<list.size();i+=config.getFanoutBatchSize()) {
            List<Long> batch=list.subList(i,Math.min(list.size(),i+config.getFanoutBatchSize()));
            String placeholders=String.join(",",Collections.nCopies(batch.size(),"?"));
            result.addAll(jdbc.queryForList("SELECT u.id FROM tb_user u WHERE u.id IN ("+placeholders+
                    ") AND EXISTS (SELECT 1 FROM tb_blog b WHERE b.user_id=u.id AND b.feed_mode=1)",Long.class,batch.toArray()));
        }
        return result;
    }

    private List<String> read(String key,String cursor,int limit) {
        List<?> values=redis.execute(read,Collections.singletonList(key),cursor==null?"+":"("+cursor,Integer.toString(limit));
        List<String> result=new ArrayList<>();
        if(values!=null) for(Object value:values) if(value.toString().matches("[0-9]{14}:[0-9]{20}")) result.add(value.toString());
        return result;
    }

    public ScrollResult query(Long userId,String cursor) {
        if(cursor!=null && (!cursor.matches("[0-9]{14}:[0-9]{20}") || cursor.length()!=35))
            throw new IllegalArgumentException("无效的分页游标");
        if(cursor!=null) {
            Long.parseLong(cursor.substring(15));
            try { LocalDateTime.parse(cursor.substring(0,14),FEED_TIME); }
            catch (DateTimeException e) { throw new IllegalArgumentException("无效的分页游标",e); }
        }
        long version=follows.version(userId);
        Set<Long> authors=follows.followedIds(userId);
        TreeSet<String> candidates=new TreeSet<>(Collections.reverseOrder());
        try {
            ensureInbox(userId,authors,version);
            candidates.addAll(read("feed:inbox:"+userId,cursor,config.getFeedMaxSize()));
            for(Long author:pullAuthors(authors)) {
                ensureOutbox(author);
                candidates.addAll(read("feed:outbox:"+author,cursor,config.getFeedPageSize()+1));
            }
        } catch(RuntimeException e) {
            log.warn("Feed cache unavailable, reading followed authors from MySQL, user={}",userId);
            candidates.clear();
            for(Map<String,Object> post:recentMany(authors,null,config.getFeedPageSize()+1,cursor))
                candidates.add(member(((Number)post.get("id")).longValue(),(String)post.get("feed_time")));
        }
        // Fetch small batches, filter unfollowed/deleted authors, stop after one page plus one.
        List<Blog> selected=new ArrayList<>();
        List<String> keys=new ArrayList<>(candidates);
        for(int i=0;i<keys.size() && selected.size()<=config.getFeedPageSize();i+=100) {
            List<Long> ids=keys.subList(i,Math.min(i+100,keys.size())).stream()
                    .map(key->Long.valueOf(key.substring(15))).collect(Collectors.toList());
            if(ids.isEmpty()) continue;
            Map<Long,Blog> byId=blogs.selectBatchIds(ids).stream().collect(Collectors.toMap(Blog::getId,b->b));
            for(Long id:ids) {
                Blog blog=byId.get(id);
                if(blog!=null && authors.contains(blog.getUserId())) selected.add(blog);
                if(selected.size()>config.getFeedPageSize()) break;
            }
        }
        ScrollResult result=new ScrollResult();
        result.setHasMore(selected.size()>config.getFeedPageSize());
        if(result.isHasMore()) selected.remove(selected.size()-1);
        result.setList(selected);
        if(!selected.isEmpty()) {
            Blog last=selected.get(selected.size()-1);
            result.setNextCursor(keys.stream().filter(key -> Long.parseLong(key.substring(15)) == last.getId()).findFirst().orElse(null));
        }
        return result;
    }
}
