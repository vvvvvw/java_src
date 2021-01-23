/*
 * Copyright (c) 1997, 2013, Oracle and/or its affiliates. All rights reserved.
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

package java.lang;
import java.lang.ref.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * This class provides thread-local variables.  These variables differ from
 * their normal counterparts in that each thread that accesses one (via its
 * {@code get} or {@code set} method) has its own, independently initialized
 * copy of the variable.  {@code ThreadLocal} instances are typically private
 * static fields in classes that wish to associate state with a thread (e.g.,
 * a user ID or Transaction ID).
 * //该类提供了线程局部 (thread-local) 变量。这些变量不同于它们的普通对应物，
 * 因为访问某个变量（通过其 get 或 set 方法）的每个线程都有自己的局部变量，
 * 它独立于变量的初始化副本。ThreadLocal 实例通常是类中的 private static 字段，
 * 它们希望将状态与某一个线程（例如，用户 ID 或事务 ID）相关联。
 * <p>For example, the class below generates unique identifiers local to each
 * thread.
 * A thread's id is assigned the first time it invokes {@code ThreadId.get()}
 * and remains unchanged on subsequent calls.
 * <pre>
 * import java.util.concurrent.atomic.AtomicInteger;
 *
 * public class ThreadId {
 *     // Atomic integer containing the next thread ID to be assigned
 *     private static final AtomicInteger nextId = new AtomicInteger(0);
 *
 *     // Thread local variable containing each thread's ID
 *     private static final ThreadLocal&lt;Integer&gt; threadId =
 *         new ThreadLocal&lt;Integer&gt;() {
 *             &#64;Override protected Integer initialValue() {
 *                 return nextId.getAndIncrement();
 *         }
 *     };
 *
 *     // Returns the current thread's unique ID, assigning it if necessary
 *     public static int get() {
 *         return threadId.get();
 *     }
 * }
 * </pre>
 * <p>Each thread holds an implicit reference to its copy of a thread-local
 * variable as long as the thread is alive and the {@code ThreadLocal}
 * instance is accessible; after a thread goes away, all of its copies of
 * thread-local instances are subject to garbage collection (unless other
 * references to these copies exist).
 * 每个线程都保持对其线程局部变量副本的隐式引用，
 * 只要线程是活动的并且 ThreadLocal 实例是可访问的；在线程消失之后，
 * 其线程局部实例的所有副本都会被垃圾回收（除非存在对这些副本的其他引用）
 * @author  Josh Bloch and Doug Lea
 * @since   1.2
 */
//ThreadLocalMap 中使用开放地址法来处理散列冲突，而 HashMap 中使用的分离链表法。
// 之所以采用不同的方式主要是因为：在 ThreadLocalMap 中的散列值分散的十分均匀，
// 很少会出现冲突。并且 ThreadLocalMap 经常需要清除无用的对象，使用纯数组更加方便。
public class ThreadLocal<T> {
    /**
     * ThreadLocals rely on per-thread linear-probe hash maps attached
     * to each thread (Thread.threadLocals and
     * inheritableThreadLocals).  The ThreadLocal objects act as keys,
     * searched via threadLocalHashCode.  This is a custom hash code
     * (useful only within ThreadLocalMaps) that eliminates collisions
     * in the common case where consecutively constructed ThreadLocals
     * are used by the same threads, while remaining well-behaved in
     * less common cases.
     */
    private final int threadLocalHashCode = nextHashCode();

    /**
     * The next hash code to be given out. Updated atomically. Starts at
     * zero.
     */
    //下一个ThreadLocal变量对应的hashCode
    private static AtomicInteger nextHashCode =
        new AtomicInteger();

    /**
     * The difference between successively generated hash codes - turns
     * implicit sequential thread-local IDs into near-optimally spread
     * multiplicative hash values for power-of-two-sized tables.
     */
    //每次新建的threadlocal对象的hash值都是在原有hash值基础上增加HASH_INCREMENT，threadlocal解决散列表的冲突而引入的神奇的hash code
    private static final int HASH_INCREMENT = 0x61c88647;

    /**
     * Returns the next hash code.
     */
    private static int nextHashCode() {
        return nextHashCode.getAndAdd(HASH_INCREMENT);
    }

