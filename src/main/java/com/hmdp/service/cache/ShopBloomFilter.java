package com.hmdp.service.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;

/**
 * 店铺 id 布隆过滤器，放在缓存之前拦截不存在的店铺 id（缓存穿透）。
 * <p>
 * 过滤器带版本号（bloom:shop:v{时间戳}），查询通过指针 key 找到当前版本：
 * - 初始化和重建都是先在新 key 上全量加载，加载完再切换指针，加载期间不会把真实店铺误判为不存在；
 * - 重建期间新增的店铺双写新旧两个过滤器，切换后不会漏；
 * - 标准布隆过滤器不能删除元素，删除的店铺留在过滤器里，由下一层的缓存空值挡住，定期重建时清掉。
 * 重建时机：启动时检查容量；每天低峰期定时全量重建（hmdp.bloom.shop.rebuild-cron）。
 */
@Slf4j
@Component
public class ShopBloomFilter {

    private static final String CURRENT_KEY = "bloom:shop:current";
    private static final String BUILDING_KEY = "bloom:shop:building";
    private static final String FILTER_KEY_PREFIX = "bloom:shop:v";
    private static final String LOCK_KEY = "lock:bloom:shop";
    // 误判率
    private static final double FALSE_PROBABILITY = 0.01;
    // 预期元素数 = 现有店铺数 * 2，留出增长余量，且不少于这个值
    private static final long MIN_CAPACITY = 1000;
    // 元素数超过预期容量的这个比例就重建，否则误判率会明显上升
    private static final double REBUILD_THRESHOLD = 0.8;
    private static final int LOAD_BATCH_SIZE = 1000;

    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ShopMapper shopMapper;

    /**
     * 启动时调用：还没有过滤器，或者元素数接近容量时重建。过滤器存在 Redis 里，重启不会重复加载。
     */
    public void init() {
        String current = stringRedisTemplate.opsForValue().get(CURRENT_KEY);
        if (current == null) {
            rebuild();
            return;
        }
        RBloomFilter<Long> filter = redissonClient.getBloomFilter(current);
        if (filter.count() >= filter.getExpectedInsertions() * REBUILD_THRESHOLD) {
            log.info("店铺布隆过滤器元素数接近容量，重建");
            rebuild();
        }
    }

    /**
     * 返回 false 表示店铺一定不存在；true 表示可能存在。
     * 过滤器还没建好或者 Redis 出错时返回 true，退回到缓存空值 + 查库，不误拦真实数据。
     */
    public boolean mightContain(Long shopId) {
        try {
            String current = stringRedisTemplate.opsForValue().get(CURRENT_KEY);
            return current == null || redissonClient.<Long>getBloomFilter(current).contains(shopId);
        } catch (Exception e) {
            log.warn("查询店铺布隆过滤器失败，放行: shopId={}", shopId, e);
            return true;
        }
    }

    /**
     * 新增店铺时调用。重建期间同时写入正在构建的新过滤器。重复添加是安全的。
     * 切换版本的瞬间，读到的旧过滤器可能刚被删除，写它会失败；只要有一个过滤器写成功就算成功。
     * 全部失败时抛异常，让新增店铺的事务回滚，否则这家店会被过滤器误判为不存在。
     */
    public void add(Long shopId) {
        RuntimeException failure = null;
        boolean added = false;
        for (String key : stringRedisTemplate.opsForValue().multiGet(Arrays.asList(CURRENT_KEY, BUILDING_KEY))) {
            if (key == null) {
                continue;
            }
            try {
                redissonClient.<Long>getBloomFilter(key).add(shopId);
                added = true;
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        if (!added && failure != null) {
            throw failure;
        }
    }

    /**
     * 定时全量重建：清掉已删除的店铺，并按当前店铺数重新计算容量。默认每天凌晨 4 点（低峰期）。
     * 多实例都会触发，由 rebuild() 里的锁保证只有一个实例执行。
     */
    @Scheduled(cron = "${hmdp.bloom.shop.rebuild-cron:0 0 4 * * ?}")
    public void scheduledRebuild() {
        log.info("定时重建店铺布隆过滤器");
        rebuild();
    }

    /**
     * 全量重建：新 key 上加载完再切换指针，然后删除旧过滤器。多实例同时触发时只有一个执行。
     */
    public void rebuild() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        if (!lock.tryLock()) {
            return;
        }
        try {
            long capacity = Math.max(shopMapper.selectCount(null) * 2L, MIN_CAPACITY);
            String newKey = FILTER_KEY_PREFIX + System.currentTimeMillis();
            RBloomFilter<Long> filter = redissonClient.getBloomFilter(newKey);
            filter.tryInit(capacity, FALSE_PROBABILITY);
            // 先开启双写再加载：加载过程中新增的店铺也会写进新过滤器
            stringRedisTemplate.opsForValue().set(BUILDING_KEY, newKey);
            long loaded = load(filter);
            String oldKey = stringRedisTemplate.opsForValue().getAndSet(CURRENT_KEY, newKey);
            stringRedisTemplate.delete(BUILDING_KEY);
            if (oldKey != null && !oldKey.equals(newKey)) {
                redissonClient.getBloomFilter(oldKey).delete();
            }
            log.info("店铺布隆过滤器重建完成：{}，{} 个店铺，预期容量 {}", newKey, loaded, capacity);
        } catch (RuntimeException e) {
            // 加载失败：继续使用旧过滤器，清理掉没建完的新过滤器，等下次重建
            log.error("店铺布隆过滤器重建失败，继续使用旧版本", e);
            String building = stringRedisTemplate.opsForValue().get(BUILDING_KEY);
            stringRedisTemplate.delete(BUILDING_KEY);
            if (building != null && !building.equals(stringRedisTemplate.opsForValue().get(CURRENT_KEY))) {
                redissonClient.getBloomFilter(building).delete();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按 id 分批加载，避免一次把整张表查进内存
     */
    private long load(RBloomFilter<Long> filter) {
        long lastId = 0;
        long total = 0;
        while (true) {
            List<Shop> shops = shopMapper.selectList(new QueryWrapper<Shop>()
                    .select("id").gt("id", lastId).orderByAsc("id").last("LIMIT " + LOAD_BATCH_SIZE));
            for (Shop shop : shops) {
                filter.add(shop.getId());
            }
            total += shops.size();
            if (shops.size() < LOAD_BATCH_SIZE) {
                return total;
            }
            lastId = shops.get(shops.size() - 1).getId();
        }
    }
}
