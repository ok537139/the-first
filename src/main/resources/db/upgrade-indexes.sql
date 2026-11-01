-- 给已有数据库补索引（hmdp.sql 已包含这些索引，新建的数据卷不需要执行）。只需执行一次。
-- Get-Content -Raw -Encoding UTF8 .\src\main\resources\db\upgrade-indexes.sql | docker exec -i hm-dianping-mysql mysql -uroot -p123456 hmdp --default-character-set=utf8mb4

-- 加唯一索引前先清理重复关注（保留 id 最小的一条）
DELETE f1 FROM tb_follow f1
JOIN tb_follow f2 ON f1.user_id = f2.user_id AND f1.follow_user_id = f2.follow_user_id AND f1.id > f2.id;

-- 一人一单的数据库兜底：Redis 已购集合丢失时，重复订单在插入时被拒绝
ALTER TABLE tb_voucher_order ADD UNIQUE INDEX uk_user_voucher (user_id, voucher_id);

-- 同一关注关系只能有一条
ALTER TABLE tb_follow ADD UNIQUE INDEX uk_user_follow (user_id, follow_user_id);

-- 评论分页：等值条件在前、排序字段在后，避免全表扫描和 filesort
ALTER TABLE tb_blog_comments
    ADD INDEX idx_blog_parent_status_time (blog_id, parent_id, status, create_time),
    ADD INDEX idx_parent_time (parent_id, create_time);
