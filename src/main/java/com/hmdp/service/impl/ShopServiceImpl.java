package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.service.cache.ShopBloomFilter;
import com.hmdp.service.consistency.ShopCacheSyncService;
import org.springframework.dao.DataAccessException;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.TransactionUtils;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private ShopBloomFilter shopBloomFilter;

    @Resource
    private ShopCacheSyncService cacheSync;

    @Override
    public Result queryById(Long id) {
        // 布隆过滤器判断一定不存在的 id 直接返回，不访问 Redis 和 MySQL；误判的少量请求由缓存空值兜底
        if (!shopBloomFilter.mightContain(id)) {
            return Result.fail("店铺不存在！");
        }
        // 缓存空值防穿透 + 互斥锁防击穿
        Shop shop;
        try {
            shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (DataAccessException e) {
            shop = getById(id);
        }

        // 只防穿透（不防击穿）
        // Shop shop = cacheClient
        //         .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿（需要提前预热，缓存格式为 RedisData）
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 7.返回
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result saveShop(Shop shop) {
        if (!validCoordinates(shop)) return Result.fail("店铺坐标超出有效范围");
        shop.setId(null);
        save(shop);
        // id 是自增的，插入后才能拿到。在事务提交前加入布隆过滤器：
        // 事务回滚只会在过滤器里多一个不存在的 id（多一次误判，缓存空值兜底）；
        // 提交后再加的话，提交到加入之间的查询会把真实存在的店铺判成不存在
        shopBloomFilter.add(shop.getId());
        cacheSync.markPending(shop.getId());
        TransactionUtils.afterCommit(() -> cacheSync.trySync(shop.getId()));
        return Result.ok(shop.getId());
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        if (!validCoordinates(shop)) return Result.fail("店铺坐标超出有效范围");
        Shop existing = query().eq("id", id).last("FOR UPDATE").one();
        if (existing == null) return Result.fail("店铺不存在");
        // 1.更新数据库
        updateById(shop);
        cacheSync.markPending(id);
        // 2.事务提交后再删除缓存：提交前删除的话，期间的读请求会读到旧数据并写回缓存
        TransactionUtils.afterCommit(() -> cacheSync.trySync(id));
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }

    private boolean validCoordinates(Shop shop) {
        return (shop.getX() == null || (Double.isFinite(shop.getX()) && Math.abs(shop.getX()) <= 180))
                && (shop.getY() == null || (Double.isFinite(shop.getY()) && Math.abs(shop.getY()) <= 85.05112878));
    }
}
