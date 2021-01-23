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
//http://brokendreams.iteye.com/blog/2259449
//https://www.cnblogs.com/lytwajue/p/7258278.html
package java.util.concurrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * A reusable synchronization barrier, similar in functionality to
 * {@link java.util.concurrent.CyclicBarrier CyclicBarrier} and
 * {@link java.util.concurrent.CountDownLatch CountDownLatch}
 * but supporting more flexible usage.
 *
 * <p><b>Registration.</b> Unlike the case for other barriers, the
 * number of parties <em>registered</em> to synchronize on a phaser
 * may vary over time.  Tasks may be registered at any time (using
 * methods {@link #register}, {@link #bulkRegister}, or forms of
 * constructors establishing initial numbers of parties), and
 * optionally deregistered upon any arrival (using {@link
 * #arriveAndDeregister}).  As is the case with most basic
 * synchronization constructs, registration and deregistration affect
 * only internal counts; they do not establish any further internal
 * bookkeeping, so tasks cannot query whether they are registered.
 * (However, you can introduce such bookkeeping by subclassing this
 * class.)
 *
 * <p><b>Synchronization.</b> Like a {@code CyclicBarrier}, a {@code
 * Phaser} may be repeatedly awaited.  Method {@link
 * #arriveAndAwaitAdvance} has effect analogous to {@link
 * java.util.concurrent.CyclicBarrier#await CyclicBarrier.await}. Each
 * generation of a phaser has an associated phase number. The phase
 * number starts at zero, and advances when all parties arrive at the
 * phaser, wrapping around to zero after reaching {@code
 * Integer.MAX_VALUE}. The use of phase numbers enables independent
 * control of actions upon arrival at a phaser and upon awaiting
 * others, via two kinds of methods that may be invoked by any
 * registered party:
 *
 * <ul>
 *
 *   <li> <b>Arrival.</b> Methods {@link #arrive} and
 *       {@link #arriveAndDeregister} record arrival.  These methods
 *       do not block, but return an associated <em>arrival phase
 *       number</em>; that is, the phase number of the phaser to which
 *       the arrival applied. When the final party for a given phase
 *       arrives, an optional action is performed and the phase
 *       advances.  These actions are performed by the party
 *       triggering a phase advance, and are arranged by overriding
 *       method {@link #onAdvance(int, int)}, which also controls
 *       termination. Overriding this method is similar to, but more
 *       flexible than, providing a barrier action to a {@code
 *       CyclicBarrier}.
 *
 *   <li> <b>Waiting.</b> Method {@link #awaitAdvance} requires an
 *       argument indicating an arrival phase number, and returns when
 *       the phaser advances to (or is already at) a different phase.
 *       Unlike similar constructions using {@code CyclicBarrier},
 *       method {@code awaitAdvance} continues to wait even if the
 *       waiting thread is interrupted. Interruptible and timeout
 *       versions are also available, but exceptions encountered while
 *       tasks wait interruptibly or with timeout do not change the
 *       state of the phaser. If necessary, you can perform any
 *       associated recovery within handlers of those exceptions,
 *       often after invoking {@code forceTermination}.  Phasers may
 *       also be used by tasks executing in a {@link ForkJoinPool},
 *       which will ensure sufficient parallelism to execute tasks
 *       when others are blocked waiting for a phase to advance.
 *
 * </ul>
 *
 * <p><b>Termination.</b> A phaser may enter a <em>termination</em>
 * state, that may be checked using method {@link #isTerminated}. Upon
 * termination, all synchronization methods immediately return without
 * waiting for advance, as indicated by a negative return value.
 * Similarly, attempts to register upon termination have no effect.
 * Termination is triggered when an invocation of {@code onAdvance}
 * returns {@code true}. The default implementation returns {@code
 * true} if a deregistration has caused the number of registered
 * parties to become zero.  As illustrated below, when phasers control
 * actions with a fixed number of iterations, it is often convenient
 * to override this method to cause termination when the current phase
 * number reaches a threshold. Method {@link #forceTermination} is
 * also available to abruptly release waiting threads and allow them
 * to terminate.
 *
 * <p><b>Tiering.</b> Phasers may be <em>tiered</em> (i.e.,
 * constructed in tree structures) to reduce contention. Phasers with
 * large numbers of parties that would otherwise experience heavy
 * synchronization contention costs may instead be set up so that
 * groups of sub-phasers share a common parent.  This may greatly
 * increase throughput even though it incurs greater per-operation
 * overhead.
 *
 * <p>In a tree of tiered phasers, registration and deregistration of
 * child phasers with their parent are managed automatically.
 * Whenever the number of registered parties of a child phaser becomes
 * non-zero (as established in the {@link #Phaser(Phaser,int)}
 * constructor, {@link #register}, or {@link #bulkRegister}), the
 * child phaser is registered with its parent.  Whenever the number of
 * registered parties becomes zero as the result of an invocation of
 * {@link #arriveAndDeregister}, the child phaser is deregistered
 * from its parent.
 *
 * <p><b>Monitoring.</b> While synchronization methods may be invoked
 * only by registered parties, the current state of a phaser may be
 * monitored by any caller.  At any given moment there are {@link
 * #getRegisteredParties} parties in total, of which {@link
 * #getArrivedParties} have arrived at the current phase ({@link
 * #getPhase}).  When the remaining ({@link #getUnarrivedParties})
 * parties arrive, the phase advances.  The values returned by these
 * methods may reflect transient states and so are not in general
 * useful for synchronization control.  Method {@link #toString}
 * returns snapshots of these state queries in a form convenient for
 * informal monitoring.
 *
 * <p><b>Sample usages:</b>
 *
 * <p>A {@code Phaser} may be used instead of a {@code CountDownLatch}
 * to control a one-shot action serving a variable number of parties.
 * The typical idiom is for the method setting this up to first
 * register, then start the actions, then deregister, as in:
 *
 *  <pre> {@code
 * void runTasks(List<Runnable> tasks) {
 *   final Phaser phaser = new Phaser(1); // "1" to register self
 *   // create and start threads
 *   for (final Runnable task : tasks) {
 *     phaser.register();
 *     new Thread() {
 *       public void run() {
 *         phaser.arriveAndAwaitAdvance(); // await all creation
 *         task.run();
 *       }
 *     }.start();
 *   }
 *
 *   // allow threads to start and deregister self
 *   phaser.arriveAndDeregister();
 * }}</pre>
 *
 * <p>One way to cause a set of threads to repeatedly perform actions
 * for a given number of iterations is to override {@code onAdvance}:
 *
 *  <pre> {@code
 * void startTasks(List<Runnable> tasks, final int iterations) {
 *   final Phaser phaser = new Phaser() {
 *     protected boolean onAdvance(int phase, int registeredParties) {
 *       return phase >= iterations || registeredParties == 0;
 *     }
 *   };
 *   phaser.register();
 *   for (final Runnable task : tasks) {
 *     phaser.register();
 *     new Thread() {
 *       public void run() {
 *         do {
 *           task.run();
 *           phaser.arriveAndAwaitAdvance();
 *         } while (!phaser.isTerminated());
 *       }
 *     }.start();
 *   }
 *   phaser.arriveAndDeregister(); // deregister self, don't wait
 * }}</pre>
 *
 * If the main task must later await termination, it
 * may re-register and then execute a similar loop:
 *  <pre> {@code
 *   // ...
 *   phaser.register();
 *   while (!phaser.isTerminated())
 *     phaser.arriveAndAwaitAdvance();}</pre>
 *
 * <p>Related constructions may be used to await particular phase numbers
 * in contexts where you are sure that the phase will never wrap around
 * {@code Integer.MAX_VALUE}. For example:
 *
 *  <pre> {@code
 * void awaitPhase(Phaser phaser, int phase) {
 *   int p = phaser.register(); // assumes caller not already registered
 *   while (p < phase) {
 *     if (phaser.isTerminated())
 *       // ... deal with unexpected termination
 *     else
 *       p = phaser.arriveAndAwaitAdvance();
 *   }
 *   phaser.arriveAndDeregister();
 * }}</pre>
 *
 *
 * <p>To create a set of {@code n} tasks using a tree of phasers, you
 * could use code of the following form, assuming a Task class with a
 * constructor accepting a {@code Phaser} that it registers with upon
 * construction. After invocation of {@code build(new Task[n], 0, n,
 * new Phaser())}, these tasks could then be started, for example by
 * submitting to a pool:
 *
 *  <pre> {@code
 * void build(Task[] tasks, int lo, int hi, Phaser ph) {
 *   if (hi - lo > TASKS_PER_PHASER) {
 *     for (int i = lo; i < hi; i += TASKS_PER_PHASER) {
 *       int j = Math.min(i + TASKS_PER_PHASER, hi);
 *       build(tasks, i, j, new Phaser(ph));
 *     }
 *   } else {
 *     for (int i = lo; i < hi; ++i)
 *       tasks[i] = new Task(ph);
 *       // assumes new Task(ph) performs ph.register()
 *   }
 * }}</pre>
 *
 * The best value of {@code TASKS_PER_PHASER} depends mainly on
 * expected synchronization rates. A value as low as four may
 * be appropriate for extremely small per-phase task bodies (thus
 * high rates), or up to hundreds for extremely large ones.
 *
 * <p><b>Implementation notes</b>: This implementation restricts the
 * maximum number of parties to 65535. Attempts to register additional
 * parties result in {@code IllegalStateException}. However, you can and
 * should create tiered phasers to accommodate arbitrarily large sets
 * of participants.
 *
 * @since 1.7
 * @author Doug Lea
 */
/*
Phaser是一个能够反复利用的同步栅栏。功能上与CyclicBarrier和CountDownLatch相似，
只是提供更加灵活的使用方法。也就是说，Phaser的同步模型与它们几乎相同。
一般运用的场景是一组线程希望同一时候到达某个运行点后（先到达的会被堵塞），运行一个指定任务，
然后这些线程才被唤醒继续运行其他任务。
Phaser通常是定义一个parties数（parties一般代指须要进行同步的线程）。当这些parties到达某个运行点，
就会调用await方法。表示到达某个阶段（phase），然后就会堵塞直到有足够的parites数（也就是线程数）
都调用过await方法后，这些线程才会被逐个唤醒，另外。在唤醒之前，能够选择性地运行某个定制的任务。
Phaser对照起CyclicBarrier。不仅它是能够反复同步，而且parties数是能够动态注冊的，另外还提供了
非堵塞的arrive方法表示先到达阶段等，大大提高了同步模型的灵活性，当然了，实现也会相对复杂。
注冊（Registration）
和其他栅栏不一样，在phaser上进行同步的注冊parites数可能会随着时间改变而不同。
能够在不论什么时间注冊任务（调用register，bulkRegister方法，或者能够初始化parties数的构造函数形式），
另外还能够在不论什么到达栅栏的时候反注冊（arriveAndDeregister方法）。作为大部分同步结构的基础。
注冊和反注冊仅仅会影响内部计数；它们没有做不论什么额外的内部记录，所以任务不能够查询它们是否被注冊。
（只是你能够继承这个类以引入这种记录）
同步（Synchronization）
就像CyclicBarrier一样，Phaser能够反复地等待。arriveAndAwaitAdvance方法与
CyclicBarrier.await作用类似。每一代（generation）的phaser有一个关联的阶段号（phase number）。
阶段号从0开始。当全部parties都到达阶段的时候就会加一。直到Integer.MAX_VALUE后返回0。
在到达某个阶段以及在等待其他parites的时候，通过下面两类能够被注冊过
的party调用的方法，能够独立控制阶段号的使用：
1.到达（Arrival）
arrive方法和arriveAndDeregister方法记录到达。
这些方法不会堵塞。但会返回一个关联的到达阶段号（arrival phase number）。
也就是，phaser在到达以后所用的阶段号。
当最后的party到达一个给定的阶段，就能够运行可选的操作，而且阶段号自加一。
这些操作都会被触发阶段添加的party运行。而且会被可重写方法onAdvance（管理Phaser的终结）
安排管理。重写onAdvance方法比起CyclicBarrier提供的栅栏操作非常相似。但更加灵活。
2.等待（Waiting）
awaitAdvance方法须要一个表示到达阶段号的參数。并在phaser前往（或者已经在）不同的阶段的时候返回。
与CyclicBarrier中类似结构不同。awaitAdvance方法会在等待线程被中断的时候继续等待。
当然也有可中断和超时版本号。可是当任务等待发生中断或者超时遇到的异常也不会改变phaser的状态。
假设必要，你能够在这些异常的处理器里运行关联的恢复操作，通常是在调用forceTermination之后恢复。
Phaser可能也会被运行在ForkJoinPool中的任务使用，这样当其他线程在等待phaser时被堵塞的时候，
就能够确保有效平行地运行任务。
终结（Termination）
phaser可能进入一个终结状态。能够通过isTerminated来检查。当终结的时候。全部的同步方法都不会
在等待下一个阶段而直接返回。返回一个负值来表示该状态。类似地，在终结的时候尝试注冊没有什么效果。
当onAdvance调用返回true的时候就会触发终结。
onAdvance默认实现为当一个反注册导致注册parties数降为0的时候返回true。当phaser要控制操作
在一个固定得迭代次数时。就能够非常方便地重写这种方法，当当前阶段号到达阀值得时候就返回true导致终结。
还有forceTermination方法也能够突然释放等待线程而且同意它们终结。
堆叠（Tiering）
Phaser能够被堆叠在一起（也就是说，以树形结构构造）来减少竞争。Phaser的parties数非常大的时候。
以一组子phasers共享一个公共父亲能够减轻严重的同步竞争的成本。这样做能够大大提供吞吐量，
但同一时候也会导致每一个操作的更高的成本。
在一棵堆叠的phaser树中，子phaser在父亲上的注冊和反注冊都会被自己主动管理。当子phaser的注冊
parties树为非0的时候。子phaser就会注冊到父亲上。
当因为arriveAndDeregister的调用使注冊的parties数变为0时，子phaser就会从父亲中反注冊。这样
就算父phaser的全部parties都到达了阶段，也必须表示子phaser的全部parties都到达了阶段并显式调用
父phaser的awaitAdvance才算到达新的阶段。反之亦然。这样父phaser或者子phaser里注冊过的全部parties
就能够一起互相等待到新的阶段。
另外，在这个堆叠结构的实现里，能够确保root结点必定是最先更新阶段号。然后才到其子结点，逐渐传递下去。
另外，在这个堆叠结构的实现里，能够确保root结点必定是最先更新阶段号。然后才到其子结点，逐渐传递下去。

    +------+      +------+      +------+
    | root |  <-- |parent| <--  | this |
    +------+      +------+      +------+


   parties:3+1   parties:3+1    parties:3
如上图所看到的，假设parties数多的时候，能够依据堆叠成为一颗树。这里假设root和parent和this都各初始化3个parties数。
然后假设当前结点this有注冊parties数，则会在parent上注冊一个parties，因此其实root和parent都注冊了4个parties数。
这样，假设this结点的3个parties数都到达了。就会调用parent的arrive，把parties数减去一，然后parent等待自己3个parties数都到达，就会调用root来减去一，这样root的3个parties数都到达就会一同释放全部等待结点，就实现了整棵树parties之间同步等待的功能。另外这个结构也非常easy看到root结点是最快进行阶段增长的。
这样做最大的优点就是降低对同一个state变量的CAS竞争带来的性能下降。只是同一时候每一个同步操作也会添加对应的负担
（每次获取状态都要和root进行阶段同步），所以一般在高并发下造成的性能下降才考虑。
监控（Monitoring）

同步方法仅仅能被注冊的parties调用时，任何调用者都能监控phaser的当前状态。不论在什么时刻，
总共有getRegisteredParties个parties。当中，有getArrivedParties个parites到达getPhase的当前阶段。
当剩下getUnarrivedParties个parties到达，phase前进。这些方法的返回值可能反映瞬时的状态，
因此一般在同步控制中不太实用。toString方法以一种能够方便信息监控的格式返回这些状态的快照。
注意点：该实现将最大party数量限制为65535.尝试注册其他party会导致IllegalStateException。
但是，您可以并且应该创建分层Phaser来容纳任意大量的party。
 */
/*
它支持更灵活的使用方式：
1.使用过程中可以随时注册和注销参与者；
2.不同于CyclicBarrier，分离出"到达"和"等待"机制；
3.支持结束，默认情况下，当没有参与者的时候Phaser就结束了；
4.支持层级Phaser结构；
5.提供针对内部状态的监控方法；
 */
public class Phaser {
    /*
     * This class implements an extension of X10 "clocks".  Thanks to
     * Vijay Saraswat for the idea, and to Vivek Sarkar for
     * enhancements to extend functionality.
     */
    /**
     * Primary state representation, holding four bit-fields:
     *
     * unarrived  -- the number of parties yet to hit barrier (bits  0-15)
     * parties    -- the number of parties to wait            (bits 16-31)
     * phase      -- the generation of the barrier            (bits 32-62)
     * terminated -- set if barrier is terminated             (bit  63 / sign)
     *
     * Except that a phaser with no registered parties is
     * distinguished by the otherwise illegal state of having zero
     * parties and one unarrived parties (encoded as EMPTY below).
     * 没有注册parties的phaser以0个parties和1个未到达的parties（编码为EMPTY）的非法状态加以区分外。
     * To efficiently maintain atomicity, these values are packed into
     * a single (atomic) long. Good performance relies on keeping
     * state decoding and encoding simple, and keeping race windows
     * short.
     * 主状态这样设计(将状态封装到一个原子的long域)可以从两方面提高性能，
     * 一个是对状态的编解码简单高效、另一个是可以减小竞争窗口(空间)。
     * All state updates are performed via CAS except initial
     * registration of a sub-phaser (i.e., one with a non-null
     * parent).  In this (relatively rare) case, we use built-in
     * synchronization to lock while first registering with its
     * parent.
     *除了子phaser（即，具有非空父母的phaser）的初始注册之外，所有状态更新都通过CAS执行。 在这个
     * （相对少见的）案例中，在第一次向其父进行注册时，我们使用内置的同步进行锁定。
     * The phase of a subphaser is allowed to lag that of its
     * ancestors until it is actually accessed -- see method
     * reconcileState.
     * 子阶段的阶段被允许滞后于其祖先，直到它被实际访问 - 见方法reconcileState。
     */
    //Phaser内部对于状态（包含parties注冊数、阶段号、未到达的parties数等）管理都使用
    // 一个volatile long型变量，同一时候利用CAS进行更新。这样做就能够保证在状态改变时。
    // 保持全部状态一致改变，这是实现无锁算法的基础之中的一个
    /*
    state变量为long类型，长度为64位。当中：
    主状态，分为4部分：
    未到达计数  -- 还没有到达栅栏的参与者计数。  (bits  0-15)
    parties     -- 栅栏全部参与者的计数。        (bits 16-31)
    phase       -- 栅栏当前所处的阶段            (bits 32-62)
    terminated  -- 栅栏的结束标记                (bit  63 / sign)
    一个没有注册参与者的phaser的主状态中会有0个参与者计数
    和1个未到达计数。
    把符号位设置位结束状态能够简单推断state是否为负表示是否结束。另外。假设当phaser没有不论什么注冊parties数，
    则会用一个无效状态EMPTY（0个已注冊和1个未到达parites数）来区分其他状态。
    除此之外，phaser定义了一些静态常量方便对state变量进行移位解析，如*_SHIFT移位和*_MASK掩位。
    另外另一些特殊值方便计算。另一些辅助方法可以从state提取某些状态值。
     */
    private volatile long state;

    private static final int  MAX_PARTIES     = 0xffff;
    private static final int  MAX_PHASE       = Integer.MAX_VALUE;
    private static final int  PARTIES_SHIFT   = 16;
    private static final int  PHASE_SHIFT     = 32;
    private static final int  UNARRIVED_MASK  = 0xffff;      // to mask ints
    private static final long PARTIES_MASK    = 0xffff0000L; // to mask longs
    private static final long COUNTS_MASK     = 0xffffffffL;
    private static final long TERMINATION_BIT = 1L << 63;

    // some special values
    private static final int  ONE_ARRIVAL     = 1;
    private static final int  ONE_PARTY       = 1 << PARTIES_SHIFT;
    private static final int  ONE_DEREGISTER  = ONE_ARRIVAL|ONE_PARTY;
    private static final int  EMPTY           = 1;

    // The following unpacking methods are usually manually inlined
    ////内部状态辅助方法
    //未到达party数量
    private static int unarrivedOf(long s) {
        int counts = (int)s;
        return (counts == EMPTY) ? 0 : (counts & UNARRIVED_MASK);
    }

    //总party数量
    private static int partiesOf(long s) {
        return (int)s >>> PARTIES_SHIFT;
    }

    //当前阶段号
    private static int phaseOf(long s) {
        return (int)(s >>> PHASE_SHIFT);
    }

    //到达party数量
    private static int arrivedOf(long s) {
        int counts = (int)s;
        return (counts == EMPTY) ? 0 :
            (counts >>> PARTIES_SHIFT) - (counts & UNARRIVED_MASK);
    }

    /**
     * The parent of this phaser, or null if none
     */
    /**
     * 当前phaser的父phaser, 如果没有父phaser，这个域为null。
     */
    private final Phaser parent;

    /**
     * The root of phaser tree. Equals this if not in a tree.
     */
    /**
     * phaser树的根节点. 如果当前phaser不在一棵树内，这个域等于自身。
     */
    private final Phaser root;

    /**
     * Heads of Treiber stacks for waiting threads. To eliminate
     * contention when releasing some threads while adding others, we
     * use two of them, alternating across even and odd phases.
     * Subphasers share queues with root to speed up releases.
     */
    //Phaser中使用Treiber Stack结构来保存等待线程，为了在一些情况下避免竞争，Phaser内部使用了2个Treiber Stack，evenQ和addQ，分别在内部phase为偶数和奇数下交替使用。
    private final AtomicReference<QNode> evenQ;
    private final AtomicReference<QNode> oddQ;

    private AtomicReference<QNode> queueFor(int phase) {
        return ((phase & 1) == 0) ? evenQ : oddQ;
    }

    /**
     * Returns message string for bounds exceptions on arrival.
     */
    private String badArrive(long s) {
        return "Attempted arrival of unregistered party for " +
            stateToString(s);
    }

    /**
     * Returns message string for bounds exceptions on registration.
     */
    private String badRegister(long s) {
        return "Attempt to register more than " +
            MAX_PARTIES + " parties for " + stateToString(s);
    }

    /**
     * Main implementation for methods arrive and arriveAndDeregister.
     * Manually tuned to speed up and minimize race windows for the
     * common case of just decrementing unarrived field.
     *
     * @param adjust value to subtract from state;
     *               ONE_ARRIVAL for arrive,
     *               ONE_DEREGISTER for arriveAndDeregister
     */
    //表示一个参与者到达栅栏，并且将自己从phaser上注销
    private int doArrive(int adjust) {
        final Phaser root = this.root;
        for (;;) {
            //获取主状态
            long s = (root == this) ? state : reconcileState();
            int phase = (int)(s >>> PHASE_SHIFT);
            if (phase < 0)
                return phase;
            int counts = (int)s;
            int unarrived = (counts == EMPTY) ? 0 : (counts & UNARRIVED_MASK);
            if (unarrived <= 0)
                throw new IllegalStateException(badArrive(s));
            //调整state和s
            if (UNSAFE.compareAndSwapLong(this, stateOffset, s, s-=adjust)) {
                //todo 这边如果传入的adjust中的未到达参与者数量为0，则和语义不符
                if (unarrived == 1) {
                    //advance到下一个phase
                    long n = s & PARTIES_MASK;  // base of next state
                    int nextUnarrived = (int)n >>> PARTIES_SHIFT;
                    //如果没有父phaser
                    if (root == this) {
                        if (onAdvance(phase, nextUnarrived))
                            n |= TERMINATION_BIT;
                        else if (nextUnarrived == 0)
                            n |= EMPTY;
                        else
                            n |= nextUnarrived;
                        int nextPhase = (phase + 1) & MAX_PHASE;
                        n |= (long)nextPhase << PHASE_SHIFT;
                        UNSAFE.compareAndSwapLong(this, stateOffset, s, n);
                        releaseWaiters(phase);
                    }
                    else if (nextUnarrived == 0) { // propagate deregistration
                        //如果当前子phaser中没有参与者了，就要从父phaser中将当前子phaser注销
                        phase = parent.doArrive(ONE_DEREGISTER);
                        UNSAFE.compareAndSwapLong(this, stateOffset,
                                                  s, s | EMPTY);
                    }
                    //如果从父phaser中把当前phaser对应的到达参与者计数去除
                    else
                        phase = parent.doArrive(ONE_ARRIVAL);
                }
                return phase;
            }
        }
    }

    /**
     * Implementation of register, bulkRegister
     *
     * @param registrations number to add to both parties and
     * unarrived fields. Must be greater than zero.
     * 返回state的第一个和第二段参数
     */
    /*
    首先计算注冊后要当前state要调整的值adjust，注意adjust把未到达的parties数和注冊的parties数都设为registrations；
    接着就进入自循环，首先考虑到堆叠的情况（parent不为null），就要调用reconcileState方法与parent的阶段号同步，
    并计算出未注冊前正确的state值。然后再依次计算注冊parties数parties，未到达数unarrived。阶段号phase
    子phaser的状态和root phaser的状态保持一致
    1.当前不是第一个注册者(参与者)。如果当前主状态中未到达数量为0，说明参与者已经全部到达栅栏，
    当前Phaser正在进入下一阶段过程中，需要等待这一过程完成(可能会阻塞)；否则会原子更新当前的主状态，
    加一个总参与者数量和一个未到达参与者数量。(过程中如果parent不为null，需要调用reconcileState调整
    一下当前主状态，和root的主状态保持一致)
    2.当前是第一个注册者且当前Phaser没有父级Phaser。直接原子更新当前主状态，加一个总参与者数量和
    一个未到达参与者数量。
    3.当前是第一个注册者且当前Phaser有父级Phaser。需要加锁操作，首先向父级Phaser注册一个参与者，
    然后原子更新主状态，加一个总参与者数量和一个未到达参与者数量。
     */
    private int doRegister(int registrations) {
        //调整主状态，将给定的数值加到总参与者和未到达参数者数量上。
        // adjustment to state
        long adjust = ((long)registrations << PARTIES_SHIFT) | registrations;
        final Phaser parent = this.parent;
        int phase;
        for (;;) {
            long s = (parent == null) ? state : reconcileState();
            //截断为16位
            int counts = (int)s;
            int parties = counts >>> PARTIES_SHIFT;
            int unarrived = counts & UNARRIVED_MASK;
            //注册的参与者数量和已存在的参与者数量加起来不能超过最大参与者数量。
            if (registrations > MAX_PARTIES - parties)
                throw new IllegalStateException(badRegister(s));
            phase = (int)(s >>> PHASE_SHIFT);
            //如果phaser已经结束，那么直接退出循环。
            if (phase < 0)
                break;
            //如果不是第一个注册。
            if (counts != EMPTY) {                  // not 1st registration
                if (parent == null || reconcileState() == s) {
                    // 如果当前未到达数量为0，说明需要进入下一阶段了，这里要等待root进入下一阶段
                    if (unarrived == 0)             // wait out advance
                        root.internalAwaitAdvance(phase, null);
                    //否则原子更新主状态
                    else if (UNSAFE.compareAndSwapLong(this, stateOffset,
                                                       s, s + adjust))
                        break;
                }
            }
            else if (parent == null) {
                // 第一个root注册(没有父级)
                // 1st root registration
                // 算出下一个主状态
                long next = ((long)phase << PHASE_SHIFT) | adjust;
                // 原子更新主状态
                if (UNSAFE.compareAndSwapLong(this, stateOffset, s, next))
                    break;
            }
            else {
                //第一个子phaser的注册，需要加锁
                synchronized (this) {               // 1st sub registration
                    //检测一下状态有没有变化
                    if (state == s) {               // recheck under lock
                        //由于是第一次注册，所以需要向父phaser注册一下
                        phase = parent.doRegister(1);
                        //如果父phaser已经结束，那么直接退出循环
                        if (phase < 0)
                            break;
                        // finish registration whenever parent registration
                        // succeeded, even when racing with termination,
                        // since these are part of the same "transaction".
                        //更新到下一个主状态
                        while (!UNSAFE.compareAndSwapLong
                               (this, stateOffset, s,
                                ((long)phase << PHASE_SHIFT) | adjust)) {
                            s = state;
                            phase = (int)(root.state >>> PHASE_SHIFT);
                            // assert (int)s == EMPTY;
                        }
                        break;
                    }
                }
            }
        }
        //返回state的第一个和第二段参数
        return phase;
    }

    /**
     * Resolves lagged phase propagation from root if necessary.
     * Reconciliation normally occurs when root has advanced but
     * subphasers have not yet done so, in which case they must finish
     * their own advance by setting unarrived to parties (or if
     * parties is zero, resetting to unregistered EMPTY state).
     *如有必要，解决来自root的Phase的滞后传播问题。 Reconciliation 通常发生在root已经
     * 进入下一个phase但是子phaser还没有完成的情况下，在这种情况下，他们必须通过设置未到达的参与方
     * （或者如果参与方数量为零，重置为未注册的EMPTY状态）以进入下一个phase。
     * @return reconciled state
     */
    /*
    reconcileState主要目的是和根结点保持阶段号同步。假设出现堆叠情况，根结点是最先进行阶段号添加，
    尽管阶段号添加的操作会逐渐传递到子phaser，但某些同步操作，如动态注冊等，须要立即获悉整棵树的
    阶段号状态避免多余的CAS，因此就须要显式和根结点保持同步。reconcileState实现就是如此，
    假设root!=this。即发生堆叠。就利用自旋CAS把当前改动状态值，要注意的是因为阶段号添加。
    会同一时候会把未到达的parties数设置为原来的注冊parties数。
     */
    //将当前Phaser和root Phaser的phase值调整为一致的
    private long reconcileState() {
        final Phaser root = this.root;
        long s = state;
        //如果root不为当前phaser
        if (root != this) {
            int phase, p;
            // CAS to root phase with current parties, tripping unarrived
            //root所处阶段和当前节点所处阶段不一致
            while ((phase = (int)(root.state >>> PHASE_SHIFT)) !=
                   (int)(s >>> PHASE_SHIFT) &&
                   !UNSAFE.compareAndSwapLong
                   (this, stateOffset, s,
                    //CAS更新到root所处阶段
                    s = (((long)phase << PHASE_SHIFT) |
                            //phase<0(终结状态),使用当前节点的参与者数量和未到达参与者数量
                            //phase>0,
                            //1.当前节点参与者数量为0，EMPTY
                            //2.当前节点参与者数量不为0，参与者数量和未到达参与者数量都使用 当前节点的参与者数量
                         ((phase < 0) ? (s & COUNTS_MASK) :
                          (((p = (int)s >>> PARTIES_SHIFT) == 0) ? EMPTY :
                           ((s & PARTIES_MASK) | p))))))
                s = state;
        }
        return s;
    }

    /**
     * Creates a new phaser with no initially registered parties, no
     * parent, and initial phase number 0. Any thread using this
     * phaser will need to first register for it.
     */
    /**
     * 创建一个没有初始参与者的phaser，默认没有父级phaser，初始
     * phase值为0。如果有任何线程想要使用这个phaser，都必须先
     * 注册这个phaser。
     */
    public Phaser() {
        this(null, 0);
    }

    /**
     * Creates a new phaser with the given number of registered
     * unarrived parties, no parent, and initial phase number 0.
     *
     *
     * 创建一个有初始参与者(未到达)数量的phaser，默认没有父级phaser，初始
     * phase值为0。
     *
     * @param parties the number of parties required to advance to the
     * next phase
     * @throws IllegalArgumentException if parties less than zero
     * or greater than the maximum number of parties supported
     */
    public Phaser(int parties) {
        this(null, parties);
    }

    /**
     * Equivalent to {@link #Phaser(Phaser, int) Phaser(parent, 0)}.
     *
     * @param parent the parent phaser
     */
    public Phaser(Phaser parent) {
        this(parent, 0);
    }

    /**
     * Creates a new phaser with the given parent and number of
     * registered unarrived parties.  When the given parent is non-null
     * and the given number of parties is greater than zero, this
     * child phaser is registered with its parent.
     ** 创建一个有给定父级phaser和初始参与者(未到达)数量的phaser，
     * 如果给定的父级phaser不为null，并且如果给定的参与者数量大于0，
     * 则当前的子phaser注册到父phaser中。
     * @param parent the parent phaser
     * @param parties the number of parties required to advance to the
     * next phase
     * @throws IllegalArgumentException if parties less than zero
     * or greater than the maximum number of parties supported
     */
    public Phaser(Phaser parent, int parties) {
        //parties不能超过65535
        if (parties >>> PARTIES_SHIFT != 0)
            throw new IllegalArgumentException("Illegal number of parties");
        int phase = 0;
        this.parent = parent;
        if (parent != null) {
            //如果父级Phaser不为空
            final Phaser root = parent.root;
            this.root = root;
            //共享父级的线程等待队列。
            this.evenQ = root.evenQ;
            this.oddQ = root.oddQ;
            if (parties != 0)
                //如果当前phaser的参与者不为0，那么注册一个参与者到父级，注意这里是一个。
                phase = parent.doRegister(1);
        }
        else {
            //如果父级为空，root就是自身
            this.root = this;
            this.evenQ = new AtomicReference<QNode>();
            this.oddQ = new AtomicReference<QNode>();
        }
        //初始化state的时候，如果没有参与者，state就是EMPTY，等于1
        this.state = (parties == 0) ? (long)EMPTY :
            ((long)phase << PHASE_SHIFT) |
            ((long)parties << PARTIES_SHIFT) |
            ((long)parties);
    }

    /**
     * Adds a new unarrived party to this phaser.  If an ongoing
     * invocation of {@link #onAdvance} is in progress, this method
     * may await its completion before returning.  If this phaser has
     * a parent, and this phaser previously had no registered parties,
     * this child phaser is also registered with its parent. If
     * this phaser is terminated, the attempt to register has
     * no effect, and a negative value is returned.
     * 添加一个新的未到达的参与者到当前phaser。如果当前正在onAdvance方法，
     * 的执行过程中，这个方法会等待其完成再返回。如果当前phaser有父phaser，
     * 并且当前phaser之前没有注册的参与者，phaser会注册到父phaser上。
     * 如果当前phaser结束了，那么方法不会产生任何作用，并返回一个负数。
     * @return the arrival phase number to which this registration 本次注册的到达phase号
     * applied.  If this value is negative, then this phaser has
     * terminated, in which case registration has no effect.
     * @throws IllegalStateException if attempting to register more
     * than the maximum supported number of parties
     */
    public int register() {
        return doRegister(1);
    }

    /**
     * Adds the given number of new unarrived parties to this phaser.
     * If an ongoing invocation of {@link #onAdvance} is in progress,
     * this method may await its completion before returning.  If this
     * phaser has a parent, and the given number of parties is greater
     * than zero, and this phaser previously had no registered
     * parties, this child phaser is also registered with its parent.
     * If this phaser is terminated, the attempt to register has no
     * effect, and a negative value is returned.
     *
     * @param parties the number of additional parties required to
     * advance to the next phase
     * @return the arrival phase number to which this registration
     * applied.  If this value is negative, then this phaser has
     * terminated, in which case registration has no effect.
     * @throws IllegalStateException if attempting to register more
     * than the maximum supported number of parties
     * @throws IllegalArgumentException if {@code parties < 0}
     */
    //相对于register的批量注册方法
    public int bulkRegister(int parties) {
        if (parties < 0)
            throw new IllegalArgumentException();
        if (parties == 0)
            return getPhase();
        return doRegister(parties);
    }

    /**
     * Arrives at this phaser, without waiting for others to arrive.
     * 到达这个phaser，不用阻塞等待其他参与者到达(不阻塞)。
     * <p>It is a usage error for an unregistered party to invoke this
     * method.  However, this error may result in an {@code
     * IllegalStateException} only upon some subsequent operation on
     * this phaser, if ever.
     *未注册参与者调用此方法是一种使用错误。 但是，这个错误只有在这个phaser上有后续操作的
     * 情况下才可能会导致一个IllegalStateException。
     * @return the arrival phase number, or a negative value if terminated
     * @throws IllegalStateException if not terminated and the number
     * of unarrived parties would become negative
     */
    public int arrive() {
        return doArrive(ONE_ARRIVAL);
    }

    /**
     * Arrives at this phaser and deregisters from it without waiting
     * for others to arrive. Deregistration reduces the number of
     * parties required to advance in future phases.  If this phaser
     * has a parent, and deregistration causes this phaser to have
     * zero parties, this phaser is also deregistered from its parent.
     *到达这个phaser并从这个phaser注销，而不用等待其他参与者到达。 取消注册减少了在未来advance阶段所需
     * 的参与数。
     * 如果这个phaser有一个父phaser，并且注销导致了这个phaser没有参与者，那么这个phaser也从其父母
     * 撤销注册。未注册参与者调用此方法是一种使用错误。 但是，这个错误只有在这个phaser上有后续操作的
     * 情况下才可能会导致一个IllegalStateException。
     * <p>It is a usage error for an unregistered party to invoke this
     * method.  However, this error may result in an {@code
     * IllegalStateException} only upon some subsequent operation on
     * this phaser, if ever.
     *
     * @return the arrival phase number, or a negative value if terminated
     * @throws IllegalStateException if not terminated and the number
     * of registered or unarrived parties would become negative
     */
    public int arriveAndDeregister() {
        //参与者和未到达参与者同时-1
        return doArrive(ONE_DEREGISTER);
    }

    /**
     * Arrives at this phaser and awaits others. Equivalent in effect
     * to {@code awaitAdvance(arrive())}.  If you need to await with
     * interruption or timeout, you can arrange this with an analogous
     * construction using one of the other forms of the {@code
     * awaitAdvance} method.  If instead you need to deregister upon
     * arrival, use {@code awaitAdvance(arriveAndDeregister())}.
     *
     * <p>It is a usage error for an unregistered party to invoke this
     * method.  However, this error may result in an {@code
     * IllegalStateException} only upon some subsequent operation on
     * this phaser, if ever.
     *
     * @return the arrival phase number, or the (negative)
     * {@linkplain #getPhase() current phase} if terminated
     * @throws IllegalStateException if not terminated and the number
     * of unarrived parties would become negative
     */
    /*
    首先将主状态中的未到达参与者数量减1，然后判断未到达参与者数量是否为0。如果不为0，
    当前线程会等待其他参与者到来；如果为0，说明当前(线程)是最后一个参与者，那么会继续
    算出下一个阶段的主状态，然后更新到Phaser中。计算过程中会通过调用onAdvance方法，
    判断当前Phaser是否结束，还会重置未到达参与者数量等。
     */
    public int arriveAndAwaitAdvance() {
        // Specialization of doArrive+awaitAdvance eliminating some reads/paths
        final Phaser root = this.root;
        for (;;) {
            //获取主状态
            long s = (root == this) ? state : reconcileState();
            //获取phase值
            int phase = (int)(s >>> PHASE_SHIFT);
            if (phase < 0)
                return phase;
            int counts = (int)s;
            //获取当前未到达参与者计数，就是之前的未到达计数减1
            int unarrived = (counts == EMPTY) ? 0 : (counts & UNARRIVED_MASK);
            //非法状态
            if (unarrived <= 0)
                throw new IllegalStateException(badArrive(s));
            //主状态中未到达参与者的计数减1
            if (UNSAFE.compareAndSwapLong(this, stateOffset, s,
                                          s -= ONE_ARRIVAL)) {
                //如果还有未到达的参与者，等待
                if (unarrived > 1)
                    return root.internalAwaitAdvance(phase, null);
                //如果当前是子级Phaser，要等待父级进入下一阶段
                if (root != this)
                    return parent.arriveAndAwaitAdvance();
                long n = s & PARTIES_MASK;  // base of next state
                //这里算出来的是总参与者的数量
                int nextUnarrived = (int)n >>> PARTIES_SHIFT;
                //调用onAdvance方法
                if (onAdvance(phase, nextUnarrived))
                    //如果onAdvance方法返回true，给主状态中设置结束标记
                    n |= TERMINATION_BIT;
                //如果总参与者数量变为0，那么将主状态设置为没有参与者的特殊状态
                else if (nextUnarrived == 0)
                    n |= EMPTY;
                else
                    //否则，重置未到达参与者数量
                    n |= nextUnarrived;
                //算出下一个phase值
                int nextPhase = (phase + 1) & MAX_PHASE;
                //设置到主状态上
                n |= (long)nextPhase << PHASE_SHIFT;
                //原子更新主状态
                if (!UNSAFE.compareAndSwapLong(this, stateOffset, s, n))
                    //如果发生竞争，返回phase值，如果当前phaser结束，返回负数
                    return (int)(state >>> PHASE_SHIFT); // terminated
                //清空上一阶段使用的线程等待队列
                releaseWaiters(phase);
                //最后返回上面算出来的nextPhase值
                return nextPhase;
            }
        }
    }

    /**
     * Awaits the phase of this phaser to advance from the given phase
     * value, returning immediately if the current phase is not equal
     * to the given phase value or this phaser is terminated.
     *
     * @param phase an arrival phase number, or negative value if
     * terminated; this argument is normally the value returned by a
     * previous call to {@code arrive} or {@code arriveAndDeregister}.
     * @return the next arrival phase number, or the argument if it is
     * negative, or the (negative) {@linkplain #getPhase() current phase}
     * if terminated
     */
    //等待phaser到达phase值表示的阶段结束，如果当前phase和给定的phase不一致或者phaser终止，立即返回当前的phase值
    public int awaitAdvance(int phase) {
        final Phaser root = this.root;
        long s = (root == this) ? state : reconcileState();
        int p = (int)(s >>> PHASE_SHIFT);
        if (phase < 0)
            return phase;
        if (p == phase)
            return root.internalAwaitAdvance(phase, null);
        return p;
    }

    /**
     * Awaits the phase of this phaser to advance from the given phase
     * value, throwing {@code InterruptedException} if interrupted
     * while waiting, or returning immediately if the current phase is
     * not equal to the given phase value or this phaser is
     * terminated.
     *
     * @param phase an arrival phase number, or negative value if
     * terminated; this argument is normally the value returned by a
     * previous call to {@code arrive} or {@code arriveAndDeregister}.
     * @return the next arrival phase number, or the argument if it is
     * negative, or the (negative) {@linkplain #getPhase() current phase}
     * if terminated
     * @throws InterruptedException if thread interrupted while waiting
     */
    //awaitAdvance方法逻辑一样，但支持中断
    public int awaitAdvanceInterruptibly(int phase)
        throws InterruptedException {
        final Phaser root = this.root;
        long s = (root == this) ? state : reconcileState();
        int p = (int)(s >>> PHASE_SHIFT);
        if (phase < 0)
            return phase;
        if (p == phase) {
            QNode node = new QNode(this, phase, true, false, 0L);
            p = root.internalAwaitAdvance(phase, node);
            if (node.wasInterrupted)
                throw new InterruptedException();
        }
        return p;
    }

    /**
     * Awaits the phase of this phaser to advance from the given phase
     * value or the given timeout to elapse, throwing {@code
     * InterruptedException} if interrupted while waiting, or
     * returning immediately if the current phase is not equal to the
     * given phase value or this phaser is terminated.
     *
     * @param phase an arrival phase number, or negative value if
     * terminated; this argument is normally the value returned by a
     * previous call to {@code arrive} or {@code arriveAndDeregister}.
     * @param timeout how long to wait before giving up, in units of
     *        {@code unit}
     * @param unit a {@code TimeUnit} determining how to interpret the
     *        {@code timeout} parameter
     * @return the next arrival phase number, or the argument if it is
     * negative, or the (negative) {@linkplain #getPhase() current phase}
     * if terminated
     * @throws InterruptedException if thread interrupted while waiting
     * @throws TimeoutException if timed out while waiting
     */
    //awaitAdvance方法逻辑一样，但支持中断和超时
    public int awaitAdvanceInterruptibly(int phase,
                                         long timeout, TimeUnit unit)
        throws InterruptedException, TimeoutException {
        long nanos = unit.toNanos(timeout);
        final Phaser root = this.root;
        long s = (root == this) ? state : reconcileState();
        int p = (int)(s >>> PHASE_SHIFT);
        if (phase < 0)
            return phase;
        if (p == phase) {
            QNode node = new QNode(this, phase, true, true, nanos);
            p = root.internalAwaitAdvance(phase, node);
            if (node.wasInterrupted)
                throw new InterruptedException();
            else if (p == phase)
                throw new TimeoutException();
        }
        return p;
    }

    /**
     * Forces this phaser to enter termination state.  Counts of
     * registered parties are unaffected.  If this phaser is a member
     * of a tiered set of phasers, then all of the phasers in the set
     * are terminated.  If this phaser is already terminated, this
     * method has no effect.  This method may be useful for
     * coordinating recovery after one or more tasks encounter
     * unexpected exceptions.
     */
    //强制结束Phaser
    public void forceTermination() {
        // Only need to change root state
        final Phaser root = this.root;
        long s;
        while ((s = root.state) >= 0) {
            if (UNSAFE.compareAndSwapLong(root, stateOffset,
                                          s, s | TERMINATION_BIT)) {
                // signal all threads
                releaseWaiters(0); // Waiters on evenQ
                releaseWaiters(1); // Waiters on oddQ
                return;
            }
        }
    }

    /**
     * Returns the current phase number. The maximum phase number is
     * {@code Integer.MAX_VALUE}, after which it restarts at
     * zero. Upon termination, the phase number is negative,
     * in which case the prevailing phase prior to termination
     * may be obtained via {@code getPhase() + Integer.MIN_VALUE}.
     *
     * @return the phase number, or a negative value if terminated
     */
    public final int getPhase() {
        return (int)(root.state >>> PHASE_SHIFT);
    }

    /**
     * Returns the number of parties registered at this phaser.
     *
     * @return the number of parties
     */
    public int getRegisteredParties() {
        return partiesOf(state);
    }

    /**
     * Returns the number of registered parties that have arrived at
     * the current phase of this phaser. If this phaser has terminated,
     * the returned value is meaningless and arbitrary.
     *
     * @return the number of arrived parties
     */
    public int getArrivedParties() {
        return arrivedOf(reconcileState());
    }

    /**
     * Returns the number of registered parties that have not yet
     * arrived at the current phase of this phaser. If this phaser has
     * terminated, the returned value is meaningless and arbitrary.
     *
     * @return the number of unarrived parties
     */
    public int getUnarrivedParties() {
        return unarrivedOf(reconcileState());
    }

    /**
     * Returns the parent of this phaser, or {@code null} if none.
     *
     * @return the parent of this phaser, or {@code null} if none
     */
    public Phaser getParent() {
        return parent;
    }

    /**
     * Returns the root ancestor of this phaser, which is the same as
     * this phaser if it has no parent.
     *
     * @return the root ancestor of this phaser
     */
    public Phaser getRoot() {
        return root;
    }

    /**
     * Returns {@code true} if this phaser has been terminated.
     *
     * @return {@code true} if this phaser has been terminated
     */
    public boolean isTerminated() {
        return root.state < 0L;
    }

    /**
     * Overridable method to perform an action upon impending phase
     * advance, and to control termination. This method is invoked
     * upon arrival of the party advancing this phaser (when all other
     * waiting parties are dormant).  If this method returns {@code
     * true}, this phaser will be set to a final termination state
     * upon advance, and subsequent calls to {@link #isTerminated}
     * will return true. Any (unchecked) Exception or Error thrown by
     * an invocation of this method is propagated to the party
     * attempting to advance this phaser, in which case no advance
     * occurs.
     *
     * <p>The arguments to this method provide the state of the phaser
     * prevailing for the current transition.  The effects of invoking
     * arrival, registration, and waiting methods on this phaser from
     * within {@code onAdvance} are unspecified and should not be
     * relied on.
     *
     * <p>If this phaser is a member of a tiered set of phasers, then
     * {@code onAdvance} is invoked only for its root phaser on each
     * advance.
     *
     * <p>To support the most common use cases, the default
     * implementation of this method returns {@code true} when the
     * number of registered parties has become zero as the result of a
     * party invoking {@code arriveAndDeregister}.  You can disable
     * this behavior, thus enabling continuation upon future
     * registrations, by overriding this method to always return
     * {@code false}:
     *
     * <pre> {@code
     * Phaser phaser = new Phaser() {
     *   protected boolean onAdvance(int phase, int parties) { return false; }
     * }}</pre>
     *
     * @param phase the current phase number on entry to this method,
     * before this phaser is advanced
     * @param registeredParties the current number of registered parties
     * @return {@code true} if this phaser should terminate 返回true，则此phaser终结
     */
    //onAdvance方法的作用就是来控制Phaser是否结束，默认行为是当总参与者数量为0时，Phaser就结束了。
    // 具体使用时可以覆盖这个方法，实现合适的策略来控制Phaser的结束时机
    protected boolean onAdvance(int phase, int registeredParties) {
        return registeredParties == 0;
    }

    /**
     * Returns a string identifying this phaser, as well as its
     * state.  The state, in brackets, includes the String {@code
     * "phase = "} followed by the phase number, {@code "parties = "}
     * followed by the number of registered parties, and {@code
     * "arrived = "} followed by the number of arrived parties.
     *
     * @return a string identifying this phaser, as well as its state
     */
    public String toString() {
        return stateToString(reconcileState());
    }

    /**
     * Implementation of toString and string-based error messages
     */
    private String stateToString(long s) {
        return super.toString() +
            "[phase = " + phaseOf(s) +
            " parties = " + partiesOf(s) +
            " arrived = " + arrivedOf(s) + "]";
    }

    // Waiting mechanics

    /**
     * Removes and signals threads from queue for phase.
     */
    //把等待线程队列里面的节点都移除了，如果节点有线程的话，将线程唤醒
    private void releaseWaiters(int phase) {
        QNode q;   // first element of queue
        Thread t;  // its thread
        AtomicReference<QNode> head = (phase & 1) == 0 ? evenQ : oddQ;
        //因为phase是递增的，因此能释放掉所有和当前phase不同的节点
        while ((q = head.get()) != null &&
               q.phase != (int)(root.state >>> PHASE_SHIFT)) {
            if (head.compareAndSet(q, q.next) &&
                (t = q.thread) != null) {
                q.thread = null;
                LockSupport.unpark(t);
            }
        }
    }

    /**
     * Variant of releaseWaiters that additionally tries to remove any
     * nodes no longer waiting for advance due to timeout or
     * interrupt. Currently, nodes are removed only if they are at
     * head of queue, which suffices to reduce memory footprint in
     * most usages.
     *
     * @return current phase on exit
     */
    /*
    releaseWaiters方法是用来清空当前不适用的等待线程队列的；abortWait方法是将当前正在使用的队列中
    由于超时或者中断且等待当前phaser的节点移除。
     */
    private int abortWait(int phase) {
        AtomicReference<QNode> head = (phase & 1) == 0 ? evenQ : oddQ;
        for (;;) {
            Thread t;
            QNode q = head.get();
            int p = (int)(root.state >>> PHASE_SHIFT);
            //如果1.没有头节点2.节点thread参数不为null且==当前phase
            //如果中断或者超时，在node.isReleasable()方法中会将节点的thread变量设置为null
            if (q == null || ((t = q.thread) != null && q.phase == p))
                return p;
            if (head.compareAndSet(q, q.next) && t != null) {
                q.thread = null;
                LockSupport.unpark(t);
            }
        }
    }

    /** The number of CPUs, for spin control */
    private static final int NCPU = Runtime.getRuntime().availableProcessors();

    /**
     * The number of times to spin before blocking while waiting for
     * advance, per arrival while waiting. On multiprocessors, fully
     * blocking and waking up a large number of threads all at once is
     * usually a very slow process, so we use rechargeable spins to
     * avoid it when threads regularly arrive: When a thread in
     * internalAwaitAdvance notices another arrival before blocking,
     * and there appear to be enough CPUs available, it spins
     * SPINS_PER_ARRIVAL more times before blocking. The value trades
     * off good-citizenship vs big unnecessary slowdowns.
     */
    /**
     * 单个参与者阻塞等待栅栏进入下一个阶段之前的自旋次数。
     * 在多核处理器下，一次性完全的阻塞和唤醒一大批线程通常比较慢，
     * 所以我们这里使用了一个可调整的自旋次数值在避免这种情况。
     * 当一个参与者线程在internalAwaitAdvance方法中阻塞之前发现了
     * 其他到达的线程，并且有cpu资源可用，那么这个参与者线程会在阻塞
     * 之前自旋SPINS_PER_ARRIVAL或者更多次。
     */
    static final int SPINS_PER_ARRIVAL = (NCPU < 2) ? 1 : 1 << 8;

    /**
     * Possibly blocks and waits for phase to advance unless aborted.
     * Call only on root phaser.
     * 阻塞并等待phase+1
     * @param phase current phase
     * @param node if non-null, the wait node to track interrupt and timeout; 如果非null，表示用来跟踪中断和超时的等待节点
     * if null, denotes noninterruptible wait 如果为null，表示不响应中断的等待
     * @return current phase
     */
    /*
    internalAwaitAdvance中的主要逻辑过程就是当前(参与者)线程等待Phaser进入下一个阶段
    (就是phase值变化)。有些细节需要注意一下。
    如果传入的node为null，过程如下：
    第1步，等待分两个阶段，自旋等待和阻塞等待，首先自旋给定的次数，如果自旋过程中未
    到达参与者数量发生变化，且变化后的为未到达参与者数量小于CPU处理器核数，那么自选次数会
    增加SPINS_PER_ARRIVAL次。如果自旋次数用完后或者当前线程被中断了，那么会创建一个
    不可中断模式的节点，节点中保存当前线程及其他信息。
    第2步，将上面创建的节点加入线程等待队列的首部(类似于压栈，因为线程等待队列就是Treiber Stack)。
    第3步，当前线程开始阻塞等待。
    第4步，当前线程被唤醒后，如果是不可中断模式的节点，需要向上层传递中断状态；
    如果当前phaser还是没有进入下一阶段，那么调用abortWait，做放弃等待操作。
    如果传入的node不为null，过程和上面类似，只是没有第1步。
     */
    private int internalAwaitAdvance(int phase, QNode node) {
        // assert root == this;
        // 清空不用的等待线程队列(Treiber Stack)
        releaseWaiters(phase-1);          // ensure old queue clean
        // 入队标识
        boolean queued = false;           // true when node is enqueued
        // 用于在发生变化时增加自旋次数
        int lastUnarrived = 0;            // to increase spins upon change
        int spins = SPINS_PER_ARRIVAL;
        long s;
        int p;
        //如果当前阶段还是==phase
        while ((p = (int)((s = state) >>> PHASE_SHIFT)) == phase) {
            //如果node为null，则在非中断模式下自旋
            if (node == null) {           // spinning in noninterruptible mode
                int unarrived = (int)s & UNARRIVED_MASK;
                //如果未到达参与者数量发生了变化，且变化后的未到达数量小于cpu核数，需要增加自旋次数。
                if (unarrived != lastUnarrived &&
                    (lastUnarrived = unarrived) < NCPU)
                    spins += SPINS_PER_ARRIVAL;
                //获取并清除当前线程中断标记
                boolean interrupted = Thread.interrupted();
                //如果当前线程被中断，或者自旋次数用完。创建一个(不可中断的)节点。
                if (interrupted || --spins < 0) { // need node to record intr
                    //创建一个node，在下一次循环中使用
                    node = new QNode(this, phase, false, false, 0L);
                    node.wasInterrupted = interrupted;
                }
            }
            //如果当前node不需要阻塞
            else if (node.isReleasable()) // done or aborted
                break;
            //如果还没有入队
            else if (!queued) {           // push onto queue
                //选择奇数队列还是偶数队列
                AtomicReference<QNode> head = (phase & 1) == 0 ? evenQ : oddQ;
                // 将节点加入队列首部
                QNode q = node.next = head.get();
                if ((q == null || q.phase == phase) &&
                        //避免无效的入队
                    (int)(state >>> PHASE_SHIFT) == phase) // avoid stale enq
                    queued = head.compareAndSet(q, node);
            }
            else {
                try {
                    //阻塞等待。
                    ForkJoinPool.managedBlock(node);
                } catch (InterruptedException ie) {
                    node.wasInterrupted = true;
                }
            }
        }

        if (node != null) {
            if (node.thread != null)
                node.thread = null;       // avoid need for unpark()
            if (node.wasInterrupted && !node.interruptible)
                //不可中断模式下要传递中断
                Thread.currentThread().interrupt();
            //如果phase还是不变
            if (p == phase && (p = (int)(state >>> PHASE_SHIFT)) == phase)
                //去除超时或者中断的节点
                return abortWait(phase); // possibly clean up on abort
        }
        //释放处在phase阶段的等待节点
        releaseWaiters(phase);
        return p;
    }

    /**
     * Wait nodes for Treiber stack representing wait queue
     */
    /*
       QNode内部结构很简单，就是保存了一些线程等待的相关信息，还有指向下一个QNode的域。
       这里要注意的是QNode实现了ForkJoinPool.ManagedBlocker，
       todo ? 作用就是当包含ForkJoinWorkerThread的QNode阻塞的时候，ForkJoinPool内部会增加一个工作线程来保证并行度
     */
    //无锁栈节点
    static final class QNode implements ForkJoinPool.ManagedBlocker {
        final Phaser phaser;
        final int phase;
        final boolean interruptible; //是否可中断
        final boolean timed;
        boolean wasInterrupted;
        long nanos;
        final long deadline;
        volatile Thread thread; // nulled to cancel wait
        QNode next;

        QNode(Phaser phaser, int phase, boolean interruptible,
              boolean timed, long nanos) {
            this.phaser = phaser;
            this.phase = phase;
            this.interruptible = interruptible;
            this.nanos = nanos;
            this.timed = timed;
            this.deadline = timed ? System.nanoTime() + nanos : 0L;
            thread = Thread.currentThread();
        }

        //当QNode中的thread为null、或者和phaser的阶段值不相等、或者被中断、或者等待超时，方法都返回true
        public boolean isReleasable() {
            if (thread == null)
                return true;
            if (phaser.getPhase() != phase) {
                thread = null;
                return true;
            }
            if (Thread.interrupted())
                wasInterrupted = true;
            if (wasInterrupted && interruptible) {
                thread = null;
                return true;
            }
            if (timed) {
                if (nanos > 0L) {
                    nanos = deadline - System.nanoTime();
                }
                if (nanos <= 0L) {
                    thread = null;
                    return true;
                }
            }
            return false;
        }

        //阻塞
        public boolean block() {
            if (isReleasable())
                return true;
            else if (!timed)
                LockSupport.park(this);
            else if (nanos > 0L)
                LockSupport.parkNanos(this, nanos);
            return isReleasable();
        }
    }

    // Unsafe mechanics

    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = Phaser.class;
            stateOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("state"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
