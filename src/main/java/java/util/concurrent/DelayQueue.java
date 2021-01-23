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

package java.util.concurrent;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.*;

/**
 * An unbounded {@linkplain BlockingQueue blocking queue} of
 * {@code Delayed} elements, in which an element can only be taken
 * when its delay has expired.  The <em>head</em> of the queue is that
 * {@code Delayed} element whose delay expired furthest in the
 * past.  If no delay has expired there is no head and {@code poll}
 * will return {@code null}. Expiration occurs when an element's
 * {@code getDelay(TimeUnit.NANOSECONDS)} method returns a value less
 * than or equal to zero.  Even though unexpired elements cannot be
 * removed using {@code take} or {@code poll}, they are otherwise
 * treated as normal elements. For example, the {@code size} method
 * returns the count of both expired and unexpired elements.
 * This queue does not permit null elements.
 *
 * <p>This class and its iterator implement all of the
 * <em>optional</em> methods of the {@link Collection} and {@link
 * Iterator} interfaces.  The Iterator provided in method {@link
 * #iterator()} is <em>not</em> guaranteed to traverse the elements of
 * the DelayQueue in any particular order.
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * @since 1.5
 * @author Doug Lea
 * @param <E> the type of elements held in this collection
 */
/*
DelayQueue 其实就是在每次往优先级队列中添加元素，然后以元素的 delay（过期值）作为排序的因素，
以此来达到先过期的元素会拍在队首，每次从队列里取出来都是最先要过期的元素。
 */
