-- Run ONCE against an existing database, before starting the new application.
-- Fresh Docker databases run this after the original schema and seed data.
USE hmdp;
ALTER TABLE tb_shop
  ADD COLUMN cache_sync_stage TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN cache_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ADD COLUMN cache_geo_type_id BIGINT NOT NULL DEFAULT 0,
  ADD INDEX idx_shop_cache_due (cache_sync_stage, cache_retry_at, id);
UPDATE tb_shop SET cache_geo_type_id = type_id, cache_sync_stage = 1;
ALTER TABLE tb_user
  ADD COLUMN follow_version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN follow_sync_pending TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN follow_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ADD INDEX idx_user_follow_due (follow_sync_pending, follow_retry_at, id);
ALTER TABLE tb_follow ADD INDEX idx_follow_fanout (follow_user_id, id, user_id);
ALTER TABLE tb_blog
  ADD COLUMN feed_mode TINYINT NOT NULL DEFAULT 0 COMMENT '0 push, 1 pull; fixed per post',
  ADD COLUMN feed_status TINYINT NOT NULL DEFAULT 0 COMMENT '0 complete, 1 pending',
  ADD COLUMN feed_cursor BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN feed_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ADD COLUMN like_initialized TINYINT NOT NULL DEFAULT 0,
  ADD INDEX idx_blog_feed_due (feed_status, feed_retry_at, id),
  ADD INDEX idx_blog_author_feed (user_id, feed_mode, create_time, id),
  ADD INDEX idx_blog_author_time (user_id, create_time, id);
ALTER TABLE tb_blog_comments
  ADD COLUMN like_initialized TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN like_cleanup_pending TINYINT NOT NULL DEFAULT 0,
  ADD INDEX idx_comment_like_cleanup (like_cleanup_pending, id);

ALTER TABLE tb_blog_comments MODIFY COLUMN status TINYINT UNSIGNED NULL DEFAULT NULL COMMENT '0 normal, 1 reported, 2 hidden, 3 deleted';