    /**
     * Returns the current thread's "initial value" for this
     * thread-local variable.  This method will be invoked the first
     * time a thread accesses the variable with the {@link #get}
     * method, unless the thread previously invoked the {@link #set}
     * method, in which case the {@code initialValue} method will not
     * be invoked for the thread.  Normally, this method is invoked at
     * most once per thread, but it may be invoked again in case of
     * subsequent invocations of {@link #remove} followed by {@link #get}.
     *
     * <p>This implementation simply returns {@code null}; if the
     * programmer desires thread-local variables to have an initial
     * value other than {@code null}, {@code ThreadLocal} must be
     * subclassed, and this method overridden.  Typically, an
     * anonymous inner class will be used.
     *
     * @return the initial value for this thread-local
     */
    //返回此线程局部变量的当前线程的“初始值”。线程第一次使用 get() 方法
    // 变量时将调用此方法，但如果线程之前调用了 set（T） 方法,就不会调用了
    protected T initialValue() {
        return null;
    }

    /**
     * Creates a thread local variable. The initial value of the variable is
     * determined by invoking the {@code get} method on the {@code Supplier}.
     *
     * @param <S> the type of the thread local's value
     * @param supplier the supplier to be used to determine the initial value
     * @return a new thread local variable
     * @throws NullPointerException if the specified supplier is null
     * @since 1.8
     */
    public static <S> ThreadLocal<S> withInitial(Supplier<? extends S> supplier) {
        return new SuppliedThreadLocal<>(supplier);
    }

    /**
     * Creates a thread local variable.
     * @see #withInitial(java.util.function.Supplier)
     */
    public ThreadLocal() {
    }

    /**
     * Returns the value in the current thread's copy of this
     * thread-local variable.  If the variable has no value for the
     * current thread, it is first initialized to the value returned
     * by an invocation of the {@link #initialValue} method.
     *
     * @return the current thread's value of this thread-local
     */
    // 返回此线程局部变量的当前线程副本中的值。
    // 如果变量没有用于当前线程的值，则先将其初始化为调用 initialValue() 方法返回的值。
    public T get() {
        // 获取当前线程
        Thread t = Thread.currentThread();
        // 获取当前线程的 ThreadLocalMap  对象
        ThreadLocalMap map = getMap(t);
        // 如果map不是null，将 ThreadlLocal 对象作为 key 获取对应的值
        if (map != null) {
            ThreadLocalMap.Entry e = map.getEntry(this);
            // 如果该值存在，则返回该值
            if (e != null) {
                @SuppressWarnings("unchecked")
                T result = (T)e.value;
                return result;
            }
        }
        // 如果上面的逻辑没有取到值，则从 initialValue  方法中取值
        return setInitialValue();
    }

    /**
     * Variant of set() to establish initialValue. Used instead
     * of set() in case user has overridden the set() method.
     *
     * @return the initial value
     */
    private T setInitialValue() {
        T value = initialValue();
        Thread t = Thread.currentThread();
        ThreadLocalMap map = getMap(t);
        if (map != null)
            map.set(this, value);
        else
            createMap(t, value);
        return value;
    }

    /**
     * Sets the current thread's copy of this thread-local variable
     * to the specified value.  Most subclasses will have no need to
     * override this method, relying solely on the {@link #initialValue}
     * method to set the values of thread-locals.
     *
     * @param value the value to be stored in the current thread's copy of
     *        this thread-local.
     */
    //将此线程局部变量的当前线程副本中的值设置为指定值。
    // 大部分子类不需要重写此方法，它们只依靠 initialValue() 方法来设置线程局部变量的值。value可以为null
    public void set(T value) {
        Thread t = Thread.currentThread();
        //根据当前线程得到线程的 ThreadLocalMap 属性
        ThreadLocalMap map = getMap(t);
        if (map != null)
            //直接将值放置到Map中
            map.set(this, value);
        else
            //如果 Map 为null， 则创建一个Map ，并将值放置到Map中
            createMap(t, value);
    }