public class DelayQueue<E extends Delayed> extends AbstractQueue<E>
    implements BlockingQueue<E> {

    /** 重入锁，实现线程安全 */
    private final transient ReentrantLock lock = new ReentrantLock();
    /** 使用优先队列实现 */
    private final PriorityQueue<E> q = new PriorityQueue<E>();

    /**
     * Thread designated to wait for the element at the head of
     * the queue.  This variant of the Leader-Follower pattern
     * (http://www.cs.wustl.edu/~schmidt/POSA/POSA2/) serves to
     * minimize unnecessary timed waiting.  When a thread becomes
     * the leader, it waits only for the next delay to elapse, but
     * other threads await indefinitely.  The leader thread must
     * signal some other thread before returning from take() or
     * poll(...), unless some other thread becomes leader in the
     * interim.  Whenever the head of the queue is replaced with
     * an element with an earlier expiration time, the leader
     * field is invalidated by being reset to null, and some
     * waiting thread, but not necessarily the current leader, is
     * signalled.  So waiting threads must be prepared to acquire
     * and lose leadership while waiting.
     */
    /**
     * 如果有多个线程在等待对象到期，只有一个线程会被设置为leader线程，这个leader线程
     * 会根据最近到期的元素来设置等待时间，其他线程都是永久等待。leader线程等待超时后会去取元素，
     * 然后唤醒其他等待线程。

     关键点在于，只有一个线程会超时等待，其他线程永久等待（超时等待要维护计时器，开销肯定相对较大），
     这样就减少了开销。
     */
    /** Leader/Followers模式 */
    //最先等待 优先队列中元素的线程
    private Thread leader = null;

    /**
     * Condition signalled when a newer element becomes available
     * at the head of the queue or a new thread may need to
     * become leader.
     */
    /** 条件对象，当新元素到达，或新线程可能需要成为leader时被通知 */
    private final Condition available = lock.newCondition();

    /**
     * Creates a new {@code DelayQueue} that is initially empty.
     */
    /**
     * 默认构造，得到空的延迟队列
     */
    public DelayQueue() {}

    /**
     * Creates a {@code DelayQueue} initially containing the elements of the
     * given collection of {@link Delayed} instances.
     *
     * @param c the collection of elements to initially contain
     * @throws NullPointerException if the specified collection or any
     *         of its elements are null
     */
    /**
     * 构造延迟队列，初始包含c中的元素
     *
     * @param c 初始包含的元素集合
     * @throws NullPointerException 当集合或集合任一元素为空时抛出空指针错误
     */
    public DelayQueue(Collection<? extends E> c) {
        this.addAll(c);
    }

    /**
     * Inserts the specified element into this delay queue.
     *
     * @param e the element to add
     * @return {@code true} (as specified by {@link Collection#add})
     * @throws NullPointerException if the specified element is null
     */
    /**
     * 向延迟队列插入元素
     *
     * @param e 要插入的元素
     * @return true
     * @throws NullPointerException 元素为空，抛出空指针错误
     */
    public boolean add(E e) {
        // 直接调用offer并返回
        return offer(e);
    }

    /**
     * Inserts the specified element into this delay queue.
     *
     * @param e the element to add
     * @return {@code true}
     * @throws NullPointerException if the specified element is null
     */
    /**
     * 向延迟队列插入元素
     *
     * @param e 要插入的元素
     * @return true
     * @throws NullPointerException 元素为空，抛出空指针错误
     */
    public boolean offer(E e) {
        final ReentrantLock lock = this.lock;
        // 获得锁
        lock.lock();
        try {
            // 向优先队列插入元素
            q.offer(e);
            // //如果新插入的对象就被排在队首了，那么leader线程的等待时间就不正确了，
            // 需要将leader线程唤醒，则置空leader，并通知条件对象，需要结合take方法看
            if (q.peek() == e) {
                leader = null;
                available.signal();
            }
            return true;
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into this delay queue. As the queue is
     * unbounded this method will never block.
     *
     * @param e the element to add
     * @throws NullPointerException {@inheritDoc}
     */
    /**
     * 向延迟队列插入元素. 因为队列是无界的，所以不会阻塞。
     *
     * @param e 要插入的元素
     * @throws NullPointerException 元素为空，抛出空指针错误
     */
    public void put(E e) {
        offer(e);
    }

    /**
     * Inserts the specified element into this delay queue. As the queue is
     * unbounded this method will never block.
     *
     * @param e the element to add
     * @param timeout This parameter is ignored as the method never blocks
     * @param unit This parameter is ignored as the method never blocks
     * @return {@code true}
     * @throws NullPointerException {@inheritDoc}
     */
    /**
     * 向延迟队列插入元素. 因为队列是无界的，所以不会阻塞，因此，直接调用offer方法并返回
     *
     * @param e 要插入的元素
     * @param timeout 不会阻塞，忽略
     * @param unit 不会阻塞，忽略
     * @return true
     * @throws NullPointerException 元素为空，抛出空指针错误
     */
    public boolean offer(E e, long timeout, TimeUnit unit) {
        // 直接调用offer方法并返回
        return offer(e);
    }

    /**
     * Retrieves and removes the head of this queue, or returns {@code null}
     * if this queue has no elements with an expired delay.
     *
     * @return the head of this queue, or {@code null} if this
     *         queue has no elements with an expired delay
     */
    /**
     * 获取并移除队首的元素, 或者返回null（如果队列不包含到达延迟时间的元素）
     *
     * @return 队首的元素, 或者null（如果队列不包含到达延迟时间的元素）
     */
    public E poll() {
        final ReentrantLock lock = this.lock;
        // 获得锁
        lock.lock();
        try {
            // 获取优先队列队首元素
            E first = q.peek();
            // 若优先队列队首元素为空，或者还没达到延迟时间，返回null
            if (first == null || first.getDelay(NANOSECONDS) > 0)
                return null;
            else
                // 否则，返回并移除队首元素
                return q.poll();
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element with an expired delay is available on this queue.
     *
     * @return the head of this queue
     * @throws InterruptedException {@inheritDoc}
     */
    /**
     * 获取并移除队首元素，该方法将阻塞，直到队列中包含达到延迟时间的元素
     *
     * @return 队首元素
     * @throws InterruptedException 阻塞时被打断，抛出打断异常
     */
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        // 获得锁，该锁可被打断
        lock.lockInterruptibly();
        try {
            // 循环处理
            for (;;) {
                // 获取队首元素
                E first = q.peek();
                if (first == null)
                    // 若元素为空，等待条件，在offer方法中会调用条件对象的通知方法
                    // 并重新进入循环
                    available.await();
                else {
                    // 获取延迟时间
                    long delay = first.getDelay(NANOSECONDS);
                    // 若达到延迟时间，返回并移除队首元素
                    if (delay <= 0)
                        return q.poll();
                    // 否则，需要进入等待
                    // 在等待时，不持有引用
                    first = null; // don't retain ref while waiting
                    // 若leader不为空，等待条件
                    //说明已经有线程在等待数据了
                    if (leader != null)
                        available.await();
                    else {
                        // 否则，设置leader为当前线程，并超时等待延迟时间
                        Thread thisThread = Thread.currentThread();
                        leader = thisThread;
                        try {
                            available.awaitNanos(delay);
                        } finally {
                            //如果leader线程还是自己（可能在offer方法中被修改）
                            if (leader == thisThread)
                                //让出leader位置
                                leader = null;
                        }
                    }
                }
            }
        } finally {
            // 通知其他线程条件得到满足
            if (leader == null && q.peek() != null)
                available.signal();
            // 释放锁
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element with an expired delay is available on this queue,
     * or the specified wait time expires.
     *
     * @return the head of this queue, or {@code null} if the
     *         specified waiting time elapses before an element with
     *         an expired delay becomes available
     * @throws InterruptedException {@inheritDoc}
     */
    /**
     * 获取并移除队首元素，该方法将阻塞，直到队列中包含达到延迟时间的元素或超时
     *
     * @return 队首元素，或者null
     * @throws InterruptedException 阻塞等待时被打断，抛出打断异常*/
    //超时可中断方法
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        //全局可中断锁
        lock.lockInterruptibly();
        try {
            for (;;) {
                //查找队首元素
                E first = q.peek();
                //如果队列为空，则根据传入的超时时间设置线程等待时间
                if (first == null) {
                    if (nanos <= 0)
                        return null;
                    else
                        nanos = available.awaitNanos(nanos);
                } else {
                    //获取队首元素的等待时间
                    long delay = first.getDelay(NANOSECONDS);
                    if (delay <= 0)
                        return q.poll();
                    if (nanos <= 0)
                        return null;
                    first = null; // don't retain ref while waiting
                    //如果leader的等待时间比当前线程的等待时间长，当前线程设置等待时间并等待
                    if (nanos < delay || leader != null)
                        nanos = available.awaitNanos(nanos);
                    else {
                        Thread thisThread = Thread.currentThread();
                        leader = thisThread;
                        try {
                            long timeLeft = available.awaitNanos(delay);
                            nanos -= delay - timeLeft;
                        } finally {
                            if (leader == thisThread)
                                leader = null;
                        }
                    }
                }
            }
        } finally {
            if (leader == null && q.peek() != null)
                available.signal();
            lock.unlock();
        }
    }

    /**
     * Retrieves, but does not remove, the head of this queue, or
     * returns {@code null} if this queue is empty.  Unlike
     * {@code poll}, if no expired elements are available in the queue,
     * this method returns the element that will expire next,
     * if one exists.
     *
     * @return the head of this queue, or {@code null} if this
     *         queue is empty
     */
    /**
     * 获取但不移除队首元素，或返回null（如果队列为空）。和poll方法不同，
     * 若队列不为空，该方法换回队首元素，不论是否达到延迟时间
     *
     * @return 队首元素，或null（如果队列为空）
     */
    public E peek() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return q.peek();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取队列大小（包括未达到延迟时间的元素）
     *
     * @return 队列大小
     */
    public int size() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return q.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns first element only if it is expired.
     * Used only by drainTo.  Call only when holding lock.
     */
    private E peekExpired() {
        // assert lock.isHeldByCurrentThread();
        E first = q.peek();
        return (first == null || first.getDelay(NANOSECONDS) > 0) ?
            null : first;
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int n = 0;
            for (E e; (e = peekExpired()) != null;) {
                c.add(e);       // In this order, in case add() throws.
                q.poll();
                ++n;
            }
            return n;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c, int maxElements) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        if (maxElements <= 0)
            return 0;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int n = 0;
            for (E e; n < maxElements && (e = peekExpired()) != null;) {
                c.add(e);       // In this order, in case add() throws.
                q.poll();
                ++n;
            }
            return n;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically removes all of the elements from this delay queue.
     * The queue will be empty after this call returns.
     * Elements with an unexpired delay are not waited for; they are
     * simply discarded from the queue.
     */
    public void clear() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            q.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Always returns {@code Integer.MAX_VALUE} because
     * a {@code DelayQueue} is not capacity constrained.
     *
     * @return {@code Integer.MAX_VALUE}
     */
    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    /**
     * Returns an array containing all of the elements in this queue.
     * The returned array elements are in no particular order.
     *
     * <p>The returned array will be "safe" in that no references to it are
     * maintained by this queue.  (In other words, this method must allocate
     * a new array).  The caller is thus free to modify the returned array.
     *
     * <p>This method acts as bridge between array-based and collection-based
     * APIs.
     *
     * @return an array containing all of the elements in this queue
     */
    public Object[] toArray() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return q.toArray();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an array containing all of the elements in this queue; the
     * runtime type of the returned array is that of the specified array.
     * The returned array elements are in no particular order.
     * If the queue fits in the specified array, it is returned therein.
     * Otherwise, a new array is allocated with the runtime type of the
     * specified array and the size of this queue.
     *
     * <p>If this queue fits in the specified array with room to spare
     * (i.e., the array has more elements than this queue), the element in
     * the array immediately following the end of the queue is set to
     * {@code null}.
     *
     * <p>Like the {@link #toArray()} method, this method acts as bridge between
     * array-based and collection-based APIs.  Further, this method allows
     * precise control over the runtime type of the output array, and may,
     * under certain circumstances, be used to save allocation costs.
     *
     * <p>The following code can be used to dump a delay queue into a newly
     * allocated array of {@code Delayed}:
     *
     * <pre> {@code Delayed[] a = q.toArray(new Delayed[0]);}</pre>
     *
     * Note that {@code toArray(new Object[0])} is identical in function to
     * {@code toArray()}.
     *
     * @param a the array into which the elements of the queue are to
     *          be stored, if it is big enough; otherwise, a new array of the
     *          same runtime type is allocated for this purpose
     * @return an array containing all of the elements in this queue
     * @throws ArrayStoreException if the runtime type of the specified array
     *         is not a supertype of the runtime type of every element in
     *         this queue
     * @throws NullPointerException if the specified array is null
     */
    public <T> T[] toArray(T[] a) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return q.toArray(a);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes a single instance of the specified element from this
     * queue, if it is present, whether or not it has expired.
     */
    public boolean remove(Object o) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return q.remove(o);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Identity-based version for use in Itr.remove
     */
    void removeEQ(Object o) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            //遍历底层的PriorityQueue，删除对应的元素（不一定真的能找到这个元素）
            for (Iterator<E> it = q.iterator(); it.hasNext(); ) {
                if (o == it.next()) {
                    it.remove();
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an iterator over all the elements (both expired and
     * unexpired) in this queue. The iterator does not return the
     * elements in any particular order.
     *
     * <p>The returned iterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * @return an iterator over the elements in this queue
     */
    public Iterator<E> iterator() {
        return new Itr(toArray());
    }

    /**
     * Snapshot iterator that works off copy of underlying q array.
     */
    /*
    迭代器是弱一致的，初始化迭代器时，会创建底层PriorityQueue中所有元素的一个拷贝，
    遍历操作会在这个拷贝上进行（弱一致，原PriorityQueue的修改无法在迭代器中体现）
    用迭代器删除元素时，会记下试图删除的元素，然后去原PriorityQueue里寻找，
    如果找到相同的元素，则将其删除（不一定真的有删除动作，因为原PriorityQueue可能
    已经将这个元素出队了）
    由于迭代器操作的实际上是PriorityQueue的一个快照，所以无论如何不会抛出
    ConcurrentModificationException
    迭代器的遍历次序没有保证
     */
    private class Itr implements Iterator<E> {
        final Object[] array; // Array of all elements
        int cursor;           // index of next element to return
        int lastRet;          // index of last element, or -1 if no such

        Itr(Object[] array) {
            lastRet = -1;
            //将PriorityQueue中的所有元素复制一份
            this.array = array;
        }

        public boolean hasNext() {
            return cursor < array.length;
        }

        @SuppressWarnings("unchecked")
        //遍历数组
        public E next() {
            if (cursor >= array.length)
                throw new NoSuchElementException();
            lastRet = cursor;
            return (E)array[cursor++];
        }

        public void remove() {
            if (lastRet < 0)
                throw new IllegalStateException();
            removeEQ(array[lastRet]);
            lastRet = -1;
        }
    }

}
