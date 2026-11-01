package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.service.consistency.FollowCacheService;
import com.hmdp.utils.TransactionUtils;
import com.hmdp.utils.UserHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource private IUserService userService;
    @Resource private JdbcTemplate jdbc;
    @Resource private FollowCacheService cache;

    @Override
    @Transactional
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if (followUserId == null || isFollow == null) return Result.fail("关注参数不能为空");
        if (userId.equals(followUserId)) return Result.fail("不能关注自己");
        if (isFollow && userService.getById(followUserId) == null) return Result.fail("用户不存在");
        List<Long> users = jdbc.queryForList("SELECT id FROM tb_user WHERE id=? FOR UPDATE",Long.class,userId);
        if (users.isEmpty()) return Result.fail("用户不存在");
        if (isFollow) {
            jdbc.update("INSERT INTO tb_follow(user_id,follow_user_id) VALUES (?,?) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)",userId,followUserId);
        } else {
            jdbc.update("DELETE FROM tb_follow WHERE user_id=? AND follow_user_id=?",userId,followUserId);
        }
        jdbc.update("UPDATE tb_user SET follow_version=follow_version+1,follow_sync_pending=1,follow_retry_at=NOW(3) WHERE id=?",userId);
        TransactionUtils.afterCommit(() -> cache.trySync(userId));
        return Result.ok();
    }
    @Override
    public Result isFollow(Long followUserId) {
        return Result.ok(query().eq("user_id",UserHolder.getUser().getId()).eq("follow_user_id",followUserId).count()>0);
    }
    @Override
    public Result followCommons(Long id) {
        Set<Long> common = new HashSet<>(cache.followedIds(UserHolder.getUser().getId()));
        common.retainAll(cache.followedIds(id));
        if (common.isEmpty()) return Result.ok(Collections.emptyList());
        return Result.ok(userService.listByIds(common).stream()
                .map(user -> BeanUtil.copyProperties(user,UserDTO.class)).collect(Collectors.toList()));
    }
}