    /**
     * Removes the current thread's value for this thread-local
     * variable.  If this thread-local variable is subsequently
     * {@linkplain #get read} by the current thread, its value will be
     * reinitialized by invoking its {@link #initialValue} method,
     * unless its value is {@linkplain #set set} by the current thread
     * in the interim.  This may result in multiple invocations of the
     * {@code initialValue} method in the current thread.
     *
     * @since 1.5
     */
    //移除此线程局部变量当前线程的值。如果此线程局部变量随后被当前线程读取，
    // 且这期间当前线程没有设置其值，则将调用其 initialValue() 方法重新初始化其值。
    // 这将导致在当前线程多次调用 initialValue 方法。则不会对该线程再调用 initialValue 方法。
    // 通常，initialValue方法对每个线程最多调用一次，但如果在调用 get() 后
    // 又调用了 remove（） ，则可能再次调用initialValue方法。
     public void remove() {
         ThreadLocalMap m = getMap(Thread.currentThread());
         if (m != null)
             m.remove(this);
     }

    /**
     * Get the map associated with a ThreadLocal. Overridden in
     * InheritableThreadLocal.
     *
     * @param  t the current thread
     * @return the map
     */
    ThreadLocalMap getMap(Thread t) {
        return t.threadLocals;
    }

    /**
     * Create the map associated with a ThreadLocal. Overridden in
     * InheritableThreadLocal.
     *
     * @param t the current thread
     * @param firstValue value for the initial entry of the map
     */
    void createMap(Thread t, T firstValue) {
        t.threadLocals = new ThreadLocalMap(this, firstValue);
    }

    /**
     * Factory method to create map of inherited thread locals.
     * Designed to be called only from Thread constructor.
     *
     * @param  parentMap the map associated with parent thread
     * @return a map containing the parent's inheritable bindings
     */
    //只被Thread构造器使用（传入的Map中的ThreadLocal应该是InheritableThreadLocal）
    static ThreadLocalMap createInheritedMap(ThreadLocalMap parentMap) {
        return new ThreadLocalMap(parentMap);
    }

    /**
     * Method childValue is visibly defined in subclass
     * InheritableThreadLocal, but is internally defined here for the
     * sake of providing createInheritedMap factory method without
     * needing to subclass the map class in InheritableThreadLocal.
     * This technique is preferable to the alternative of embedding
     * instanceof tests in methods.
     */
    T childValue(T parentValue) {
        throw new UnsupportedOperationException();
    }

    /**
     * An extension of ThreadLocal that obtains its initial value from
     * the specified {@code Supplier}.
     */
    static final class SuppliedThreadLocal<T> extends ThreadLocal<T> {

        private final Supplier<? extends T> supplier;

        SuppliedThreadLocal(Supplier<? extends T> supplier) {
            this.supplier = Objects.requireNonNull(supplier);
        }

        @Override
        protected T initialValue() {
            return supplier.get();
        }
    }

    /**
     * ThreadLocalMap is a customized hash map suitable only for
     * maintaining thread local values. No operations are exported
     * outside of the ThreadLocal class. The class is package private to
     * allow declaration of fields in class Thread.  To help deal with
     * very large and long-lived usages, the hash table entries use
     * WeakReferences for keys. However, since reference queues are not
     * used, stale entries are guaranteed to be removed only when
     * the table starts running out of space.
     */
    static class ThreadLocalMap {

        /**
         * The entries in this hash map extend WeakReference, using
         * its main ref field as the key (which is always a
         * ThreadLocal object).  Note that null keys (i.e. entry.get()
         * == null) mean that the key is no longer referenced, so the
         * entry can be expunged from table.  Such entries are referred to
         * as "stale entries" in the code that follows.
         */
        static class Entry extends WeakReference<ThreadLocal<?>> {
            /** The value associated with this ThreadLocal. */
            Object value;

            Entry(ThreadLocal<?> k, Object v) {
                super(k);
                value = v;
            }
        }

        /**
         * The initial capacity -- MUST be a power of two.
         */
        private static final int INITIAL_CAPACITY = 16;

        /**
         * The table, resized as necessary.
         * table.length MUST always be a power of two.
         */
        private Entry[] table;

        /**
         * The number of entries in the table.
         */
        private int size = 0;

        /**
         * The next size value at which to resize.
         */
        //负载因子，默认为2/3的size，如果size>threshold就扩容
        private int threshold; // Default to 0

        /**
         * Set the resize threshold to maintain at worst a 2/3 load factor.
         */
        private void setThreshold(int len) {
            threshold = len * 2 / 3;
        }

        /**
         * Increment i modulo len.
         */
        private static int nextIndex(int i, int len) {
            return ((i + 1 < len) ? i + 1 : 0);
        }

