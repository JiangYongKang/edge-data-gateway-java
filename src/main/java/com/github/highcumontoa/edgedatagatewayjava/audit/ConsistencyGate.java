package com.github.highcumontoa.edgedatagatewayjava.audit;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 统计与缓冲联动写操作的互斥闸门（公平读写锁）。
 *
 * <p>写侧（采集接受/溢出丢弃/拒新、补传确认）在写锁内完成
 * “缓冲变更 + 统计记账”的复合动作；核对器在读锁内一次性读取
 * 缓冲视图与统计快照。由于读写互斥，核对器拿到的必然是某一逻辑瞬间的
 * 完整切面，绝不会把复合动作两侧的计数拼成一份自相矛盾的结论。
 *
 * <p>锁顺序约定：gate → buffer.lock → stats 锁（单向，无环）。
 */
public final class ConsistencyGate {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    /** 核对器读取一致性切面时使用。 */
    public Lock readLock() {
        return lock.readLock();
    }

    /** 采集接受/溢出、补传确认等“缓冲+统计”复合写时使用。 */
    public Lock writeLock() {
        return lock.writeLock();
    }
}
