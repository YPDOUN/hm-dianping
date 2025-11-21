package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
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

    /**
     * 通过笔记id查询笔记信息
     */
    @Override
    public Result queryBlogById(Long id) {
        if (id == null) {
            return Result.fail("该笔记不存在！");
        }

        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("该笔记不存在！");
        }
        // 2.存在则查询相关的用户信息
        queryBlogUser(blog);
        // 3.查询笔记是否被点赞
        isBlogLiked(blog);

        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        // 当前用户未登录直接返回
        if (user == null) {
            return;
        }
        Long userId = user.getId();
        // 2.查询当前登录用户是否已经点过赞了
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    private void queryBlogUser(Blog blog) {
        User user = userService.getById(blog.getUserId());
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 返回用户笔记的分页信息
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            // 查询笔记是否被点赞
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 实现点赞逻辑
     */
    @Override
    public Result likeBlog(Long id) {

        String key = BLOG_LIKED_KEY + id;
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        //读取点赞信息
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //读取失败，则写入redis，并将数据库点赞数+1
        if (score == null) {
            //点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
                return Result.ok("点赞成功！");
            }
        } else {
            //读取成功，证明已经点赞，则数据库-1，并从set中移除
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
                return Result.ok("已取消点赞！");
            }
        }
        return Result.fail("点赞失败！");
    }

    /**
     * 获取点赞排行榜
     */
    @Override
    public Result likesRank(Long id) {

        String key = BLOG_LIKED_KEY + id;
        // 查询点赞时间前5的用户id列表
        Set<String> top5UserIdList = stringRedisTemplate.opsForZSet().range(key, 0, 4);

        if (top5UserIdList != null) {
            // 将字符id转为Long型
            List<Long> ids = top5UserIdList.stream()
                    .map(Long::parseLong)
                    .collect(Collectors.toList());
            //根据id查询用户，并转为DTO对象
            String strIds = StrUtil.join(",", ids);
            //根据id查询用户 where id in (?, ?) order by field(id, 5, 1)
            List<UserDTO> userDTOS = userService.query()
                    .in("id", ids).last("order by field(id," + strIds + ")").list()
                    .stream()
                    .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                    .collect(Collectors.toList());

            return Result.ok(userDTOS);
        }

        return Result.fail("获取排行榜失败！");
    }
}
