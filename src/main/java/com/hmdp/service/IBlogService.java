package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 通过笔记id查询笔记信息
     */
    Result queryBlogById(Long id);

    /**
     * 返回用户笔记的分页信息
     */
    Result queryHotBlog(Integer current);

    /**
     * 实现点赞逻辑
     */
    Result likeBlog(Long id);
}
