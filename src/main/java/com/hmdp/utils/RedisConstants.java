package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    // 同一手机号发送验证码的最小间隔（秒）
    public static final String LOGIN_CODE_LIMIT_KEY = "login:code:limit:";
    public static final Long LOGIN_CODE_INTERVAL = 60L;
    // 验证码校验失败次数，达到上限后作废验证码
    public static final String LOGIN_CODE_FAIL_KEY = "login:code:fail:";
    public static final int LOGIN_CODE_MAX_FAIL = 5;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String CACHE_VOUCHER_LIST_KEY = "cache:voucher:list:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    // 秒杀活动时间，Hash：begin / end，毫秒时间戳
    public static final String SECKILL_TIME_KEY = "seckill:time:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String BLOG_COMMENT_LIKED_KEY = "blog:comment:liked:";
    public static final String FEED_KEY = "feed:";
    // 收件箱最多保留的笔记数
    public static final long FEED_MAX_SIZE = 1000;
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
