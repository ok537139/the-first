package com.hmdp.config;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Follow;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IShopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.ZoneId;

import static com.hmdp.utils.RedisConstants.FEED_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupDataInitializer {

    private final IShopService shopService;
    private final ISeckillVoucherService seckillVoucherService;
    private final IFollowService followService;
    private final IBlogService blogService;
    private final StringRedisTemplate stringRedisTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpRedisIndexes() {
        List<Shop> shops = shopService.list();
        for (Shop shop : shops) {
            if (shop.getX() != null && shop.getY() != null) {
                stringRedisTemplate.opsForGeo().add(
                        SHOP_GEO_KEY + shop.getTypeId(),
                        new Point(shop.getX(), shop.getY()),
                        shop.getId().toString()
                );
            }
        }

        List<SeckillVoucher> vouchers = seckillVoucherService.list();
        for (SeckillVoucher voucher : vouchers) {
            stringRedisTemplate.opsForValue().setIfAbsent(
                    SECKILL_STOCK_KEY + voucher.getVoucherId(),
                    voucher.getStock().toString()
            );
        }

        List<Follow> follows = followService.list();
        for (Follow follow : follows) {
            stringRedisTemplate.opsForSet().add("follows:" + follow.getUserId(), follow.getFollowUserId().toString());
            List<Blog> blogs = blogService.query().eq("user_id", follow.getFollowUserId()).list();
            for (Blog blog : blogs) {
                long score = blog.getCreateTime() == null ? System.currentTimeMillis()
                        : blog.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                stringRedisTemplate.opsForZSet().add(FEED_KEY + follow.getUserId(), blog.getId().toString(), score);
            }
        }
        log.info("Redis 索引初始化完成：{} 家店铺，{} 张秒杀券，{} 条关注关系", shops.size(), vouchers.size(), follows.size());
    }
}
