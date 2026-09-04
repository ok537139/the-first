package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_COMMENT_LIKED_KEY;

@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments>
        implements IBlogCommentsService {

    @Resource
    private IBlogService blogService;

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryComments(Long blogId, Integer current) {
        if (blogId == null || current == null || current < 1) {
            return Result.fail("参数错误");
        }
        Page<BlogComments> page = query()
                .eq("blog_id", blogId)
                .eq("parent_id", 0)
                .eq("status", 0)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));

        List<BlogComments> roots = page.getRecords();
        if (roots.isEmpty()) {
            return Result.ok(Collections.emptyList(), page.getTotal());
        }

        List<Long> rootIds = roots.stream().map(BlogComments::getId).collect(Collectors.toList());
        List<BlogComments> replies = query()
                .eq("blog_id", blogId)
                .in("parent_id", rootIds)
                .eq("status", 0)
                .orderByAsc("create_time")
                .list();

        List<BlogComments> all = new ArrayList<>(roots);
        all.addAll(replies);
        enrichComments(all);

        Map<Long, List<BlogComments>> replyMap = replies.stream()
                .collect(Collectors.groupingBy(BlogComments::getParentId, LinkedHashMap::new, Collectors.toList()));
        roots.forEach(comment -> comment.setReplies(
                replyMap.getOrDefault(comment.getId(), Collections.emptyList())
        ));
        return Result.ok(roots, page.getTotal());
    }

    @Override
    @Transactional
    public Result saveComment(BlogComments comment) {
        if (comment == null || comment.getBlogId() == null || StrUtil.isBlank(comment.getContent())) {
            return Result.fail("评论内容不能为空");
        }
        String content = comment.getContent().trim();
        if (content.length() > 255) {
            return Result.fail("评论内容不能超过255个字符");
        }
        Blog blog = blogService.getById(comment.getBlogId());
        if (blog == null) {
            return Result.fail("笔记不存在");
        }

        Long parentId = comment.getParentId() == null ? 0L : comment.getParentId();
        Long answerId = comment.getAnswerId() == null ? 0L : comment.getAnswerId();
        if (parentId > 0) {
            BlogComments parent = getById(parentId);
            if (parent == null || !Objects.equals(parent.getBlogId(), comment.getBlogId()) || parent.getParentId() != 0) {
                return Result.fail("回复的一级评论不存在");
            }
            if (answerId > 0) {
                BlogComments answer = getById(answerId);
                if (answer == null || !Objects.equals(answer.getBlogId(), comment.getBlogId())
                        || !(Objects.equals(answer.getId(), parentId) || Objects.equals(answer.getParentId(), parentId))) {
                    return Result.fail("被回复的评论不存在");
                }
            }
        } else {
            answerId = 0L;
        }

        comment.setId(null);
        comment.setUserId(UserHolder.getUser().getId());
        comment.setParentId(parentId);
        comment.setAnswerId(answerId);
        comment.setContent(content);
        comment.setLiked(0);
        comment.setStatus(0);
        if (!save(comment)) {
            return Result.fail("发表评论失败");
        }
        blogService.update().setSql("comments = IFNULL(comments, 0) + 1")
                .eq("id", comment.getBlogId()).update();
        return Result.ok(comment.getId());
    }

    @Override
    public Result likeComment(Long id) {
        BlogComments comment = getById(id);
        if (comment == null || !Objects.equals(comment.getStatus(), 0)) {
            return Result.fail("评论不存在");
        }
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_COMMENT_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            boolean success = update().setSql("liked = IFNULL(liked, 0) + 1").eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            boolean success = update().setSql("liked = GREATEST(IFNULL(liked, 0) - 1, 0)").eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    @Transactional
    public Result deleteComment(Long id) {
        BlogComments comment = getById(id);
        if (comment == null || !Objects.equals(comment.getStatus(), 0)) {
            return Result.fail("评论不存在");
        }
        if (!Objects.equals(comment.getUserId(), UserHolder.getUser().getId())) {
            return Result.fail("只能删除自己的评论");
        }

        List<BlogComments> removed = query().eq("id", id).or().eq("parent_id", id).list();
        boolean success = remove(new QueryWrapper<BlogComments>().eq("id", id).or().eq("parent_id", id));
        if (!success) {
            return Result.fail("删除评论失败");
        }
        blogService.update()
                .setSql("comments = GREATEST(IFNULL(comments, 0) - " + removed.size() + ", 0)")
                .eq("id", comment.getBlogId())
                .update();
        removed.forEach(item -> stringRedisTemplate.delete(BLOG_COMMENT_LIKED_KEY + item.getId()));
        return Result.ok();
    }

    private void enrichComments(List<BlogComments> comments) {
        Set<Long> userIds = comments.stream().map(BlogComments::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, BlogComments> commentMap = comments.stream()
                .collect(Collectors.toMap(BlogComments::getId, Function.identity()));
        UserDTO currentUser = UserHolder.getUser();

        for (BlogComments comment : comments) {
            User user = userMap.get(comment.getUserId());
            if (user != null) {
                comment.setUserName(user.getNickName());
                comment.setUserIcon(user.getIcon());
            }
            if (comment.getAnswerId() != null && comment.getAnswerId() > 0) {
                BlogComments answer = commentMap.get(comment.getAnswerId());
                User answerUser = answer == null ? null : userMap.get(answer.getUserId());
                comment.setAnswerName(answerUser == null ? null : answerUser.getNickName());
            }
            if (currentUser != null) {
                Double score = stringRedisTemplate.opsForZSet().score(
                        BLOG_COMMENT_LIKED_KEY + comment.getId(), currentUser.getId().toString());
                comment.setIsLike(score != null);
            } else {
                comment.setIsLike(false);
            }
        }
    }
}