        /**
         * Decrement i modulo len.
         */
        private static int prevIndex(int i, int len) {
            return ((i - 1 >= 0) ? i - 1 : len - 1);
        }

        /**
         * Construct a new map initially containing (firstKey, firstValue).
         * ThreadLocalMaps are constructed lazily, so we only create
         * one when we have at least one entry to put in it.
         */
        ThreadLocalMap(ThreadLocal<?> firstKey, Object firstValue) {
            //// 默认长度16
            table = new Entry[INITIAL_CAPACITY];
            //// 得到下标
            int i = firstKey.threadLocalHashCode & (INITIAL_CAPACITY - 1);
            // 创建一个entry对象并插入数组
            table[i] = new Entry(firstKey, firstValue);
            // 设置长度属性为1
            size = 1;

            setThreshold(INITIAL_CAPACITY); //设置阀值== 16 * 2 / 3 == 10,resize 的时候，并不是10，而是 10 - 10 / 4，也就是 8，负载因子为 0.5
        }

        /**
         * Construct a new map including all Inheritable ThreadLocals
         * from given parent map. Called only by createInheritedMap.
         *
         * @param parentMap the map associated with parent thread.
         */
        /**
         * 构建一个包含所有parentMap中Inheritable ThreadLocals的ThreadLocalMap
         * 该函数只被 createInheritedMap() 调用.
         */
        // 只被createInheritedMap调用
        private ThreadLocalMap(ThreadLocalMap parentMap) {
            Entry[] parentTable = parentMap.table;
            int len = parentTable.length;
            setThreshold(len);
            table = new Entry[len];

            // 逐一复制 parentMap 的记录
            for (int j = 0; j < len; j++) {
                Entry e = parentTable[j];
                if (e != null) {
                    @SuppressWarnings("unchecked")
                    ThreadLocal<Object> key = (ThreadLocal<Object>) e.get();
                    if (key != null) {
                        Object value = key.childValue(e.value);
                        Entry c = new Entry(key, value);
                        int h = key.threadLocalHashCode & (len - 1);
                        while (table[h] != null)
                            h = nextIndex(h, len);
                        table[h] = c;
                        size++;
                    }
                }
            }
        }

        /**
         * Get the entry associated with key.  This method
         * itself handles only the fast path: a direct hit of existing
         * key. It otherwise relays to getEntryAfterMiss.  This is
         * designed to maximize performance for direct hits, in part
         * by making this method readily inlinable.
         *
         * @param  key the thread local object
         * @return the entry associated with key, or null if no such
         */
        //获取key 对应的 entry，如果没有找到，则返回null
        private Entry getEntry(ThreadLocal<?> key) {
            int i = key.threadLocalHashCode & (table.length - 1);
            Entry e = table[i];
            //如果hash 没有冲突，直接返回 对应的值
            if (e != null && e.get() == key)
                return e;
            else
                //如果hash冲突了，调用 getEntryAfterMiss 方法
                return getEntryAfterMiss(key, i, e);
        }

        /**
         * Version of getEntry method for use when key is not found in
         * its direct hash slot.
         *
         * @param  key the thread local object
         * @param  i the table index for key's hash code
         * @param  e the entry at table[i]
         * @return the entry associated with key, or null if no such
         */
        //循环所有的元素，直到找到 key 对应的 entry，如果发现了
        // 某个元素的 key 是 null，顺手调用 expungeStaleEntry 方法清理 从该元素位置开始到第一个entry为null为元素截止的所有 key 为 null 的 entry
        private Entry getEntryAfterMiss(ThreadLocal<?> key, int i, Entry e) {
            Entry[] tab = table;
            int len = tab.length;

            //从前往后遍历直到第一个entry为null的元素或者直到找到 key 对应的 entry
            //再次过程中entry已经被回收（key为null），则调用expungeStaleEntry
            while (e != null) {
                ThreadLocal<?> k = e.get();
                if (k == key)
                    return e;
                if (k == null)
                    expungeStaleEntry(i);
                else
                    i = nextIndex(i, len);
                e = tab[i];
            }
            return null;
        }

