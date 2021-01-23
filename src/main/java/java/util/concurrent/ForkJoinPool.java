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

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.security.AccessControlContext;
import java.security.ProtectionDomain;
import java.security.Permissions;

/**
 * An {@link ExecutorService} for running {@link ForkJoinTask}s.
 * A {@code ForkJoinPool} provides the entry point for submissions
 * from non-{@code ForkJoinTask} clients, as well as management and
 * monitoring operations.
 *
 * <p>A {@code ForkJoinPool} differs from other kinds of {@link
 * ExecutorService} mainly by virtue of employing
 * <em>work-stealing</em>: all threads in the pool attempt to find and
 * execute tasks submitted to the pool and/or created by other active
 * tasks (eventually blocking waiting for work if none exist). This
 * enables efficient processing when most tasks spawn other subtasks
 * (as do most {@code ForkJoinTask}s), as well as when many small
 * tasks are submitted to the pool from external clients.  Especially
 * when setting <em>asyncMode</em> to true in constructors, {@code
 * ForkJoinPool}s may also be appropriate for use with event-style
 * tasks that are never joined.
 *
 * <p>A static {@link #commonPool()} is available and appropriate for
 * most applications. The common pool is used by any ForkJoinTask that
 * is not explicitly submitted to a specified pool. Using the common
 * pool normally reduces resource usage (its threads are slowly
 * reclaimed during periods of non-use, and reinstated upon subsequent
 * use).
 *
 * <p>For applications that require separate or custom pools, a {@code
 * ForkJoinPool} may be constructed with a given target parallelism
 * level; by default, equal to the number of available processors.
 * The pool attempts to maintain enough active (or available) threads
 * by dynamically adding, suspending, or resuming internal worker
 * threads, even if some tasks are stalled waiting to join others.
 * However, no such adjustments are guaranteed in the face of blocked
 * I/O or other unmanaged synchronization. The nested {@link
 * ManagedBlocker} interface enables extension of the kinds of
 * synchronization accommodated.
 *
 * <p>In addition to execution and lifecycle control methods, this
 * class provides status check methods (for example
 * {@link #getStealCount}) that are intended to aid in developing,
 * tuning, and monitoring fork/join applications. Also, method
 * {@link #toString} returns indications of pool state in a
 * convenient form for informal monitoring.
 *
 * <p>As is the case with other ExecutorServices, there are three
 * main task execution methods summarized in the following table.
 * These are designed to be used primarily by clients not already
 * engaged in fork/join computations in the current pool.  The main
 * forms of these methods accept instances of {@code ForkJoinTask},
 * but overloaded forms also allow mixed execution of plain {@code
 * Runnable}- or {@code Callable}- based activities as well.  However,
 * tasks that are already executing in a pool should normally instead
 * use the within-computation forms listed in the table unless using
 * async event-style tasks that are not usually joined, in which case
 * there is little difference among choice of methods.
 *
 * <table BORDER CELLPADDING=3 CELLSPACING=1>
 * <caption>Summary of task execution methods</caption>
 *  <tr>
 *    <td></td>
 *    <td ALIGN=CENTER> <b>Call from non-fork/join clients</b></td>
 *    <td ALIGN=CENTER> <b>Call from within fork/join computations</b></td>
 *  </tr>
 *  <tr>
 *    <td> <b>Arrange async execution</b></td>
 *    <td> {@link #execute(ForkJoinTask)}</td>
 *    <td> {@link ForkJoinTask#fork}</td>
 *  </tr>
 *  <tr>
 *    <td> <b>Await and obtain result</b></td>
 *    <td> {@link #invoke(ForkJoinTask)}</td>
 *    <td> {@link ForkJoinTask#invoke}</td>
 *  </tr>
 *  <tr>
 *    <td> <b>Arrange exec and obtain Future</b></td>
 *    <td> {@link #submit(ForkJoinTask)}</td>
 *    <td> {@link ForkJoinTask#fork} (ForkJoinTasks <em>are</em> Futures)</td>
 *  </tr>
 * </table>
 *
 * <p>The common pool is by default constructed with default
 * parameters, but these may be controlled by setting three
 * {@linkplain System#getProperty system properties}:
 * <ul>
 * <li>{@code java.util.concurrent.ForkJoinPool.common.parallelism}
 * - the parallelism level, a non-negative integer
 * <li>{@code java.util.concurrent.ForkJoinPool.common.threadFactory}
 * - the class name of a {@link ForkJoinWorkerThreadFactory}
 * <li>{@code java.util.concurrent.ForkJoinPool.common.exceptionHandler}
 * - the class name of a {@link UncaughtExceptionHandler}
 * </ul>
 * If a {@link SecurityManager} is present and no factory is
 * specified, then the default pool uses a factory supplying
 * threads that have no {@link Permissions} enabled.
 * The system class loader is used to load these classes.
 * Upon any error in establishing these settings, default parameters
 * are used. It is possible to disable or limit the use of threads in
 * the common pool by setting the parallelism property to zero, and/or
 * using a factory that may return {@code null}. However doing so may
 * cause unjoined tasks to never be executed.
 *
 * <p><b>Implementation notes</b>: This implementation restricts the
 * maximum number of running threads to 32767. Attempts to create
 * pools with greater than the maximum number result in
 * {@code IllegalArgumentException}.
 *
 * <p>This implementation rejects submitted tasks (that is, by throwing
 * {@link RejectedExecutionException}) only when the pool is shut down
 * or internal resources have been exhausted.
 *
 * @since 1.7
 * @author Doug Lea
 */
@sun.misc.Contended
public class ForkJoinPool extends AbstractExecutorService {

