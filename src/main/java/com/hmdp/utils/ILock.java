package com.hmdp.utils;

/**
 * @author 李
 * @version 1.0
 */
public interface ILock {
    /**
     * 尝试获取锁
     *
     * @param timeoutSec 锁持有的时间，过期后自动释放
     * @return true代表获取锁成功，false代表获取锁失败
     */
    public boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    public void unLock();
}
