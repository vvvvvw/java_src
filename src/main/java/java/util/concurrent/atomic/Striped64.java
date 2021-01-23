/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent.atomic;
import java.util.function.LongBinaryOperator;
import java.util.function.DoubleBinaryOperator;
import java.util.concurrent.ThreadLocalRandom;
// TODO: 2017/9/3 tolook
/**
 * A package-local class holding common representation and mechanics
 * for classes supporting dynamic striping on 64bit values. The class
 * extends Number so that concrete subclasses must publicly do so.
 */
@SuppressWarnings("serial")
abstract class Striped64 extends Number {
    /*
    这个类维护一个延迟初始的、原子地更新值的表，加上额外的 “base” 字段。表的大小是 2 的幂。索引使用每线程的哈希码来masked。这个的几乎所有声明都是包私有的，通过子类直接访问。

表的条目是 Cell 类，一个填充过（通过 sun.misc.Contended ）的 AtomicLong 的变体，用于减少缓存竞争。填充对于多数 Atomics 是过度杀伤的，因为它们一般不规则地分布在内存里，因此彼此间不会有太多冲突。但存在于数组的原子对象将倾向于彼此相邻地放置，因此将通常共享缓存行（对 性能有巨大的副作用），在没有这个防备下。

部分地，因为Cell相对比较大，我们避免创建它们直到需要时。当没有竞争时，所有的更新都作用到 base 字段。根据第一次竞争（更新 base 的 CAS 失败），表被初始化为大小 2。表的大小根据更多的竞争加倍，直到大于或等于CPU数量的最小的 2 的幂。表的槽在它们需要之前保持空。

一个单独的自旋锁（“cellsBusy”）用于初始化和resize表，还有用新的Cell填充槽。不需要阻塞锁，当锁不可得，线程尝试其他槽（或 base）。在这些重试中，会增加竞争和减少本地性，这仍然好于其他选择。

通过 ThreadLocalRandom 维护线程探针字段，作为每线程的哈希码。我们让它们为 0 来保持未初始化直到它们在槽 0 竞争。然后初始化它们为通常不会互相冲突的值。当执行更新操作时，竞争和/或表冲突通过失败了的 CAS 来指示。根据冲突，如果表的大小小于容量，它的大小加倍，除非有些线程持有了锁。如果一个哈希后的槽是空的，且锁可得，创建新的Cell。否则，如果槽存 在，重试CAS。重试通过 “重散列，double hashing” 来继续，使用一个次要的哈希算法（Marsaglia XorShift）来尝试找到一个自由槽位。
//todo ?
表的大小是有上限的，因为，当线程数多于CPU数时，假如每个线程绑定到一个CPU上，存在一个完美的哈希函数映射线程到槽上，消除了冲突。当我们 到达容量，我们随机改变碰撞线程的哈希码搜索这个映射。因为搜索是随机的，冲突只能通过CAS失败来知道，收敛convergence 是慢的，因为线程通常不会一直绑定到CPU上，可能根本不会发生。然而，尽管有这些限制，在这些案例下观察到的竞争频率显著地低。

当哈希到特定 Cell 的线程终止后，Cell 可能变为空闲的，表加倍后导致没有线程哈希到扩展的 Cell 也会出现这种情况。我们不尝试去检测或移除这些 Cell，在实例长期运行的假设下，观察到的竞争水平将重现，所以 Cell 将最终被再次需要。对于短期存活的实例，这没关系。
     */
    /*
     * This class maintains a lazily-initialized table of atomically
     * updated variables, plus an extra "base" field. The table size
     * is a power of two. Indexing uses masked per-thread hash codes.
     * Nearly all declarations in this class are package-private,
     * accessed directly by subclasses.
     *
     * Table entries are of class Cell; a variant of AtomicLong padded
     * (via @sun.misc.Contended) to reduce cache contention. Padding
     * is overkill for most Atomics because they are usually
     * irregularly scattered in memory and thus don't interfere much
     * with each other. But Atomic objects residing in arrays will
     * tend to be placed adjacent to each other, and so will most
     * often share cache lines (with a huge negative performance
     * impact) without this precaution.
     *
     * In part because Cells are relatively large, we avoid creating
     * them until they are needed.  When there is no contention, all
     * updates are made to the base field.  Upon first contention (a
     * failed CAS on base update), the table is initialized to size 2.
     * The table size is doubled upon further contention until
     * reaching the nearest power of two greater than or equal to the
     * number of CPUS. Table slots remain empty (null) until they are
     * needed.
     *
     * A single spinlock ("cellsBusy") is used for initializing and
     * resizing the table, as well as populating slots with new Cells.
     * There is no need for a blocking lock; when the lock is not
     * available, threads try other slots (or the base).  During these
     * retries, there is increased contention and reduced locality,
     * which is still better than alternatives.
     *
     * The Thread probe fields maintained via ThreadLocalRandom serve
     * as per-thread hash codes. We let them remain uninitialized as
     * zero (if they come in this way) until they contend at slot
     * 0. They are then initialized to values that typically do not
     * often conflict with others.  Contention and/or table collisions
     * are indicated by failed CASes when performing an update
     * operation. Upon a collision, if the table size is less than
     * the capacity, it is doubled in size unless some other thread
     * holds the lock. If a hashed slot is empty, and lock is
     * available, a new Cell is created. Otherwise, if the slot
     * exists, a CAS is tried.  Retries proceed by "double hashing",
     * using a secondary hash (Marsaglia XorShift) to try to find a
     * free slot.
     *
     * The table size is capped because, when there are more threads
     * than CPUs, supposing that each thread were bound to a CPU,
     * there would exist a perfect hash function mapping threads to
     * slots that eliminates collisions. When we reach capacity, we
     * search for this mapping by randomly varying the hash codes of
     * colliding threads.  Because search is random, and collisions
     * only become known via CAS failures, convergence can be slow,
     * and because threads are typically not bound to CPUS forever,
     * may not occur at all. However, despite these limitations,
     * observed contention rates are typically low in these cases.
     *
     * It is possible for a Cell to become unused when threads that
     * once hashed to it terminate, as well as in the case where
     * doubling the table causes no thread to hash to it under
     * expanded mask.  We do not try to detect or remove such cells,
     * under the assumption that for long-running instances, observed
     * contention levels will recur, so the cells will eventually be
     * needed again; and for short-lived ones, it does not matter.
     */