    /*
     * Implementation Overview
     *
     * This class and its nested classes provide the main
     * functionality and control for a set of worker threads:
     * Submissions from non-FJ threads enter into submission queues.
     * Workers take these tasks and typically split them into subtasks
     * that may be stolen by other workers.  Preference rules give
     * first priority to processing tasks from their own queues (LIFO
     * or FIFO, depending on mode), then to randomized FIFO steals of
     * tasks in other queues.  This framework began as vehicle for
     * supporting tree-structured parallelism using work-stealing.
     * Over time, its scalability advantages led to extensions and
     * changes to better support more diverse usage contexts.  Because
     * most internal methods and nested classes are interrelated,
     * their main rationale and descriptions are presented here;
     * individual methods and nested classes contain only brief
     * comments about details.
     *
     * ForkJoinPool 和它的内部类为一组工作线程提供主要功能和控制：
     * 非FJ线程任务放在 Submission queue，工作线程拿到这些任务并拆分为
     * 多个子任务，队列任务也可能被其他工作线程偷取。工作线程优先处理来自
     * 自身队列的任务（LIFO或FIFO顺序，参数mode决定），然后以 FIFO 的顺序
     * 随机窃取其他队列中的任务。
     * work-stealing 框架在刚开始是为了支持树状结构并行操作，随着时间的推移
     * 它的可伸缩性优势得到扩展和优化，以更好地支持更多样化的使用。由于很多内部方法
     * 和嵌套类相互关联，它们的主要原理和描述都在下面进行说明；个别方法和嵌套
     * 类只包含关于细节的简短注释。
     *
     * WorkQueues
     * ==========
     *
     * Most operations occur within work-stealing queues (in nested
     * class WorkQueue).  These are special forms of Deques that
     * support only three of the four possible end-operations -- push,
     * pop, and poll (aka steal), under the further constraints that
     * push and pop are called only from the owning thread (or, as
     * extended here, under a lock), while poll may be called from
     * other threads.  (If you are unfamiliar with them, you probably
     * want to read Herlihy and Shavit's book "The Art of
     * Multiprocessor programming", chapter 16 describing these in
     * more detail before proceeding.)  The main work-stealing queue
     * design is roughly similar to those in the papers "Dynamic
     * Circular Work-Stealing Deque" by Chase and Lev, SPAA 2005
     * (http://research.sun.com/scalable/pubs/index.html) and
     * "Idempotent work stealing" by Michael, Saraswat, and Vechev,
     * PPoPP 2009 (http://portal.acm.org/citation.cfm?id=1504186).
     * The main differences ultimately stem from GC requirements that
     * we null out taken slots as soon as we can, to maintain as small
     * a footprint as possible even in programs generating huge
     * numbers of tasks. To accomplish this, we shift the CAS
     * arbitrating pop vs poll (steal) from being on the indices
     * ("base" and "top") to the slots themselves.
     *
     * 大多数操作都发生在work-stealing队列中(内部类WorkQueue)。此workQueue支持三种形式的出列操作：
     * push、pop、poll(也叫steal)，push和pop只能被队列内部持有的线程调用，poll可被其他线程偷取任务
     * 时调用。和最初的 Work-Stealing 队列不同，考虑到GC问题，我们一有机会就将队列的获取槽位置空，
     * 即使是在生成大量任务的程序中，也要保持尽可能小的占用空间。为了实现这一点，我们使用
     * CAS解决pop和poll(steal)的线程冲突问题。
     * 关于pop和poll的区别，下面用一段简单的伪代码说明：
   pop，持有队列的工作线程调用:
    if ((base != top) and
        (在top位的任务不为null) and
        (通过CAS操作设置top位为null))
           递减top值并返回任务;

   poll，通常是偷取线程调用：
    if ((base != top) and
        (在base位的任务不为null) and
        (base位没有被其他线程修改) and
        (通过CAS操作设置base位为null))
           增加base值并返回任务;
     * Adding tasks then takes the form of a classic array push(task):
     *    q.array[q.top] = task; ++q.top;
     *
     * (The actual code needs to null-check and size-check the array,
     * properly fence the accesses, and possibly signal waiting
     * workers to start scanning -- see below.)  Both a successful pop
     * and poll mainly entail a CAS of a slot from non-null to null.
     *
     * The pop operation (always performed by owner) is:
     *   if ((base != top) and
     *        (the task at top slot is not null) and
     *        (CAS slot to null))
     *           decrement top and return task;
     *
     * And the poll operation (usually by a stealer) is
     *    if ((base != top) and
     *        (the task at base slot is not null) and
     *        (base has not changed) and
     *        (CAS slot to null))
     *           increment base and return task;
     *
     * Because we rely on CASes of references, we do not need tag bits
     * on base or top.  They are simple ints as used in any circular
     * array-based queue (see for example ArrayDeque).  Updates to the
     * indices guarantee that top == base means the queue is empty,
     * but otherwise may err on the side of possibly making the queue
     * appear nonempty when a push, pop, or poll have not fully
     * committed. (Method isEmpty() checks the case of a partially
     * completed removal of the last element.)  Because of this, the
     * poll operation, considered individually, is not wait-free. One
     * thief cannot successfully continue until another in-progress
     * one (or, if previously empty, a push) completes.  However, in
     * the aggregate, we ensure at least probabilistic
     * non-blockingness.  If an attempted steal fails, a thief always
     * chooses a different random victim target to try next. So, in
     * order for one thief to progress, it suffices for any
     * in-progress poll or new push on any empty queue to
     * complete. (This is why we normally use method pollAt and its
     * variants that try once at the apparent base index, else
     * consider alternative actions, rather than method poll, which
     * retries.)
     *
     *  由于使用了 CAS 引用操作 workerQueue 数组，所以在队列 base 和 top 不必使用标志位。
     * 这些也是数组结构的队列的特性（例如ArrayDeque）。对索引的更新保证了当 top==base 时代表这个队列为空，
     * 但是如果在push、pop或poll没有完全完成的情况下，可能出现即使 base==top 但队列为非空的错误情况
     * （isEmpty方法可以检查“移除最后一个元素操作”部分完成的情况）。所以，单独考虑 poll 操作，它并不是
     * wait-free 的(无等待算法)。在一个线程正在偷取任务时，另外一个线程是无法完成偷取操作的。
     * 大体上讲，我们起码有一定概率保证了阻塞性。如果一个偷取操作失败，偷取线程会选择另外一个随机目标继续尝试。所以，
     * 为了保证偷取线程能够正常执行，它必须能够满足任何正在执行的对 queue 的 poll 或 push 操作都能完成（
     * 这就是为什么我们通常使用 pollAt 方法和它的变体，在已知的 base 索引中先尝试一次，然后再考虑可替代的操作，
     * 而不直接使用可以重试的 poll 方法）。
     *
     * This approach also enables support of a user mode in which
     * local task processing is in FIFO, not LIFO order, simply by
     * using poll rather than pop.  This can be useful in
     * message-passing frameworks in which tasks are never joined.
     * However neither mode considers affinities, loads, cache
     * localities, etc, so rarely provide the best possible
     * performance on a given machine, but portably provide good
     * throughput by averaging over these factors.  Further, even if
     * we did try to use such information, we do not usually have a
     * basis for exploiting it.  For example, some sets of tasks
     * profit from cache affinities, but others are harmed by cache
     * pollution effects. Additionally, even though it requires
     * scanning, long-term throughput is often best using random
     * selection rather than directed selection policies, so cheap
     * randomization of sufficient quality is used whenever
     * applicable.  Various Marsaglia XorShifts (some with different
     * shift constants) are inlined at use points.
     *
     *  这种方法同样也支持本地任务（Submission task）以 FIFO 模式运行（使用poll）。
     * FIFO 和 LIFO 这两种模式都不会考虑共用性、加载、缓存地址等，所以很少能在给定的机器上提供最好的性能，
     * 但通过对这些因素进行平均，可以提供良好的吞吐量。更进一步来讲，即使我们尝试使用这些信息，也没有能利用它的基础。
     * 例如，一些任务集从缓存共用中获取到良好的性能收益，但其他任务集会因此受到它的影响。另外，虽然队列中提供了扫描功能，
     * 但是从长远看来为了吞吐量通常最好使用随机选择，而非直接选择。所以，在ForkJoinPool 中我们也就使用了
     * 一种 XorShifts（一种随机算法，有些带有不同的偏移量） 随机算法。
     * WorkQueues are also used in a similar way for tasks submitted
     * to the pool. We cannot mix these tasks in the same queues used
     * by workers. Instead, we randomly associate submission queues
     * with submitting threads, using a form of hashing.  The
     * ThreadLocalRandom probe value serves as a hash code for
     * choosing existing queues, and may be randomly repositioned upon
     * contention with other submitters.  In essence, submitters act
     * like workers except that they are restricted to executing local
     * tasks that they submitted (or in the case of CountedCompleters,
     * others with the same root task).  Insertion of tasks in shared
     * mode requires a lock (mainly to protect in the case of
     * resizing) but we use only a simple spinlock (using field
     * qlock), because submitters encountering a busy queue move on to
     * try or create other queues -- they block only when creating and
     * registering new queues. Additionally, "qlock" saturates to an
     * unlockable value (-1) at shutdown. Unlocking still can be and
     * is performed by cheaper ordered writes of "qlock" in successful
     * cases, but uses CAS in unsuccessful cases.
     * WorkQueue 对于提交到池中的任务也使用类似的随机插入方式。我们不能把这些被工作线程使用的任务混合同一个队列中，
     * 所以，我们会使用一种随机哈希算法（有点类似ConcurrentHashMap的随机算法）将工作队列与工作线程关联起来。
     * ThreadLocalRandom的probe(探针值)会为选中的已存在的队列提供一个哈希值，在与其他提交任务的线程（submitters）竞争时，
     * 也可以利用probe来随机移位。实际上，submitters 就像工作线程（worker）一样，只不过他们被限制只能执行它们提交的本地任务
     * （CountedCompleter类型任务也不能执行）。在共享模式里提交的任务需要锁来控制（在扩容情况下提供保护），我们
     * 使用了一个简单的自旋锁(qlock字段，后面详细讲解)，因为 submitters 遇到一个繁忙队列时会继续尝试提交其他队列或创建新的
     * 队列-只有在 submitters 创建和注册新队列时阻塞。另外，"qlock"会在 shutdown 时饱和到不可锁定值(-1)，但是解锁操作
     * 依然可以执行。
     * 3. 池管理
     * work-stealing 模式的吞吐量优势来自于分散控制：工作线程主要从它们自己的队列或其他worker的队列中获取任务，
     * 速度可以超过每秒十亿。池本身的创建、激活(为运行中的任务提供扫描功能)、撤销、阻塞和销毁线程这些操作的信息都很小，
     * 只有一小部分属性我们可以全局追踪或维护，所以我们把他们封装成一系列小的数字变量，并且这些变量通常是没有阻塞或锁定
     * 的原子数。几乎所有基本的原子控制状态都被保存在两个volatile变量中(ctl和runState)，这两个变量常被用来状态读取
     * 和一致性检查（此外，"config"字段持有了不可变的配置状态）。
     * ctl 定义为一个64位的原子类型字段，用来标识对工作线程进行添加、灭活、重新激活和对队列出列、
     * 入列操作。ctl 原子性地维护了活跃线程数、工作线程总数，和一个放置等待线程的队列。
     * 活跃线程数同样也担任静止指标的角色，当工作线程确认已经没有任务可以执行时，就递减这个值。
     * 工作队列其实是一个 Treiber 栈（无锁并发栈），它可以以最近使用的顺序来存储活跃线程。这
     * 改善了线程执行性能和任务定位，只要任务在栈的顶端，在发生争用时也可以很好的释放工作线程。
     * 当找不到 worker 时，我们使用 park/unpark 来操作已经被放到空闲 worker 栈的工作线程
     * （使用 ctl 低32位表示）。栈顶状态也维护了工作线程的“scanState”子域：它的索引和状态(ctl)，
     * 加上一个版本计数(SS_SEQ)，再加上子域数（同样也可作为版本戳）提供对 Treiber 栈ABA问题的保护。
     * runState表示当前池的运行状态（STARTED、STOP等），同时也可作为锁保护workQueues数组的更新。当
     * 作为一个锁使用时，通常只能被一部分操作持有(唯一的异常是数组进行一次性初始化和非常规扩容)，
     * 所以最多在一段自旋后总是处于可用状态。需要注意的是，awaitRunStateLock 方法(仅当使用CAS获取失败时调用)
     * 在自旋一段时间之后，使用 wait/notify 机制来实现阻塞(这种情况很少)。对于一个高竞争的锁来说这是一个很不好的设计，
     * 但多数pool在自旋限制之后都是在无锁竞争情况下运行的，所以作为一个保守的选择它运行的还是比较稳定的。
     * 因为我们没有其他的内部对象作为监视器使用，所以“stealCounter”(AtomicLong，也是在externalSubmit中延迟加载)
     * 充当了这个角色。
     * "runState" 和 "ctl"的用法只有在一种情况中会相互影响： 当添加一个新的工作线程时（tryAddWorker），只有在获得锁时才可以对ctl进行CAS操作。

scanState：工作线程(worker)和线程池(pool)都使用了 scanState，通过 scanState 可以管理和追踪工作线程是否为 INACTIVE（可能正在阻塞等待唤醒）状态，也可以判断任务是否为 SCANNING 状态（当两者都不是时，它就是正在运行的任务）。当一个工作线程处于灭活状态(INACTIVE)，它的scanState被设置为禁止执行任务，但是即便如此它也必须扫描一次以避免队列争用。注意，scanState 的更新在队列CAS释放之后（会有延迟）。在工作线程入队后，scanState的低16位必须持有它在池中的索引，所以我们在初始化时就将索引设置好(参考 registerWorker 方法)，并将其一直保存在那里或在必要时恢复它。

WorkQueue记录： ForkJoinPool 内部持有一个 WorkQueue 数组（"workQueues"）来记录任务工作队列。这个数组在第一次使用时(externalSubmit方法)创建，如果必要的话对其进行扩容。数组的更新操作受runState锁的保护，但可以并发读取。为了简化基于索引的操作，数组大小一定为2的幂，并且可存储null值。工作任务(Worker queues)存放在奇数位索引;共享任务(submission/external queues)存放在偶数位索引，最多64个槽位。以这种方式将它们组合在一起可以简化和加速任务扫描。

所有工作线程都是按需创建，被 submission 任务触发，然后被终止工作线程替代，或者作为阻塞线程的补偿线程。为了简化GC的工作，我们并不会直接持有工作线程的引用，而是通过索引的方式来访问工作队列(所以ForkJoinPool的代码结构看上去很麻烦)。实际上，工作队列数组充当弱引用机制，例如使用 ctl 也维护了索引的栈顶(top)域而不是直接存储引用。

闲置工作线程排队：跟高性能计算(HPC)work-stealing 框架不同，当没有立刻扫描到任务时，我们不能让工作线程无限制的自旋扫描， 除非有任务可用，否则不能开启/重启工作线程。另一方面，当新任务提交或生成时，我们必须快速地将它们推进行动。在许多情况下，通过增加时间来激活工作线程是限制整体性能的主要因素，并且JIT编译和分配使程序启动更加复杂，所以我们应尽可能地简化它。

内存排序：由于我们必须不定时的唤醒某个工作线程，为了不会丢失 signal 信号指令，就需要更强的内存排序规则（full-fence）来避免指令重排序。一些核心操作：如出列、更新ctl状态也需要 full-fence 来进行 CAS 操作。数组的读取则是使用 Unsafe 类提供的仿照 volatile 的方式。从其他线程访问 WorkQueue 的base、top 和任务数组时，也需要保证首次读取的可见性，所以，我们对"base"引用使用了 volatile 来修饰，并且在读取其他字段或引用之前总是先读取"base"。队列的持有线程也必须要保证变量的顺序更新，所以在更新时使用了内建指令（如 putOrderedObject）。

Worker（工作线程）创建：创建 worker 时，首先增加 worker 总数(作为一个保留字段)，然后构造一个 ForkJoinWorkerThread。在构造新的 ForkJoinWorkerThread 期间，会调用 registerWorker 来创建对应的工作队列 workQueue，并在workQueues数组(如果需要就对数组扩容)中为之分配一个索引，然后启动线程。如果在这期间出现异常或线程工厂创建线程失败，则调用 deregisterWorker 回滚线程数和其他记录。如果工作线程创建失败，线程池就会使用比目标数更少的工作线程继续运行。如果发生异常，异常通常被传递给其他外部调用者。为每个工作线程都分配索引避免了在扫描时发生偏移。我们可以把这个数组看作是一个简单的2幂哈希表，根据元素需要来进行扩容。当需要扩容或一个工作线程被撤销并替换时，增加 seedIndex 值以保证不会发生碰撞，在这之后要尽可能的保证低碰撞。

灭活和等待：任务入队时可能会遭遇到内部竞争：当一个线程已经放弃查找任务，但还没有进入工作队列等待，此时另外一个生产任务的线程可能会错过查看（并唤醒）这个线程。当一个工作线程偷取不到任务，它就会被灭活并入队等待。很多时候，由于GC或OS调度，缺少任务只是暂时性的；为了减少这种类型的灭活，扫描者在扫描期间会计算队列的 checksum (每次偷取时，对每个队列的 base 索引进行求和)。在扫描完成后并且 checksum 已经稳定，工作线程才可以放弃查找并尝试灭活；为了避免错过唤醒，在成功入队等候再次稳定之后会再重复这个扫描过程。在这种状态下，工作线程不能 take/run 任务，除非这个任务在队列中被释放，所以工作线程本身最终会尝试释放自己或其他继承者(参见tryRelease)。另外，在一次空的扫描过程中，一个失活的工作线程在阻塞(park)前会自旋指定的次数(参见 awaitWork)。注意关于伴随着 parking 或其他阻塞的 Thread.interrupt 指令的不寻常的约定：线程的中断状态仅仅用来检查线程是否可以销毁，而且在线程阻塞时同样也会检查线程是否可销毁，所以在调用park阻塞线程之前我们会先清除中断状态（调用Thread.interrupted），这样做是主要是为了防止在用户代码中，会有一些与内部使用不相关的调用导致中断状态被设置，从而导致线程被错误地销毁。

唤醒和激活：在至少有一个可被找到并执行的任务时，工作线程才会被创建或激活。在一个先前为空的队列中进行push(工作线程或外部提交)操作时，如果有空闲工作线程正在等待就唤醒它，如果工作线程少于给定并行度(parallelism)就创建一个新的工作线程。在多数平台上，signal(unpark)指令的开销很大，在真正唤醒一个线程和它实际运行之间的时间会很长，因此，尽可能多地消除这些延迟是很有必要的。并且，失活的工作线程并非都是被阻塞，它们经常会进行二次扫描或自旋，我们通过设置/清除 WorkQueue 的"parker"引用来减少不必要的 unpark 操作。

缩减工作线程：资源空闲一定的时间后需要释放。如果池保持静止状态超过一个周期时长（IDLE_TIMEOUT，默认2秒），工作线程就会由于超时而被终止(参见 awaitWork)，随着线程数量的减少和周期的增加，最终移除所有的工作线程。当剩余线程数多于2个，过量的线程在下一个静止点会被立即终止。

关闭和终止：
shutdownNow()：直接终止，内部调用 tryTerminate 首先设置runState值，然后终止调用线程和其他所有工作线程，通过设置他们的 qlock 状态来帮助销毁，取消他们未处理的任务，并唤醒等待的线程，重复上述操作一直到池处于稳定状态（循环数被工作线程数量限制，至少3次）。
shutdown()：首先检查池是否可以终止，依赖"ctl"内维护的的活动线程数—在awaitWork中如果池处于稳定状态并且活动线程数<=0也会调用tryTerminate进行销毁操作。不过，外部提交任务(submissions)并不依照上述条件。
tryTerminate 通过扫描队列(处于稳定状态)，以确保在触发"STOP"阶段之前没有正在进行的submission任务和work任务需要处理。（注意：如果在池关闭时调用 helpQuiescePool 会发生内部冲突，因为他们都会等待静止，在 helpQuiescePool 完成执行之前 tryTerminate 都不会被触发）

4. Join任务
由于我们把许多任务都复用给一批工作线程，并且不能让它们阻塞，也不能为这些任务重新分配其他的运行时堆栈。所以就使用了一种"延续性"的形式，即使这可能不是一个好的方案，因为我们可能既需要一个非阻塞的任务，也需要它的延续性来继续运行。为此我们总结了两种策略：
Helping-帮助运行：如果偷取还未开始，为这些 joiner 安排一些它可以执行的其它任务。
Compensating-补偿运行：如果没有足够的活动线程，tryCompensate()可能创建或重新激活一个备用的线程来为被阻塞的 joiner 补偿运行。
第三种形式(在方法 tryRemoveAndExec 中实现)相当于帮助一个假想的补偿线程来运行任务：如果补偿线程窃取并执行的是被join的任务,那么 join 线程不需要补偿线程就可以直接执行它（尽管牺牲了更大的运行时堆栈，但这种权衡通常是值得的）。设想一下这种互相帮助的场景：补偿线程帮助 join 线程执行任务，反过来 join 线程也会帮助补偿线程执行任务。

helpStealer（补偿执行）使用了一种“线性帮助(linear helping)”的算法。每个工作线程都记录了最近一个从其他工作队列（或 submission 队列）偷取过来的任务（"currentSteal"引用），同样也记录了当前被 join 的任务（currentJoin 引用）。helpStealer 方法使用这些标记去尝试找到偷取者并帮助它执行任务，（也就是说，从偷取任务中拿到任务并执行，“偷取者偷我的任务执行，我去偷偷取者的任务执行”），这样就可以加速任务的执行。这种算法在 ForkJoinPool 中的大概实现方式如下：

从 worker 到 steal 之间我们只保存依赖关系，而不是记录每个 steal 任务。有时可能需要对 workQueues 进行线性扫描来定位偷取者，但是一般不需要，因为偷取者在偷取任务时会把他的索引存放在在 hint 引用里。一个 worker 可能进行了多个偷取操作，但只记录了其中一个偷取者的索引(通常是最近的那个)，为了节省开销，hint 在需要时才会记录。
它是相对“浅层的”，忽略了嵌套和可能发生的循环相互偷取。
"currentJoin"引用只有在 join 的时候被更新，这意味着我们在执行生命周期比较长的任务时会丢失链接，导致GC停转(在这种情况下利用阻塞通常是一个好的方案)。
我们使用 checksum 限制查找任务的次数，然后挂起工作线程，必要时使用其他工作线程替换它。
注意：CountedCompleter 的帮助动作不需要追踪"currentJoin"：helpComplete 方法会获取并执行在同一个父节点下等待的所有任务。不过，这仍然需要对 completer 的链表进行遍历，所以使用 CountedCompleters 相对于直接定位"currentJoin"效率要低。

补偿执行的目的不在于在任何给定的时间内保持未阻塞线程的目标并行数。这个类之前的版本为所有阻塞的join任务都提供即时补偿，然而，在实践中，绝大多数阻塞都是由GC和其他JVM或OS活动产生的瞬时的附带影响，这种情况下使用补偿线程替换工作线程会使情况变得更糟。现在，通过检查 WorkQueue.scanState 的状态确认所有活动线程都正在运行，然后使用补偿操作消除多余的活跃线程数。补偿操作通常情况下是被忽略运行的(容忍少数线程)，因为它带来的利益很少：当一个有着空等待队列的工作线程在 join 时阻塞，它仍然会有足够的线程来保证活性，所以不需要进行补偿。补偿机制可能是有界限的。commonPool的界限(commonMaxSpares)使 JVM 在资源耗尽之前能更好的处理程序错误和资源滥用。用户可能通过自定义工厂来限制线程的构造，所以界限的作用在这种 pool 中是不精确的。当线程撤销(deregister)时，工作线程的总数会随之减少，不用等到他们退出并且资源被 JVM 和 OS 回收时才减少工作线程数，所以活跃线程在此瞬间可能会超过界限。

5. Common Pool
在 ForkJoinPool 静态初始化之后 commonPool 会一直存在，并且在应用中共享，使用 common pool 通常可以减少资源占用(它的线程在空闲一段时间后可以回收再使用)。大多数初始操作会发生在首次提交任务时，在方法 externalSubmit 中进行。
当外部线程提交任务到 commonPool 时，在 join 过程中他们也可以帮助执行子任务(参见 externalHelpComplete 和相关方法)。这种 caller-helps 策略是合理的，它会设置 commonPool 并行度为1(或更多，但小于可用内核的总数)，或者对于纯粹的caller-runs(调用者运行)直接设置为0。我们不需要记录外部提交任务是否属于 commonPool—如果不是，外部帮助方法会快速返回结果。

当系统配置中有一个SecurityManager(安全管理)时，我们使用InnocuousForkJoinWorkerThread代替ForkJoinWorkerThread。这些工作线程没有设置许可，不属于任何明确的用户线程组，并且在执行完任何顶层任务后消除所有的ThreadLocal变量(参见 WorkQueue.runTask)。这些相关的机制(主要在ForkJoinWorkerThread中)都是依赖JVM的，
* 而且为了达到预期效果，必须访问特定的线程域。
     *
     * Management
     * ==========
     *
     * The main throughput advantages of work-stealing stem from
     * decentralized control -- workers mostly take tasks from
     * themselves or each other, at rates that can exceed a billion
     * per second.  The pool itself creates, activates (enables
     * scanning for and running tasks), deactivates, blocks, and
     * terminates threads, all with minimal central information.
     * There are only a few properties that we can globally track or
     * maintain, so we pack them into a small number of variables,
     * often maintaining atomicity without blocking or locking.
     * Nearly all essentially atomic control state is held in two
     * volatile variables that are by far most often read (not
     * written) as status and consistency checks. (Also, field
     * "config" holds unchanging configuration state.)
     *
     * Field "ctl" contains 64 bits holding information needed to
     * atomically decide to add, inactivate, enqueue (on an event
     * queue), dequeue, and/or re-activate workers.  To enable this
     * packing, we restrict maximum parallelism to (1<<15)-1 (which is
     * far in excess of normal operating range) to allow ids, counts,
     * and their negations (used for thresholding) to fit into 16bit
     * subfields.
     *
     * Field "runState" holds lockable state bits (STARTED, STOP, etc)
     * also protecting updates to the workQueues array.  When used as
     * a lock, it is normally held only for a few instructions (the
     * only exceptions are one-time array initialization and uncommon
     * resizing), so is nearly always available after at most a brief
     * spin. But to be extra-cautious, after spinning, method
     * awaitRunStateLock (called only if an initial CAS fails), uses a
     * wait/notify mechanics on a builtin monitor to block when
     * (rarely) needed. This would be a terrible idea for a highly
     * contended lock, but most pools run without the lock ever
     * contending after the spin limit, so this works fine as a more
     * conservative alternative. Because we don't otherwise have an
     * internal Object to use as a monitor, the "stealCounter" (an
     * AtomicLong) is used when available (it too must be lazily
     * initialized; see externalSubmit).
     *
     * Usages of "runState" vs "ctl" interact in only one case:
     * deciding to add a worker thread (see tryAddWorker), in which
     * case the ctl CAS is performed while the lock is held.
     *
     * Recording WorkQueues.  WorkQueues are recorded in the
     * "workQueues" array. The array is created upon first use (see
     * externalSubmit) and expanded if necessary.  Updates to the
     * array while recording new workers and unrecording terminated
     * ones are protected from each other by the runState lock, but
     * the array is otherwise concurrently readable, and accessed
     * directly. We also ensure that reads of the array reference
     * itself never become too stale. To simplify index-based
     * operations, the array size is always a power of two, and all
     * readers must tolerate null slots. Worker queues are at odd
     * indices. Shared (submission) queues are at even indices, up to
     * a maximum of 64 slots, to limit growth even if array needs to
     * expand to add more workers. Grouping them together in this way
     * simplifies and speeds up task scanning.
     *
     * All worker thread creation is on-demand, triggered by task
     * submissions, replacement of terminated workers, and/or
     * compensation for blocked workers. However, all other support
     * code is set up to work with other policies.  To ensure that we
     * do not hold on to worker references that would prevent GC, All
     * accesses to workQueues are via indices into the workQueues
     * array (which is one source of some of the messy code
     * constructions here). In essence, the workQueues array serves as
     * a weak reference mechanism. Thus for example the stack top
     * subfield of ctl stores indices, not references.
     *
     * Queuing Idle Workers. Unlike HPC work-stealing frameworks, we
     * cannot let workers spin indefinitely scanning for tasks when
     * none can be found immediately, and we cannot start/resume
     * workers unless there appear to be tasks available.  On the
     * other hand, we must quickly prod them into action when new
     * tasks are submitted or generated. In many usages, ramp-up time
     * to activate workers is the main limiting factor in overall
     * performance, which is compounded at program start-up by JIT
     * compilation and allocation. So we streamline this as much as
     * possible.
     *
     * The "ctl" field atomically maintains active and total worker
     * counts as well as a queue to place waiting threads so they can
     * be located for signalling. Active counts also play the role of
     * quiescence indicators, so are decremented when workers believe
     * that there are no more tasks to execute. The "queue" is
     * actually a form of Treiber stack.  A stack is ideal for
     * activating threads in most-recently used order. This improves
     * performance and locality, outweighing the disadvantages of
     * being prone to contention and inability to release a worker
     * unless it is topmost on stack.  We park/unpark workers after
     * pushing on the idle worker stack (represented by the lower
     * 32bit subfield of ctl) when they cannot find work.  The top
     * stack state holds the value of the "scanState" field of the
     * worker: its index and status, plus a version counter that, in
     * addition to the count subfields (also serving as version
     * stamps) provide protection against Treiber stack ABA effects.
     *
     * Field scanState is used by both workers and the pool to manage
     * and track whether a worker is INACTIVE (possibly blocked
     * waiting for a signal), or SCANNING for tasks (when neither hold
     * it is busy running tasks).  When a worker is inactivated, its
     * scanState field is set, and is prevented from executing tasks,
     * even though it must scan once for them to avoid queuing
     * races. Note that scanState updates lag queue CAS releases so
     * usage requires care. When queued, the lower 16 bits of
     * scanState must hold its pool index. So we place the index there
     * upon initialization (see registerWorker) and otherwise keep it
     * there or restore it when necessary.
     *
     * Memory ordering.  See "Correct and Efficient Work-Stealing for
     * Weak Memory Models" by Le, Pop, Cohen, and Nardelli, PPoPP 2013
     * (http://www.di.ens.fr/~zappa/readings/ppopp13.pdf) for an
     * analysis of memory ordering requirements in work-stealing
     * algorithms similar to the one used here.  We usually need
     * stronger than minimal ordering because we must sometimes signal
     * workers, requiring Dekker-like full-fences to avoid lost
     * signals.  Arranging for enough ordering without expensive
     * over-fencing requires tradeoffs among the supported means of
     * expressing access constraints. The most central operations,
     * taking from queues and updating ctl state, require full-fence
     * CAS.  Array slots are read using the emulation of volatiles
     * provided by Unsafe.  Access from other threads to WorkQueue
     * base, top, and array requires a volatile load of the first of
     * any of these read.  We use the convention of declaring the
     * "base" index volatile, and always read it before other fields.
     * The owner thread must ensure ordered updates, so writes use
     * ordered intrinsics unless they can piggyback on those for other
     * writes.  Similar conventions and rationales hold for other
     * WorkQueue fields (such as "currentSteal") that are only written
     * by owners but observed by others.
     *
     * Creating workers. To create a worker, we pre-increment total
     * count (serving as a reservation), and attempt to construct a
     * ForkJoinWorkerThread via its factory. Upon construction, the
     * new thread invokes registerWorker, where it constructs a
     * WorkQueue and is assigned an index in the workQueues array
     * (expanding the array if necessary). The thread is then
     * started. Upon any exception across these steps, or null return
     * from factory, deregisterWorker adjusts counts and records
     * accordingly.  If a null return, the pool continues running with
     * fewer than the target number workers. If exceptional, the
     * exception is propagated, generally to some external caller.
     * Worker index assignment avoids the bias in scanning that would
     * occur if entries were sequentially packed starting at the front
     * of the workQueues array. We treat the array as a simple
     * power-of-two hash table, expanding as needed. The seedIndex
     * increment ensures no collisions until a resize is needed or a
     * worker is deregistered and replaced, and thereafter keeps
     * probability of collision low. We cannot use
     * ThreadLocalRandom.getProbe() for similar purposes here because
     * the thread has not started yet, but do so for creating
     * submission queues for existing external threads.
     *
     * Deactivation and waiting. Queuing encounters several intrinsic
     * races; most notably that a task-producing thread can miss
     * seeing (and signalling) another thread that gave up looking for
     * work but has not yet entered the wait queue.  When a worker
     * cannot find a task to steal, it deactivates and enqueues. Very
     * often, the lack of tasks is transient due to GC or OS
     * scheduling. To reduce false-alarm deactivation, scanners
     * compute checksums of queue states during sweeps.  (The
     * stability checks used here and elsewhere are probabilistic
     * variants of snapshot techniques -- see Herlihy & Shavit.)
     * Workers give up and try to deactivate only after the sum is
     * stable across scans. Further, to avoid missed signals, they
     * repeat this scanning process after successful enqueuing until
     * again stable.  In this state, the worker cannot take/run a task
     * it sees until it is released from the queue, so the worker
     * itself eventually tries to release itself or any successor (see
     * tryRelease).  Otherwise, upon an empty scan, a deactivated
     * worker uses an adaptive local spin construction (see awaitWork)
     * before blocking (via park). Note the unusual conventions about
     * Thread.interrupts surrounding parking and other blocking:
     * Because interrupts are used solely to alert threads to check
     * termination, which is checked anyway upon blocking, we clear
     * status (using Thread.interrupted) before any call to park, so
     * that park does not immediately return due to status being set
     * via some other unrelated call to interrupt in user code.
     *
     * Signalling and activation.  Workers are created or activated
     * only when there appears to be at least one task they might be
     * able to find and execute.  Upon push (either by a worker or an
     * external submission) to a previously (possibly) empty queue,
     * workers are signalled if idle, or created if fewer exist than
     * the given parallelism level.  These primary signals are
     * buttressed by others whenever other threads remove a task from
     * a queue and notice that there are other tasks there as well.
     * On most platforms, signalling (unpark) overhead time is
     * noticeably long, and the time between signalling a thread and
     * it actually making progress can be very noticeably long, so it
     * is worth offloading these delays from critical paths as much as
     * possible. Also, because inactive workers are often rescanning
     * or spinning rather than blocking, we set and clear the "parker"
     * field of WorkQueues to reduce unnecessary calls to unpark.
     * (This requires a secondary recheck to avoid missed signals.)
     *
     * Trimming workers. To release resources after periods of lack of
     * use, a worker starting to wait when the pool is quiescent will
     * time out and terminate (see awaitWork) if the pool has remained
     * quiescent for period IDLE_TIMEOUT, increasing the period as the
     * number of threads decreases, eventually removing all workers.
     * Also, when more than two spare threads exist, excess threads
     * are immediately terminated at the next quiescent point.
     * (Padding by two avoids hysteresis.)
     *
     * Shutdown and Termination. A call to shutdownNow invokes
     * tryTerminate to atomically set a runState bit. The calling
     * thread, as well as every other worker thereafter terminating,
     * helps terminate others by setting their (qlock) status,
     * cancelling their unprocessed tasks, and waking them up, doing
     * so repeatedly until stable (but with a loop bounded by the
     * number of workers).  Calls to non-abrupt shutdown() preface
     * this by checking whether termination should commence. This
     * relies primarily on the active count bits of "ctl" maintaining
     * consensus -- tryTerminate is called from awaitWork whenever
     * quiescent. However, external submitters do not take part in
     * this consensus.  So, tryTerminate sweeps through queues (until
     * stable) to ensure lack of in-flight submissions and workers
     * about to process them before triggering the "STOP" phase of
     * termination. (Note: there is an intrinsic conflict if
     * helpQuiescePool is called when shutdown is enabled. Both wait
     * for quiescence, but tryTerminate is biased to not trigger until
     * helpQuiescePool completes.)
     *
     *
     * Joining Tasks
     * =============
     *
     * Any of several actions may be taken when one worker is waiting
     * to join a task stolen (or always held) by another.  Because we
     * are multiplexing many tasks on to a pool of workers, we can't
     * just let them block (as in Thread.join).  We also cannot just
     * reassign the joiner's run-time stack with another and replace
     * it later, which would be a form of "continuation", that even if
     * possible is not necessarily a good idea since we may need both
     * an unblocked task and its continuation to progress.  Instead we
     * combine two tactics:
     *
     *   Helping: Arranging for the joiner to execute some task that it
     *      would be running if the steal had not occurred.
     *
     *   Compensating: Unless there are already enough live threads,
     *      method tryCompensate() may create or re-activate a spare
     *      thread to compensate for blocked joiners until they unblock.
     *
     * A third form (implemented in tryRemoveAndExec) amounts to
     * helping a hypothetical compensator: If we can readily tell that
     * a possible action of a compensator is to steal and execute the
     * task being joined, the joining thread can do so directly,
     * without the need for a compensation thread (although at the
     * expense of larger run-time stacks, but the tradeoff is
     * typically worthwhile).
     *
     * The ManagedBlocker extension API can't use helping so relies
     * only on compensation in method awaitBlocker.
     *
     * The algorithm in helpStealer entails a form of "linear
     * helping".  Each worker records (in field currentSteal) the most
     * recent task it stole from some other worker (or a submission).
     * It also records (in field currentJoin) the task it is currently
     * actively joining. Method helpStealer uses these markers to try
     * to find a worker to help (i.e., steal back a task from and
     * execute it) that could hasten completion of the actively joined
     * task.  Thus, the joiner executes a task that would be on its
     * own local deque had the to-be-joined task not been stolen. This
     * is a conservative variant of the approach described in Wagner &
     * Calder "Leapfrogging: a portable technique for implementing
     * efficient futures" SIGPLAN Notices, 1993
     * (http://portal.acm.org/citation.cfm?id=155354). It differs in
     * that: (1) We only maintain dependency links across workers upon
     * steals, rather than use per-task bookkeeping.  This sometimes
     * requires a linear scan of workQueues array to locate stealers,
     * but often doesn't because stealers leave hints (that may become
     * stale/wrong) of where to locate them.  It is only a hint
     * because a worker might have had multiple steals and the hint
     * records only one of them (usually the most current).  Hinting
     * isolates cost to when it is needed, rather than adding to
     * per-task overhead.  (2) It is "shallow", ignoring nesting and
     * potentially cyclic mutual steals.  (3) It is intentionally
     * racy: field currentJoin is updated only while actively joining,
     * which means that we miss links in the chain during long-lived
     * tasks, GC stalls etc (which is OK since blocking in such cases
     * is usually a good idea).  (4) We bound the number of attempts
     * to find work using checksums and fall back to suspending the
     * worker and if necessary replacing it with another.
     *
     * Helping actions for CountedCompleters do not require tracking
     * currentJoins: Method helpComplete takes and executes any task
     * with the same root as the task being waited on (preferring
     * local pops to non-local polls). However, this still entails
     * some traversal of completer chains, so is less efficient than
     * using CountedCompleters without explicit joins.
     *
     * Compensation does not aim to keep exactly the target
     * parallelism number of unblocked threads running at any given
     * time. Some previous versions of this class employed immediate
     * compensations for any blocked join. However, in practice, the
     * vast majority of blockages are transient byproducts of GC and
     * other JVM or OS activities that are made worse by replacement.
     * Currently, compensation is attempted only after validating that
     * all purportedly active threads are processing tasks by checking
     * field WorkQueue.scanState, which eliminates most false
     * positives.  Also, compensation is bypassed (tolerating fewer
     * threads) in the most common case in which it is rarely
     * beneficial: when a worker with an empty queue (thus no
     * continuation tasks) blocks on a join and there still remain
     * enough threads to ensure liveness.
     *
     * The compensation mechanism may be bounded.  Bounds for the
     * commonPool (see commonMaxSpares) better enable JVMs to cope
     * with programming errors and abuse before running out of
     * resources to do so. In other cases, users may supply factories
     * that limit thread construction. The effects of bounding in this
     * pool (like all others) is imprecise.  Total worker counts are
     * decremented when threads deregister, not when they exit and
     * resources are reclaimed by the JVM and OS. So the number of
     * simultaneously live threads may transiently exceed bounds.
     *
     * Common Pool
     * ===========
     *
     * The static common pool always exists after static
     * initialization.  Since it (or any other created pool) need
     * never be used, we minimize initial construction overhead and
     * footprint to the setup of about a dozen fields, with no nested
     * allocation. Most bootstrapping occurs within method
     * externalSubmit during the first submission to the pool.
     *
     * When external threads submit to the common pool, they can
     * perform subtask processing (see externalHelpComplete and
     * related methods) upon joins.  This caller-helps policy makes it
     * sensible to set common pool parallelism level to one (or more)
     * less than the total number of available cores, or even zero for
     * pure caller-runs.  We do not need to record whether external
     * submissions are to the common pool -- if not, external help
     * methods return quickly. These submitters would otherwise be
     * blocked waiting for completion, so the extra effort (with
     * liberally sprinkled task status checks) in inapplicable cases
     * amounts to an odd form of limited spin-wait before blocking in
     * ForkJoinTask.join.
     *
     * As a more appropriate default in managed environments, unless
     * overridden by system properties, we use workers of subclass
     * InnocuousForkJoinWorkerThread when there is a SecurityManager
     * present. These workers have no permissions set, do not belong
     * to any user-defined ThreadGroup, and erase all ThreadLocals
     * after executing any top-level task (see WorkQueue.runTask).
     * The associated mechanics (mainly in ForkJoinWorkerThread) may
     * be JVM-dependent and must access particular Thread class fields
     * to achieve this effect.
     *
     * Style notes
     * ===========
     *
     * Memory ordering relies mainly on Unsafe intrinsics that carry
     * the further responsibility of explicitly performing null- and
     * bounds- checks otherwise carried out implicitly by JVMs.  This
     * can be awkward and ugly, but also reflects the need to control
     * outcomes across the unusual cases that arise in very racy code
     * with very few invariants. So these explicit checks would exist
     * in some form anyway.  All fields are read into locals before
     * use, and null-checked if they are references.  This is usually
     * done in a "C"-like style of listing declarations at the heads
     * of methods or blocks, and using inline assignments on first
     * encounter.  Array bounds-checks are usually performed by
     * masking with array.length-1, which relies on the invariant that
     * these arrays are created with positive lengths, which is itself
     * paranoically checked. Nearly all explicit checks lead to
     * bypass/return, not exception throws, because they may
     * legitimately arise due to cancellation/revocation during
     * shutdown.
     *
     * There is a lot of representation-level coupling among classes
     * ForkJoinPool, ForkJoinWorkerThread, and ForkJoinTask.  The
     * fields of WorkQueue maintain data structures managed by
     * ForkJoinPool, so are directly accessed.  There is little point
     * trying to reduce this, since any associated future changes in
     * representations will need to be accompanied by algorithmic
     * changes anyway. Several methods intrinsically sprawl because
     * they must accumulate sets of consistent reads of fields held in
     * local variables.  There are also other coding oddities
     * (including several unnecessary-looking hoisted null checks)
     * that help some methods perform reasonably even when interpreted
     * (not compiled).
     *
     * The order of declarations in this file is (with a few exceptions):
     * (1) Static utility functions
     * (2) Nested (static) classes
     * (3) Static fields
     * (4) Fields, along with constants used when unpacking some of them
     * (5) Internal control methods
     * (6) Callbacks and other support for ForkJoinTask methods
     * (7) Exported methods
     * (8) Static block initializing statics in minimally dependent order
     */

