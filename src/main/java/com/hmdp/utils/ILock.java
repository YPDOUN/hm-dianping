package com.hmdp.utils;

public interface ILock {

    /**
     * 尝试获取锁
     * @param timeOutSec 锁持有的超时时间，过期自动失效
     * @return ture代表获取锁成功，false代表失败
     */
    boolean tryLock(long timeOutSec);

    /**
     * 释放锁
     */
    void unLock();
}