        /**
         * Set the value associated with key.
         *
         * @param key the thread local object
         * @param value the value to be set
         */
        private void set(ThreadLocal<?> key, Object value) {

            // We don't use a fast path as with get() because it is at
            // least as common to use set() to create new entries as
            // it is to replace existing ones, in which case, a fast
            // path would fail more often than not.

            Entry[] tab = table;
            int len = tab.length;
            // 根据 ThreadLocal 的 HashCode 得到对应的下标
            int i = key.threadLocalHashCode & (len-1);

            /*
            // 首先通过下标找对应的entry对象，如果没有，则创建一个新的 entry对象
            // 如果key冲突了，则将下标加一
            （加一后如果小于数组长度则使用该值，否则使用0），
            //如果为null，表示原对象已经被gc了，则创建一个新的 entry 对象填充该槽
            // 再次尝试获取对应的 entry，如果不为null，则在循环中继续判断key 是否重复或者k是否是null
             */
            //因为有threshold 扩容，所以不会导致死循环
            for (Entry e = tab[i];
                 e != null;
                 e = tab[i = nextIndex(i, len)]) {
                ThreadLocal<?> k = e.get();

                if (k == key) {
                    e.value = value;
                    return;
                }

                //// 如果key被 GC 回收了（因为是软引用），则创建一个新的 entry 对象填充该槽
                if (k == null) {
                    replaceStaleEntry(key, value, i);
                    return;
                }
            }
            // // 创建一个新的 entry 对象
            tab[i] = new Entry(key, value);
            //// 长度加一
            int sz = ++size;
            //如果 hash 没有冲突，也会调用 cleanSomeSlots 方法，
            // 该方法同样会清除无用的 entry，也就是 key 为null 的节点
            //// 如果没有清除多余的entry 并且数组长度达到了阀值，则扩容
            if (!cleanSomeSlots(i, sz) && sz >= threshold)
                rehash();
        }

        /**
         * Remove the entry for key.
         */
        //通过线性探测法找到 key 对应的 entry，调用 clear 方法，
        // 将 ThreadLocal 设置为null，调用 expungeStaleEntry 方法，该方法顺便会清理其他的 key 为 null 的 entry
        private void remove(ThreadLocal<?> key) {
            Entry[] tab = table;
            int len = tab.length;
            int i = key.threadLocalHashCode & (len-1);
            for (Entry e = tab[i];
                 e != null;
                 e = tab[i = nextIndex(i, len)]) {
                if (e.get() == key) {
                    e.clear();
                    expungeStaleEntry(i);
                    return;
                }
            }
        }

        /**
         * Replace a stale entry encountered during a set operation
         * with an entry for the specified key.  The value passed in
         * the value parameter is stored in the entry, whether or not
         * an entry already exists for the specified key.
         *
         * As a side effect, this method expunges all stale entries in the
         * "run" containing the stale entry.  (A run is a sequence of entries
         * between two null slots.)
         *
         * @param  key the key
         * @param  value the value to be associated with key
         * @param  staleSlot index of the first stale entry encountered while
         *         searching for key.
         */
        //删除 ThreadLocal 为 null 的 entry
        ////通过线性探测法，找到每个槽位，如果该槽位的key为相同，
        // 就替换这个value；如果这个key 是null，则将原来的entry 设置为null，并重新创建一个entry
        //走到了这里，会从后往前清理一部分被gc的threadlocal，也就是说，当hash
        // 冲突的时候并且对应的槽位的key值是null，就会清除对应的key 为null 的entry。
        private void replaceStaleEntry(ThreadLocal<?> key, Object value,
                                       int staleSlot) {
            Entry[] tab = table;
            int len = tab.length;
            Entry e;

            // Back up to check for prior stale entry in current run.
            // We clean out whole runs at a time to avoid continual
            // incremental rehashing due to garbage collector freeing
            // up refs in bunches (i.e., whenever the collector runs).
            //staleSlot为 发现的 第一个key为null的槽位
            int slotToExpunge = staleSlot;
            //一直向前直到第一个entry为null的entry
            for (int i = prevIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = prevIndex(i, len))
                if (e.get() == null)
                    //此时slotToExpunge为从后往前遍历的第一个key为null的槽位
                    slotToExpunge = i;

            // Find either the key or trailing null slot of run, whichever
            // occurs first
            //从staleSlot+1从前往后遍历，直到第一个entry为null的entry，
            //其中
            // 1.如果遍历到 和 key对应的entry，则直接将value设置到 entry中，并且和 tab[staleSlot]交换,并且清理被gc的槽位
            //2.

            for (int i = nextIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = nextIndex(i, len)) {
                ThreadLocal<?> k = e.get();

                // If we find key, then we need to swap it
                // with the stale entry to maintain hash table order.
                // The newly stale slot, or any other stale slot
                // encountered above it, can then be sent to expungeStaleEntry
                // to remove or rehash all of the other entries in run.
                if (k == key) {
                    e.value = value;

                    //将 table[i] 和 tab[staleSlot] 交换
                    tab[i] = tab[staleSlot];
                    tab[staleSlot] = e;

                    // Start expunge at preceding stale entry if it exists
                    //如果 上面向前遍历时没有找到其他key为null的槽位，从当前槽位开始清理被gc的threadlocalmap

                    if (slotToExpunge == staleSlot)
                        slotToExpunge = i;
                    cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
                    return;
                }

                // If we didn't find stale entry on backward scan, the
                // first stale entry seen while scanning for key is the
                // first still present in the run.
                //如果 上面向前遍历时没有找到其他key为null的槽位，从当前槽位(当前槽位是从前往后遍历第一个key为null的槽位)开始清理被gc的threadlocalmap
                if (k == null && slotToExpunge == staleSlot)
                    //将 slotToExpunge设置为 i
                    slotToExpunge = i;
            }

            // If key not found, put new entry in stale slot
            //如果没有发现本key对应的 entry，将tab[staleSlot]设置为本key对应的entry
            tab[staleSlot].value = null;
            tab[staleSlot] = new Entry(key, value);

            // If there are any other stale entries in run, expunge them
            //清理槽位
            if (slotToExpunge != staleSlot)
                cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
        }