    /**
     * Padded variant of AtomicLong supporting only raw accesses plus CAS.
     *
     * JVM intrinsics note: It would be possible to use a release-only
     * form of CAS here, if it were provided.
     */
    /*
    1.striping和缓存行填充：通过把类数据 striping 为 64bit 的片段，使数据成为缓存行友好的，减少CAS竞争。
    2.分解表示：对于一个数字 5，可以分解为一序列数的和：2 + 3，这个数字加 1 也等价于它的分解序列中的任一 数字加 1：5 + 1 = 2 + (3 + 1)。
    3.通过把分解序列存放在表里面，表的条目都是填充后的 Cell；限制表的大小为 2 的幂，则可以用掩码来实现索引；同时把表的大小限制为大于等于CPU数量的最小的 2 的幂。
    4.当表的条目上出现竞争时，在到达容量前表扩容一倍，通过增加条目来减少竞争。
     */
    @sun.misc.Contended static final class Cell {
        volatile long value;
        Cell(long x) { value = x; }
        final boolean cas(long cmp, long val) {
            return UNSAFE.compareAndSwapLong(this, valueOffset, cmp, val);
        }

        // Unsafe mechanics
        private static final sun.misc.Unsafe UNSAFE;
        private static final long valueOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> ak = Cell.class;
                valueOffset = UNSAFE.objectFieldOffset
                    (ak.getDeclaredField("value"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /** Number of CPUS, to place bound on table size */
    static final int NCPU = Runtime.getRuntime().availableProcessors();

    /**
     * Table of cells. When non-null, size is a power of 2.
     */
    // 存放 Cell 的表。当不为空时大小是 2 的幂。
    // cell的长度上限是 大于等于cpu核数的最小的 2的幂
    transient volatile Cell[] cells;

    /**
     * Base value, used mainly when there is no contention, but also as
     * a fallback during table initialization races. Updated via CAS.
     */
    // base 值，在没有竞争时使用，也作为表初始化竞争时的一个后备。
    transient volatile long base;

    /**
     * Spinlock (locked via CAS) used when resizing and/or creating Cells.
     */
    // 自旋锁，在 resizing 和/或 创建Cell时使用。
    transient volatile int cellsBusy;

    /**
     * Package-private default constructor
     */
    Striped64() {
    }

    /**
     * CASes the base field.
     */
    final boolean casBase(long cmp, long val) {
        return UNSAFE.compareAndSwapLong(this, BASE, cmp, val);
    }

    /**
     * CASes the cellsBusy field from 0 to 1 to acquire lock.
     */
    final boolean casCellsBusy() {
        return UNSAFE.compareAndSwapInt(this, CELLSBUSY, 0, 1);
    }

    /**
     * Returns the probe value for the current thread.
     * Duplicated from ThreadLocalRandom because of packaging restrictions.
     */
    //todo ?
    static final int getProbe() {
        return UNSAFE.getInt(Thread.currentThread(), PROBE);
    }

    /**
     * Pseudo-randomly advances and records the given probe value for the
     * given thread.
     * Duplicated from ThreadLocalRandom because of packaging restrictions.
     */
    //todo ?
    static final int advanceProbe(int probe) {
        probe ^= probe << 13;   // xorshift
        probe ^= probe >>> 17;
        probe ^= probe << 5;
        UNSAFE.putInt(Thread.currentThread(), PROBE, probe);
        return probe;
    }

    /**
     * Handles cases of updates involving initialization, resizing,
     * creating new Cells, and/or contention. See above for
     * explanation. This method suffers the usual non-modularity
     * problems of optimistic retry code, relying on rechecked sets of
     * reads.
     *
     * @param x the value
     * @param fn the update function, or null for add (this convention
     * avoids the need for an extra field or function in LongAdder).
     * @param wasUncontended false if CAS failed before call
     */
    /*
    if 表已初始化
        if 映射到的槽是空的，加锁后再次判断，如果仍然是空的，初始化cell并关联到槽。
        else if （槽不为空）在槽上之前的CAS已经失败，重试。
        else if （槽不为空、且之前的CAS没失败，）在此槽的cell上尝试更新
        else if 表已达到容量上限或被扩容了，重试。
        else if 如果不存在冲突，则设置为存在冲突，重试。
        else if 如果成功获取到锁，则扩容。
        else 重散列，尝试其他槽。
    else if 锁空闲且获取锁成功，初始化表
    else if 回退 base 上更新且成功则退出
    else 继续

     */
    final void longAccumulate(long x, LongBinaryOperator fn,
                              //todo ？ 不知道wasUncontended有什么用处
                              boolean wasUncontended) {
        int h;
        if ((h = getProbe()) == 0) {
            // 未初始化的
            ThreadLocalRandom.current(); // force initialization // 强制初始化
            h = getProbe();
            wasUncontended = true;
        }
        // 最后的槽不为空则 true，也用于控制扩容，false重试。
        boolean collide = false;                // True if last slot nonempty
        for (;;) {
            Cell[] as; Cell a; int n; long v;
            if ((as = cells) != null && (n = as.length) > 0) {
                // 表已经初始化
                if ((a = as[(n - 1) & h]) == null) {
                    // 线程所映射到的槽是空的。
                    if (cellsBusy == 0) {       // Try to attach new Cell // 尝试关联新的Cell
                        // 锁未被使用，乐观地创建并初始化cell。
                        Cell r = new Cell(x);   // Optimistically create
                        if (cellsBusy == 0 && casCellsBusy()) {
                            // 锁仍然是空闲的、且成功获取到锁
                            boolean created = false;
                            try {               // Recheck under lock // 在持有锁时再次检查槽是否空闲。
                                Cell[] rs; int m, j;
                                if ((rs = cells) != null &&
                                    (m = rs.length) > 0 &&
                                    rs[j = (m - 1) & h] == null) {
                                    // 所映射的槽仍为空
                                    rs[j] = r; // 关联 cell 到槽
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0; // 释放锁
                            }
                            if (created)
                                break;  // 成功创建cell并关联到槽，退出
                            continue;           // Slot is now non-empty // 槽现在不为空了
                        }
                    }
                    // 设置为不存在冲突
                    collide = false;
                }
                // 槽被占用了
                else if (!wasUncontended)       // CAS already known to fail // 已经知道 CAS 失败
                    wasUncontended = true;      // Continue after rehash // 在重散列后继续
                // 在当前槽的cell上尝试更新
                else if (a.cas(v = a.value, ((fn == null) ? v + x :
                                             fn.applyAsLong(v, x))))
                    break;
                // 表大小达到上限contend或扩容了；
                // 表达到上限后就不会再尝试下面if的扩容了，只会重散列，尝试其他槽
                else if (n >= NCPU || cells != as)
                    collide = false;            // At max size or stale
                //  如果不存在冲突，则设置为存在冲突
                else if (!collide)
                    collide = true;
                // 存在冲突，获取锁，扩容
                else if (cellsBusy == 0 && casCellsBusy()) {
                    // 锁空闲且成功获取到锁
                    try {
                        if (cells == as) {      // Expand table unless stale // 距上一次检查后表没有改变，扩容：加倍
                            Cell[] rs = new Cell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            cells = rs;
                        }
                    } finally {
                        cellsBusy = 0; // 释放锁
                    }
                    collide = false;
                    continue;                   // Retry with expanded table // 在扩容后的表上重试
                }
                // 没法获取锁，重散列，尝试其他槽
                h = advanceProbe(h);
            }
            else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
                // 加锁的情况下初始化表
                boolean init = false;
                try {                           // Initialize table
                    if (cells == as) {
                        Cell[] rs = new Cell[2];
                        rs[h & 1] = new Cell(x);
                        cells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0; // 释放锁
                }
                if (init)
                    break; // 成功初始化，已更新，跳出循环
            }
            else if (casBase(v = base, ((fn == null) ? v + x :
                                        fn.applyAsLong(v, x))))
                // 表未被初始化，可能正在初始化，回退使用 base。
                break;                          // Fall back on using base // 回退到使用 base
        }
    }

    /**
     * Same as longAccumulate, but injecting long/double conversions
     * in too many places to sensibly merge with long version, given
     * the low-overhead requirements of this class. So must instead be
     * maintained by copy/paste/adapt.
     */
    final void doubleAccumulate(double x, DoubleBinaryOperator fn,
                                boolean wasUncontended) {
        int h;
        if ((h = getProbe()) == 0) {
            ThreadLocalRandom.current(); // force initialization
            h = getProbe();
            wasUncontended = true;
        }
        boolean collide = false;                // True if last slot nonempty
        for (;;) {
            Cell[] as; Cell a; int n; long v;
            if ((as = cells) != null && (n = as.length) > 0) {
                if ((a = as[(n - 1) & h]) == null) {
                    if (cellsBusy == 0) {       // Try to attach new Cell
                        Cell r = new Cell(Double.doubleToRawLongBits(x));
                        if (cellsBusy == 0 && casCellsBusy()) {
                            boolean created = false;
                            try {               // Recheck under lock
                                Cell[] rs; int m, j;
                                if ((rs = cells) != null &&
                                    (m = rs.length) > 0 &&
                                    rs[j = (m - 1) & h] == null) {
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0;
                            }
                            if (created)
                                break;
                            continue;           // Slot is now non-empty
                        }
                    }
                    collide = false;
                }
                else if (!wasUncontended)       // CAS already known to fail
                    wasUncontended = true;      // Continue after rehash
                else if (a.cas(v = a.value,
                               ((fn == null) ?
                                Double.doubleToRawLongBits
                                (Double.longBitsToDouble(v) + x) :
                                Double.doubleToRawLongBits
                                (fn.applyAsDouble
                                 (Double.longBitsToDouble(v), x)))))
                    break;
                else if (n >= NCPU || cells != as)
                    collide = false;            // At max size or stale
                else if (!collide)
                    collide = true;
                else if (cellsBusy == 0 && casCellsBusy()) {
                    try {
                        if (cells == as) {      // Expand table unless stale
                            Cell[] rs = new Cell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            cells = rs;
                        }
                    } finally {
                        cellsBusy = 0;
                    }
                    collide = false;
                    continue;                   // Retry with expanded table
                }
                h = advanceProbe(h);
            }
            else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
                boolean init = false;
                try {                           // Initialize table
                    if (cells == as) {
                        Cell[] rs = new Cell[2];
                        rs[h & 1] = new Cell(Double.doubleToRawLongBits(x));
                        cells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0;
                }
                if (init)
                    break;
            }
            else if (casBase(v = base,
                             ((fn == null) ?
                              Double.doubleToRawLongBits
                              (Double.longBitsToDouble(v) + x) :
                              Double.doubleToRawLongBits
                              (fn.applyAsDouble
                               (Double.longBitsToDouble(v), x)))))
                break;                          // Fall back on using base
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long BASE;
    private static final long CELLSBUSY;
    private static final long PROBE;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> sk = Striped64.class;
            BASE = UNSAFE.objectFieldOffset
                (sk.getDeclaredField("base"));
            CELLSBUSY = UNSAFE.objectFieldOffset
                (sk.getDeclaredField("cellsBusy"));
            Class<?> tk = Thread.class;
            //todo ?
            PROBE = UNSAFE.objectFieldOffset
                (tk.getDeclaredField("threadLocalRandomProbe"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
