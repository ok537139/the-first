package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource private com.hmdp.service.consistency.LikeConsistencyService likes;
    @Resource private com.hmdp.service.consistency.HybridFeedService feed;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 批量查询作者和点赞状态
        fillAuthorAndLiked(records);
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 2.查询blog有关的用户、是否被点赞
        fillAuthorAndLiked(Collections.singletonList(blog));
        return Result.ok(blog);
    }

    /**
     * 批量填充作者信息和当前用户的点赞状态，避免 N+1：
     * 作者批量查库，点赞计数与当前用户状态通过一次 Redis pipeline 读取；
     * 现在用户一次 IN 查询，点赞状态用 pipeline 一次发出。
     */
    private void fillAuthorAndLiked(List<Blog> blogs) {
        if (blogs.isEmpty()) {
            return;
        }
        // 1.作者：一次 IN 查询。作者账号不存在时跳过，不再空指针
        Set<Long> userIds = blogs.stream().map(Blog::getUserId).collect(Collectors.toSet());
        Map<Long, User> users = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        for (Blog blog : blogs) {
            User user = users.get(blog.getUserId());
            if (user != null) {
                blog.setName(user.getNickName());
                blog.setIcon(user.getIcon());
            }
        }
        UserDTO current = UserHolder.getUser();
        Map<Long,long[]> likeStates = likes.readMany(false,blogs.stream().map(Blog::getId).collect(Collectors.toList()),
                current == null ? null : current.getId());
        for (Blog blog : blogs) {
            long[] state = likeStates.get(blog.getId());
            if (state != null) {
                if (state[0] >= 0) blog.setLiked((int)state[0]);
                blog.setIsLike(state[1] == 1);
            }
        }
    }

    @Override
    public Result likeBlog(Long id, Boolean liked) {
        return likes.setLiked(false,id,UserHolder.getUser().getId(),liked);
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4.返回
        return Result.ok(userDTOS);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Result saveBlog(Blog blog) {
        if (blog.getShopId() == null || StrUtil.isBlank(blog.getTitle()) || StrUtil.isBlank(blog.getContent()))
            return Result.fail("店铺、标题和内容不能为空");
        blog.setId(null);
        blog.setUserId(UserHolder.getUser().getId());
        blog.setLiked(0);
        blog.setComments(0);
        blog.setCreateTime(java.time.LocalDateTime.now().withNano(0));
        blog.setUpdateTime(blog.getCreateTime());
        if (!save(blog)) return Result.fail("新增笔记失败");
        feed.register(blog);
        return Result.ok(blog.getId());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Result queryBlogOfFollow(String cursor) {
        try {
            ScrollResult result = feed.query(UserHolder.getUser().getId(),cursor);
            fillAuthorAndLiked((List<Blog>)result.getList());
            return Result.ok(result);
        } catch (IllegalArgumentException e) {
            return Result.fail("无效的分页游标");
        }
    }

    @Override
    public Result queryByAuthor(Long userId, Integer current) {
        if (current == null || current < 1) return Result.fail("页码错误");
        List<Blog> records = query().eq("user_id",userId).orderByDesc("create_time","id")
                .page(new Page<>(current,SystemConstants.MAX_PAGE_SIZE)).getRecords();
        fillAuthorAndLiked(records);
        return Result.ok(records);
    }
}