        /**
         * Expunge a stale entry by rehashing any possibly colliding entries
         * lying between staleSlot and the next null slot.  This also expunges
         * any other stale entries encountered before the trailing null.  See
         * Knuth, Section 6.4
         *
         * @param staleSlot index of slot known to have null key
         * @return the index of the next null slot after staleSlot
         * (all between staleSlot and this slot will have been checked
         * for expunging).
         */
        //todo 如果只是调用expungeStaleEntry来清理节点，不可能会出现 如下情况
        //清理前： 1    2     3     4 5
        // 清理后:  1 null    null   4  5
        //将stale的entry置为null，同时rehash
        //1.清除 staleSlot位置的entry
        //2.从 staleSlot+1开始从前往后遍历直到第一个 为null的entry
        //遍历过程中
        //2.1.entry对应的key为null，表示已经被gc但是 entry没有被清除，则清除该entry
        //2.2.entry对应的key不为null，将 entry放置到使用entry对应的key的hashCode和开放地址法发现的第一个可以放置的entry上
        private int expungeStaleEntry(int staleSlot) {
            Entry[] tab = table;
            int len = tab.length;

            //清除 staleSlot位置的entry
            // expunge entry at staleSlot
            tab[staleSlot].value = null;
            tab[staleSlot] = null;
            size--;

            // Rehash until we encounter null
            Entry e;
            int i;
            //从 staleSlot+1开始遍历直到第一个 为null的entry
            //遍历过程中
            //1.entry对应的key为null，表示已经被gc但是 entry没有被清除，则清除该entry
            //2.entry对应的key不为null，将 entry放置到使用entry对应的key的hashCode和开放地址法发现的第一个可以放置的entry上
            for (i = nextIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = nextIndex(i, len)) {
                ThreadLocal<?> k = e.get();
                if (k == null) {
                    e.value = null;
                    tab[i] = null;
                    size--;
                } else {
                    //todo 如果只是调用expungeStaleEntry来清理节点，不可能会出现 如下情况，这种保证是如下代码实现的
                    //清理前： 1    2     3     4 5
                    // 清理后:  1 null    null   4  5
                    //将 tab[h]放置到使用 tab[h]对应的hashCode和开放地址法发现的第一个可以放置的entry上
                    int h = k.threadLocalHashCode & (len - 1);
                    if (h != i) {
                        tab[i] = null;

                        // Unlike Knuth 6.4 Algorithm R, we must scan until
                        // null because multiple entries could have been stale.
                        while (tab[h] != null)
                            h = nextIndex(h, len);
                        tab[h] = e;
                    }
                }
            }
            //返回从 staleSlot+1开始第一个 entry为null的索引
            return i;
        }

