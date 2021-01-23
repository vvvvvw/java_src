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
 * Written by Doug Lea, Bill Scherer, and Michael Scott with
 * assistance from members of JCP JSR-166 Expert Group and released to
 * the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.*;
import java.util.Spliterator;
import java.util.Spliterators;

/**
 * A {@linkplain BlockingQueue blocking queue} in which each insert
 * operation must wait for a corresponding remove operation by another
 * thread, and vice versa.  A synchronous queue does not have any
 * internal capacity, not even a capacity of one.  You cannot
 * {@code peek} at a synchronous queue because an element is only
 * present when you try to remove it; you cannot insert an element
 * (using any method) unless another thread is trying to remove it;
 * you cannot iterate as there is nothing to iterate.  The
 * <em>head</em> of the queue is the element that the first queued
 * inserting thread is trying to add to the queue; if there is no such
 * queued thread then no element is available for removal and
 * {@code poll()} will return {@code null}.  For purposes of other
 * {@code Collection} methods (for example {@code contains}), a
 * {@code SynchronousQueue} acts as an empty collection.  This queue
 * does not permit {@code null} elements.
 *
 * <p>Synchronous queues are similar to rendezvous channels used in
 * CSP and Ada. They are well suited for handoff designs, in which an
 * object running in one thread must sync up with an object running
 * in another thread in order to hand it some information, event, or
 * task.
 *
 * <p>This class supports an optional fairness policy for ordering
 * waiting producer and consumer threads.  By default, this ordering
 * is not guaranteed. However, a queue constructed with fairness set
 * to {@code true} grants threads access in FIFO order.
 *
 * <p>This class and its iterator implement all of the
 * <em>optional</em> methods of the {@link Collection} and {@link
 * Iterator} interfaces.
 * SynchronousQueue阻塞队列，每次插入操作必须等待一个协同的移除线程，反之亦然。
 SynchronousQueue同步队列没有容量，可以说，没有一个容量。由于队列中只有在消费线程，
 尝试消费元素的时候，才会出现元素，所以不能进行peek操作；不能用任何方法，
 生产元素，除非有消费者在尝试消费元素，同时由于队列中没有元素，所以不能迭代。
 head是第一个生产线程尝试生产的元素；如果没有这样的生产线程，那么没有元素可利用，
 remove和poll操作将会返回null。SynchronousQueue实际一个空集合类。同时同步队列不允许为null。
 同步队列与CSP和Ada场景下的通道相似，在这种通道中，一个线程同步或生产一个元素，消息，资源，同时
 另一个线程消费这些资源或任务。
 同步队列支持生产者和消费者等待的公平性策略。默认情况下，不能保证生产消费的顺序。
 如果一个同步队列构造为公平性，则可以线程以FIFO访问队列元素
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * @since 1.5
 * @author Doug Lea and Bill Scherer and Michael Scott
 * @param <E> the type of elements held in this collection
 */
/*
SynchronousQueue是一种比较特殊的阻塞队列，不同于之前的阻塞队列，特点为：
1.每次put必须有take的存在，也就是说生产者将一个元素put传递给一个消费者，消费者不存在就put不成功；
2.内部没有容量来维持存放元素，所以size，迭代等一些方法没有意义；
3.使用cas操作，没有使用锁；
4.todo 通过栈(非公平)，队列(公平)2中结构来支持公平\非公平策略。
 */
