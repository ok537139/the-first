package com.hmdp.controller;
import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.hmdp.service.IBlogCommentsService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService commentsService;

    @GetMapping("/list")
    public Result queryComments(
            @RequestParam("blogId") Long blogId,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return commentsService.queryComments(blogId, current);
    }

    @PostMapping
    public Result saveComment(@RequestBody BlogComments comment) {
        return commentsService.saveComment(comment);
    }

    @PutMapping("/like/{id}")
    public Result likeComment(@PathVariable("id") Long id, @RequestParam("liked") Boolean liked) {
        return commentsService.likeComment(id, liked);
    }

    @DeleteMapping("/{id}")
    public Result deleteComment(@PathVariable("id") Long id) {
        return commentsService.deleteComment(id);
    }

}
