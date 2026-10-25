package com.hmdp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    // 允许上传的图片类型。不限制的话可以上传 HTML 等文件，通过 /imgs/** 访问时会造成 XSS
    private static final List<String> ALLOWED_SUFFIXES = Arrays.asList("jpg", "jpeg", "png", "gif", "webp");

    private static final Path BLOG_IMAGE_DIR = Paths.get(SystemConstants.IMAGE_UPLOAD_DIR, "blogs").toAbsolutePath().normalize();

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        // 获取原始文件名称
        String originalFilename = image.getOriginalFilename();
        String suffix = StrUtil.subAfter(StrUtil.nullToEmpty(originalFilename), ".", true).toLowerCase(Locale.ROOT);
        String contentType = image.getContentType();
        if (!ALLOWED_SUFFIXES.contains(suffix) || contentType == null || !contentType.startsWith("image/")) {
            return Result.fail("只能上传 jpg、png、gif、webp 格式的图片");
        }
        try {
            // 生成新文件名
            String fileName = createNewFileName(suffix);
            // 保存文件
            image.transferTo(new File(SystemConstants.IMAGE_UPLOAD_DIR, fileName));
            // 返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        // 规范化后必须仍在图片目录内，防止 name=../../xxx 删除任意文件（路径穿越）
        Path target = Paths.get(SystemConstants.IMAGE_UPLOAD_DIR, filename).toAbsolutePath().normalize();
        if (!target.startsWith(BLOG_IMAGE_DIR) || target.toFile().isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(target.toFile());
        return Result.ok();
    }

    private String createNewFileName(String suffix) {
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 判断目录是否存在
        File dir = new File(SystemConstants.IMAGE_UPLOAD_DIR, StrUtil.format("/blogs/{}/{}", d1, d2));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 生成文件名
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