public class SynchronousQueue<E> extends AbstractQueue<E>
    implements BlockingQueue<E>, java.io.Serializable {
    private static final long serialVersionUID = -3223113410248163686L;

    /*
     * This class implements extensions of the dual stack and dual
     * queue algorithms described in "Nonblocking Concurrent Objects
     * with Condition Synchronization", by W. N. Scherer III and
     * M. L. Scott.  18th Annual Conf. on Distributed Computing,
     * Oct. 2004 (see also
     * http://www.cs.rochester.edu/u/scott/synchronization/pseudocode/duals.html).
     * 同步队列实现拓展了双栈和双队列算法（条件同步的非阻塞并发对象），
     在分布计算年刊中有具体描述，见下面连接
     * The (Lifo) stack is used for non-fair mode, and the (Fifo)
     * queue for fair mode. The performance of the two is generally
     * similar. Fifo usually supports higher throughput under
     * contention but Lifo maintains higher thread locality in common
     * applications.
     *LIFO栈用于非公平模式，FIFO队列用于公平模式。两者的性能大体相同。
     FIFO通常用于有高吞吐量存在竞争的场景，LIFO栈用于
     Lifo maintains higher thread locality in common
     applications..这句不翻译了，保持原味。

     * A dual queue (and similarly stack) is one that at any given
     * time either holds "data" -- items provided by put operations,
     * or "requests" -- slots representing take operations, or is
     * empty. A call to "fulfill" (i.e., a call requesting an item
     * from a queue holding data or vice versa) dequeues a
     * complementary node.  The most interesting feature of these
     * queues is that any operation can figure out which mode the
     * queue is in, and act accordingly without needing locks.
     *双队列是一个在任何时候持有由put操作提供元素的data，slots表示的
     take操作的请求，或为空队列，与栈相似。一个调用fulfill操作（请求队列中
     的持有元素，即进行put操作），将会有一个不足元素出队列，反之亦然，
     意思为一个take操作对一个put操作，一个put操作必须对应一个take操作。
     这种队列最有趣的特点是，任何操作不根据锁，可以判断进队列的模式，
     是非公平的LIFO栈stack还是公平的FIFO队列queue。
     * Both the queue and stack extend abstract class Transferer
     * defining the single method transfer that does a put or a
     * take. These are unified into a single method because in dual
     * data structures, the put and take operations are symmetrical,
     * so nearly all code can be combined. The resulting transfer
     * methods are on the long side, but are easier to follow than
     * they would be if broken up into nearly-duplicated parts.
     *队列和栈继承了Transferer类，Transferer定义简单的方法（转换，转让）
     做put或take操作。因为在双数据结构中，put和take操作是对称的，所以他们
     统一定义在一个方法中，所以几乎所有的代码可以放在一起。 The resulting transfer
     * methods are on the long side, but are easier to follow than
     * they would be if broken up into nearly-duplicated parts.
这段不翻译保持原味。
     * The queue and stack data structures share many conceptual
     * similarities but very few concrete details. For simplicity,
     * they are kept distinct so that they can later evolve
     * separately.
     *队列和栈数据结构有许多概念上相同的属性，但也有一些具体的不同。
     为了简单起见，他们保持着区别，确保later evolve separately。

     * The algorithms here differ from the versions in the above paper
     * in extending them for use in synchronous queues, as well as
     * dealing with cancellation. The main differences include:
     *这个算法与上面论文中的算法有所不同，我们扩展为了论文中的算法用在同步
     队列中，也用于处理cancellation。主要的不同包括：
     *  1. The original algorithms used bit-marked pointers, but
     *     the ones here use mode bits in nodes, leading to a number
     *     of further adaptations.
     *  2. SynchronousQueues must block threads waiting to become
     *     fulfilled.
     *  3. Support for cancellation via timeout and interrupts,
     *     including cleaning out cancelled nodes/threads
     *     from lists to avoid garbage retention and memory depletion.
     *1.原始算法中用了bit标记指针，本同步队列的实现算法中，则在节点中使用bits模式，
     将导致number进一步的调整。
     2.同步队列必须阻塞线程等待变的可填充。
     3.支持通过中断和超时取消等待策略，包括从等待队列中清除取消的节点或线程，
     以避免产生垃圾，和内存泄漏。
     * Blocking is mainly accomplished using LockSupport park/unpark,
     * except that nodes that appear to be the next ones to become
     * fulfilled first spin a bit (on multiprocessors only). On very
     * busy synchronous queues, spinning can dramatically improve
     * throughput. And on less busy ones, the amount of spinning is
     * small enough not to be noticeable.
     *通过LockSupport的park/unpark方法，实现阻塞，除了在多处理器上，
     下一个变得可填充的先自旋的节点或线程。在繁忙的同步队列中，自旋可以显著
     提高吞吐量。在不繁忙时，自旋并不太多的消耗。
     * Cleaning is done in different ways in queues vs stacks.  For
     * queues, we can almost always remove a node immediately in O(1)
     * time (modulo retries for consistency checks) when it is
     * cancelled. But if it may be pinned as the current tail, it must
     * wait until some subsequent cancellation. For stacks, we need a
     * potentially O(n) traversal to be sure that we can remove the
     * node, but this can run concurrently with other threads
     * accessing the stack.
     *在队列和栈中，清除操作有着不同的实现。在队列中，当一个节点或线程取消时，
     我们大多数情况下，可以立即以常量1（一致性检查尝试次数的模）的时间移除一个节点或线程。
     但是如果一直在队列的尾部，则必须等后来的线程节点取消。对于栈，
     我们可能需要时间O(n)遍历已确定那个节点我们可以移除，但是这个可以与
     其他线程并发访问栈。
     * While garbage collection takes care of most node reclamation
     * issues that otherwise complicate nonblocking algorithms, care
     * is taken to "forget" references to data, other nodes, and
     * threads that might be held on to long-term by blocked
     * threads. In cases where setting to null would otherwise
     * conflict with main algorithms, this is done by changing a
     * node's link to now point to the node itself. This doesn't arise
     * much for Stack nodes (because blocked threads do not hang on to
     * old head pointers), but references in Queue nodes must be
     * aggressively forgotten to avoid reachability of everything any
     * node has ever referred to since arrival.
     * 然而垃圾回收器必须关注其他复杂非阻塞算法的节点再生问题，数据，节点的引用
    及线程也在会通过阻塞其他线程，以便长期持有锁。以防此类情况的发生，
    引用将会为设置为null，以免与主要算法冲突，本算法姐姐方法是节点链接指向其自己。
    这样不为引起大量的栈节点（因为阻塞线程，不能停留在head指针上），但是为了
    避免其他的所有节点与以前引用的节点可达，队列节点的引用必须显示忘记索引。
     */


    /**
     * Shared internal API for dual stacks and queues.
     */
    //这个是栈和队列的公共基类，所有的put，take操作最后都是调用这个transfer方法
    abstract static class Transferer<E> {
        /**
         * Performs a put or take.
         * put和take都调用这个函数
         * @param e if non-null, the item to be handed to a consumer;
         *          if null, requests that transfer return an item
         *          offered by producer.
         *          e不为null，put操作，表示生产者将e转交给一个消费者
         *          e为null,take操作,表示消费者获取一个生产者转交的数据
         * @param timed if this operation should timeout
         *              支持超时
         * @param nanos the timeout, in nanoseconds
         * @return if non-null, the item provided or received; if null,
         *         the operation failed due to timeout or interrupt --
         *         the caller can distinguish which of these occurred
         *         by checking Thread.interrupted.
         *         如果非null，则为提供或者接收到的元素；如果为null，这表示
         *         transfer操作由于超时或者中断失败了--调用者可以通过检查Thread.interrupted
         *         来区分到底发生了超时或者中断
         */
        abstract E transfer(E e, boolean timed, long nanos);
    }

    /** The number of CPUs, for spin control */
    //获取运行时环境的处理个数
    static final int NCPUS = Runtime.getRuntime().availableProcessors();

    /**
     * The number of times to spin before blocking in timed waits.
     * The value is empirically derived -- it works well across a
     * variety of processors and OSes. Empirically, the best value
     * seems not to vary with number of CPUs (beyond 2) so is just
     * a constant.
     */
    /*
     在超时等待阻塞前，自旋尝试的次数，这个值是一个，在不同处理器和系统性能上
     良好工作的经验值。经验上来讲，最好的值，不要随着CPUS的个数/2的值变动，
     所以它是一个常量，当处理器个数小于2，则为0，否则为32。
     */
    static final int maxTimedSpins = (NCPUS < 2) ? 0 : 32;

    /**
     * The number of times to spin before blocking in untimed waits.
     * This is greater than timed value because untimed waits spin
     * faster since they don't need to check times on each spin.
     * 在非超时 等待阻塞之前，自旋的次数，最大非超时自旋时间大于最大自旋
     时间，因为由于非超时自旋不需要在每次自旋时，不需要检查时间，所以，
     非超时自旋非常快。
     */
    static final int maxUntimedSpins = maxTimedSpins * 16;

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices.
     * 快速自旋的时间，而不是park的时间，一个粗略的估计值。
     */
    static final long spinForTimeoutThreshold = 1000L;

    /** Dual stack */
    //todo 双栈 非公平策略(LIFO)
    static final class TransferStack<E> extends Transferer<E> {
        /*
         * This extends Scherer-Scott dual stack algorithm, differing,
         * among other ways, by using "covering" nodes rather than
         * bit-marked pointers: Fulfilling operations push on marker
         * nodes (with FULFILLING bit set in mode) to reserve a spot
         * to match a waiting node.
         * 本stack实现的是算法是拓展了Scherer-Scott双栈的算法，所不同的时，用
         * covering节点，而不是bit-marked指针：在bit集合填充模式下，填充操作将会为
         * 匹配一个等待节点保留资源，生产一个标记节点
         */

        /* Modes for SNodes, ORed together in node fields */
        /** Node represents an unfulfilled consumer */
        //REQUEST节点表示一个未填充的消费者
        static final int REQUEST    = 0;
        /** Node represents an unfulfilled producer */
        //DATA节点表示一个未填充的生产者
        static final int DATA       = 1;
        /** Node is fulfilling another unfulfilled DATA or REQUEST */
        //FULFILLING节点表示生产者正在给等待资源的消费者补给资源，或生产者在等待消费者消费资源
        static final int FULFILLING = 2;

        /** Returns true if m has fulfilling bit set. */
        //true:已经有节点匹配.
        static boolean isFulfilling(int m) { return (m & FULFILLING) != 0; }

        /** Node class for TransferStacks. */
        //栈节点
        static final class SNode {
            //节点的后继
            volatile SNode next;        // next node in stack
            // 本节点的匹配节点（如果取消，match设置为自己）
            volatile SNode match;       // the node matched to this
            // 当前等待线程，用于park，unpark
            volatile Thread waiter;     // to control park/unpark
            // put时data，take时null
            Object item;                // data; or null for REQUESTs
            //节点模式
            int mode;
            // Note: item and mode fields don't need to be volatile
            // since they are always written before, and read after,
            // other volatile/atomic operations.
            ////元素item和mode不需要设置为volatile类型，由于他们总是在其他可见/原子操作写之前，读之后
            SNode(Object item) {
                this.item = item;
            }


            //设置节点后继
            boolean casNext(SNode cmp, SNode val) {
                //先使用cmp==next，可以减少无效的compareAndSwapObject调用
                return cmp == next &&
                    UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
            }

            /**
             * Tries to match node s to this node, if so, waking up thread.
             * Fulfillers call tryMatch to identify their waiters.
             * Waiters block until they have been matched.
             *
             * @param s the node to match
             * @return true if successfully matched to s
             */
            //匹配当前节点和s节点。如果匹配成功，唤醒等待线程
            //尝试匹配目标节点与本节点，如果匹配，可以唤醒线程。补给者调用tryMatch方法
            //确定它们的等待线程。等待线程阻塞到它们自己被匹配。如果匹配返回true。
            boolean tryMatch(SNode s) {
                //match不存在，则cas修改为s，唤醒等待线程并返回true
                if (match == null &&
                    UNSAFE.compareAndSwapObject(this, matchOffset, null, s)) {
                    Thread w = waiter;
                    //如果节点有等待的线程，那就置null，unpark
                    if (w != null) {    // waiters need at most one unpark
                        waiter = null;
                        LockSupport.unpark(w);
                    }
                    return true;
                }
                //如果match已经存在或者cas失败，那就直接匹配match跟s
                return match == s;
            }

            /**
             * Tries to cancel a wait by matching node to itself.
             */
            //匹配线程修改成自己
            void tryCancel() {
                UNSAFE.compareAndSwapObject(this, matchOffset, null, this);
            }

            //match是否==自己
            boolean isCancelled() {
                return match == this;
            }

            // Unsafe mechanics
            private static final sun.misc.Unsafe UNSAFE;
            private static final long matchOffset;
            private static final long nextOffset;

            static {
                try {
                    UNSAFE = sun.misc.Unsafe.getUnsafe();
                    Class<?> k = SNode.class;
                    matchOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("match"));
                    nextOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("next"));
                } catch (Exception e) {
                    throw new Error(e);
                }
            }
        }

        //栈头节点（非哨兵节点）
        /** The head (top) of the stack */
        volatile SNode head;
        //CAS操作,判断head旧值是否为h，如果是则设置nh为当前head
        boolean casHead(SNode h, SNode nh) {
            return h == head &&
                UNSAFE.compareAndSwapObject(this, headOffset, h, nh);
        }

        /**
         * Creates or resets fields of a node. Called only from transfer
         * where the node to push on stack is lazily created and
         * reused when possible to help reduce intervals between reads
         * and CASes of head and to avoid surges of garbage when CASes
         * to push nodes fail due to contention.
         * 创建或重新设置节点的fields。在节点入栈懒创建节点或者重用节点（可能需要帮助
         * 减少读和head的CAS操作的时间间隔以避免由于竞争CAS操作节点入栈失败引起的垃圾）时，
         * 此方法会被transfer调用
         */
        static SNode snode(SNode s, Object e, SNode next, int mode) {
            if (s == null) s = new SNode(e);
            s.mode = mode;
            s.next = next;
            return s;
        }

        /**
         * Puts or takes an item.
         */
        /*
        从TransferStack中Snode节点可以看出：节点关联一个等待线程waiter，后继next，
        匹配节点match，节点元素item和模式mode；模式由三种，REQUEST节点表示消费者等待消费资源，
        DATA表示生产者等待生产资源。FULFILLING节点表示生产者正在给等待资源的消费者补给资源，
        或生产者在等待消费者消费资源。当有线程take/put操作时，查看栈头，如果是空队列，
        或栈头节点的模式与要放入的节点模式相同；如果是超时等待，判断时间是否小于0，小于0则取消节点等待；
        如果非超时，则将创建的新节点入栈成功，即放在栈头，自旋等待匹配节点（timed决定超时，不超时）；
        如果匹配返回的是自己，节点取消等待，从栈中移除，并遍历栈移除取消等待的节点；匹配成功，
        两个节点同时出栈，REQUEST模式返回，匹配到的节点元素（DATA），DATA模式返回返回当前节点元素）。
        如果与栈头节点的模式不同且不为FULFILLING，匹配节点，成功者，两个节点同时出栈，REQUEST模式返回，
        匹配到的节点元素（DATA），DATA（put）模式返回返回当前节点元素。如果栈头为FULFILLING，
        找出栈头的匹配节点，栈头与匹配到的节点同时出栈。从分析非公平模式下的TransferStack，
        可以看出一个REQUEST操作必须同时伴随着一个DATA操作，一个DATA操作必须同时伴随着一个REQUEST操作，
        这也是同步队列的命名中含Synchronous原因。这也应了这句话
         */
        //put或take一个元素
        @SuppressWarnings("unchecked")
        E transfer(E e, boolean timed, long nanos) {
            /*
             * Basic algorithm is to loop trying one of three actions:
             * 算法的基本步骤是，循环尝试一下3步
             * 1. If apparently empty or already containing nodes of same
             *    mode, try to push node on stack and wait for a match,
             *    returning it, or null if cancelled.
             * 1.如果队列为空或已经包含相同模式的节点，则尝试节点入栈，等待匹配， 返回该节点，如果取消返回null。
             * 2. If apparently containing node of complementary mode,
             *    try to push a fulfilling node on to stack, match
             *    with corresponding waiting node, pop both from
             *    stack, and return matched item. The matching or
             *    unlinking might not actually be necessary because of
             *    other threads performing action 3:
             * 2. 如果包含一个互补模式的节点（take(REQUEST)->put(DATA)；put(DATA)->take(REQUEST)），
             *    则尝试将一个FULFILLING节点入栈，匹配对应的等待节点，两个节点同时出栈，返回匹配的元素。
             *    由于其他线程执行步骤3，实际匹配和解除链接指针动作不会发生。
             * 3. If top of stack already holds another fulfilling node,
             *    help it out by doing its match and/or pop
             *    operations, and then continue. The code for helping
             *    is essentially the same as for fulfilling, except
             *    that it doesn't return the item.
             * 3. 如果栈顶存在另外一个FULFILLING的节点，则匹配节点，并出栈。这段的代码
             *    与fulfilling相同，除非没有元素返回
             */

            SNode s = null; // constructed/reused as needed
            //根据元素判断节点模式，元素不为null，则为DATA，否则为REQUEST
            int mode = (e == null) ? REQUEST : DATA;

            for (;;) {
                SNode h = head;
                if (h == null || h.mode == mode) {  // empty or same-mode
                    //如果队列为空或已经包含相同模式的节点，则尝试节点入栈，等待匹配
                    if (timed && nanos <= 0) {      // can't wait
                        //如果已经超时
                        if (h != null && h.isCancelled())
                            //如果头节点被取消，出栈，设置栈头为其后继
                            casHead(h, h.next);     // pop cancelled node
                        else
                            //否则返回null
                            return null;
                    } else if (casHead(h, s = snode(s, e, h, mode))) {
                        //如果非超时，则将创建的新节点入栈成功，即放在栈头，自旋等待匹配节点（timed决定超时，不超时）
                        SNode m = awaitFulfill(s, timed, nanos);
                        if (m == s) {               // wait was cancelled
                            //如果返回的是自己，节点取消等待，从栈中移除，并遍历栈移除取消等待的节点
                            clean(s);
                            return null;
                        }
                        if ((h = head) != null && h.next == s)
                            //s节点匹配成功，则设置栈头为s的后继
                            casHead(h, s.next);     // help s's fulfiller
                        //匹配成功，REQUEST模式返回匹配到的节点元素（DATA），DATA模式返回当前节点元素
                        return (E) ((mode == REQUEST) ? m.item : s.item);
                    }
                } else if (!isFulfilling(h.mode)) { // try to fulfill
                    //如果栈头节点模式不为Fulfilling
                    if (h.isCancelled())            // already cancelled
                        //判断是否取消等待，是则出栈
                        casHead(h, h.next);         // pop and retry
                    else if (casHead(h, s=snode(s, e, h, FULFILLING|mode))) {
                        //非取消等待，则是节点入栈
                        for (;;) { // loop until matched or waiters disappear
                            SNode m = s.next;       // m is s's match
                            if (m == null) {        // all waiters are gone
                                //后继节点为null，则出栈
                                casHead(s, null);   // pop fulfill node
                                s = null;           // use new node next time
                                break;              // restart main loop
                            }
                            SNode mn = m.next;
                            if (m.tryMatch(s)) {
                                //尝试匹配s节点
                                //匹配成功两个节点则出栈
                                casHead(s, mn);     // pop both s and m
                                return (E) ((mode == REQUEST) ? m.item : s.item);
                            } else
                                //否则，跳过s的后继节点// lost match
                                s.casNext(m, mn);   // help unlink
                        }
                    }
                } else {
                    //如果栈头节点模式为Fulfilling,找出栈头的匹配节点// help a fulfiller
                    SNode m = h.next;               // m is h's match
                    if (m == null)
                        //如果无后继等待节点，则栈头出栈// waiter is gone
                        casHead(h, null);           // pop fulfilling node
                    else {
                        //h节点为FULFILLING，表示已经和h.next配对，尝试匹配，如果匹配成功，栈头和匹配节点出栈，否则跳过后继节点
                        SNode mn = m.next;
                        if (m.tryMatch(h))          // help match
                            casHead(h, mn);         // pop both h and m
                        else                        // lost match
                            h.casNext(m, mn);       // help unlink
                    }
                }
            }
        }

        /**
         * Spins/blocks until node s is matched by a fulfill operation.
         * 自旋或阻塞，直到节点被一个fulfill操作匹配
         * @param s the waiting node 等待被匹配的节点
         * @param timed true if timed wait 是否超时等待
         * @param nanos timeout value 时间值
         * @return matched node, or s if cancelled  如果匹配返回match的节点，如果取消等待返回s
         */
        SNode awaitFulfill(SNode s, boolean timed, long nanos) {
            /*
             * When a node/thread is about to block, it sets its waiter
             * field and then rechecks state at least one more time
             * before actually parking, thus covering race vs
             * fulfiller noticing that waiter is non-null so should be
             * woken.
             * 当一个节点线程将要阻塞时，在实际park之前，设置等待线程的field，并重新至少检查
             * 自身状态一次，这样可以避免在fulfiller注意到有等待线程非null，可以操作时，掩盖了竞争。
             * When invoked by nodes that appear at the point of call
             * to be at the head of the stack, calls to park are
             * preceded by spins to avoid blocking when producers and
             * consumers are arriving very close in time.  This can
             * happen enough to bother only on multiprocessors.
             * 当awaitFulfill被栈头节点调用时，通过自旋park一段时间，以免在刚要阻塞的时刻，
             * 有生产者或消费者到达。这在多处理机上将会发生。
             * The order of checks for returning out of main loop
             * reflects fact that interrupts have precedence over
             * normal returns, which have precedence over
             * timeouts. (So, on timeout, one last check for match is
             * done before giving up.) Except that calls from untimed
             * SynchronousQueue.{poll/offer} don't check interrupts
             * and don't wait at all, so are trapped in transfer
             * method rather than calling awaitFulfill.
             * 主循环检查返回的顺序将会反应如下事实：在正常返回时，中断是否处理，还是超时处理。
             * （在放弃匹配之前，及最后一次检查，正好超时），除非调用SynchronousQueue的
             * 非超时poll/offer操作，不会检查中断，不等待，那么将调用transfer方法中的其他部分逻辑，
             * 而不是调用awaitFulfill。
             */
            final long deadline = timed ? System.nanoTime() + nanos : 0L;
            Thread w = Thread.currentThread();
            //获取自旋的次数
            int spins = (shouldSpin(s) ?
                         (timed ? maxTimedSpins : maxUntimedSpins) : 0);
            for (;;) {
                if (w.isInterrupted())
                    //如果线程被中断，则取消等待
                    s.tryCancel();
                SNode m = s.match;
                if (m != null)
                    //如果节点的匹配节点不为null，则返回匹配节点
                    return m;
                if (timed) {
                    nanos = deadline - System.nanoTime();
                    if (nanos <= 0L) {
                        //如果超时，则取消等待
                        s.tryCancel();
                        continue;
                    }
                }
                if (spins > 0)
                    //如果自旋次数大于零，且可以自旋，则自旋次数减1
                    spins = shouldSpin(s) ? (spins-1) : 0;
                else if (s.waiter == null)
                    //如果节点S的等待线程为空，则设置当前节点为S节点的等待线程，以便可以park后继节点。
                    s.waiter = w; // establish waiter so can park next iter
                else if (!timed)
                    //非超时等待者，park当前线程
                    LockSupport.park(this);
                else if (nanos > spinForTimeoutThreshold)
                    //如果超时时间大于，最大自旋阈值，则超时park当前线程
                    LockSupport.parkNanos(this, nanos);
            }
        }

        /**
         * Returns true if node s is at head or there is an active
         * fulfiller.
         * 返回true，如果s是头结点或者头结点处于FULFILLING状态
         */
        boolean shouldSpin(SNode s) {
            SNode h = head;
            return (h == s || h == null || isFulfilling(h.mode));
        }

        /**
         * Unlinks s from the stack.
         */
        void clean(SNode s) {
            s.item = null;   // forget item
            s.waiter = null; // forget thread

            /*
             * At worst we may need to traverse entire stack to unlink
             * s. If there are multiple concurrent calls to clean, we
             * might not see s if another thread has already removed
             * it. But we can stop when we see any node known to
             * follow s. We use s.next unless it too is cancelled, in
             * which case we try the node one past. We don't check any
             * further because we don't want to doubly traverse just to
             * find sentinel.
             * 最糟糕的情况是我们需要遍历整个栈，unlink节点s。如果有多个线程同时访问
               clean方法，由于其他线程可能移除s节点，我们也许看不到s节点。但是当发现一个节点的前缀为s
               时我们可以停止操作。我们可以用s节点的后继，除非s节点取消，否则，
               我们可越过s节点。我们不会进一步地检查，因为我们不想仅仅为了发现s节点，遍历两次。
             */

            SNode past = s.next;
            if (past != null && past.isCancelled())
                past = past.next;

            // Absorb cancelled nodes at head
            SNode p;
            while ((p = head) != null && p != past && p.isCancelled())
                //设置栈头节点的后继为第一个非取消等待的节点
                casHead(p, p.next);

            // Unsplice embedded nodes
            //将头节点到past之间所有cancel的节点全部unlink
            while (p != null && p != past) {
                SNode n = p.next;
                if (n != null && n.isCancelled())
                    p.casNext(n, n.next);
                else
                    p = n;
            }
        }

        // Unsafe mechanics
        private static final sun.misc.Unsafe UNSAFE;
        private static final long headOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> k = TransferStack.class;
                headOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("head"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /*
    TransferQueue在执行take/put操作时，首先根据元素是否判断当前节点的模式，
如果元素为null则为REQUEST（take）模式，否则为DATA模式（put）。
然后自旋匹配节点，如果队列头或尾节点没有初始化，则跳出本次自旋，
如果队列为空，或当前节点与队尾模式相同，自旋或阻塞直到节点被fulfilled；
如果队列不为空，且与队头的模式不同，及匹配成功，出队列，如果是REQUEST操作，
返回匹配到节点的元素，如果为DATA操作，返回当前节点元素。
TransferQueue相对于TransferStack来说，操作匹配过程更简单，
TransferStack为非公平策略下的实现LIFO，TransferQueue是公平策略下的实现FIFO。
TransferQueue中的QNODE与TransferStack的SNODE节点有所不同处理后继next，
等待线程，节点元素外，SNODE还有一个对应的模式REQUEST，DATA或FULFILLING，
而QNODE中用一个布尔值isData来表示模式，这个模式的判断主要根据是元素是否为null，
如果为null，则为REQUEST（take）模式，否则为DATA模式（put）。
     */
    /** Dual Queue */
    static final class TransferQueue<E> extends Transferer<E> {
        /*
         * This extends Scherer-Scott dual queue algorithm, differing,
         * among other ways, by using modes within nodes rather than
         * marked pointers. The algorithm is a little simpler than
         * that for stacks because fulfillers do not need explicit
         * nodes, and matching is done by CAS'ing QNode.item field
         * from non-null to null (for put) or vice versa (for take).
         * 本算法实现拓展了Scherer-Scott双队列算法，不同的是用节点模式，
 而不是标记指针来区分节点操作类型。这个算法比栈算法的实现简单，
 因为fulfillers不需要明确指定节点，同时匹配节点用CAS操作QNode.item
 字段即可，put操作从非null到null，反则亦然，take从null到非null。
         */

        /** Node class for TransferQueue. */
        static final class QNode {
            volatile QNode next;          // next node in queue 后继
            volatile Object item;         // CAS'ed to or from null 节点元素
            volatile Thread waiter;       // to control park/unpark 等待线程
            final boolean isData; //是否为DATA模式，QNODE中用一个布尔值isData来表示模式，这个模式的判断主要根据是元素是否为null，如果为null，则为REQUEST（take）模式，否则为DATA模式（put）
            //设置元素和模式
            QNode(Object item, boolean isData) {
                this.item = item;
                this.isData = isData;
            }
            //设置节点的后继
            boolean casNext(QNode cmp, QNode val) {
                return next == cmp &&
                    UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
            }
            //设置节点的元素
            boolean casItem(Object cmp, Object val) {
                return item == cmp &&
                    UNSAFE.compareAndSwapObject(this, itemOffset, cmp, val);
            }

            /**
             * Tries to cancel by CAS'ing ref to this as item.
             */
            //取消节点等待，元素指向自己
            void tryCancel(Object cmp) {
                UNSAFE.compareAndSwapObject(this, itemOffset, cmp, this);
            }
            //是否取消等待
            boolean isCancelled() {
                return item == this;
            }

            /**
             * Returns true if this node is known to be off the queue
             * because its next pointer has been forgotten due to
             * an advanceHead operation.
             * 是否出队列
             */
            boolean isOffList() {
                return next == this;
            }

            // Unsafe mechanics
            private static final sun.misc.Unsafe UNSAFE;
            private static final long itemOffset;
            private static final long nextOffset;

            static {
                try {
                    UNSAFE = sun.misc.Unsafe.getUnsafe();
                    Class<?> k = QNode.class;
                    itemOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("item"));
                    nextOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("next"));
                } catch (Exception e) {
                    throw new Error(e);
                }
            }
        }

        /** Head of queue */
        //队列头节点(哨兵节点)
        transient volatile QNode head;
        /** Tail of queue */
        //队列尾节点(非哨兵节点)
        transient volatile QNode tail;
        /**
         * Reference to a cancelled node that might not yet have been
         * unlinked from queue because it was the last inserted node
         * when it was cancelled.
         * 刚入队列就取消等待，还没有出队列的节点的前驱
         */
        transient volatile QNode cleanMe;

        //构造队列
        TransferQueue() {
            QNode h = new QNode(null, false); // initialize to dummy node.
            head = h;
            tail = h;
        }

        /**
         * Tries to cas nh as new head; if successful, unlink
         * old head's next node to avoid garbage retention.
         * 尝试设置新的队头节点为nh，成功则解除旧队列头节点的next链接并指向该旧队列头节点
         */
        void advanceHead(QNode h, QNode nh) {
            if (h == head &&
                UNSAFE.compareAndSwapObject(this, headOffset, h, nh))
                h.next = h; // forget old next
        }

        /**
         * Tries to cas nt as new tail.
         * 尝试设置队尾
         */
        void advanceTail(QNode t, QNode nt) {
            if (tail == t)
                UNSAFE.compareAndSwapObject(this, tailOffset, t, nt);
        }

        /**
         * Tries to CAS cleanMe slot.
         * 尝试设置cleanMe节点为val
         */
        boolean casCleanMe(QNode cmp, QNode val) {
            return cleanMe == cmp &&
                UNSAFE.compareAndSwapObject(this, cleanMeOffset, cmp, val);
        }

        /**
         * Puts or takes an item.
         * 生产或消费一个元素
         */
        @SuppressWarnings("unchecked")
        E transfer(E e, boolean timed, long nanos) {
            /* Basic algorithm is to loop trying to take either of
             * two actions:
             * 基本算法是循环尝试，执行下面两步中任意一个：
             * 1. If queue apparently empty or holding same-mode nodes,
             *    try to add node to queue of waiters, wait to be
             *    fulfilled (or cancelled) and return matching item.
             *1.如果队列为空，或队列中为相同模式的节点，尝试节点入队列等待，
     直到fulfilled，返回匹配元素，或者由于中断，超时取消等待。
             * 2. If queue apparently contains waiting items, and this
             *    call is of complementary mode, try to fulfill by CAS'ing
             *    item field of waiting node and dequeuing it, and then
             *    returning matching item.
             *2.如果队列中包含节点，transfer方法被一个协同模式的节点调用，
     则尝试补给或填充等待线程节点的元素，并出队列，返回匹配元素。
             * In each case, along the way, check for and try to help
             * advance head and tail on behalf of other stalled/slow
             * threads.
             *在每一种情况，执行的过程中，检查和尝试帮助其他stalled/slow线程移动队列头和尾节点
             * The loop starts off with a null check guarding against
             * seeing uninitialized head or tail values. This never
             * happens in current SynchronousQueue, but could if
             * callers held non-volatile/final ref to the
             * transferer. The check is here anyway because it places
             * null checks at top of loop, which is usually faster
             * than having them implicitly interspersed.
             * 循环开始，首先进行null检查，防止为初始队列头和尾节点。当然这种情况，
     在当前同步队列中，不可能发生，如果调用持有transferer的non-volatile/final引用，
     可能出现这种情况。一般在循环的开始，都要进行null检查，检查过程非常快，不用过多担心
     性能问题。
             */

            QNode s = null; // constructed/reused as needed
            //如果元素e不为null，则为DATA模式，否则为REQUEST模式
            boolean isData = (e != null);

            for (;;) {
                QNode t = tail;
                QNode h = head;
                //如果队列头或尾节点没有初始化，则跳出本次自旋
                if (t == null || h == null)         // saw uninitialized value
                    continue;                       // spin

                if (h == t || t.isData == isData) { // empty or same-mode
                    //如果队列为空，或当前节点与队尾节点模式相同
                    QNode tn = t.next;
                    if (t != tail)                  // inconsistent read
                        //如果t已经不是队尾，非一致性读取，跳出本次自旋
                        continue;
                    if (tn != null) {               // lagging tail
                        //如果t的next不为null，表示已经设置新的队尾，跳出本次自旋
                        advanceTail(t, tn);
                        continue;
                    }
                    if (timed && nanos <= 0)        // can't wait
                        //如果超时，且超时时间小于0，则返回null
                        return null;
                    if (s == null)
                        //根据元素和模式构造节点
                        s = new QNode(e, isData);
                    if (!t.casNext(null, s))        // failed to link in
                        //新节点入队列
                        continue;
                    //设置队尾为当前节点
                    advanceTail(t, s);              // swing tail and wait
                    //自旋或阻塞直到节点被fulfilled
                    Object x = awaitFulfill(s, e, timed, nanos);
                    if (x == s) {                   // wait was cancelled
                        //如果s指向自己，s出队列，并清除队列中取消等待的线程节点
                        clean(t, s);
                        return null;
                    }

                    if (!s.isOffList()) {           // not already unlinked
                        //如果s节点已经不再队列中，移除
                        advanceHead(t, s);          // unlink if head
                        if (x != null)              // and forget fields
                            s.item = s;
                        s.waiter = null;
                    }
                    //如果自旋等待匹配的节点元素不为null，则返回x，否则返回e
                    return (x != null) ? (E)x : e;

                } else {                            // complementary-mode
                    //如果队列不为空，且与队头的模式不同，及匹配成功
                    QNode m = h.next;               // node to fulfill
                    if (t != tail || m == null || h != head)
                        //如果h不为当前队头，则返回，即读取不一致
                        continue;                   // inconsistent read

                    Object x = m.item;
                    if (isData == (x != null) ||    // m already fulfilled
                        x == m ||                   // m cancelled
                        !m.casItem(x, e)) {         // lost CAS
                        //如果队头后继，取消等待，则出队列
                        advanceHead(h, m);          // dequeue and retry
                        continue;
                    }
                    //否则匹配成功
                    advanceHead(h, m);              // successfully fulfilled
                    //unpark等待线程
                    LockSupport.unpark(m.waiter);
                    //如果匹配节点元素不为null，则返回x，否则返回e，
                    //即take操作，返回等待put线程节点元素；put操作，返回put元素
                    return (x != null) ? (E)x : e;
                }
            }
        }

        /**
         * Spins/blocks until node s is fulfilled.
         * 自旋或阻塞直到节点被fulfilled
         * @param s the waiting node 等待节点
         * @param e the comparison value for checking match 检查匹配的比较元素
         * @param timed true if timed wait 是否超时等待
         * @param nanos timeout value 超时等待时间
         * @return matched item, or s if cancelled 成功返回匹配元素，取消返回s
         */
        Object awaitFulfill(QNode s, E e, boolean timed, long nanos) {
            /* Same idea as TransferStack.awaitFulfill  这里与栈中的实现思路是一样的*/
            //获取超时的当前时间，当前线程，自旋数
            final long deadline = timed ? System.nanoTime() + nanos : 0L;
            Thread w = Thread.currentThread();
            int spins = ((head.next == s) ?
                         (timed ? maxTimedSpins : maxUntimedSpins) : 0);
            for (;;) {
                if (w.isInterrupted())
                    //如果中断，则取消等待
                    s.tryCancel(e);
                Object x = s.item;
                if (x != e)
                    //如果s的节点的元素不相等，则返回x,即指向s节点(表示当前节点被取消)，等待clean
                    return x;
                if (timed) {
                    nanos = deadline - System.nanoTime();
                    if (nanos <= 0L) {
                        //如果超时，则取消等待
                        s.tryCancel(e);
                        continue;
                    }
                }
                if (spins > 0)
                    //自旋数减一
                    --spins;
                else if (s.waiter == null)
                    //如果是节点的等待线程为空，则设置为当前线程
                    s.waiter = w;
                else if (!timed)
                    //非超时，则park
                    LockSupport.park(this);
                else if (nanos > spinForTimeoutThreshold)
                    //超时时间大于自旋时间，则超时park
                    LockSupport.parkNanos(this, nanos);
            }
        }

        /**
         * Gets rid of cancelled node s with original predecessor pred.
         * @params pred s的前驱节点
         * @params s  取消等待的线程节点
         * 移除队列中取消等待的线程节点
         */
        void clean(QNode pred, QNode s) {
            s.waiter = null; // forget thread
            /*
             * At any given time, exactly one node on list cannot be
             * deleted -- the last inserted node. To accommodate this,
             * if we cannot delete s, we save its predecessor as
             * "cleanMe", deleting the previously saved version
             * first. At least one of node s or the node previously
             * saved can always be deleted, so this always terminates.
             * 在任何时候，当队列中只有一个节点(最后一个节点)时，该节点不能被删除。
     为了应对这种情况，如果我们不能删除最后入队列的节点，我们可以用cleanMe记录它的前驱，
     删除cleanMe后继节点。s节点和cleanMe后继节点至少删除一个，则循环一定会终止。
             */
            while (pred.next == s) { // Return early if already unlinked
                //如果s为队尾节点，且前驱为旧队尾
                QNode h = head;
                QNode hn = h.next;   // Absorb cancelled first node as head
                if (hn != null && hn.isCancelled()) {
                    //如果队头不为空，且取消等待，设置后继为新的队头元素
                    advanceHead(h, hn);
                    continue;
                }
                QNode t = tail;      // Ensure consistent read for tail
                if (t == h)
                    //空队列，则返回
                    return;
                QNode tn = t.next;
                if (t != tail)
                    //如果队尾有变化，跳出循环
                    continue;
                if (tn != null) {
                    //如果队尾后继不为null，则设置新的队尾
                    advanceTail(t, tn);
                    continue;
                }
                if (s != t) {        // If not tail, try to unsplice
                    //s节点不是最后一个节点
                    QNode sn = s.next;
                    if (sn == s || pred.casNext(s, sn))
                        //s节点指向自己(s已经cancel)，或者pred.casNext成功则返回
                        return;
                }
                QNode dp = cleanMe;
                if (dp != null) {    // Try unlinking previous cancelled node
                    //移除前一个取消等待但是推迟清理的节点
                    QNode d = dp.next;
                    QNode dn;
                    if (d == null ||               // d is gone or dp.next为null
                        d == dp ||                 // d is off list or dp已经被移除
                        !d.isCancelled() ||        // d not cancelled or d没有被取消
                        (d != t &&                 // d not tail and d不是尾节点且d.next不为null且d没有被移除且dp.next被设置为dp.next.next
                         (dn = d.next) != null &&  //   has successor
                         dn != d &&                //   that is on list
                         dp.casNext(d, dn)))       // d unspliced
                        //cleanMe节点设置为null
                        casCleanMe(dp, null);
                    if (dp == pred)
                        //如果dp节点就是pred节点（在下面的判断中设置后再次进入循环）
                        return;      // s is already saved node
                } else if (casCleanMe(null, pred))
                    //如果之前的cleanMe节点为null，推迟清理s
                    return;          // Postpone cleaning s
            }
        }

        private static final sun.misc.Unsafe UNSAFE;
        private static final long headOffset;
        private static final long tailOffset;
        private static final long cleanMeOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> k = TransferQueue.class;
                headOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("head"));
                tailOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("tail"));
                cleanMeOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("cleanMe"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /**
     * The transferer. Set only in constructor, but cannot be declared
     * as final without further complicating serialization.  Since
     * this is accessed only at most once per public method, there
     * isn't a noticeable performance penalty for using volatile
     * instead of final here.
     * transferer在构造函数中初始化，没有进一步的复杂序列化的情况下，不需要
     声明为final。由于transferer至多在public方法中用一次，所以volatile取代final不会有
     太多的性能代价。
     */
    private transient volatile Transferer<E> transferer;

    /**
     * Creates a {@code SynchronousQueue} with nonfair access policy.
     */
    public SynchronousQueue() {
        //默认为非公平，栈
        this(false);
    }

    /**
     * Creates a {@code SynchronousQueue} with the specified fairness policy.
     *
     * @param fair if true, waiting threads contend in FIFO order for
     *        access; otherwise the order is unspecified.
     */
    public SynchronousQueue(boolean fair) {
        transferer = fair ? new TransferQueue<E>() : new TransferStack<E>();
    }

    /**
     * Adds the specified element to this queue, waiting if necessary for
     * another thread to receive it.
     *
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        if (transferer.transfer(e, false, 0) == null) {
            //返回为null，则put失败，中断当前线程
            Thread.interrupted();
            throw new InterruptedException();
        }
    }

    /**
     * Inserts the specified element into this queue, waiting if necessary
     * up to the specified wait time for another thread to receive it.
     *
     * @return {@code true} if successful, or {@code false} if the
     *         specified waiting time elapses before a consumer appears
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
        throws InterruptedException {
        if (e == null) throw new NullPointerException();
        if (transferer.transfer(e, true, unit.toNanos(timeout)) != null)
            return true;
        if (!Thread.interrupted())
            return false;
        throw new InterruptedException();
    }

    /**
     * Inserts the specified element into this queue, if another thread is
     * waiting to receive it.
     *
     * @param e the element to add
     * @return {@code true} if the element was added to this queue, else
     *         {@code false}
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        return transferer.transfer(e, true, 0) != null;
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * for another thread to insert it.
     *
     * @return the head of this queue
     * @throws InterruptedException {@inheritDoc}
     */
    public E take() throws InterruptedException {
        E e = transferer.transfer(null, false, 0);
        if (e != null)
            return e;
        Thread.interrupted();
        throw new InterruptedException();
    }

    /**
     * Retrieves and removes the head of this queue, waiting
     * if necessary up to the specified wait time, for another thread
     * to insert it.
     *
     * @return the head of this queue, or {@code null} if the
     *         specified waiting time elapses before an element is present
     * @throws InterruptedException {@inheritDoc}
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        E e = transferer.transfer(null, true, unit.toNanos(timeout));
        if (e != null || !Thread.interrupted())
            return e;
        throw new InterruptedException();
    }

    /**
     * Retrieves and removes the head of this queue, if another thread
     * is currently making an element available.
     *
     * @return the head of this queue, or {@code null} if no
     *         element is available
     */
    public E poll() {
        return transferer.transfer(null, true, 0);
    }

    /**
     * Always returns {@code true}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return {@code true}
     */
    public boolean isEmpty() {
        return true;
    }

    /**
     * Always returns zero.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return zero
     */
    public int size() {
        return 0;
    }

    /**
     * Always returns zero.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return zero
     */
    public int remainingCapacity() {
        return 0;
    }

    /**
     * Does nothing.
     * A {@code SynchronousQueue} has no internal capacity.
     */
    public void clear() {
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param o the element
     * @return {@code false}
     */
    public boolean contains(Object o) {
        return false;
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param o the element to remove
     * @return {@code false}
     */
    public boolean remove(Object o) {
        return false;
    }

    /**
     * Returns {@code false} unless the given collection is empty.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false} unless given collection is empty
     */
    public boolean containsAll(Collection<?> c) {
        return c.isEmpty();
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false}
     */
    public boolean removeAll(Collection<?> c) {
        return false;
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false}
     */
    public boolean retainAll(Collection<?> c) {
        return false;
    }

    /**
     * Always returns {@code null}.
     * A {@code SynchronousQueue} does not return elements
     * unless actively waited on.
     *
     * @return {@code null}
     */
    public E peek() {
        return null;
    }

    /**
     * Returns an empty iterator in which {@code hasNext} always returns
     * {@code false}.
     *
     * @return an empty iterator
     */
    public Iterator<E> iterator() {
        return Collections.emptyIterator();
    }

    /**
     * Returns an empty spliterator in which calls to
     * {@link java.util.Spliterator#trySplit()} always return {@code null}.
     *
     * @return an empty spliterator
     * @since 1.8
     */
    public Spliterator<E> spliterator() {
        return Spliterators.emptySpliterator();
    }

    /**
     * Returns a zero-length array.
     * @return a zero-length array
     */
    public Object[] toArray() {
        return new Object[0];
    }

    /**
     * Sets the zeroeth element of the specified array to {@code null}
     * (if the array has non-zero length) and returns it.
     *
     * @param a the array
     * @return the specified array
     * @throws NullPointerException if the specified array is null
     */
    public <T> T[] toArray(T[] a) {
        if (a.length > 0)
            a[0] = null;
        return a;
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
        int n = 0;
        for (E e; (e = poll()) != null;) {
            c.add(e);
            ++n;
        }
        return n;
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
        int n = 0;
        for (E e; n < maxElements && (e = poll()) != null;) {
            c.add(e);
            ++n;
        }
        return n;
    }

    /*
     * To cope with serialization strategy in the 1.5 version of
     * SynchronousQueue, we declare some unused classes and fields
     * that exist solely to enable serializability across versions.
     * These fields are never used, so are initialized only if this
     * object is ever serialized or deserialized.
     */

    //序列化与反序列的作用主要是判断同步队列到底是公平的，还是非公平的
    @SuppressWarnings("serial")
    static class WaitQueue implements java.io.Serializable { }
    static class LifoWaitQueue extends WaitQueue {
        private static final long serialVersionUID = -3633113410248163686L;
    }
    static class FifoWaitQueue extends WaitQueue {
        private static final long serialVersionUID = -3623113410248163686L;
    }
    private ReentrantLock qlock;
    private WaitQueue waitingProducers;
    private WaitQueue waitingConsumers;

    /**
     * Saves this queue to a stream (that is, serializes it).
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws java.io.IOException {
        boolean fair = transferer instanceof TransferQueue;
        if (fair) {
            qlock = new ReentrantLock(true);
            waitingProducers = new FifoWaitQueue();
            waitingConsumers = new FifoWaitQueue();
        }
        else {
            qlock = new ReentrantLock();
            waitingProducers = new LifoWaitQueue();
            waitingConsumers = new LifoWaitQueue();
        }
        s.defaultWriteObject();
    }

    /**
     * Reconstitutes this queue from a stream (that is, deserializes it).
     * @param s the stream
     * @throws ClassNotFoundException if the class of a serialized object
     *         could not be found
     * @throws java.io.IOException if an I/O error occurs
     */
    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        if (waitingProducers instanceof FifoWaitQueue)
            transferer = new TransferQueue<E>();
        else
            transferer = new TransferStack<E>();
    }

    // Unsafe mechanics
    static long objectFieldOffset(sun.misc.Unsafe UNSAFE,
                                  String field, Class<?> klazz) {
        try {
            return UNSAFE.objectFieldOffset(klazz.getDeclaredField(field));
        } catch (NoSuchFieldException e) {
            // Convert Exception to corresponding Error
            NoSuchFieldError error = new NoSuchFieldError(field);
            error.initCause(e);
            throw error;
        }
    }

}
