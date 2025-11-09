package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private static final String KEY_PREFIX = "lock:";
    //每个线程的UUID不一样，拼接线程号即可
    private static final String THREAD_PREFIX = UUID.fastUUID().toString(true) + "-";
    //读取lua脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);

    }

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeOutSec) {
        //创建一个唯一的线程号
        String threadId = THREAD_PREFIX + Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeOutSec, TimeUnit.SECONDS);
        //Boolean.TRUE为常量
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList("KEY_PREFIX + name"),
                THREAD_PREFIX + Thread.currentThread().getId());
    }

    /*@Override
    public void unLock() {
        //获取线程标识
        String threadId = THREAD_PREFIX + Thread.currentThread().getId();
        //获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //释放锁
        if (id.equals(threadId)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}