        /**
         * Heuristically scan some cells looking for stale entries.
         * This is invoked when either a new element is added, or
         * another stale one has been expunged. It performs a
         * logarithmic number of scans, as a balance between no
         * scanning (fast but retains garbage) and a number of scans
         * proportional to number of elements, that would find all
         * garbage but would cause some insertions to take O(n) time.
         *
         * @param i a position known NOT to hold a stale entry. The
         * scan starts at the element after i.
         *
         * @param n scan control: {@code log2(n)} cells are scanned,
         * unless a stale entry is found, in which case
         * {@code log2(table.length)-1} additional cells are scanned.
         * When called from insertions, this parameter is the number
         * of elements, but when from replaceStaleEntry, it is the
         * table length. (Note: all this could be changed to be either
         * more or less aggressive by weighting n instead of just
         * using straight log n. But this version is simple, fast, and
         * seems to work well.)
         * 扫描控制：{@code log2（n）}单元被扫描，除非找到过时的条目，
         * 在这种情况下{@code log2（table.length）-1}扫描其他单元格。
         *  从插入调用时，此参数是元素的数量，但是当来自replaceStaleEntry时，
         *  它是表的长度。 （注意：所有这些都可以通过加权n而不是仅使用直接记录
         *   来改变或多或少地激进。但是这个版本很简单，快速，并且似乎运行良好。）
         * @return true if any stale entries have been removed.
         */
        //启发式，从前往后遍历log2(n)的entry(每次遇到key为null的元素，将遍历长度重新设置为log2(tab.length))，并判断他们的key，如果key是null，
        // 则调用 expungeStaleEntry 方法，也就是清除 entry。最后返回 true
        //清除无用的 entry，也就是 key 为null 的节点
        //i:从第几个元素开始 n：遍历长度log2(tab.length)
        private boolean cleanSomeSlots(int i, int n) {
            boolean removed = false;
            Entry[] tab = table;
            int len = tab.length;
            do {
                i = nextIndex(i, len);
                Entry e = tab[i];
                if (e != null && e.get() == null) {
                    n = len;
                    removed = true;
                    i = expungeStaleEntry(i);
                }
            } while ( (n >>>= 1) != 0);
            //遍历了 log2(n)的数量的元素后是否有发现被gc的元素
            return removed;
        }

        /**
         * Re-pack and/or re-size the table. First scan the entire
         * table removing stale entries. If this doesn't sufficiently
         * shrink the size of the table, double the table size.
         */
        private void rehash() {
            expungeStaleEntries();

            // Use lower threshold for doubling to avoid hysteresis
            //如果清理过的size还是大于threshold - threshold / 4，则真正扩容 使用较低的阈值加倍以避免滞后
            if (size >= threshold - threshold / 4)
                resize();
        }

        /**
         * Double the capacity of the table.
         */
        //该方法会直接扩容为原来的2倍，并将老数组的数据都移动到 新数组，
        // \size 变量记录了里面有多少数据，最后设置扩容阀值为 2/3。
        private void resize() {
            Entry[] oldTab = table;
            int oldLen = oldTab.length;
            int newLen = oldLen * 2;
            Entry[] newTab = new Entry[newLen];
            int count = 0;

            for (int j = 0; j < oldLen; ++j) {
                Entry e = oldTab[j];
                if (e != null) {
                    ThreadLocal<?> k = e.get();
                    if (k == null) {
                        e.value = null; // Help the GC
                    } else {
                        int h = k.threadLocalHashCode & (newLen - 1);
                        while (newTab[h] != null)
                            h = nextIndex(h, newLen);
                        newTab[h] = e;
                        count++;
                    }
                }
            }

            setThreshold(newLen);
            size = count;
            table = newTab;
        }

        /**
         * Expunge all stale entries in the table.
         */
        //清理所有被gc的entry，其他地方只遍历一部分，只有在rerehash方法时才会遍历全部entry
        private void expungeStaleEntries() {
            Entry[] tab = table;
            int len = tab.length;
            for (int j = 0; j < len; j++) {
                Entry e = tab[j];
                if (e != null && e.get() == null)
                    expungeStaleEntry(j);
            }
        }
    }
}