    // Static utilities

    /**
     * If there is a security manager, makes sure caller has
     * permission to modify threads.
     */
    //java检查权限
    private static void checkPermission() {
        SecurityManager security = System.getSecurityManager();
        if (security != null)
            security.checkPermission(modifyThreadPermission);
    }

    // Nested classes

    /**
     * Factory for creating new {@link ForkJoinWorkerThread}s.
     * A {@code ForkJoinWorkerThreadFactory} must be defined and used
     * for {@code ForkJoinWorkerThread} subclasses that extend base
     * functionality or initialize threads with different contexts.
     */
    //接口，生成ForkJoinWorkerThread对象
    public static interface ForkJoinWorkerThreadFactory {
        /**
         * Returns a new worker thread operating in the given pool.
         *
         * @param pool the pool this thread works in
         * @return the new worker thread
         * @throws NullPointerException if the pool is null
         */
        public ForkJoinWorkerThread newThread(ForkJoinPool pool);
    }

    /**
     * Default ForkJoinWorkerThreadFactory implementation; creates a
     * new ForkJoinWorkerThread.
     */
    //静态内部类，返回ForkJoinWorkerThread对象
    static final class DefaultForkJoinWorkerThreadFactory
            implements ForkJoinWorkerThreadFactory {
        public final ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return new ForkJoinWorkerThread(pool);
        }
    }

    /**
     * Class for artificial tasks that are used to replace the target
     * of local joins if they are removed from an interior queue slot
     * in WorkQueue.tryRemoveAndExec. We don't need the proxy to
     * actually do anything beyond having a unique identity.
     */
    static final class EmptyTask extends ForkJoinTask<Void> {
        private static final long serialVersionUID = -7721805057305804111L;
        EmptyTask() { status = ForkJoinTask.NORMAL; } // force done
        public final Void getRawResult() { return null; }
        public final void setRawResult(Void x) {}
        public final boolean exec() { return true; }
    }

    // Constants shared across ForkJoinPool and WorkQueue

    // Bounds
    // 限定参数
    static final int SMASK        = 0xffff;        // short bits == max index //  低位掩码，也是最大索引位
    //最大工作线程数 2^16个worker
    static final int MAX_CAP      = 0x7fff;        // max #workers - 1 //  工作线程最大容量
    static final int EVENMASK     = 0xfffe;        // even short bits //  偶数低位掩码
    static final int SQMASK       = 0x007e;        // max 64 (even) slots //  偶数位掩码（workQueues 数组最多64个槽位）

    // ctl 子域和 WorkQueue.scanState 的掩码和标志位
    // Masks and units for WorkQueue.scanState and ctl sp subfield
    static final int SCANNING     = 1;             // false when running tasks // 标记是否正在运行任务还是在扫描
    static final int INACTIVE     = 1 << 31;       // must be negative // 失活状态  负数
    static final int SS_SEQ       = 1 << 16;       // version count // 版本戳，防止ABA问题


    // ForkJoinPool.config 和 WorkQueue.config 的配置信息标记
    // Mode bits for ForkJoinPool.config and WorkQueue.config
    static final int MODE_MASK    = 0xffff << 16;  // top half of int // 模式掩码
    static final int LIFO_QUEUE   = 0; //LIFO队列
    static final int FIFO_QUEUE   = 1 << 16; //FIFO队列
    static final int SHARED_QUEUE = 1 << 31;       // must be negative // 共享模式队列，负数

    /**
     * Queues supporting work-stealing as well as external task
     * submission. See above for descriptions and algorithms.
     * Performance on most platforms is very sensitive to placement of
     * instances of both WorkQueues and their arrays -- we absolutely
     * do not want multiple WorkQueue instances or multiple queue
     * arrays sharing cache lines. The @Contended annotation alerts
     * JVMs to try to keep instances apart.
     */
    //双向列表,用于task的排
    @sun.misc.Contended
    static final class WorkQueue {

        /**
         * Capacity of work-stealing queue array upon initialization.
         * Must be a power of two; at least 4, but should be larger to
         * reduce or eliminate cacheline sharing among queues.
         * Currently, it is much larger, as a partial workaround for
         * the fact that JVMs often place arrays in locations that
         * share GC bookkeeping (especially cardmarks) such that
         * per-write accesses encounter serious memory contention.
         */
        ////初始队列容量，2的幂
        static final int INITIAL_QUEUE_CAPACITY = 1 << 13;

        /**
         * Maximum size for queue arrays. Must be a power of two less
         * than or equal to 1 << (31 - width of array entry) to ensure
         * lack of wraparound of index calculations, but defined to a
         * value a bit less than this to help users trap runaway
         * programs before saturating systems.
         */
        ////最大队列容量
        static final int MAXIMUM_QUEUE_CAPACITY = 1 << 26; // 64M

        /*
        // <0: inactive; odd:scanning 如果WorkQueue没有属于自己的owner(下标为偶数的都没有),
        该值为 inactive 也就是一个负数。如果有自己的owner，该值的初始值为其在WorkQueue[]数组中的下标，也肯定是个奇数。
        如果这个值，变成了偶数，说明该队列所属的Thread正在执行Task
         */
        //// 实例字段
        // Instance fields
        volatile int scanState; // Woker状态, <0: inactive; odd:scanning   // versioned, <0: inactive; odd:scanning
        int stackPred; // 记录前一个栈顶的ctl            // pool stack (ctl) predecessor todo ? 记录前任的 idle worker
        int nsteals; // 偷取任务数              // number of steals
        int hint;  // 记录偷取者索引，初始为随机索引                // randomization and stealer index hint
        int config;  // 池索引和模式              // pool index and mode todo ? 如果下标为偶数的WorkQueue,则其mode是共享类型。如果有自己的owner 默认是 LIFO
        volatile int qlock; // 1: locked, < 0: terminate; else 0  每个workqueue的锁，为1表示被锁，为0表示没有被锁      // 1: locked, < 0: terminate; else 0 锁标识,在多线程往队列中添加数据，会有竞争，使用此标识抢占锁。
        volatile int base; //下一个poll操作的索引（栈底/队列头）        // index of next slot for poll    worker steal的偏移量,因为其他的线程都可以偷该队列的任务,所有base使用volatile标识。
        int top; //  下一个push操作的索引（栈顶/队列尾）                  // index of next slot for push  owner执行任务的偏移量
        ForkJoinTask<?>[] array; // 任务数组  // the elements (initially unallocated)
        final ForkJoinPool pool; // the containing pool (may be null)  // the containing pool (may be null)
        final ForkJoinWorkerThread owner; // 当前工作队列的工作线程，共享模式下为nul // owning thread or null if shared
        volatile Thread parker; // 调用park阻塞期间为owner，其他情况为null   // == owner during call to park; else null 如果 owner 挂起，则使用该变量做记录
        volatile ForkJoinTask<?> currentJoin; // 记录被join过来的任务  // task being joined in awaitJoin 当前正在join等待结果的任务
        volatile ForkJoinTask<?> currentSteal; // 记录从其他工作队列偷取过来的任务 // mainly used by helpStealer 当前执行的任务是steal过来的任务，该变量做记录

        WorkQueue(ForkJoinPool pool, ForkJoinWorkerThread owner) {
            this.pool = pool;
            this.owner = owner;
            // Place indices in the center of array (that is not yet allocated)
            base = top = INITIAL_QUEUE_CAPACITY >>> 1;
        }

        /**
         * Returns an exportable index (used by ForkJoinWorkerThread).
         */
        final int getPoolIndex() {
            return (config & 0xffff) >>> 1; // ignore odd/even tag bit
        }

        /**
         * Returns the approximate number of tasks in the queue.
         */
        final int queueSize() {
            int n = base - top;       // non-owner callers must read base first
            return (n >= 0) ? 0 : -n; // ignore transient negative
        }

        /**
         * Provides a more accurate estimate of whether this queue has
         * any tasks than does queueSize, by checking whether a
         * near-empty queue has at least one unclaimed task.
         */
        final boolean isEmpty() {
            ForkJoinTask<?>[] a; int n, m, s;
            return ((n = base - (s = top)) >= 0 ||
                    (n == -1 &&           // possibly one task
                            ((a = array) == null || (m = a.length - 1) < 0 ||
                                    U.getObject
                                            (a, (long)((m & (s - 1)) << ASHIFT) + ABASE) == null)));
        }

        /**
         * Pushes a task. Call only by owner in unshared queues.  (The
         * shared-queue version is embedded in method externalPush.)
         *
         * @param task the task. Caller must ensure non-null.
         * @throws RejectedExecutionException if array cannot be resized
         */
        //首先把任务放入等待队列并更新top位；如果当前 WorkQueue 为新建的等待队列（top-base<=1），
        // 则调用signalWork方法为当前 WorkQueue 新建或唤醒一个工作线程；
        // 如果 WorkQueue 中的任务数组容量过小，则调用growArray()方法对其进行两倍扩容
        final void push(ForkJoinTask<?> task) {
            ForkJoinTask<?>[] a; ForkJoinPool p;
            int b = base, s = top, n;
            if ((a = array) != null) {    // ignore if queue removed
                int m = a.length - 1;     // fenced write for task visibility
                U.putOrderedObject(a, ((m & s) << ASHIFT) + ABASE, task);
                U.putOrderedInt(this, QTOP, s + 1);
                if ((n = s - b) <= 1) {  //首次提交，创建或唤醒一个工作线程
                    if ((p = pool) != null)
                        p.signalWork(p.workQueues, this);
                }
                else if (n >= m)
                    growArray();
            }
        }

        /**
         * Initializes or doubles the capacity of array. Call either
         * by owner or with lock held -- it is OK for base, but not
         * top, to move while resizings are in progress.
         */
        //将 workqueue 扩容为原来的两倍
        final ForkJoinTask<?>[] growArray() {
            ForkJoinTask<?>[] oldA = array; //获取内部任务列表
            int size = oldA != null ? oldA.length << 1 : INITIAL_QUEUE_CAPACITY;
            if (size > MAXIMUM_QUEUE_CAPACITY)
                throw new RejectedExecutionException("Queue capacity exceeded");
            int oldMask, t, b;
            //新建一个两倍容量的任务数组
            ForkJoinTask<?>[] a = array = new ForkJoinTask<?>[size];
            if (oldA != null && (oldMask = oldA.length - 1) >= 0 &&
                    (t = top) - (b = base) > 0) {
                int mask = size - 1;
                //从老数组中拿出数据，放到新的数组中
                do { // emulate poll from old array, push to new array
                    ForkJoinTask<?> x;
                    int oldj = ((b & oldMask) << ASHIFT) + ABASE;
                    int j    = ((b &    mask) << ASHIFT) + ABASE;
                    x = (ForkJoinTask<?>)U.getObjectVolatile(oldA, oldj);
                    if (x != null &&
                            U.compareAndSwapObject(oldA, oldj, x, null))
                        U.putObjectVolatile(a, j, x);
                } while (++b != t);
            }
            return a;
        }

        /**
         * Takes next task, if one exists, in LIFO order.  Call only
         * by owner in unshared queues.
         */
        //从栈顶 获取新任务
        final ForkJoinTask<?> pop() {
            ForkJoinTask<?>[] a; ForkJoinTask<?> t; int m;
            if ((a = array) != null && (m = a.length - 1) >= 0) {
                for (int s; (s = top - 1) - base >= 0;) {
                    long j = ((m & s) << ASHIFT) + ABASE;
                    if ((t = (ForkJoinTask<?>)U.getObject(a, j)) == null)
                        break;
                    if (U.compareAndSwapObject(a, j, t, null)) {
                        U.putOrderedInt(this, QTOP, s);
                        return t;
                    }
                }
            }
            return null;
        }

        /**
         * Takes a task in FIFO order if b is base of queue and a task
         * can be claimed without contention. Specialized versions
         * appear in ForkJoinPool methods scan and helpStealer.
         */
        final ForkJoinTask<?> pollAt(int b) {
            ForkJoinTask<?> t; ForkJoinTask<?>[] a;
            if ((a = array) != null) {
                int j = (((a.length - 1) & b) << ASHIFT) + ABASE;
                if ((t = (ForkJoinTask<?>)U.getObjectVolatile(a, j)) != null &&
                        base == b && U.compareAndSwapObject(a, j, t, null)) {
                    base = b + 1;
                    return t;
                }
            }
            return null;
        }

        /**
         * Takes next task, if one exists, in FIFO order.
         */
        //从 栈顶 取任务
        final ForkJoinTask<?> poll() {
            ForkJoinTask<?>[] a; int b; ForkJoinTask<?> t;
            while ((b = base) - top < 0 && (a = array) != null) {
                int j = (((a.length - 1) & b) << ASHIFT) + ABASE;
                t = (ForkJoinTask<?>)U.getObjectVolatile(a, j);
                if (base == b) {
                    if (t != null) {
                        if (U.compareAndSwapObject(a, j, t, null)) {
                            base = b + 1;
                            return t;
                        }
                    }
                    else if (b + 1 == top) // now empty
                        break;
                }
            }
            return null;
        }

        /**
         * Takes next task, if one exists, in order specified by mode.
         */
        final ForkJoinTask<?> nextLocalTask() {
            return (config & FIFO_QUEUE) == 0 ? pop() : poll();
        }

        /**
         * Returns next task, if one exists, in order specified by mode.
         */
        final ForkJoinTask<?> peek() {
            ForkJoinTask<?>[] a = array; int m;
            if (a == null || (m = a.length - 1) < 0)
                return null;
            int i = (config & FIFO_QUEUE) == 0 ? top - 1 : base;
            int j = ((i & m) << ASHIFT) + ABASE;
            return (ForkJoinTask<?>)U.getObjectVolatile(a, j);
        }

        /**
         * Pops the given task only if it is at the current top.
         * (A shared version is available only via FJP.tryExternalUnpush)
         */
        //只有当前task位于栈顶的时候才弹出并返回true
        final boolean tryUnpush(ForkJoinTask<?> t) {
            ForkJoinTask<?>[] a; int s;
            if ((a = array) != null && (s = top) != base &&
                    U.compareAndSwapObject
                            (a, (((a.length - 1) & --s) << ASHIFT) + ABASE, t, null)) {
                U.putOrderedInt(this, QTOP, s);
                return true;
            }
            return false;
        }

        /**
         * Removes and cancels all known tasks, ignoring any exceptions.
         */
        final void cancelAll() {
            ForkJoinTask<?> t;
            if ((t = currentJoin) != null) {
                currentJoin = null;
                ForkJoinTask.cancelIgnoringExceptions(t);
            }
            if ((t = currentSteal) != null) {
                currentSteal = null;
                ForkJoinTask.cancelIgnoringExceptions(t);
            }
            while ((t = poll()) != null)
                ForkJoinTask.cancelIgnoringExceptions(t);
        }

        // Specialized execution methods

        /**
         * Polls and runs tasks until empty.
         */

        final void pollAndExecAll() {
            for (ForkJoinTask<?> t; (t = poll()) != null;)
                t.doExec();
        }

        /**
         * Removes and executes all local tasks. If LIFO, invokes
         * pollAndExecAll. Otherwise implements a specialized pop loop
         * to exec until empty.
         */
        //删除并执行所有本地任务。 如果为LIFO，则调用pollAndExecAll。 否则，将执行一个专门的pop循环以执行直到空。
        //执行并移除所有本地任务
        final void execLocalTasks() {
            int b = base, m, s;
            ForkJoinTask<?>[] a = array;
            if (b - (s = top - 1) <= 0 && a != null &&
                    (m = a.length - 1) >= 0) {
                //如果 后进先出，从栈顶 开始 获取任务并执行
                if ((config & FIFO_QUEUE) == 0) {
                    for (ForkJoinTask<?> t;;) {
                        if ((t = (ForkJoinTask<?>)U.getAndSetObject
                                (a, ((m & s) << ASHIFT) + ABASE, null)) == null)
                            break;
                        U.putOrderedInt(this, QTOP, s);
                        t.doExec();
                        if (base - (s = top - 1) > 0)
                            break;
                    }
                }
                else
                    //LIFO模式执行，取base任务
                    pollAndExecAll();
            }
        }

        /**
         * Executes the given task and any remaining local tasks.
         */
        //在scan方法扫描到任务之后，调用WorkQueue.runTask()来执行获取到的任务，大概流程如下：

        //1.标记scanState为正在执行状态；
        //2.更新currentSteal为当前获取到的任务并执行它，任务的执行调用了ForkJoinTask.doExec()方法
        final void runTask(ForkJoinTask<?> task) {
            if (task != null) {
                scanState &= ~SCANNING; // mark as busy //标记为非 scanning状态
                (currentSteal = task).doExec(); //更新currentSteal并执行任务
                U.putOrderedObject(this, QCURRENTSTEAL, null); // release for GC
                //调用execLocalTasks依次执行当前WorkerQueue中的任务
                execLocalTasks(); //依次执行本地任务
                ForkJoinWorkerThread thread = owner;
                if (++nsteals < 0)      // collect on overflow
                    transferStealCount(pool); //增加偷取任务数
                scanState |= SCANNING;
                if (thread != null)
                    thread.afterTopLevelExec(); //执行钩子函数
            }
        }

        /**
         * Adds steal count to pool stealCounter if it exists, and resets.
         */
        final void transferStealCount(ForkJoinPool p) {
            AtomicLong sc;
            if (p != null && (sc = p.stealCounter) != null) {
                int s = nsteals;
                nsteals = 0;            // if negative, correct for overflow
                sc.getAndAdd((long)(s < 0 ? Integer.MAX_VALUE : s));
            }
        }

        /**
         * If present, removes from queue and executes the given task,
         * or any other cancelled task. Used only by awaitJoin.
         *
         * @return true if queue empty and task not known to be done
         */
        /*
        从top位开始自旋向下找到给定任务，如果找到把它从当前 Worker 的任务队列中移除并执行它。注意返回的参数：
        如果任务队列为空或者任务未执行完毕返回true；任务执行完毕返回false。
         */
        final boolean tryRemoveAndExec(ForkJoinTask<?> task) {
            ForkJoinTask<?>[] a; int m, s, b, n;
            if ((a = array) != null && (m = a.length - 1) >= 0 &&
                    task != null) {
                while ((n = (s = top) - (b = base)) > 0) {
                    //从top往下自旋查找
                    for (ForkJoinTask<?> t;;) {      // traverse from s to b
                        long j = ((--s & m) << ASHIFT) + ABASE; //计算任务索引
                        if ((t = (ForkJoinTask<?>)U.getObject(a, j)) == null) //获取索引到的任务
                            return s + 1 == top;     // shorter than expected
                        else if (t == task) { //给定任务为索引任务
                            boolean removed = false;
                            if (s + 1 == top) {      // pop
                                if (U.compareAndSwapObject(a, j, task, null)) { //弹出任务
                                    U.putOrderedInt(this, QTOP, s); //更新top
                                    removed = true;
                                }
                            }
                            else if (base == b)      // replace with proxy
                                removed = U.compareAndSwapObject(
                                        a, j, task, new EmptyTask()); //join任务已经被移除，替换为一个占位任务
                            if (removed)
                                task.doExec(); //执行
                            break;
                        }
                        else if (t.status < 0 && s + 1 == top) { //给定任务不是top任务
                            if (U.compareAndSwapObject(a, j, t, null)) //弹出任务
                                U.putOrderedInt(this, QTOP, s); //更新top
                            break;                  // was cancelled
                        }
                        if (--n == 0)
                            return false;
                    }
                    if (task.status < 0)
                        return false;
                }
            }
            return true;
        }

        /**
         * Pops task if in the same CC computation as the given task,
         * in either shared or owned mode. Used only by helpComplete.
         */
        final CountedCompleter<?> popCC(CountedCompleter<?> task, int mode) {
            int s; ForkJoinTask<?>[] a; Object o;
            if (base - (s = top) < 0 && (a = array) != null) {
                long j = (((a.length - 1) & (s - 1)) << ASHIFT) + ABASE;
                if ((o = U.getObjectVolatile(a, j)) != null &&
                        (o instanceof CountedCompleter)) {
                    CountedCompleter<?> t = (CountedCompleter<?>)o;
                    for (CountedCompleter<?> r = t;;) {
                        if (r == task) {
                            if (mode < 0) { // must lock
                                if (U.compareAndSwapInt(this, QLOCK, 0, 1)) {
                                    if (top == s && array == a &&
                                            U.compareAndSwapObject(a, j, t, null)) {
                                        U.putOrderedInt(this, QTOP, s - 1);
                                        U.putOrderedInt(this, QLOCK, 0);
                                        return t;
                                    }
                                    U.compareAndSwapInt(this, QLOCK, 1, 0);
                                }
                            }
                            else if (U.compareAndSwapObject(a, j, t, null)) {
                                U.putOrderedInt(this, QTOP, s - 1);
                                return t;
                            }
                            break;
                        }
                        else if ((r = r.completer) == null) // try parent
                            break;
                    }
                }
            }
            return null;
        }

        /**
         * Steals and runs a task in the same CC computation as the
         * given task if one exists and can be taken without
         * contention. Otherwise returns a checksum/control value for
         * use by method helpComplete.
         *
         * @return 1 if successful, 2 if retryable (lost to another
         * stealer), -1 if non-empty but no matching task found, else
         * the base index, forced negative.
         */
        final int pollAndExecCC(CountedCompleter<?> task) {
            int b, h; ForkJoinTask<?>[] a; Object o;
            if ((b = base) - top >= 0 || (a = array) == null)
                h = b | Integer.MIN_VALUE;  // to sense movement on re-poll
            else {
                long j = (((a.length - 1) & b) << ASHIFT) + ABASE;
                if ((o = U.getObjectVolatile(a, j)) == null)
                    h = 2;                  // retryable
                else if (!(o instanceof CountedCompleter))
                    h = -1;                 // unmatchable
                else {
                    CountedCompleter<?> t = (CountedCompleter<?>)o;
                    for (CountedCompleter<?> r = t;;) {
                        if (r == task) {
                            if (base == b &&
                                    U.compareAndSwapObject(a, j, t, null)) {
                                base = b + 1;
                                t.doExec();
                                h = 1;      // success
                            }
                            else
                                h = 2;      // lost CAS
                            break;
                        }
                        else if ((r = r.completer) == null) {
                            h = -1;         // unmatched
                            break;
                        }
                    }
                }
            }
            return h;
        }

        /**
         * Returns true if owned and not known to be blocked.
         */
        final boolean isApparentlyUnblocked() {
            Thread wt; Thread.State s;
            return (scanState >= 0 &&
                    (wt = owner) != null &&
                    (s = wt.getState()) != Thread.State.BLOCKED &&
                    s != Thread.State.WAITING &&
                    s != Thread.State.TIMED_WAITING);
        }

        // Unsafe mechanics. Note that some are (and must be) the same as in FJP
        private static final sun.misc.Unsafe U;
        private static final int  ABASE;
        private static final int  ASHIFT;
        private static final long QTOP;
        private static final long QLOCK;
        private static final long QCURRENTSTEAL;
        static {
            try {
                U = sun.misc.Unsafe.getUnsafe();
                Class<?> wk = WorkQueue.class;
                Class<?> ak = ForkJoinTask[].class;
                QTOP = U.objectFieldOffset
                        (wk.getDeclaredField("top"));
                QLOCK = U.objectFieldOffset
                        (wk.getDeclaredField("qlock"));
                QCURRENTSTEAL = U.objectFieldOffset
                        (wk.getDeclaredField("currentSteal"));
                ABASE = U.arrayBaseOffset(ak);
                int scale = U.arrayIndexScale(ak);
                if ((scale & (scale - 1)) != 0)
                    throw new Error("data type scale not a power of two");
                ASHIFT = 31 - Integer.numberOfLeadingZeros(scale);
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }


/** 静态初始化字段 */
    // static fields (initialized in static initializer below)

    /**
     * Creates a new ForkJoinWorkerThread. This factory is used unless
     * overridden in ForkJoinPool constructors.
     */
    //线程工厂
    public static final ForkJoinWorkerThreadFactory
            defaultForkJoinWorkerThreadFactory;

    /**
     * Permission required for callers of methods that may start or
     * kill threads.
     */
    //启动或杀死线程的方法调用者的权限
    private static final RuntimePermission modifyThreadPermission;

    /**
     * Common (static) pool. Non-null for public use unless a static
     * construction exception, but internal usages null-check on use
     * to paranoically avoid potential initialization circularities
     * as well as to simplify generated code.
     */
    // 公共静态pool
    static final ForkJoinPool common;

    /**
     * Common pool parallelism. To allow simpler use and management
     * when common pool threads are disabled, we allow the underlying
     * common.parallelism field to be zero, but in that case still report
     * parallelism as 1 to reflect resulting caller-runs mechanics.
     */
    //并行度，对应内部common池
    static final int commonParallelism;

    /**
     * Limit on spare thread construction in tryCompensate.
     */

//备用线程数，在tryCompensate中使用
    private static int commonMaxSpares;

    /**
     * Sequence number for creating workerNamePrefix.
     */
    //创建workerNamePrefix(工作线程名称前缀)时的序号
    private static int poolNumberSequence;

    /**
     * Returns the next sequence number. We don't expect this to
     * ever contend, so use simple builtin sync.
     */
    private static final synchronized int nextPoolId() {
        return ++poolNumberSequence;
    }

    // static configuration constants

    /**
     * Initial timeout value (in nanoseconds) for the thread
     * triggering quiescence to park waiting for new work. On timeout,
     * the thread will instead try to shrink the number of
     * workers. The value should be large enough to avoid overly
     * aggressive shrinkage during most transient stalls (long GCs
     * etc).
     */
    //线程阻塞等待新的任务的超时值(以纳秒为单位)，默认2秒
    private static final long IDLE_TIMEOUT = 2000L * 1000L * 1000L; // 2sec

    /**
     * Tolerance for idle timeouts, to cope with timer undershoots
     */
    //空闲超时时间，防止timer未命中
    private static final long TIMEOUT_SLOP = 20L * 1000L * 1000L;  // 20ms

    /**
     * The initial value for commonMaxSpares during static
     * initialization. The value is far in excess of normal
     * requirements, but also far short of MAX_CAP and typical
     * OS thread limits, so allows JVMs to catch misuse/abuse
     * before running out of resources needed to do so.
     */
    //默认备用线程数
    private static final int DEFAULT_COMMON_MAX_SPARES = 256;

    /**
     * Number of times to spin-wait before blocking. The spins (in
     * awaitRunStateLock and awaitWork) currently use randomized
     * spins. Currently set to zero to reduce CPU usage.
     *
     * If greater than zero the value of SPINS must be a power
     * of two, at least 4.  A value of 2048 causes spinning for a
     * small fraction of typical context-switch times.
     *
     * If/when MWAIT-like intrinsics becomes available, they
     * may allow quieter spinning.
     */
    //阻塞前自旋的次数，用在在awaitRunStateLock和awaitWork中
    private static final int SPINS  = 0;

    /**
     * Increment for seed generators. See class ThreadLocal for
     * explanation.
     */
    //indexSeed的增量
    private static final int SEED_INCREMENT = 0x9e3779b9;

    /*
     * Bits and masks for field ctl, packed with 4 16 bit subfields:
     * AC: Number of active running workers minus target parallelism
     * TC: Number of total workers minus target parallelism
     * SS: version count and status of top waiting thread
     * ID: poolIndex of top of Treiber stack of waiters
     *
     * When convenient, we can extract the lower 32 stack top bits
     * (including version bits) as sp=(int)ctl.  The offsets of counts
     * by the target parallelism and the positionings of fields makes
     * it possible to perform the most common checks via sign tests of
     * fields: When ac is negative, there are not enough active
     * workers, when tc is negative, there are not enough total
     * workers.  When sp is non-zero, there are waiting workers.  To
     * deal with possibly negative fields, we use casts in and out of
     * "short" and/or signed shifts to maintain signedness.
     *
     * Because it occupies uppermost bits, we can add one active count
     * using getAndAddLong of AC_UNIT, rather than CAS, when returning
     * from a blocked join.  Other updates entail multiple subfields
     * and masking, requiring CAS.
     */

    // Lower and upper word masks
    // ctl的低位掩码
    private static final long SP_MASK    = 0xffffffffL;
    // ctl 的高位掩码
    private static final long UC_MASK    = ~SP_MASK;

    // 活跃线程数
    // Active counts
    private static final int  AC_SHIFT   = 48;
    //
    private static final long AC_UNIT    = 0x0001L << AC_SHIFT;
    private static final long AC_MASK    = 0xffffL << AC_SHIFT;

    // Total counts
    private static final int  TC_SHIFT   = 32;
    private static final long TC_UNIT    = 0x0001L << TC_SHIFT;
    private static final long TC_MASK    = 0xffffL << TC_SHIFT;
    private static final long ADD_WORKER = 0x0001L << (TC_SHIFT + 15); // sign 0x800000000000 用来判断总线程数的第一位是否为负数，从而判断是否还能增加线程

    // 池状态
    // runState bits: SHUTDOWN must be negative, others arbitrary powers of two
    //标识Pool运行状态,使用bit位来标识不同状态,shutdown必须非负，其他状态都是2的幂，执行runstate&状态码可以判断当前所属状态
    private static final int  RSLOCK     = 1;  //runstate 锁定状态
    private static final int  RSIGNAL    = 1 << 1;
    private static final int  STARTED    = 1 << 2;
    private static final int  STOP       = 1 << 29;
    private static final int  TERMINATED = 1 << 30;
    private static final int  SHUTDOWN   = 1 << 31;

    // 实例字段
    // Instance fields
    // ForkJoinPool 的内部状态都是通过一个64位的 long 型 变量ctl来存储，它由四个16位的子域组成：
    //AC: 正在运行工作线程数减去parallelism，高16位
    //TC: 总工作线程数减去parallelism，中高16位
    //SS: WorkQueue状态，第一位表示active的还是inactive，其余十五位表示版本号(对付ABA)；，中低16位
    //ID: 栈顶 WorkQueue 在池中的索引（poolIndex），低16位
    //一个TreiberStack。后文讲的栈顶，指这里下标所在的WorkQueue。
    //AC和TC初始化时取的是parallelism负数，后续代码可以直接判断正负，为负代表还没有达到parallelism。
    //另外ctl低32位有个技巧可以直接用sp=(int)ctl取得，为负代表存在空闲worker。
    //
    volatile long ctl;                   // main pool control // 主控制参数
    //线程池状态（RSLOCK/RSIGNAL/STARTED/STOP/TERMINATED/SHUTDOWN） runState小于0表示已经关闭
    volatile int runState;               // lockable status // 运行状态锁
    //config保存不变的参数，包括了parallelism和mode，
    // 供后续读取。mode可选FIFO_QUEUE和LIFO_QUEUE，默认是LIFO_QUEUE，具体用哪种，就要看业务。
    // async_mode,parallelism 0x0XXXX，0x1XXXX
    // config = (parallelism & SMASK) | mode
    final int config;                    // parallelism, mode // 并行度|模式
    int indexSeed;                       // to generate worker index // 用于生成工作线程索引
    //ForkJoinPool 内部持有一个 WorkQueue 数组（"workQueues"）来记录任务工作队列。这个数组在第一次
    // 使用时(externalSubmit方法)创建，如果必要的话对其进行扩容。数组的更新操作受runState锁的保护，
    // 但可以并发读取。为了简化基于索引的操作，数组大小一定为2的幂，并且可存储null值。
    // 内部任务(Worker queues)存放在奇数位索引;外部任务(submission/external queues)存放在偶数位索引，
    // 最多64个槽位。以这种方式将它们组合在一起可以简化和加速任务扫描
    volatile WorkQueue[] workQueues;     // main registry // 主对象注册信息，workQueue
    //ForkJoin线程池工厂
    final ForkJoinWorkerThreadFactory factory; // 线程工厂
    final UncaughtExceptionHandler ueh;  // per-worker UEH // 每个工作线程的异常信息
    final String workerNamePrefix;       // to create worker name string  // 用于创建工作线程的名称
    volatile AtomicLong stealCounter;    // also used as sync monitor // 偷取任务总数，也可作为同步监视器

    /**
     * Acquires the runState lock; returns current (locked) runState.
     */
    // 锁定 runstate，如果 已经被锁定或者 锁定竞争失败，则 自旋 几次后 阻塞 在 stealCounter 变量上直到被唤醒
    private int lockRunState() {
        int rs;
        //如果 已经锁定或者 没有被本次调用 成功锁定，则 调用awaitRunStateLock，否则返回 更改后的 runState
        return ((((rs = runState) & RSLOCK) != 0 /*是否已经锁定*/ ||
                !U.compareAndSwapInt(this, RUNSTATE, rs, rs |= RSLOCK)) /*是否成功锁定*/ ?
                awaitRunStateLock() : rs);
    }

    /**
     * Spins and/or blocks until runstate lock is available.  See
     * above for explanation.
     */
    //自选 或者 阻塞 直到 runstate 锁定 被释放
    private int awaitRunStateLock() {
        Object lock;
        boolean wasInterrupted = false;
        for (int spins = SPINS, r = 0, rs, ns;;) {
            if (((rs = runState) & RSLOCK) == 0) {
                //如果 runState锁定被释放
                if (U.compareAndSwapInt(this, RUNSTATE, rs, ns = rs | RSLOCK)) {
                    if (wasInterrupted) {
                        try {
                            Thread.currentThread().interrupt();
                        } catch (SecurityException ignore) {
                        }
                    }
                    return ns;
                }
            }
            else if (r == 0)
                //生成 随机种子
                r = ThreadLocalRandom.nextSecondarySeed();
            else if (spins > 0) {
                r ^= r << 6; r ^= r >>> 21; r ^= r << 7; // xorshift
                // xorshift计算出一个随机数，如果随机数 大于0，则 spins -1
                if (r >= 0)
                    --spins;
            }
            else if ((rs & STARTED) == 0 || (lock = stealCounter) == null)
                //如果 forkjoinpool 还没有初始化完成，则将本 线程 重置到 就绪队列
                Thread.yield();   // initialization race
            else if (U.compareAndSwapInt(this, RUNSTATE, rs, rs | RSIGNAL)) {
                //如果 自旋次数 用完，则设置 待唤醒 状态 且 阻塞本线程
                synchronized (lock) {
                    if ((runState & RSIGNAL) != 0) {
                        //如果还没有被 其他线程唤醒，则阻塞
                        try {
                            lock.wait();
                        } catch (InterruptedException ie) {
                            if (!(Thread.currentThread() instanceof
                                    ForkJoinWorkerThread))
                                wasInterrupted = true;
                        }
                    }
                    else
                        //否则 唤醒 所有等待线程
                        lock.notifyAll();
                }
            }
        }
    }

    /**
     * Unlocks and sets runState to newRunState.
     *
     * @param oldRunState a value returned from lockRunState
     * @param newRunState the next value (must have lock bit clear).
     */
    private void unlockRunState(int oldRunState, int newRunState) {
        if (!U.compareAndSwapInt(this, RUNSTATE, oldRunState, newRunState)) {
            Object lock = stealCounter;
            runState = newRunState;              // clears RSIGNAL bit
            if (lock != null)
                synchronized (lock) { lock.notifyAll(); }
        }
    }

    // Creating, registering and deregistering workers

    /**
     * Tries to construct and start one worker. Assumes that total
     * count has already been incremented as a reservation.  Invokes
     * deregisterWorker on any failure.
     *
     * @return true if successful
     */
    //createWorker首先通过线程工厂创一个新的ForkJoinWorkerThread，然后启动这个工作线程（wt.start()）。
    //如果期间发生异常，调用deregisterWorker处理线程创建失败的逻辑
    private boolean createWorker() {
        ForkJoinWorkerThreadFactory fac = factory;
        Throwable ex = null;
        ForkJoinWorkerThread wt = null;
        try {
            if (fac != null && (wt = fac.newThread(this)) != null) {
                wt.start();
                return true;
            }
        } catch (Throwable rex) {
            ex = rex;
        }
        deregisterWorker(wt, ex);
        return false;
    }

    /**
     * Tries to add one worker, incrementing ctl counts before doing
     * so, relying on createWorker to back out on failure.
     *
     * @param c incoming ctl value, with total count negative and no
     * idle workers.  On CAS failure, c is refreshed and retried if
     * this holds (otherwise, a new worker is not needed).
     */
    //尝试添加一个新的工作线程，首先更新ctl中的工作线程数，然后调用createWorker()创建工作线程。
    private void tryAddWorker(long c) {
        boolean add = false;
        do {
            long nc = ((AC_MASK & (c + AC_UNIT)) |
                    (TC_MASK & (c + TC_UNIT))); //更新 ctl中的总线程数和 活动线程数
            if (ctl == c) {
                int rs, stop;                 // check if terminating
                if ((stop = (rs = lockRunState()) & STOP) == 0)
                    add = U.compareAndSwapLong(this, CTL, c, nc);
                unlockRunState(rs, rs & ~RSLOCK); //释放锁
                if (stop != 0)
                    break;
                if (add) {
                    createWorker(); //创建工作线程
                    break;
                }
            }
        } while (((c = ctl) & ADD_WORKER) != 0L && (int)c == 0);
    }

    /**
     * Callback from ForkJoinWorkerThread constructor to establish and
     * record its WorkQueue.
     *
     * @param wt the worker thread
     * @return the worker's queue
     */
    //ForkJoinWorkerThread 在构造时首先调用父类 Thread 的方法，然后为工作线程注册pool和workQueue，
    // 而workQueue的注册任务由ForkJoinPool.registerWorker来完成
    final WorkQueue registerWorker(ForkJoinWorkerThread wt) {
        UncaughtExceptionHandler handler;
        //设置为守护线程
        wt.setDaemon(true);                           // configure thread
        if ((handler = ueh) != null)
            wt.setUncaughtExceptionHandler(handler);
        WorkQueue w = new WorkQueue(this, wt); //构造新的WorkQueue
        int i = 0;                                    // assign a pool index
        int mode = config & MODE_MASK; //获取模式
        int rs = lockRunState();
        try {
            WorkQueue[] ws; int n;                    // skip if no array
            if ((ws = workQueues) != null && (n = ws.length) > 0) {
                //生成新建WorkQueue的索引
                int s = indexSeed += SEED_INCREMENT;  // unlikely to collide 如果已经存在其他线程的 workqueue在该位置，则重新生成
                int m = n - 1;
                i = ((s << 1) | 1) & m;               // odd-numbered indices 奇数索引，Worker任务放在奇数索引位 odd-numbered indices
                if (ws[i] != null) {     // collision 已存在，重新计算索引位             // collision
                    int probes = 0;                   // step by approx half n
                    int step = (n <= 4) ? 2 : ((n >>> 1) & EVENMASK) + 2; //step大概是 n的1/2
                    while (ws[i = (i + step) & m] != null) {
                        if (++probes >= n) { //如果探测次数大于 n
                            workQueues = ws = Arrays.copyOf(ws, n <<= 1); //所有索引位都被占用，对workQueues进行扩容为原来的2倍
                            m = n - 1;
                            probes = 0;
                        }
                    }
                }
                w.hint = s;                           // use as random seed
                w.config = i | mode;
                w.scanState = i;                      // publication fence
                ws[i] = w;
            }
        } finally {
            unlockRunState(rs, rs & ~RSLOCK);
        }
        wt.setName(workerNamePrefix.concat(Integer.toString(i >>> 1)));
        return w;
    }

    /**
     * Final callback from terminating worker, as well as upon failure
     * to construct or start a worker.  Removes record of worker from
     * array, and adjusts counts. If pool is shutting down, tries to
     * complete termination.
     *
     * @param wt the worker thread, or null if construction failed
     * @param ex the exception causing failure, or null if none
     */
    //deregisterWorker方法用于工作线程运行完毕之后终止线程或处理工作线程异常，
    //主要就是清除已关闭的工作线程或回滚创建线程之前的操作，并把传入的异常抛给 ForkJoinTask 来处理。
    final void deregisterWorker(ForkJoinWorkerThread wt, Throwable ex) {
        WorkQueue w = null;
        //1.移除workQueue
        if (wt != null && (w = wt.workQueue) != null) { //获取ForkJoinWorkerThread对应的workqueue
            WorkQueue[] ws;                           // remove index from array
            int idx = w.config & SMASK; //计算workQueue索引
            int rs = lockRunState(); //获取runState锁和当前池运行状态
            if ((ws = workQueues) != null && ws.length > idx && ws[idx] == w)
                ws[idx] = null; //移除workQueue
            unlockRunState(rs, rs & ~RSLOCK); //解除runState锁
        }
        //2.减少CTL数
        long c;                                       // decrement counts
        do {} while (!U.compareAndSwapLong
                (this, CTL, c = ctl, ((AC_MASK & (c - AC_UNIT)) |
                        (TC_MASK & (c - TC_UNIT)) |
                        (SP_MASK & c))));
        //3.处理被移除workQueue内部相关参数
        if (w != null) {
            w.qlock = -1;                             // ensure set
            w.transferStealCount(this);
            w.cancelAll();                            // cancel remaining tasks
        }
        //4.如果线程未终止，替换被移除的workQueue并唤醒内部线程
        for (;;) {                                    // possibly replace
            WorkQueue[] ws; int m, sp;
            //尝试终止线程池
            if (tryTerminate(false, false) || w == null || w.array == null ||
                    (runState & STOP) != 0 || (ws = workQueues) == null ||
                    (m = ws.length - 1) < 0)              // already terminating
                break;
            //唤醒被替换的线程，依赖于下一步
            if ((sp = (int)(c = ctl)) != 0) {         // wake up replacement
                if (tryRelease(c, ws[sp & m], AC_UNIT))
                    break;
            }
            //创建工作线程替换
            else if (ex != null && (c & ADD_WORKER) != 0L) {
                tryAddWorker(c);                      // create replacement
                break;
            }
            else                                      // don't need replacement
                break;
        }
        //5.处理异常
        if (ex == null)                               // help clean on way out
            ForkJoinTask.helpExpungeStaleExceptions();
        else                                          // rethrow
            ForkJoinTask.rethrow(ex);
    }

    // Signalling

    /**
     * Tries to create or activate a worker if too few are active.
     *
     * @param ws the worker array to use to find signallees
     * @param q a WorkQueue --if non-null, don't retry if now empty
     */
    ////创建或激活一个工作线程来运行任务
    //新建或唤醒一个工作线程，在externalPush、externalSubmit、workQueue.push、scan中调用。
    // 如果还有空闲线程，则尝试唤醒索引到的 WorkQueue 的parker线程；
    // 如果工作线程过少（(ctl & ADD_WORKER) != 0L），则调用tryAddWorker添加一个新的工作线程。
    final void signalWork(WorkQueue[] ws, WorkQueue q) {
        long c;
        int sp, i;
        WorkQueue v;
        Thread p;
        while ((c = ctl) < 0L) { //工作线程还没有到上限                       // too few active
            if ((sp = (int)c) == 0) {                  // no idle workers
                if ((c & ADD_WORKER) != 0L)            // too few workers
                    tryAddWorker(c); //工作线程太少，添加新的工作线程
                break;
            }
            if (ws == null)                            // unstarted/terminated
                break;
            if (ws.length <= (i = sp & SMASK))         // terminated
                break;
            if ((v = ws[i]) == null)                   // terminating
                break;
            //计算ctl，加上版本戳SS_SEQ避免ABA问题
            int vs = (sp + SS_SEQ) & ~INACTIVE;        // next scanState
            int d = sp - v.scanState;                  // screen CAS
            //计算活跃线程数（高32位）并更新为下一个栈顶的scanState（低32位）
            long nc = (UC_MASK & (c + AC_UNIT)) | (SP_MASK & v.stackPred);
            if (d == 0 && U.compareAndSwapLong(this, CTL, c, nc)) {
                v.scanState = vs;                      // activate v
                if ((p = v.parker) != null)
                    U.unpark(p); //唤醒阻塞线程
                break;
            }
            if (q != null && q.base == q.top)          // no more work
                break;
        }
    }

    /**
     * Signals and releases worker v if it is top of idle worker
     * stack.  This performs a one-shot version of signalWork only if
     * there is (apparently) at least one idle worker.
     *
     * @param c incoming ctl value
     * @param v if non-null, a worker
     * @param inc the increment to active count (zero when compensating)
     * @return true if successful
     */
    //1.如果base位任务为空或发生偏移，则对索引位进行随机移位，然后重新扫描；
    //2.如果扫描整个workQueues之后没有获取到任务，则设置当前工作线程为INACTIVE状态；
    // 然后重置checkSum，再次扫描一圈之后如果还没有任务则跳出循环返回null。
    private boolean tryRelease(long c, WorkQueue v, long inc) {
        int sp = (int)c, vs = (sp + SS_SEQ) & ~INACTIVE; Thread p;
        //ctl低32位等于scanState，说明可以唤醒parker线程
        if (v != null && v.scanState == sp) {          // v is at top of stack
            long nc = (UC_MASK & (c + inc)) | (SP_MASK & v.stackPred);
            //计算活跃线程数（高32位）并更新为下一个栈顶的scanState（低32位）
            if (U.compareAndSwapLong(this, CTL, c, nc)) {
                v.scanState = vs;
                if ((p = v.parker) != null)
                    U.unpark(p); //唤醒线程
                return true;
            }
        }
        return false;
    }

    // Scanning for tasks

    /**
     * Top-level runloop for workers, called by ForkJoinWorkerThread.run.
     */
    //runWorker是 ForkJoinWorkerThread 的主运行方法，用来依次执行当前工作线程中的任务。
    // 函数流程很简单：调用scan方法依次获取任务，然后调用WorkQueue .runTask运行任务；如果未扫描到任务，
    // 则调用awaitWork等待，直到工作线程/线程池终止或等待超时(20ms)
    final void runWorker(WorkQueue w) {
        w.growArray();   //分配 线程队列        // allocate queue
        int seed = w.hint;               // initially holds randomization hint
        int r = (seed == 0) ? 1 : seed;  // avoid 0 for xorShift
        for (ForkJoinTask<?> t;;) {
            if ((t = scan(w, r)) != null) //扫描任务执行
                w.runTask(t);
            else if (!awaitWork(w, r))
                break;
            r ^= r << 13; r ^= r >>> 17; r ^= r << 5; // xorshift //生成随机数
        }
    }

    /**
     * Scans for and tries to steal a top-level task. Scans start at a
     * random location, randomly moving on apparent contention,
     * otherwise continuing linearly until reaching two consecutive
     * empty passes over all queues with the same checksum (summing
     * each base index of each queue, that moves on each steal), at
     * which point the worker tries to inactivate and then re-scans,
     * attempting to re-activate (itself or some other worker) if
     * finding a task; otherwise returning null to await work.  Scans
     * otherwise touch as little memory as possible, to reduce
     * disruption on other scanning threads.
     *
     * @param w the worker (via its WorkQueue)
     * @param r a random seed
     * @return a task, or null if none found
     */
    //扫描并尝试偷取一个任务。使用w.hint进行随机索引 WorkQueue，
    //就是说并不一定会执行当前 WorkQueue 中的任务，而是偷取别的Worker的任务来执行
    //函数的大概执行流程如下：
    //1.取随机位置的一个 WorkQueue；
    //2.获取base位的 ForkJoinTask，成功取到后更新base位并返回任务；如果取到的 WorkQueue
    // 中任务数大于1，则调用signalWork创建或唤醒其他工作线程；
    //3.如果当前工作线程处于不活跃状态（INACTIVE），则调用tryRelease尝试唤醒栈顶工作线程来执行。
    private ForkJoinTask<?> scan(WorkQueue w, int r) {
        WorkQueue[] ws; int m;
        if ((ws = workQueues) != null && (m = ws.length - 1) > 0 && w != null) {
            int ss = w.scanState;                     // initially non-negative
            //初始扫描起点，自旋扫描
            for (int origin = r & m, k = origin, oldSum = 0, checkSum = 0;;) {
                //当前正在扫描的队列
                WorkQueue q;
                ForkJoinTask<?>[] a;
                ForkJoinTask<?> t;
                int b, n; long c;
                if ((q = ws[k]) != null) { //获取workQueue
                    if ((n = (b = q.base) - q.top) < 0 &&
                            (a = q.array) != null) {      // non-empty
                        //获取 k对应的workqueue的栈底 元素
                        long i = (((a.length - 1) & b) << ASHIFT) + ABASE;
                        if ((t = ((ForkJoinTask<?>)
                                U.getObjectVolatile(a, i))) != null &&
                                q.base == b) {
                            if (ss >= 0) { //当前处于scanning
                                if (U.compareAndSwapObject(a, i, t, null)) {
                                    //栈底 + 1
                                    q.base = b + 1; //更新base位
                                    if (n < -1)       // signal others
                                        //激活新的 工作线程
                                        signalWork(ws, q); //创建或唤醒工作线程来运行任务
                                    //返回 获取到的栈底task
                                    return t;
                                }
                            }
                            else if (oldSum == 0 &&   // try to activate 尝试激活 inactive的工作线程
                                    w.scanState < 0)
                                tryRelease(c = ctl, ws[m & (int)c], AC_UNIT); //唤醒栈顶工作线程
                        }
                        //base位置任务为空或base位置偏移，随机移位重新扫描
                        if (ss < 0)                   // refresh
                            ss = w.scanState;
                        r ^= r << 1; r ^= r >>> 3; r ^= r << 10;
                        origin = k = r & m;           // move and rescan
                        oldSum = checkSum = 0; //队列任务为空，记录base位
                        continue;
                    }
                    checkSum += b;
                }
                //更新索引k 继续向后查找
                if ((k = (k + 1) & m) == origin) {    // continue until stable
                    //运行到这里说明已经扫描了全部的 workQueues，但并未扫描到任务
                    if ((ss >= 0 || (ss == (ss = w.scanState))) &&
                            oldSum == (oldSum = checkSum)) {
                        if (ss < 0 || w.qlock < 0)    // already inactive
                            // 已经被灭活或终止,跳出循环
                            break;
                        //对当前WorkQueue进行灭活操作
                        int ns = ss | INACTIVE;       // try to inactivate
                        long nc = ((SP_MASK & ns) |
                                (UC_MASK & ((c = ctl) - AC_UNIT))); //计算ctl为INACTIVE状态并减少活跃线程数
                        w.stackPred = (int)c;         // hold prev stack top
                        U.putInt(w, QSCANSTATE, ns); //修改scanState为inactive状态
                        if (U.compareAndSwapLong(this, CTL, c, nc)) //更新scanState为灭活状态
                            ss = ns;
                        else
                            w.scanState = ss;         // back out //重置checkSum，继续循环
                    }
                    checkSum = 0;
                }
            }
        }
        return null;
    }

    /**
     * Possibly blocks worker w waiting for a task to steal, or
     * returns false if the worker should terminate.  If inactivating
     * w has caused the pool to become quiescent, checks for pool
     * termination, and, so long as this is not the only worker, waits
     * for up to a given duration.  On timeout, if ctl has not
     * changed, terminates the worker, which will in turn wake up
     * another worker to possibly repeat this process.
     *
     * @param w the calling worker
     * @param r a random seed (for spins)
     * @return false if the worker should terminate
     */
    // 函数的具体执行流程大家看源码，这里简单说一下：
    //在等待获取任务期间，如果工作线程或线程池已经终止则直接返回false。如果当前无 active 线程，
    // 尝试终止线程池并返回false，如果终止失败并且当前是最后一个等待的 Worker，
    // 就阻塞指定的时间（IDLE_TIMEOUT）；等到届期或被唤醒后如果发现自己是scanning
    // （scanState >= 0）状态，说明已经等到任务，
    // 跳出等待返回true继续 scan，否则的更新ctl并返回false。
    private boolean awaitWork(WorkQueue w, int r) {
        if (w == null || w.qlock < 0)                 // w is terminating
            return false;
        for (int pred = w.stackPred, spins = SPINS, ss;;) {
            if ((ss = w.scanState) >= 0) //正在扫描，跳出循环
                break;
            else if (spins > 0) {
                r ^= r << 6; r ^= r >>> 21; r ^= r << 7;
                if (r >= 0 && --spins == 0) {         // randomize spins
                    WorkQueue v; WorkQueue[] ws; int s, j; AtomicLong sc;
                    if (pred != 0 && (ws = workQueues) != null &&
                            (j = pred & SMASK) < ws.length &&
                            (v = ws[j]) != null &&        // see if pred parking
                            (v.parker == null || v.scanState >= 0))
                        spins = SPINS;                // continue spinning
                }
            }
            else if (w.qlock < 0)                     // recheck after spins // 当前workQueue已经终止，返回false recheck after spins
                return false;
            else if (!Thread.interrupted()) { //判断线程是否被中断，并清除中断状态
                long c, prevctl, parkTime, deadline;
                int ac = (int)((c = ctl) >> AC_SHIFT) + (config & SMASK); //活跃线程数
                if ((ac <= 0 && tryTerminate(false, false)) || //无active线程，尝试终止
                        (runState & STOP) != 0)           // pool terminating
                    return false;
                if (ac <= 0 && ss == (int)c) {        // is last waiter
                    //计算活跃线程数（高32位）并更新为下一个栈顶的scanState（低32位）
                    prevctl = (UC_MASK & (c + AC_UNIT)) | (SP_MASK & pred);
                    int t = (short)(c >>> TC_SHIFT);  // shrink excess spares
                    if (t > 2 && U.compareAndSwapLong(this, CTL, c, prevctl)) //总线程过量
                        return false;                 // else use timed wait
                    //计算空闲超时时间
                    parkTime = IDLE_TIMEOUT * ((t >= 0) ? 1 : 1 - t);
                    deadline = System.nanoTime() + parkTime - TIMEOUT_SLOP;
                }
                else
                    prevctl = parkTime = deadline = 0L;
                Thread wt = Thread.currentThread();
                U.putObject(wt, PARKBLOCKER, this);   // emulate LockSupport
                w.parker = wt; //设置parker，准备阻塞
                if (w.scanState < 0 && ctl == c)      // recheck before park
                    U.park(false, parkTime); //阻塞指定的时间
                U.putOrderedObject(w, QPARKER, null);
                U.putObject(wt, PARKBLOCKER, null);
                if (w.scanState >= 0) //正在扫描，说明等到任务，跳出循环
                    break;
                if (parkTime != 0L && ctl == c &&
                        deadline - System.nanoTime() <= 0L &&
                        U.compareAndSwapLong(this, CTL, c, prevctl)) //未等到任务，更新ctl，返回false
                    return false;                     // shrink pool
            }
        }
        return true;
    }

    // Joining tasks

    /**
     * Tries to steal and run tasks within the target's computation.
     * Uses a variant of the top-level algorithm, restricted to tasks
     * with the given task as ancestor: It prefers taking and running
     * eligible tasks popped from the worker's own queue (via
     * popCC). Otherwise it scans others, randomly moving on
     * contention or execution, deciding to give up based on a
     * checksum (via return codes frob pollAndExecCC). The maxTasks
     * argument supports external usages; internal calls use zero,
     * allowing unbounded steps (external calls trap non-positive
     * values).
     *
     * @param w caller
     * @param maxTasks if non-zero, the maximum number of other tasks to run
     * @return task status on exit
     */
    final int helpComplete(WorkQueue w, CountedCompleter<?> task,
                           int maxTasks) {
        WorkQueue[] ws; int s = 0, m;
        if ((ws = workQueues) != null && (m = ws.length - 1) >= 0 &&
                task != null && w != null) {
            int mode = w.config;                 // for popCC
            int r = w.hint ^ w.top;              // arbitrary seed for origin
            int origin = r & m;                  // first queue to scan
            int h = 1;                           // 1:ran, >1:contended, <0:hash
            for (int k = origin, oldSum = 0, checkSum = 0;;) {
                CountedCompleter<?> p; WorkQueue q;
                if ((s = task.status) < 0)
                    break;
                if (h == 1 && (p = w.popCC(task, mode)) != null) {
                    p.doExec();                  // run local task
                    if (maxTasks != 0 && --maxTasks == 0)
                        break;
                    origin = k;                  // reset
                    oldSum = checkSum = 0;
                }
                else {                           // poll other queues
                    if ((q = ws[k]) == null)
                        h = 0;
                    else if ((h = q.pollAndExecCC(task)) < 0)
                        checkSum += h;
                    if (h > 0) {
                        if (h == 1 && maxTasks != 0 && --maxTasks == 0)
                            break;
                        r ^= r << 13; r ^= r >>> 17; r ^= r << 5; // xorshift
                        origin = k = r & m;      // move and restart
                        oldSum = checkSum = 0;
                    }
                    else if ((k = (k + 1) & m) == origin) {
                        if (oldSum == (oldSum = checkSum))
                            break;
                        checkSum = 0;
                    }
                }
            }
        }
        return s;
    }

    /**
     * Tries to locate and execute tasks for a stealer of the given
     * task, or in turn one of its stealers, Traces currentSteal ->
     * currentJoin links looking for a thread working on a descendant
     * of the given task and with a non-empty queue to steal back and
     * execute tasks from. The first call to this method upon a
     * waiting join will often entail scanning/search, (which is OK
     * because the joiner has nothing better to do), but this method
     * leaves hints in workers to speed up subsequent calls.
     *
     * @param w caller
     * @param task the task to join
     */
    //如果队列为空或任务执行失败，说明任务可能被偷，调用此方法来帮助偷取者执行任务。
    // 基本思想是：偷取者帮助我执行任务，我去帮助偷取者执行它的任务。
    //函数执行流程如下：

    //循环定位偷取者，由于Worker是在奇数索引位，所以每次会跳两个索引位。
    // 定位到偷取者之后，更新调用者 WorkQueue 的hint为偷取者的索引，方便下次定位；
    //定位到偷取者后，开始帮助偷取者执行任务。从偷取者的base索引开始，每次偷取一个任务执行。
    // 在帮助偷取者执行任务后，如果调用者发现本身已经有任务（w.top != top），
    // 则依次弹出自己的任务(LIFO顺序)并执行（也就是说自己偷自己的任务执行）。
    private void helpStealer(WorkQueue w, ForkJoinTask<?> task) {
        WorkQueue[] ws = workQueues;
        int oldSum = 0, checkSum, m;
        if (ws != null && (m = ws.length - 1) >= 0 && w != null &&
                task != null) {
            do {                                       // restart point
                checkSum = 0;                          // for stability check
                ForkJoinTask<?> subtask;
                WorkQueue j = w, v;                    // v is subtask stealer
                descent: for (subtask = task; subtask.status >= 0; ) {
                    //1. 找到给定WorkQueue的偷取者v
                    for (int h = j.hint | 1, k = 0, i; ; k += 2) { //跳两个索引，因为Worker在奇数索引位
                        if (k > m)                     // can't find stealer
                            break descent;
                        if ((v = ws[i = (h + k) & m]) != null) {
                            if (v.currentSteal == subtask) { //定位到偷取者
                                j.hint = i; //更新stealer索引
                                break;
                            }
                            checkSum += v.base;
                        }
                    }
                    //2. 帮助偷取者v执行任务
                    for (;;) {                         // help v or descend
                        ForkJoinTask<?>[] a; int b; //偷取者内部的任务
                        checkSum += (b = v.base);
                        ForkJoinTask<?> next = v.currentJoin; //获取偷取者的join任务
                        if (subtask.status < 0 || j.currentJoin != subtask ||
                                v.currentSteal != subtask) // stale 如果被偷取的任务已经执行完，则跳出循环
                            break descent; // stale，跳出descent循环重来
                        if (b - v.top >= 0 || (a = v.array) == null) {
                            if ((subtask = next) == null) //偷取者的join任务为null，跳出descent循环
                                break descent;
                            j = v;
                            break; //偷取者内部任务为空，可能任务也被偷走了；跳出本次循环，查找偷取者的偷取者
                        }
                        int i = (((a.length - 1) & b) << ASHIFT) + ABASE; //获取base偏移地址
                        ForkJoinTask<?> t = ((ForkJoinTask<?>)
                                U.getObjectVolatile(a, i)); //获取偷取者的base任务
                        if (v.base == b) {
                            if (t == null)             // stale
                                break descent; // stale，跳出descent循环重来
                            if (U.compareAndSwapObject(a, i, t, null)) { //弹出任务
                                v.base = b + 1; //更新偷取者的base位
                                ForkJoinTask<?> ps = w.currentSteal; //获取调用者偷来的任务
                                int top = w.top;
                                //首先更新给定workQueue的currentSteal为偷取者的base任务，然后执行该任务
                                //然后通过检查top来判断给定workQueue是否有自己的任务，如果有，
                                // 则依次弹出任务(LIFO)->更新currentSteal->执行该任务（注意这里是自己偷自己的任务执行）
                                do {
                                    U.putOrderedObject(w, QCURRENTSTEAL, t);
                                    t.doExec();        // clear local tasks too
                                } while (task.status >= 0 &&
                                        w.top != top && //内部有自己的任务，依次弹出执行
                                        (t = w.pop()) != null);
                                U.putOrderedObject(w, QCURRENTSTEAL, ps); //还原给定workQueue的currentSteal
                                if (w.base != w.top) //给定workQueue有自己的任务了，帮助结束，返回
                                    return;            // can't further help
                            }
                        }
                    }
                }
            } while (task.status >= 0 && oldSum != (oldSum = checkSum));
        }
    }

    /**
     * Tries to decrement active count (sometimes implicitly) and
     * possibly release or create a compensating worker in preparation
     * for blocking. Returns false (retryable by caller), on
     * contention, detected staleness, instability, or termination.
     *
     * @param w caller
     */
    //需要补偿：
    //调用者队列不为空，并且有空闲工作线程，这种情况会唤醒空闲线程（调用tryRelease方法）
    //池尚未停止，活跃线程数不足，这时会新建一个工作线程（调用createWorker方法）
    //不需要补偿：
    //调用者已终止或池处于不稳定状态
    //总线程数大于并行度 && 活动线程数大于1 && 调用者任务队列为空

    //执行补偿操作：尝试缩减活动线程量，可能释放或创建一个补偿线程来准备阻塞
    private boolean tryCompensate(WorkQueue w) {
        boolean canBlock;
        WorkQueue[] ws; long c; int m, pc, sp;
        if (w == null || w.qlock < 0 ||           // caller terminating
                (ws = workQueues) == null || (m = ws.length - 1) <= 0 ||
                (pc = config & SMASK) == 0)           // parallelism disabled
            canBlock = false; //调用者已终止
        else if ((sp = (int)(c = ctl)) != 0)      // release idle worker
            canBlock = tryRelease(c, ws[sp & m], 0L); //唤醒等待的工作线程
        else { //没有空闲线程
            int ac = (int)(c >> AC_SHIFT) + pc; //活跃线程数
            int tc = (short)(c >> TC_SHIFT) + pc; //总线程数
            int nbusy = 0;                        // validate saturation
            for (int i = 0; i <= m; ++i) {        // two passes of odd indices
                WorkQueue v;
                if ((v = ws[((i << 1) | 1) & m]) != null) { //取奇数索引位
                    if ((v.scanState & SCANNING) != 0) //没有正在运行任务，跳出
                        break;
                    ++nbusy; //正在运行任务，添加标记
                }
            }
            if (nbusy != (tc << 1) || ctl != c)
                canBlock = false;                 // unstable or stale
            else if (tc >= pc && ac > 1 && w.isEmpty()) { //总线程数大于并行度 && 活动线程数大于1 &&
                // 调用者任务队列为空，不需要补偿
                long nc = ((AC_MASK & (c - AC_UNIT)) |
                        (~AC_MASK & c));       // uncompensated
                canBlock = U.compareAndSwapLong(this, CTL, c, nc); //更新活跃线程数
            }
            else if (tc >= MAX_CAP ||
                    (this == common && tc >= pc + commonMaxSpares)) //超出最大线程数
                throw new RejectedExecutionException(
                        "Thread limit exceeded replacing blocked worker");
            else {                                // similar to tryAddWorker
                boolean add = false; int rs;      // CAS within lock
                long nc = ((AC_MASK & c) |
                        (TC_MASK & (c + TC_UNIT))); //超出最大线程数
                if (((rs = lockRunState()) & STOP) == 0)
                    add = U.compareAndSwapLong(this, CTL, c, nc); //更新总线程数
                unlockRunState(rs, rs & ~RSLOCK);
                //运行到这里说明活跃工作线程数不足，需要创建一个新的工作线程来补偿
                canBlock = add && createWorker(); // throws on exception
            }
        }
        return canBlock;
    }

    /**
     * Helps and/or blocks until the given task is done or timeout.
     *
     * @param w caller
     * @param task the task
     * @param deadline for timed waits, if nonzero
     * @return task status on exit
     */
    //阻塞 直到 给定的task 完成或者 超时
    //如果当前 join 任务不在Worker等待队列的top位，或者任务执行失败，
    // 调用此方法来帮助执行或阻塞当前 join 的任务。函数执行流程如下：
    //
    //由于每次调用awaitJoin都会优先执行当前join的任务，
    // 所以首先会更新currentJoin为当前join任务；
    //进入自旋：
    //首先检查任务是否已经完成（通过task.status < 0判断），
    // 如果给定任务执行完毕|取消|异常 则跳出循环返回执行状态s；
    //如果是 CountedCompleter 任务类型，调用helpComplete方法来完成join操作
    // （后面笔者会开新篇来专门讲解CountedCompleter，本篇暂时不做详细解析）；
    //非 CountedCompleter 任务类型调用WorkQueue.tryRemoveAndExec尝试执行任务；
    //如果给定 WorkQueue 的等待队列为空或任务执行失败，说明任务可能被偷，
    // 调用helpStealer帮助偷取者执行任务（也就是说，偷取者帮我执行任务，
    // 我去帮偷取者执行它的任务）；
    //再次判断任务是否执行完毕（task.status < 0），如果任务执行失败，
    // 计算一个等待时间准备进行补偿操作；
    //调用tryCompensate方法为给定 WorkQueue 尝试执行补偿操作。在执行补偿期间，
    // 如果发现 资源争用|池处于unstable状态|当前Worker已终止，则调用
    // ForkJoinTask.internalWait()方法等待指定的时间，任务唤醒之后继续自旋
    final int awaitJoin(WorkQueue w, ForkJoinTask<?> task, long deadline) {
        int s = 0;
        if (task != null && w != null) {
            ForkJoinTask<?> prevJoin = w.currentJoin; //获取给定Worker的join任务
            U.putOrderedObject(w, QCURRENTJOIN, task); //把currentJoin替换为给定任务
            //判断是否为CountedCompleter类型的任务
            CountedCompleter<?> cc = (task instanceof CountedCompleter) ?
                    (CountedCompleter<?>)task : null;
            for (;;) {
                if ((s = task.status) < 0) //已经完成|取消|异常 跳出循环
                    break;
                if (cc != null) //CountedCompleter任务由helpComplete来完成join
                    helpComplete(w, cc, 0);
                else if (w.base == w.top || w.tryRemoveAndExec(task)) //尝试执行
                    helpStealer(w, task); //队列为空或执行失败，任务可能被偷，帮助偷取者执行该任务
                if ((s = task.status) < 0) //已经完成|取消|异常，跳出循环
                    break;
                //计算任务等待时间
                long ms, ns;
                if (deadline == 0L)
                    ms = 0L;
                else if ((ns = deadline - System.nanoTime()) <= 0L)
                    break;
                else if ((ms = TimeUnit.NANOSECONDS.toMillis(ns)) <= 0L)
                    ms = 1L;
                if (tryCompensate(w)) { //执行补偿操作
                    task.internalWait(ms); //补偿执行成功，任务等待指定时间
                    U.getAndAddLong(this, CTL, AC_UNIT); //更新活跃线程数
                }
            }
            U.putOrderedObject(w, QCURRENTJOIN, prevJoin); //循环结束，替换为原来的join任务
        }
        return s;
    }

    // Specialized scanning

    /**
     * Returns a (probably) non-empty steal queue, if one is found
     * during a scan, else null.  This method must be retried by
     * caller if, by the time it tries to use the queue, it is empty.
     */
    private WorkQueue findNonEmptyStealQueue() {
        WorkQueue[] ws; int m;  // one-shot version of scan loop
        int r = ThreadLocalRandom.nextSecondarySeed();
        if ((ws = workQueues) != null && (m = ws.length - 1) >= 0) {
            for (int origin = r & m, k = origin, oldSum = 0, checkSum = 0;;) {
                WorkQueue q; int b;
                if ((q = ws[k]) != null) {
                    if ((b = q.base) - q.top < 0)
                        return q;
                    checkSum += b;
                }
                if ((k = (k + 1) & m) == origin) {
                    if (oldSum == (oldSum = checkSum))
                        break;
                    checkSum = 0;
                }
            }
        }
        return null;
    }

    /**
     * Runs tasks until {@code isQuiescent()}. We piggyback on
     * active count ctl maintenance, but rather than blocking
     * when tasks cannot be found, we rescan until all others cannot
     * find tasks either.
     */
    final void helpQuiescePool(WorkQueue w) {
        ForkJoinTask<?> ps = w.currentSteal; // save context
        for (boolean active = true;;) {
            long c; WorkQueue q; ForkJoinTask<?> t; int b;
            w.execLocalTasks();     // run locals before each scan
            if ((q = findNonEmptyStealQueue()) != null) {
                if (!active) {      // re-establish active count
                    active = true;
                    U.getAndAddLong(this, CTL, AC_UNIT);
                }
                if ((b = q.base) - q.top < 0 && (t = q.pollAt(b)) != null) {
                    U.putOrderedObject(w, QCURRENTSTEAL, t);
                    t.doExec();
                    if (++w.nsteals < 0)
                        w.transferStealCount(this);
                }
            }
            else if (active) {      // decrement active count without queuing
                long nc = (AC_MASK & ((c = ctl) - AC_UNIT)) | (~AC_MASK & c);
                if ((int)(nc >> AC_SHIFT) + (config & SMASK) <= 0)
                    break;          // bypass decrement-then-increment
                if (U.compareAndSwapLong(this, CTL, c, nc))
                    active = false;
            }
            else if ((int)((c = ctl) >> AC_SHIFT) + (config & SMASK) <= 0 &&
                    U.compareAndSwapLong(this, CTL, c, c + AC_UNIT))
                break;
        }
        U.putOrderedObject(w, QCURRENTSTEAL, ps);
    }

    /**
     * Gets and removes a local or stolen task for the given worker.
     *
     * @return a task, if available
     */
    final ForkJoinTask<?> nextTaskFor(WorkQueue w) {
        for (ForkJoinTask<?> t;;) {
            WorkQueue q; int b;
            if ((t = w.nextLocalTask()) != null)
                return t;
            if ((q = findNonEmptyStealQueue()) == null)
                return null;
            if ((b = q.base) - q.top < 0 && (t = q.pollAt(b)) != null)
                return t;
        }
    }

    /**
     * Returns a cheap heuristic guide for task partitioning when
     * programmers, frameworks, tools, or languages have little or no
     * idea about task granularity.  In essence, by offering this
     * method, we ask users only about tradeoffs in overhead vs
     * expected throughput and its variance, rather than how finely to
     * partition tasks.
     *
     * In a steady state strict (tree-structured) computation, each
     * thread makes available for stealing enough tasks for other
     * threads to remain active. Inductively, if all threads play by
     * the same rules, each thread should make available only a
     * constant number of tasks.
     *
     * The minimum useful constant is just 1. But using a value of 1
     * would require immediate replenishment upon each steal to
     * maintain enough tasks, which is infeasible.  Further,
     * partitionings/granularities of offered tasks should minimize
     * steal rates, which in general means that threads nearer the top
     * of computation tree should generate more than those nearer the
     * bottom. In perfect steady state, each thread is at
     * approximately the same level of computation tree. However,
     * producing extra tasks amortizes the uncertainty of progress and
     * diffusion assumptions.
     *
     * So, users will want to use values larger (but not much larger)
     * than 1 to both smooth over transient shortages and hedge
     * against uneven progress; as traded off against the cost of
     * extra task overhead. We leave the user to pick a threshold
     * value to compare with the results of this call to guide
     * decisions, but recommend values such as 3.
     *
     * When all threads are active, it is on average OK to estimate
     * surplus strictly locally. In steady-state, if one thread is
     * maintaining say 2 surplus tasks, then so are others. So we can
     * just use estimated queue length.  However, this strategy alone
     * leads to serious mis-estimates in some non-steady-state
     * conditions (ramp-up, ramp-down, other stalls). We can detect
     * many of these by further considering the number of "idle"
     * threads, that are known to have zero queued tasks, so
     * compensate by a factor of (#idle/#active) threads.
     */
    static int getSurplusQueuedTaskCount() {
        Thread t; ForkJoinWorkerThread wt; ForkJoinPool pool; WorkQueue q;
        if (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)) {
            int p = (pool = (wt = (ForkJoinWorkerThread)t).pool).
                    config & SMASK;
            int n = (q = wt.workQueue).top - q.base;
            int a = (int)(pool.ctl >> AC_SHIFT) + p;
            return n - (a > (p >>>= 1) ? 0 :
                    a > (p >>>= 1) ? 1 :
                            a > (p >>>= 1) ? 2 :
                                    a > (p >>>= 1) ? 4 :
                                            8);
        }
        return 0;
    }

    //  Termination

    /**
     * Possibly initiates and/or completes termination.
     *
     * @param now if true, unconditionally terminate, else only
     * if no work and no active workers 如果now()为true,无条件终止，否则，之后当没有任务和活动worker的时候才终止
     * @param enable if true, enable shutdown when next possible 如果为true，
     * @return true if now terminating or terminated
     */
    private boolean tryTerminate(boolean now, boolean enable) {
        int rs;
        //公共线程池不终止
        if (this == common)                       // cannot shut down
            return false;
        //
        if ((rs = runState) >= 0) {
            if (!enable)
                return false;
            rs = lockRunState();                  // enter SHUTDOWN phase
            unlockRunState(rs, (rs & ~RSLOCK) | SHUTDOWN);
        }

        if ((rs & STOP) == 0) {
            if (!now) {                           // check quiescence
                for (long oldSum = 0L;;) {        // repeat until stable
                    WorkQueue[] ws; WorkQueue w; int m, b; long c;
                    long checkSum = ctl;
                    if ((int)(checkSum >> AC_SHIFT) + (config & SMASK) > 0)
                        return false;             // still active workers
                    if ((ws = workQueues) == null || (m = ws.length - 1) <= 0)
                        break;                    // check queues
                    for (int i = 0; i <= m; ++i) {
                        if ((w = ws[i]) != null) {
                            if ((b = w.base) != w.top || w.scanState >= 0 ||
                                    w.currentSteal != null) {
                                tryRelease(c = ctl, ws[m & (int)c], AC_UNIT);
                                return false;     // arrange for recheck
                            }
                            checkSum += b;
                            if ((i & 1) == 0)
                                w.qlock = -1;     // try to disable external
                        }
                    }
                    if (oldSum == (oldSum = checkSum))
                        break;
                }
            }
            if ((runState & STOP) == 0) {
                rs = lockRunState();              // enter STOP phase
                unlockRunState(rs, (rs & ~RSLOCK) | STOP);
            }
        }

        int pass = 0;                             // 3 passes to help terminate
        for (long oldSum = 0L;;) {                // or until done or stable
            WorkQueue[] ws; WorkQueue w; ForkJoinWorkerThread wt; int m;
            long checkSum = ctl;
            if ((short)(checkSum >>> TC_SHIFT) + (config & SMASK) <= 0 ||
                    (ws = workQueues) == null || (m = ws.length - 1) <= 0) {
                if ((runState & TERMINATED) == 0) {
                    rs = lockRunState();          // done
                    unlockRunState(rs, (rs & ~RSLOCK) | TERMINATED);
                    synchronized (this) { notifyAll(); } // for awaitTermination
                }
                break;
            }
            for (int i = 0; i <= m; ++i) {
                if ((w = ws[i]) != null) {
                    checkSum += w.base;
                    w.qlock = -1;                 // try to disable
                    if (pass > 0) {
                        w.cancelAll();            // clear queue
                        if (pass > 1 && (wt = w.owner) != null) {
                            if (!wt.isInterrupted()) {
                                try {             // unblock join
                                    wt.interrupt();
                                } catch (Throwable ignore) {
                                }
                            }
                            if (w.scanState < 0)
                                U.unpark(wt);     // wake up
                        }
                    }
                }
            }
            if (checkSum != oldSum) {             // unstable
                oldSum = checkSum;
                pass = 0;
            }
            else if (pass > 3 && pass > m)        // can't further help
                break;
            else if (++pass > 1) {                // try to dequeue
                long c; int j = 0, sp;            // bound attempts
                while (j++ <= m && (sp = (int)(c = ctl)) != 0)
                    tryRelease(c, ws[sp & m], AC_UNIT);
            }
        }
        return true;
    }

    // External operations

    /**
     * Full version of externalPush, handling uncommon cases, as well
     * as performing secondary initialization upon the first
     * submission of the first task to the pool.  It also detects
     * first submission by an external thread and creates a new shared
     * queue if the one at index if empty or contended.
     *
     * @param task the task. Caller must ensure non-null.
     */
    /**
     * 如果第一次提交(或者是hash之后的队列还未初始化),调用externalSubmit
     * externalSubmit是externalPush的完整版本，主要用于第一次提交任务时(初始化workQueues及相关属性，
     * 并且提交给定任务到队列中)。具体执行步骤如下：
     *
     * 1.如果池为终止状态(runState<0)，调用tryTerminate来终止线程池，并抛出任务拒绝异常；
     * 2.如果尚未初始化，就为 FJP 执行初始化操作：初始化stealCounter、创建workerQueues，然后继续自旋；
     * 3.初始化完成后，执行在externalPush中相同的操作：获取 workQueue，放入指定任务。任务提交成功后调用signalWork方法创建或激活线程；
     * 4.如果在步骤3中获取到的 workQueue 为null，会在这一步中创建一个 workQueue，创建成功继续自旋执行第三步操作；
     * 5.如果非上述情况，或者有线程争用资源导致获取锁失败，就重新获取线程探针值继续自旋。
     * @param task
     */
    private void externalSubmit(ForkJoinTask<?> task) {
        int r;                                    // initialize caller's probe
        //初始化调用线程的探针值，用于计算WorkQueue索引
        if ((r = ThreadLocalRandom.getProbe()) == 0) {
            ThreadLocalRandom.localInit();
            r = ThreadLocalRandom.getProbe();
        }
        for (;;) {
            WorkQueue[] ws; WorkQueue q; int rs, m, k;
            boolean move = false;
            if ((rs = runState) < 0) {
                //如果线程池已经关闭
                tryTerminate(false, false);     // help terminate
                throw new RejectedExecutionException();
            }
            //如果当前运行状态 还没有started，//初始化workQueues
            else if ((rs & STARTED) == 0 ||     // initialize
                    ((ws = workQueues) == null || (m = ws.length - 1) < 0)) {
                int ns = 0;
                rs = lockRunState(); //锁定runState
                try {
                    if ((rs & STARTED) == 0) {
                        U.compareAndSwapObject(this, STEALCOUNTER, null,
                                new AtomicLong());
                        //创建workQueues，容量为2的幂次方
                        // create workQueues array with size a power of two
                        //并行度
                        int p = config & SMASK; // ensure at least 2 slots
                        //找到第一个大于等于p的2的幂次方
                        int n = (p > 1) ? p - 1 : 1;
                        n |= n >>> 1; n |= n >>> 2;  n |= n >>> 4;
                        n |= n >>> 8; n |= n >>> 16;
                        //在第一个 大于n的2的幂次方 上 再乘以2
                        n = (n + 1) << 1;
                        workQueues = new WorkQueue[n];
                        ns = STARTED;
                    }
                } finally {
                    //解锁
                    unlockRunState(rs, (rs & ~RSLOCK) | ns);
                }
            }
            else if ((q = ws[k = r & m & SQMASK]) != null) { //获取随机偶数槽位的workQueue
                //如果当前已经started
                if (q.qlock == 0 && U.compareAndSwapInt(q, QLOCK, 0, 1)) {
                    // 获取qLock锁
                    ForkJoinTask<?>[] a = q.array; //当前workQueue的全部任务
                    int s = q.top;
                    boolean submitted = false; // initial submission or resizing 是否成功翻入task
                    try {                      // locked version of push
                        if ((a != null && a.length > s + 1 - q.base) /*队列还有可以存放的位置 或者 扩容成功*/ ||
                                (a = q.growArray()) != null) { //扩容
                            int j = (((a.length - 1) & s) << ASHIFT) + ABASE; //获取可以放入task的地址
                            U.putOrderedObject(a, j, task);  //放入给定任务
                            U.putOrderedInt(q, QTOP, s + 1); //更新栈顶指针
                            submitted = true;
                        }
                    } finally {
                        U.compareAndSwapInt(q, QLOCK, 1, 0);
                    }
                    if (submitted) { //任务提交成功，创建或激活工作线程
                        signalWork(ws, q); //创建或激活一个工作线程来运行任务
                        return;
                    }
                }
                move = true;                   // move on failure 操作失败，重新获取探针值
            }
            else if (((rs = runState) & RSLOCK) == 0) { // create new queue // create new queue
                // 如果随机的偶数槽位没有workqueue，则创建
                q = new WorkQueue(this, null);
                q.hint = r;
                q.config = k | SHARED_QUEUE;
                q.scanState = INACTIVE;
                rs = lockRunState();           // publish index
                if (rs > 0 &&  (ws = workQueues) != null &&
                        k < ws.length && ws[k] == null)
                    ws[k] = q;                 // else terminated
                unlockRunState(rs, rs & ~RSLOCK);
            }
            else
                move = true;                   // move if busy
            if (move)
                r = ThreadLocalRandom.advanceProbe(r);
        }
    }

    /**
     * Tries to add the given task to a submission queue at
     * submitter's current queue. Only the (vastly) most common path
     * is directly handled in this method, while screening for need
     * for externalSubmit.
     *
     * @param task the task. Caller must ensure non-null.
     */
    //添加给定任务到submission队列中
    //流程：首先找到一个随机偶数槽位的 workQueue，然后把任务放入这个 workQueue 的任务数组中，
    // 并更新top位。如果队列的剩余任务数小于1，则尝试创建或激活一个工作线程来运行任务
    // （防止在externalSubmit初始化时发生异常导致工作线程创建失败）。
    /*
    如果第一次提交(或者是hash之后的队列还未初始化),调用externalSubmit

    第一遍循环: (runState不是开始状态): 1.lock; 2.创建数组WorkQueue[n]，这里的n是power of 2; 3. runState设置为开始状态。
    第二遍循环:(根据ThreadLocalRandom.getProbe()hash后的数组中相应位置的WorkQueue未初始化): 初始化WorkQueue,通过这种方式创立的WorkQueue均是SHARED_QUEUE,scanState为INACTIVE
    第三遍循环: 找到刚刚创建的WorkQueue,lock住队列,将数据塞到arraytop位置。如果添加成功，就用调用接下来要摊开讲的重要的方法signalWork。
    如果hash之后的队列已经存在

    lock住队列,将数据塞到top位置。如果该队列任务很少(n <= 1)也会调用signalWork
     */
    final void externalPush(ForkJoinTask<?> task) {
        WorkQueue[] ws; WorkQueue q; int m;
        //取得探测数r(线程私有的threadLocalRandomProbe)
        int r = ThreadLocalRandom.getProbe();
        //分别、ps(执行器私有的plock)、ws(执行器私有的任务队列)
        int rs = runState;
        if ((ws = workQueues) != null && (m = (ws.length - 1)) >= 0 &&
                (q = ws[m & r & SQMASK]) != null && r != 0 && rs > 0 && //获取随机偶数槽位的workQueue
                U.compareAndSwapInt(q, QLOCK, 0, 1)) { //锁定对应的workQueue
            ForkJoinTask<?>[] a; int am, n, s;
            if ((a = q.array) != null &&
                    (am = a.length - 1) > (n = (s = q.top) - q.base)) {
                int j = ((am & s) << ASHIFT) + ABASE; //计算任务索引位置
                U.putOrderedObject(a, j, task); //任务入列
                U.putOrderedInt(q, QTOP, s + 1); //更新push slot
                U.putIntVolatile(q, QLOCK, 0); //解除锁定
                if (n <= 1)
                    signalWork(ws, q); //首次提交时创建或激活一个工作线程
                return;
            }
            U.compareAndSwapInt(q, QLOCK, 1, 0); //解除锁定
        }
        externalSubmit(task); //如果forkjoinpool还没有初始化或者 对应槽位的workQueue还没有被创建，则调用 externalSubmit
    }

    /**
     * Returns common pool queue for an external thread.
     */
    static WorkQueue commonSubmitterQueue() {
        ForkJoinPool p = common;
        int r = ThreadLocalRandom.getProbe();
        WorkQueue[] ws; int m;
        return (p != null && (ws = p.workQueues) != null &&
                (m = ws.length - 1) >= 0) ?
                ws[m & r & SQMASK] : null;
    }

    /**
     * Performs tryUnpush for an external submitter: Finds queue,
     * locks if apparently non-empty, validates upon locking, and
     * adjusts top. Each check can fail but rarely does.
     */
    //为外部提交者提供 tryUnpush 功能（给定任务在top位时弹出任务）
    //tryExternalUnpush的作用就是判断当前任务是否在top位，如果是则弹出任务，
    // 然后在externalAwaitDone中调用doExec()执行任务。
    final boolean tryExternalUnpush(ForkJoinTask<?> task) {
        WorkQueue[] ws; WorkQueue w; ForkJoinTask<?>[] a; int m, s;
        int r = ThreadLocalRandom.getProbe();
        if ((ws = workQueues) != null && (m = ws.length - 1) >= 0 &&
                (w = ws[m & r & SQMASK]) != null &&
                (a = w.array) != null && (s = w.top) != w.base) {
            long j = (((a.length - 1) & (s - 1)) << ASHIFT) + ABASE; //取top位任务
            if (U.compareAndSwapInt(w, QLOCK, 0, 1)) { //加锁
                if (w.top == s && w.array == a &&
                        U.getObject(a, j) == task &&
                        U.compareAndSwapObject(a, j, task, null)) { //符合条件，弹出
                    U.putOrderedInt(w, QTOP, s - 1); //更新top
                    U.putOrderedInt(w, QLOCK, 0); //解锁，返回true
                    return true; //当前任务不在top位，解锁返回false
                }
                U.compareAndSwapInt(w, QLOCK, 1, 0);
            }
        }
        return false;
    }

    /**
     * Performs helpComplete for an external submitter.
     */
    //
    final int externalHelpComplete(CountedCompleter<?> task, int maxTasks) {
        WorkQueue[] ws; int n;
        int r = ThreadLocalRandom.getProbe();
        return ((ws = workQueues) == null || (n = ws.length) == 0) ? 0 :
                helpComplete(ws[(n - 1) & r & SQMASK], task, maxTasks);
    }

    // Exported methods

    // Constructors

    /**
     * Creates a {@code ForkJoinPool} with parallelism equal to {@link
     * java.lang.Runtime#availableProcessors}, using the {@linkplain
     * #defaultForkJoinWorkerThreadFactory default thread factory},
     * no UncaughtExceptionHandler, and non-async LIFO processing mode.
     *
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public ForkJoinPool() {
        this(Math.min(MAX_CAP, Runtime.getRuntime().availableProcessors()),
                defaultForkJoinWorkerThreadFactory, null, false);
    }

    /**
     * Creates a {@code ForkJoinPool} with the indicated parallelism
     * level, the {@linkplain
     * #defaultForkJoinWorkerThreadFactory default thread factory},
     * no UncaughtExceptionHandler, and non-async LIFO processing mode.
     *
     * @param parallelism the parallelism level
     * @throws IllegalArgumentException if parallelism less than or
     *         equal to zero, or greater than implementation limit
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public ForkJoinPool(int parallelism) {
        this(parallelism, defaultForkJoinWorkerThreadFactory, null, false);
    }

    /**
     * Creates a {@code ForkJoinPool} with the given parameters.
     *
     * @param parallelism the parallelism level. For default value,
     * use {@link java.lang.Runtime#availableProcessors}.
     * @param factory the factory for creating new threads. For default value,
     * use {@link #defaultForkJoinWorkerThreadFactory}.
     * @param handler the handler for internal worker threads that
     * terminate due to unrecoverable errors encountered while executing
     * tasks. For default value, use {@code null}.
     * @param asyncMode if true,
     * establishes local first-in-first-out scheduling mode for forked
     * tasks that are never joined. This mode may be more appropriate
     * than default locally stack-based mode in applications in which
     * worker threads only process event-style asynchronous tasks.
     * For default value, use {@code false}.
     * @throws IllegalArgumentException if parallelism less than or
     *         equal to zero, or greater than implementation limit
     * @throws NullPointerException if the factory is null
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    /**
     * 工作线程在处理本地任务时也使用 FIFO 顺序。这种模式下的 ForkJoinPool
     *      * 更接近于是一个消息队列，而不是用来处理递归式的任务
     * @param parallelism 并行度，默认为CPU数，最小为1
     * @param factory 工作线程工厂
     * @param handler 处理工作线程运行任务时的异常情况类，默认为null
     * @param asyncMode todo 是否为异步模式，默认为 false。如果为true，表示子任务的执行遵循 FIFO 顺序并且任务不能被合并（join），
     *                  这种模式适用于工作线程只运行事件类型的异步任务
     */
    public ForkJoinPool(int parallelism,
                        ForkJoinWorkerThreadFactory factory,
                        UncaughtExceptionHandler handler,
                        boolean asyncMode) {
        this(checkParallelism(parallelism),
                checkFactory(factory),
                handler,
                //asyncMode为true，任务队列FIFO;为false，任务队列LIFO
                asyncMode ? FIFO_QUEUE : LIFO_QUEUE,
                "ForkJoinPool-" + nextPoolId() + "-worker-");
        checkPermission();
    }

    //判断创建worker数量是否在[0,MAX_CAP)范围内
    private static int checkParallelism(int parallelism) {
        if (parallelism <= 0 || parallelism > MAX_CAP)
            throw new IllegalArgumentException();
        return parallelism;
    }

    //检查ForkJoinWorkerThreadFactory是否为空
    private static ForkJoinWorkerThreadFactory checkFactory
    (ForkJoinWorkerThreadFactory factory) {
        if (factory == null)
            throw new NullPointerException();
        return factory;
    }

    /**
     * Creates a {@code ForkJoinPool} with the given parameters, without
     * any security checks or parameter validation.  Invoked directly by
     * makeCommonPool.
     */
    //parallelism默认是cpu核心数，ForkJoinPool里线程数量依据于它，
    // 但不表示最大线程数，不要等同于ThreadPoolExecutor里的corePoolSize或者maximumPoolSize。
    private ForkJoinPool(int parallelism,
                         ForkJoinWorkerThreadFactory factory,
                         UncaughtExceptionHandler handler,
                         int mode,
                         String workerNamePrefix) {
        this.workerNamePrefix = workerNamePrefix;
        this.factory = factory;
        this.ueh = handler;
        this.config = (parallelism & SMASK) | mode;
        //初始化ctl
        /*
        0x xxxx-1  xxxx-2  xxxx-3  xxxx-4
        编号为1的16位: AC 表示现在获取的线程数,这里的初始化比较有技巧,使用的是并行数的相反数,这样如果active的线程数,还没到达了我们设置的阈值的时候,ctl是个负数,我们可以根据ctl的正负直观的知道现在的并行数达到阈值了么。
        编号为2的16位：TC 表示线程总量,初始值也是并行数的相反数。这里需要说明一下，这个编号1所表示的活跃的线程数的区别,我们虽然开启了并行数等量的线程,但是可能在某些条件下,运行的thread不得不wait或者park,原因我们后面会提到，这个时候，虽然我们开启的线程数量是和并行数相同，但是实际真正执行的却不是这么多。TC 记录了我们一共开启了多少线程，而AC则记录了没有挂起的线程。
        编号为3的16位：后32位标识 idle workers 前面16位第一位标识是active的还是inactive的,其他为是版本标识。
        编号为4的16位：标识idle workers 在WorkQueue[]数组中的index。这里需要说明的是,ctl的后32位其实只能表示一个idle workers，那么我们如果有很多个idle worker要怎么办呢？老爷子使用的是stack的概念来保存这些信息。后32位标识的是top的那个,我们能从top中的变量stackPred追踪到下一个idle worker
        */
        long np = (long)(-parallelism); // offset ctl counts
        //首先设置前两个编号的比特位
        this.ctl = ((np << AC_SHIFT) & AC_MASK) | ((np << TC_SHIFT) & TC_MASK);
    }

    /**
     * Returns the common pool instance. This pool is statically
     * constructed; its run state is unaffected by attempts to {@link
     * #shutdown} or {@link #shutdownNow}. However this pool and any
     * ongoing processing are automatically terminated upon program
     * {@link System#exit}.  Any program that relies on asynchronous
     * task processing to complete before program termination should
     * invoke {@code commonPool().}{@link #awaitQuiescence awaitQuiescence},
     * before exit.
     *
     * @return the common pool instance
     * @since 1.8
     */
    public static ForkJoinPool commonPool() {
        // assert common != null : "static init error";
        return common;
    }

    // Execution methods

    /**
     * Performs the given task, returning its result upon completion.
     * If the computation encounters an unchecked Exception or Error,
     * it is rethrown as the outcome of this invocation.  Rethrown
     * exceptions behave in the same way as regular exceptions, but,
     * when possible, contain stack traces (as displayed for example
     * using {@code ex.printStackTrace()}) of both the current thread
     * as well as the thread actually encountering the exception;
     * minimally only the latter.
     *
     * @param task the task
     * @param <T> the type of the task's result
     * @return the task's result
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public <T> T invoke(ForkJoinTask<T> task) {
        if (task == null)
            throw new NullPointerException();
        externalPush(task);
        return task.join();
    }

    /**
     * Arranges for (asynchronous) execution of the given task.
     *
     * @param task the task
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public void execute(ForkJoinTask<?> task) {
        if (task == null)
            throw new NullPointerException();
        externalPush(task);
    }

    // AbstractExecutorService methods

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public void execute(Runnable task) {
        if (task == null)
            throw new NullPointerException();
        ForkJoinTask<?> job;
        if (task instanceof ForkJoinTask<?>) // avoid re-wrap
            job = (ForkJoinTask<?>) task;
        else
            job = new ForkJoinTask.RunnableExecuteAction(task);
        externalPush(job);
    }

    /**
     * Submits a ForkJoinTask for execution.
     *
     * @param task the task to submit
     * @param <T> the type of the task's result
     * @return the task
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public <T> ForkJoinTask<T> submit(ForkJoinTask<T> task) {
        if (task == null)
            throw new NullPointerException();
        externalPush(task);
        return task;
    }

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public <T> ForkJoinTask<T> submit(Callable<T> task) {
        ForkJoinTask<T> job = new ForkJoinTask.AdaptedCallable<T>(task);
        externalPush(job);
        return job;
    }

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public <T> ForkJoinTask<T> submit(Runnable task, T result) {
        ForkJoinTask<T> job = new ForkJoinTask.AdaptedRunnable<T>(task, result);
        externalPush(job);
        return job;
    }

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public ForkJoinTask<?> submit(Runnable task) {
        if (task == null)
            throw new NullPointerException();
        ForkJoinTask<?> job;
        if (task instanceof ForkJoinTask<?>) // avoid re-wrap
            job = (ForkJoinTask<?>) task;
        else
            job = new ForkJoinTask.AdaptedRunnableAction(task);
        externalPush(job);
        return job;
    }

    /**
     * @throws NullPointerException       {@inheritDoc}
     * @throws RejectedExecutionException {@inheritDoc}
     */
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
        // In previous versions of this class, this method constructed
        // a task to run ForkJoinTask.invokeAll, but now external
        // invocation of multiple tasks is at least as efficient.
        ArrayList<Future<T>> futures = new ArrayList<>(tasks.size());

        boolean done = false;
        try {
            for (Callable<T> t : tasks) {
                ForkJoinTask<T> f = new ForkJoinTask.AdaptedCallable<T>(t);
                futures.add(f);
                externalPush(f);
            }
            for (int i = 0, size = futures.size(); i < size; i++)
                ((ForkJoinTask<?>)futures.get(i)).quietlyJoin();
            done = true;
            return futures;
        } finally {
            if (!done)
                for (int i = 0, size = futures.size(); i < size; i++)
                    futures.get(i).cancel(false);
        }
    }

    /**
     * Returns the factory used for constructing new workers.
     *
     * @return the factory used for constructing new workers
     */
    public ForkJoinWorkerThreadFactory getFactory() {
        return factory;
    }

    /**
     * Returns the handler for internal worker threads that terminate
     * due to unrecoverable errors encountered while executing tasks.
     *
     * @return the handler, or {@code null} if none
     */
    public UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return ueh;
    }

    /**
     * Returns the targeted parallelism level of this pool.
     *
     * @return the targeted parallelism level of this pool
     */
    public int getParallelism() {
        int par;
        return ((par = config & SMASK) > 0) ? par : 1;
    }

    /**
     * Returns the targeted parallelism level of the common pool.
     *
     * @return the targeted parallelism level of the common pool
     * @since 1.8
     */
    public static int getCommonPoolParallelism() {
        return commonParallelism;
    }

    /**
     * Returns the number of worker threads that have started but not
     * yet terminated.  The result returned by this method may differ
     * from {@link #getParallelism} when threads are created to
     * maintain parallelism when others are cooperatively blocked.
     *
     * @return the number of worker threads
     */
    public int getPoolSize() {
        return (config & SMASK) + (short)(ctl >>> TC_SHIFT);
    }

    /**
     * Returns {@code true} if this pool uses local first-in-first-out
     * scheduling mode for forked tasks that are never joined.
     *
     * @return {@code true} if this pool uses async mode
     */
    public boolean getAsyncMode() {
        return (config & FIFO_QUEUE) != 0;
    }

    /**
     * Returns an estimate of the number of worker threads that are
     * not blocked waiting to join tasks or for other managed
     * synchronization. This method may overestimate the
     * number of running threads.
     *
     * @return the number of worker threads
     */
    public int getRunningThreadCount() {
        int rc = 0;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 1; i < ws.length; i += 2) {
                if ((w = ws[i]) != null && w.isApparentlyUnblocked())
                    ++rc;
            }
        }
        return rc;
    }

    /**
     * Returns an estimate of the number of threads that are currently
     * stealing or executing tasks. This method may overestimate the
     * number of active threads.
     *
     * @return the number of active threads
     */
    public int getActiveThreadCount() {
        int r = (config & SMASK) + (int)(ctl >> AC_SHIFT);
        return (r <= 0) ? 0 : r; // suppress momentarily negative values
    }

    /**
     * Returns {@code true} if all worker threads are currently idle.
     * An idle worker is one that cannot obtain a task to execute
     * because none are available to steal from other threads, and
     * there are no pending submissions to the pool. This method is
     * conservative; it might not return {@code true} immediately upon
     * idleness of all threads, but will eventually become true if
     * threads remain inactive.
     *
     * @return {@code true} if all threads are currently idle
     */
    public boolean isQuiescent() {
        return (config & SMASK) + (int)(ctl >> AC_SHIFT) <= 0;
    }

    /**
     * Returns an estimate of the total number of tasks stolen from
     * one thread's work queue by another. The reported value
     * underestimates the actual total number of steals when the pool
     * is not quiescent. This value may be useful for monitoring and
     * tuning fork/join programs: in general, steal counts should be
     * high enough to keep threads busy, but low enough to avoid
     * overhead and contention across threads.
     *
     * @return the number of steals
     */
    public long getStealCount() {
        AtomicLong sc = stealCounter;
        long count = (sc == null) ? 0L : sc.get();
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 1; i < ws.length; i += 2) {
                if ((w = ws[i]) != null)
                    count += w.nsteals;
            }
        }
        return count;
    }

    /**
     * Returns an estimate of the total number of tasks currently held
     * in queues by worker threads (but not including tasks submitted
     * to the pool that have not begun executing). This value is only
     * an approximation, obtained by iterating across all threads in
     * the pool. This method may be useful for tuning task
     * granularities.
     *
     * @return the number of queued tasks
     */
    public long getQueuedTaskCount() {
        long count = 0;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 1; i < ws.length; i += 2) {
                if ((w = ws[i]) != null)
                    count += w.queueSize();
            }
        }
        return count;
    }

    /**
     * Returns an estimate of the number of tasks submitted to this
     * pool that have not yet begun executing.  This method may take
     * time proportional to the number of submissions.
     *
     * @return the number of queued submissions
     */
    public int getQueuedSubmissionCount() {
        int count = 0;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; i += 2) {
                if ((w = ws[i]) != null)
                    count += w.queueSize();
            }
        }
        return count;
    }

    /**
     * Returns {@code true} if there are any tasks submitted to this
     * pool that have not yet begun executing.
     *
     * @return {@code true} if there are any queued submissions
     */
    public boolean hasQueuedSubmissions() {
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; i += 2) {
                if ((w = ws[i]) != null && !w.isEmpty())
                    return true;
            }
        }
        return false;
    }

    /**
     * Removes and returns the next unexecuted submission if one is
     * available.  This method may be useful in extensions to this
     * class that re-assign work in systems with multiple pools.
     *
     * @return the next submission, or {@code null} if none
     */
    protected ForkJoinTask<?> pollSubmission() {
        WorkQueue[] ws; WorkQueue w; ForkJoinTask<?> t;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; i += 2) {
                if ((w = ws[i]) != null && (t = w.poll()) != null)
                    return t;
            }
        }
        return null;
    }

    /**
     * Removes all available unexecuted submitted and forked tasks
     * from scheduling queues and adds them to the given collection,
     * without altering their execution status. These may include
     * artificially generated or wrapped tasks. This method is
     * designed to be invoked only when the pool is known to be
     * quiescent. Invocations at other times may not remove all
     * tasks. A failure encountered while attempting to add elements
     * to collection {@code c} may result in elements being in
     * neither, either or both collections when the associated
     * exception is thrown.  The behavior of this operation is
     * undefined if the specified collection is modified while the
     * operation is in progress.
     *
     * @param c the collection to transfer elements into
     * @return the number of elements transferred
     */
    protected int drainTasksTo(Collection<? super ForkJoinTask<?>> c) {
        int count = 0;
        WorkQueue[] ws; WorkQueue w; ForkJoinTask<?> t;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; ++i) {
                if ((w = ws[i]) != null) {
                    while ((t = w.poll()) != null) {
                        c.add(t);
                        ++count;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Returns a string identifying this pool, as well as its state,
     * including indications of run state, parallelism level, and
     * worker and task counts.
     *
     * @return a string identifying this pool, as well as its state
     */
    public String toString() {
        // Use a single pass through workQueues to collect counts
        long qt = 0L, qs = 0L; int rc = 0;
        AtomicLong sc = stealCounter;
        long st = (sc == null) ? 0L : sc.get();
        long c = ctl;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; ++i) {
                if ((w = ws[i]) != null) {
                    int size = w.queueSize();
                    if ((i & 1) == 0)
                        qs += size;
                    else {
                        qt += size;
                        st += w.nsteals;
                        if (w.isApparentlyUnblocked())
                            ++rc;
                    }
                }
            }
        }
        int pc = (config & SMASK);
        int tc = pc + (short)(c >>> TC_SHIFT);
        int ac = pc + (int)(c >> AC_SHIFT);
        if (ac < 0) // ignore transient negative
            ac = 0;
        int rs = runState;
        String level = ((rs & TERMINATED) != 0 ? "Terminated" :
                (rs & STOP)       != 0 ? "Terminating" :
                        (rs & SHUTDOWN)   != 0 ? "Shutting down" :
                                "Running");
        return super.toString() +
                "[" + level +
                ", parallelism = " + pc +
                ", size = " + tc +
                ", active = " + ac +
                ", running = " + rc +
                ", steals = " + st +
                ", tasks = " + qt +
                ", submissions = " + qs +
                "]";
    }

    /**
     * Possibly initiates an orderly shutdown in which previously
     * submitted tasks are executed, but no new tasks will be
     * accepted. Invocation has no effect on execution state if this
     * is the {@link #commonPool()}, and no additional effect if
     * already shut down.  Tasks that are in the process of being
     * submitted concurrently during the course of this method may or
     * may not be rejected.
     *
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public void shutdown() {
        checkPermission();
        tryTerminate(false, true);
    }

    /**
     * Possibly attempts to cancel and/or stop all tasks, and reject
     * all subsequently submitted tasks.  Invocation has no effect on
     * execution state if this is the {@link #commonPool()}, and no
     * additional effect if already shut down. Otherwise, tasks that
     * are in the process of being submitted or executed concurrently
     * during the course of this method may or may not be
     * rejected. This method cancels both existing and unexecuted
     * tasks, in order to permit termination in the presence of task
     * dependencies. So the method always returns an empty list
     * (unlike the case for some other Executors).
     *
     * @return an empty list
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public List<Runnable> shutdownNow() {
        checkPermission();
        tryTerminate(true, true);
        return Collections.emptyList();
    }

    /**
     * Returns {@code true} if all tasks have completed following shut down.
     *
     * @return {@code true} if all tasks have completed following shut down
     */
    public boolean isTerminated() {
        return (runState & TERMINATED) != 0;
    }

    /**
     * Returns {@code true} if the process of termination has
     * commenced but not yet completed.  This method may be useful for
     * debugging. A return of {@code true} reported a sufficient
     * period after shutdown may indicate that submitted tasks have
     * ignored or suppressed interruption, or are waiting for I/O,
     * causing this executor not to properly terminate. (See the
     * advisory notes for class {@link ForkJoinTask} stating that
     * tasks should not normally entail blocking operations.  But if
     * they do, they must abort them on interrupt.)
     *
     * @return {@code true} if terminating but not yet terminated
     */
    public boolean isTerminating() {
        int rs = runState;
        return (rs & STOP) != 0 && (rs & TERMINATED) == 0;
    }

    /**
     * Returns {@code true} if this pool has been shut down.
     *
     * @return {@code true} if this pool has been shut down
     */
    public boolean isShutdown() {
        return (runState & SHUTDOWN) != 0;
    }

    /**
     * Blocks until all tasks have completed execution after a
     * shutdown request, or the timeout occurs, or the current thread
     * is interrupted, whichever happens first. Because the {@link
     * #commonPool()} never terminates until program shutdown, when
     * applied to the common pool, this method is equivalent to {@link
     * #awaitQuiescence(long, TimeUnit)} but always returns {@code false}.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if this executor terminated and
     *         {@code false} if the timeout elapsed before termination
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (this == common) {
            awaitQuiescence(timeout, unit);
            return false;
        }
        long nanos = unit.toNanos(timeout);
        if (isTerminated())
            return true;
        if (nanos <= 0L)
            return false;
        long deadline = System.nanoTime() + nanos;
        synchronized (this) {
            for (;;) {
                if (isTerminated())
                    return true;
                if (nanos <= 0L)
                    return false;
                long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
                wait(millis > 0L ? millis : 1L);
                nanos = deadline - System.nanoTime();
            }
        }
    }

    /**
     * If called by a ForkJoinTask operating in this pool, equivalent
     * in effect to {@link ForkJoinTask#helpQuiesce}. Otherwise,
     * waits and/or attempts to assist performing tasks until this
     * pool {@link #isQuiescent} or the indicated timeout elapses.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if quiescent; {@code false} if the
     * timeout elapsed.
     */
    public boolean awaitQuiescence(long timeout, TimeUnit unit) {
        long nanos = unit.toNanos(timeout);
        ForkJoinWorkerThread wt;
        Thread thread = Thread.currentThread();
        if ((thread instanceof ForkJoinWorkerThread) &&
                (wt = (ForkJoinWorkerThread)thread).pool == this) {
            helpQuiescePool(wt.workQueue);
            return true;
        }
        long startTime = System.nanoTime();
        WorkQueue[] ws;
        int r = 0, m;
        boolean found = true;
        while (!isQuiescent() && (ws = workQueues) != null &&
                (m = ws.length - 1) >= 0) {
            if (!found) {
                if ((System.nanoTime() - startTime) > nanos)
                    return false;
                Thread.yield(); // cannot block
            }
            found = false;
            for (int j = (m + 1) << 2; j >= 0; --j) {
                ForkJoinTask<?> t; WorkQueue q; int b, k;
                if ((k = r++ & m) <= m && k >= 0 && (q = ws[k]) != null &&
                        (b = q.base) - q.top < 0) {
                    found = true;
                    if ((t = q.pollAt(b)) != null)
                        t.doExec();
                    break;
                }
            }
        }
        return true;
    }

    /**
     * Waits and/or attempts to assist performing tasks indefinitely
     * until the {@link #commonPool()} {@link #isQuiescent}.
     */
    static void quiesceCommonPool() {
        common.awaitQuiescence(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    /**
     * Interface for extending managed parallelism for tasks running
     * in {@link ForkJoinPool}s.
     *
     * <p>A {@code ManagedBlocker} provides two methods.  Method
     * {@link #isReleasable} must return {@code true} if blocking is
     * not necessary. Method {@link #block} blocks the current thread
     * if necessary (perhaps internally invoking {@code isReleasable}
     * before actually blocking). These actions are performed by any
     * thread invoking {@link ForkJoinPool#managedBlock(ManagedBlocker)}.
     * The unusual methods in this API accommodate synchronizers that
     * may, but don't usually, block for long periods. Similarly, they
     * allow more efficient internal handling of cases in which
     * additional workers may be, but usually are not, needed to
     * ensure sufficient parallelism.  Toward this end,
     * implementations of method {@code isReleasable} must be amenable
     * to repeated invocation.
     *用于扩展在ForkJoinPools中运行的任务的并行性管理的接口。ManagedBlocker提供了两种方法。
     * 如果阻塞是不必要的，方法isReleasable（）必须返回true。 方法block（）根据需要阻塞
     * 当前线程（也许在实际阻塞之前内部调用isReleasable）。 这些操作由调用
     * ForkJoinPool.managedBlock（ManagedBlocker）的任何线程执行。 这个API中不寻常的方法
     * 可以容纳同步器，但是通常不会长时间阻塞。 同样，它们可以更有效地处理那些可能需要
     * 额外workers(通常不需要)来确保足够的并行性的情境。
     * 为此，方法isReleasable 的实现必须适合重复调用。
     * <p>For example, here is a ManagedBlocker based on a
     * ReentrantLock:
     *  <pre> {@code
     * class ManagedLocker implements ManagedBlocker {
     *   final ReentrantLock lock;
     *   boolean hasLock = false;
     *   ManagedLocker(ReentrantLock lock) { this.lock = lock; }
     *   public boolean block() {
     *     if (!hasLock)
     *       lock.lock();
     *     return true;
     *   }
     *   public boolean isReleasable() {
     *     return hasLock || (hasLock = lock.tryLock());
     *   }
     * }}</pre>
     *
     * <p>Here is a class that possibly blocks waiting for an
     * item on a given queue:
     *  <pre> {@code
     * class QueueTaker<E> implements ManagedBlocker {
     *   final BlockingQueue<E> queue;
     *   volatile E item = null;
     *   QueueTaker(BlockingQueue<E> q) { this.queue = q; }
     *   public boolean block() throws InterruptedException {
     *     if (item == null)
     *       item = queue.take();
     *     return true;
     *   }
     *   public boolean isReleasable() {
     *     return item != null || (item = queue.poll()) != null;
     *   }
     *   public E getItem() { // call after pool.managedBlock completes
     *     return item;
     *   }
     * }}</pre>
     */
    public static interface ManagedBlocker {
        /**
         * Possibly blocks the current thread, for example waiting for
         * a lock or condition.
         *P 可能阻塞当前线程，例如等待锁或者是条件变量
         * @return {@code true} if no additional blocking is necessary
         * (i.e., if isReleasable would return true)
         * @throws InterruptedException if interrupted while waiting
         * (the method is not required to do so, but is allowed to)
         */
        boolean block() throws InterruptedException;

        /**
         * Returns {@code true} if blocking is unnecessary. 阻塞是否有必要
         * @return {@code true} if blocking is unnecessary
         */
        boolean isReleasable();
    }

    /**
     * Runs the given possibly blocking task.  When {@linkplain
     * ForkJoinTask#inForkJoinPool() running in a ForkJoinPool}, this
     * method possibly arranges for a spare thread to be activated if
     * necessary to ensure sufficient parallelism while the current
     * thread is blocked in {@link ManagedBlocker#block blocker.block()}.
     *
     * <p>This method repeatedly calls {@code blocker.isReleasable()} and
     * {@code blocker.block()} until either method returns {@code true}.
     * Every call to {@code blocker.block()} is preceded by a call to
     * {@code blocker.isReleasable()} that returned {@code false}.
     *
     * <p>If not running in a ForkJoinPool, this method is
     * behaviorally equivalent to
     *  <pre> {@code
     * while (!blocker.isReleasable())
     *   if (blocker.block())
     *     break;}</pre>
     *
     * If running in a ForkJoinPool, the pool may first be expanded to
     * ensure sufficient parallelism available during the call to
     * {@code blocker.block()}.
     *
     * @param blocker the blocker task
     * @throws InterruptedException if {@code blocker.block()} did so
     */
    public static void managedBlock(ManagedBlocker blocker)
            throws InterruptedException {
        ForkJoinPool p;
        ForkJoinWorkerThread wt;
        Thread t = Thread.currentThread();
        if ((t instanceof ForkJoinWorkerThread) &&
                (p = (wt = (ForkJoinWorkerThread)t).pool) != null) {
            WorkQueue w = wt.workQueue;
            while (!blocker.isReleasable()) {
                if (p.tryCompensate(w)) {
                    try {
                        do {} while (!blocker.isReleasable() &&
                                !blocker.block());
                    } finally {
                        U.getAndAddLong(p, CTL, AC_UNIT);
                    }
                    break;
                }
            }
        }
        else {
            do {} while (!blocker.isReleasable() &&
                    !blocker.block());
        }
    }

    // AbstractExecutorService overrides.  These rely on undocumented
    // fact that ForkJoinTask.adapt returns ForkJoinTasks that also
    // implement RunnableFuture.

    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new ForkJoinTask.AdaptedRunnable<T>(runnable, value);
    }

    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new ForkJoinTask.AdaptedCallable<T>(callable);
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe U;
    private static final int  ABASE; // forkjoinpool数组的第一个元素的地址
    private static final int  ASHIFT;
    private static final long CTL;
    private static final long RUNSTATE;
    private static final long STEALCOUNTER;
    private static final long PARKBLOCKER;
    private static final long QTOP;
    private static final long QLOCK;
    private static final long QSCANSTATE;
    private static final long QPARKER;
    private static final long QCURRENTSTEAL;
    private static final long QCURRENTJOIN;

    static {
        // initialize field offsets for CAS etc
        try {
            U = sun.misc.Unsafe.getUnsafe();
            Class<?> k = ForkJoinPool.class;
            CTL = U.objectFieldOffset
                    (k.getDeclaredField("ctl"));
            RUNSTATE = U.objectFieldOffset
                    (k.getDeclaredField("runState"));
            STEALCOUNTER = U.objectFieldOffset
                    (k.getDeclaredField("stealCounter"));
            Class<?> tk = Thread.class;
            PARKBLOCKER = U.objectFieldOffset
                    (tk.getDeclaredField("parkBlocker"));
            Class<?> wk = WorkQueue.class;
            QTOP = U.objectFieldOffset
                    (wk.getDeclaredField("top"));
            QLOCK = U.objectFieldOffset
                    (wk.getDeclaredField("qlock"));
            QSCANSTATE = U.objectFieldOffset
                    (wk.getDeclaredField("scanState"));
            QPARKER = U.objectFieldOffset
                    (wk.getDeclaredField("parker"));
            QCURRENTSTEAL = U.objectFieldOffset
                    (wk.getDeclaredField("currentSteal"));
            QCURRENTJOIN = U.objectFieldOffset
                    (wk.getDeclaredField("currentJoin"));
            Class<?> ak = ForkJoinTask[].class;
            ABASE = U.arrayBaseOffset(ak);
            //数组元素的长度
            int scale = U.arrayIndexScale(ak);
            if ((scale & (scale - 1)) != 0)
                throw new Error("data type scale not a power of two");
            //每个数组元素的长度 换算成 2的n次幂
            ASHIFT = 31 - Integer.numberOfLeadingZeros(scale);
        } catch (Exception e) {
            throw new Error(e);
        }

        commonMaxSpares = DEFAULT_COMMON_MAX_SPARES;
        defaultForkJoinWorkerThreadFactory =
                new DefaultForkJoinWorkerThreadFactory();
        modifyThreadPermission = new RuntimePermission("modifyThread");

        common = java.security.AccessController.doPrivileged
                (new java.security.PrivilegedAction<ForkJoinPool>() {
                    public ForkJoinPool run() { return makeCommonPool(); }});
        int par = common.config & SMASK; // report 1 even if threads disabled
        commonParallelism = par > 0 ? par : 1;
    }

    /**
     * Creates and returns the common pool, respecting user settings
     * specified via system properties.
     */
    private static ForkJoinPool makeCommonPool() {
        int parallelism = -1;
        ForkJoinWorkerThreadFactory factory = null;
        UncaughtExceptionHandler handler = null;
        try {  // ignore exceptions in accessing/parsing properties
            String pp = System.getProperty
                    ("java.util.concurrent.ForkJoinPool.common.parallelism"); //并行度
            String fp = System.getProperty
                    ("java.util.concurrent.ForkJoinPool.common.threadFactory"); //线程工厂
            String hp = System.getProperty
                    ("java.util.concurrent.ForkJoinPool.common.exceptionHandler");//异常处理类
            if (pp != null)
                parallelism = Integer.parseInt(pp);
            if (fp != null)
                factory = ((ForkJoinWorkerThreadFactory)ClassLoader.
                        getSystemClassLoader().loadClass(fp).newInstance());
            if (hp != null)
                handler = ((UncaughtExceptionHandler)ClassLoader.
                        getSystemClassLoader().loadClass(hp).newInstance());
        } catch (Exception ignore) {
        }
        if (factory == null) {
            if (System.getSecurityManager() == null)
                factory = defaultForkJoinWorkerThreadFactory;
            else // use security-managed default
                factory = new InnocuousForkJoinWorkerThreadFactory();
        }
        if (parallelism < 0 && // default 1 less than #cores
                (parallelism = Runtime.getRuntime().availableProcessors() - 1) <= 0)
            parallelism = 1;
        if (parallelism > MAX_CAP)
            parallelism = MAX_CAP;
        return new ForkJoinPool(parallelism, factory, handler, LIFO_QUEUE,
                "ForkJoinPool.commonPool-worker-");
    }

    /**
     * Factory for innocuous worker threads
     */
    static final class InnocuousForkJoinWorkerThreadFactory
            implements ForkJoinWorkerThreadFactory {

        /**
         * An ACC to restrict permissions for the factory itself.
         * The constructed workers have no permissions set.
         */
        private static final AccessControlContext innocuousAcc;
        static {
            Permissions innocuousPerms = new Permissions();
            innocuousPerms.add(modifyThreadPermission);
            innocuousPerms.add(new RuntimePermission(
                    "enableContextClassLoaderOverride"));
            innocuousPerms.add(new RuntimePermission(
                    "modifyThreadGroup"));
            innocuousAcc = new AccessControlContext(new ProtectionDomain[] {
                    new ProtectionDomain(null, innocuousPerms)
            });
        }

        public final ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return (ForkJoinWorkerThread.InnocuousForkJoinWorkerThread)
                    java.security.AccessController.doPrivileged(
                            new java.security.PrivilegedAction<ForkJoinWorkerThread>() {
                                public ForkJoinWorkerThread run() {
                                    return new ForkJoinWorkerThread.
                                            InnocuousForkJoinWorkerThread(pool);
                                }}, innocuousAcc);
        }
    }

}
