SET NAMES utf8mb4;
USE hmdp;

-- 用户资料：基础库已有 1、2、4、5 四位用户。
INSERT INTO tb_user_info (user_id, city, introduce, fans, followee, gender, birthday, credits, level)
VALUES
    (1, '杭州', '喜欢沿着运河找小店，也爱记录周末的好去处。', 128, 36, 1, '1996-05-18', 860, 3),
    (2, '杭州', '认真吃饭，偶尔健身。甜品和牛排都不能少。', 96, 28, 1, '1998-09-12', 640, 2),
    (4, '杭州', '新杭州人，正在解锁城市每一个角落。', 18, 12, 0, '1994-03-08', 220, 1),
    (5, '杭州', '周末探店搭子，喜欢 KTV 和夜宵。', 21, 15, 0, '1995-11-23', 310, 1)
ON DUPLICATE KEY UPDATE
    city = VALUES(city), introduce = VALUES(introduce), fans = VALUES(fans),
    followee = VALUES(followee), gender = VALUES(gender), birthday = VALUES(birthday),
    credits = VALUES(credits), level = VALUES(level);

-- 为原来没有商户的分类各补充一家店。
INSERT INTO tb_shop (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
VALUES
    (101, '青禾造型工作室', 3, 'https://images.unsplash.com/photo-1560066984-138dadb4c035?auto=format&fit=crop&w=1200&q=80', '武林广场', '体育场路218号3层', 120.16621, 30.27412, 168, 1836, 428, 48, '10:00-21:30'),
    (102, '运河力量健身馆', 4, 'https://images.unsplash.com/photo-1534438327276-14e5300c3a48?auto=format&fit=crop&w=1200&q=80', '拱宸桥', '桥弄街88号运河天地2层', 120.14532, 30.32081, 120, 3260, 782, 47, '07:00-23:00'),
    (103, '松月足道', 5, 'https://images.unsplash.com/photo-1540555700478-4be289fbecef?auto=format&fit=crop&w=1200&q=80', '大关', '香积寺路196号', 120.15311, 30.30762, 138, 2481, 536, 46, '12:00-02:00'),
    (104, '栖云 SPA', 6, 'https://images.unsplash.com/photo-1544161515-4ab6ce6db874?auto=format&fit=crop&w=1200&q=80', '远洋乐堤港', '丽水路66号乐堤港B区4层', 120.14791, 30.31356, 398, 968, 231, 49, '10:30-22:30'),
    (105, '小小探险家亲子乐园', 7, 'https://images.unsplash.com/photo-1596461404969-9ae70f2830c1?auto=format&fit=crop&w=1200&q=80', '北部新城', '杭行路666号万达广场3层', 120.12654, 30.33628, 88, 5142, 1240, 47, '10:00-21:00'),
    (106, '微醺运河精酿酒吧', 8, 'https://images.unsplash.com/photo-1514933651103-005eec06c04b?auto=format&fit=crop&w=1200&q=80', '小河直街', '小河路52号', 120.14588, 30.30984, 126, 2870, 619, 48, '18:00-02:00'),
    (107, '拾光派对轰趴馆', 9, 'https://images.unsplash.com/photo-1492684223066-81342ee5ff30?auto=format&fit=crop&w=1200&q=80', '城西银泰', '丰潭路380号A座6层', 120.10911, 30.29572, 168, 1524, 306, 46, '10:00-24:00'),
    (108, 'MOMO 美甲美睫', 10, 'https://images.unsplash.com/photo-1604654894610-df63bc536371?auto=format&fit=crop&w=1200&q=80', '武林路', '武林路163号2层', 120.16238, 30.26519, 158, 2136, 492, 48, '10:30-21:30')
ON DUPLICATE KEY UPDATE
    name = VALUES(name), type_id = VALUES(type_id), images = VALUES(images), area = VALUES(area),
    address = VALUES(address), x = VALUES(x), y = VALUES(y), avg_price = VALUES(avg_price),
    sold = VALUES(sold), comments = VALUES(comments), score = VALUES(score), open_hours = VALUES(open_hours);

-- 普通券与秒杀券。
INSERT INTO tb_voucher (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
VALUES
    (1001, 4, '100元品质晚餐券', '工作日与周末通用', '堂食可用；不可兑换现金；每桌限用一张', 8800, 10000, 0, 1),
    (1002, 1, '限时秒杀 · 50元代金券', '每日限量，抢完即止', '堂食可用；每人限购一张；不可与其他优惠同享', 1990, 5000, 1, 1),
    (1003, 104, '双人舒缓 SPA 套餐', '提前两小时预约', '含双人 60 分钟护理；节假日通用', 59800, 76000, 0, 1)
ON DUPLICATE KEY UPDATE
    shop_id = VALUES(shop_id), title = VALUES(title), sub_title = VALUES(sub_title),
    rules = VALUES(rules), pay_value = VALUES(pay_value), actual_value = VALUES(actual_value),
    type = VALUES(type), status = VALUES(status);

INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time)
VALUES (1002, 50, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY))
ON DUPLICATE KEY UPDATE stock = VALUES(stock), begin_time = VALUES(begin_time), end_time = VALUES(end_time);

-- 关注关系和评论数据，均使用固定演示 id，保证脚本可重复执行。
INSERT INTO tb_follow (id, user_id, follow_user_id)
VALUES (1001, 1, 2), (1002, 1, 4), (1003, 2, 1), (1004, 4, 2), (1005, 5, 1)
ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), follow_user_id = VALUES(follow_user_id);

INSERT INTO tb_blog_comments (id, user_id, blog_id, parent_id, answer_id, content, liked, status)
VALUES
    (1001, 1, 4, 0, 0, '环境和牛排都很棒，已经加入周末清单！', 8, 0),
    (1002, 4, 5, 0, 0, '这家菠萝包真的值得专程去吃。', 5, 0),
    (1003, 2, 6, 0, 0, '路线很清楚，下次也想去体验。', 3, 0),
    (1004, 2, 4, 1001, 1001, '周末去的话建议提前订位，靠窗的位置很舒服。', 2, 0)
ON DUPLICATE KEY UPDATE content = VALUES(content), liked = VALUES(liked), status = VALUES(status);

INSERT INTO tb_voucher_order (id, user_id, voucher_id, pay_type, status, pay_time)
VALUES (1001001, 2, 1001, 3, 2, NOW())
ON DUPLICATE KEY UPDATE pay_type = VALUES(pay_type), status = VALUES(status), pay_time = VALUES(pay_time);

UPDATE tb_blog b
SET b.comments = (SELECT COUNT(*) FROM tb_blog_comments c WHERE c.blog_id = b.id)
WHERE b.id IN (4, 5, 6, 7);

SET NAMES utf8mb4;
