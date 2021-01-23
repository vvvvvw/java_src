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
import java.util.concurrent.locks.LockSupport;

/**
 * A cancellable asynchronous computation.  This class provides a base
 * implementation of {@link Future}, with methods to start and cancel
 * a computation, query to see if the computation is complete, and
 * retrieve the result of the computation.  The result can only be
 * retrieved when the computation has completed; the {@code get}
 * methods will block if the computation has not yet completed.  Once
 * the computation has completed, the computation cannot be restarted
 * or cancelled (unless the computation is invoked using
 * {@link #runAndReset}).
 *  FutureTask是一个可取消的异步计算任务，并提供了基于Future接口实现的开始和取消
 *  计算任务，查看计算任务状态和在计算任务结束后获取结果的方法。计算任务的结果，只有
 *  在计算任务完成时，才能取得，如果计算任务还没完成，将会阻塞。只要计算任务完成，
 *  计算任务就不能被取消或重新启动。
 * <p>A {@code FutureTask} can be used to wrap a {@link Callable} or
 * {@link Runnable} object.  Because {@code FutureTask} implements
 * {@code Runnable}, a {@code FutureTask} can be submitted to an
 * {@link Executor} for execution.
 *FutureTask可用于包装Callable和Runnable对象。由于FutureTask实现了Runnable接口，
 * 所有可以被调到Executor执行。
 * <p>In addition to serving as a standalone class, this class provides
 * {@code protected} functionality that may be useful when creating
 * customized task classes.
 * 除了作为一个独立的类外，此类还提供了 protected 功能，这在创建自定义任务类时可能很有用。
 * @since 1.5
 * @author Doug Lea
 * @param <V> The result type returned by this FutureTask's {@code get} methods
 */

/**
 1.状态为NEW时，cancel和run方法才可以被运行
 2.run方法对cancel方法的影响
 在调用call()方法期间，cancel()方法还可以运行；
 如果call()方法返回或抛出异常之后CAS成功，则cancel()方法之后都会返回false
 意义：
 在任务还未开始，或者任务已被运行，但未结束，这两种情况下都可以取消；
 如果任务已经结束，则不可以被取消
 3.cancel方法对run方法的影响
 cancel()方法第一步CAS成功之后，
 1）如果run()方法还没运行，之后就不可以再被运行；
 2）如果run()方法已经调用call()方法，则call()方法可以运行直至返回或抛出异常，但run()方法的第二步中的CAS会失败，且运行结果也不会被设置
 意义：
 取消成功后，如果任务还未开始，则之后也不会得到运行；
 如果任务已经开始，则可以运行直至返回或抛出异常，但任务结果或异常不会被设置
 * @param <V>
 */
/**
 * FutureTask表示可以取消的异步任务
 * 从继承关系可以看出，FutureTask至少包含以下四个内容：
 * 1.任务内容——run()方法
 * 2.获取任务结果——get()方法
 * 3.获取任务状态——isCancelled()和isDone()方法
 * 4.取消任务——cancel()方法
 */
/*
FutureTask中的任务状态由变量state表示，任务状态都基于state判断。而futureTask的阻塞则是通过自旋+挂起线程实现。
 */
public class FutureTask<V> implements RunnableFuture<V> {
    /*
     * Revision notes: This differs from previous versions of this
     * class that relied on AbstractQueuedSynchronizer, mainly to
     * avoid surprising users about retaining interrupt status during
     * cancellation races. Sync control in the current design relies
     * on a "state" field updated via CAS to track completion, along
     * with a simple Treiber stack to hold waiting threads.
     *
     * Style note: As usual, we bypass overhead of using
     * AtomicXFieldUpdaters and instead directly use Unsafe intrinsics.
     */

    /**
     * The run state of this task, initially NEW.  The run state
     * transitions to a terminal state only in methods set,
     * setException, and cancel.  During completion, state may take on
     * transient values of COMPLETING (while outcome is being set) or
     * INTERRUPTING (only while interrupting the runner to satisfy a
     * cancel(true)). Transitions from these intermediate to final
     * states use cheaper ordered/lazy writes because values are unique
     * and cannot be further modified.
     *
     * Possible state transitions:
     * NEW -> COMPLETING -> NORMAL
     * NEW -> COMPLETING -> EXCEPTIONAL
     * NEW -> CANCELLED
     * NEW -> INTERRUPTING -> INTERRUPTED
     */
    /**
     * 当前任务的运行状态。
     *
     * 可能存在的状态转换
     * NEW -> COMPLETING -> NORMAL（有正常结果）
     * NEW -> COMPLETING -> EXCEPTIONAL（结果为异常）
     * NEW -> CANCELLED（无结果）
     * NEW -> INTERRUPTING -> INTERRUPTED（无结果）
     */
    private volatile int state;
    private static final int NEW          = 0; //初始状态,表示是个新的任务或者还没被执行完的任务。这是初始状态。
    private static final int COMPLETING   = 1;//任务已经执行完成或者执行任务的时候发生异常，但是任务执行结果或者异常原因还没有保存到outcome字段(outcome字段用来保存任务执行结果，如果发生异常，则用来保存异常原因)的时候，状态会从NEW变更到COMPLETING。但是这个状态会时间会比较短，属于中间状态。
    private static final int NORMAL       = 2; //任务正常完成，任务已经执行完成并且任务执行结果已经保存到outcome字段，状态会从COMPLETING转换到NORMAL。这是一个最终态。
    private static final int EXCEPTIONAL  = 3; //:任务执行发生异常并且异常原因已经保存到outcome字段中后，状态会从COMPLETING转换到EXCEPTIONAL。这是一个最终态。
    private static final int CANCELLED    = 4; //任务已被取消,任务还没开始执行或者已经开始执行但是还没有执行完成的时候，用户调用了cancel(false)方法取消任务且不中断任务执行线程，这个时候状态会从NEW转化为CANCELLED状态。这是一个最终态。
    private static final int INTERRUPTING = 5; //任务还没开始执行或者已经执行但是还没有执行完成的时候，用户调用了cancel(true)方法取消任务并且要中断任务执行线程但还没有发送中断信号之前，状态会从NEW转化为INTERRUPTING。
    private static final int INTERRUPTED  = 6; //线程已被中断,调用interrupt()发送中断信号之后状态会从INTERRUPTING转换到INTERRUPTED。这是一个最终态。

    /** The underlying callable; nulled out after running */
    private Callable<V> callable;
    /** The result to return or exception to throw from get() */
    //用于get()返回的结果，也可能是用于get()方法抛出的异常
    private Object outcome; // non-volatile, protected by state reads/writes
    /** The thread running the callable; CASed during run() */
    //执行callable的线程，调用FutureTask.run()方法通过CAS设置
    private volatile Thread runner;
    /** Treiber stack of waiting threads */
    //Treiber stack（无锁并发栈）
    private volatile WaitNode waiters;

    /**
     * Returns result or throws exception for completed task.
     *
     * @param s completed state value
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        Object x = outcome;
        // 1. 任务正常执行完成，返回任务执行结果
        if (s == NORMAL)
            return (V)x;
        // 2. 任务被取消，抛出CancellationException异常
        if (s >= CANCELLED)
            throw new CancellationException();
        // 3. 其他状态，抛出执行异常ExecutionException
        throw new ExecutionException((Throwable)x);
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Callable}.
     * 创建一个FutureTask，在执行时，将会执行参数Callable，初始化状态为NEW
     * @param  callable the callable task
     * @throws NullPointerException if the callable is null
     */
    public FutureTask(Callable<V> callable) {
        if (callable == null)
            throw new NullPointerException();
        this.callable = callable;
        this.state = NEW;       // ensure visibility of callable
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Runnable}, and arrange that {@code get} will return the
     * given result on successful completion.
     *
     * @param runnable the runnable task
     * @param result the result to return on successful completion. If
     * you don't need a particular result, consider using
     * constructions of the form:
     * {@code Future<?> f = new FutureTask<Void>(runnable, null)}
     * @throws NullPointerException if the runnable is null
     */
    //赋值callable，并初始化状态为NEW
    public FutureTask(Runnable runnable, V result) {
        //返回的callable的call方法会调用runnable.run方法，然后返回result
        this.callable = Executors.callable(runnable, result);
        this.state = NEW;       // ensure visibility of callable
    }

    //取消状态包括：已被取消，已被中断，中断中
    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    /**
     * 由isCancelled和isDone两个方法可以看出
     * 1.完成状态包括：不为New状态
     * 2.取消状态包括：COMPLETING,已被取消，已被中断，中断中
     */
    public boolean isDone() {
        return state != NEW;
    }

    /**
     * cancel方法
     * 1.如果还没有执行结果，根据mayInterruptIfRunning是否为true，CAS设置状态为INTERRUPTING或CANCELLED，
     *   设置成功，继续第二步，否则返回false
     * 2.如果mayInterruptIfRunning为true，调用runner.interupt()，设置状态为INTERRUPTED
     * 3.唤醒所有在get()方法等待的线程
     */
    public boolean cancel(boolean mayInterruptIfRunning) {
        // 1. 状态如果不是NEW，说明任务或者已经执行过，或者已经被取消，直接返回
        // 2. 状态如果是NEW，则尝试把当前执行线程保存在runner字段中
        //// 如果赋值失败则直接返回
        if (!(state == NEW &&
              UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                  mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        // 2. 如果需要中断任务执行线程
        try {    // in case call to interrupt throws exception
            if (mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    if (t != null)
                        //中断任务执行线程
                        t.interrupt();
                } finally { // final state
                    //修改状态为INTERRUPTED
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
        } finally {
            finishCompletion();
        }
        return true;
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    /*
    任务发起线程可以调用get()方法来获取任务执行结果，如果此时任务已经执行完毕则会直接返回任务结果，如果任务还没执行完毕，
    则调用方会阻塞直到任务执行结束返回结果为止。
     */
    /*
    判断任务当前的state <= COMPLETING是否成立。前面分析过，COMPLETING状态是任务是否执行完成的临界状态。
    如果成立，表明任务还没有结束(这里的结束包括任务正常执行完毕，任务执行异常，任务被取消)，则会调用awaitDone()进行阻塞等待。
    如果不成立表明任务已经结束，调用report()返回结果。
     */
    public V get() throws InterruptedException, ExecutionException {
        int s = state;
        if (s <= COMPLETING)
            s = awaitDone(false, 0L);
        return report(s);
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    public V get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null)
            throw new NullPointerException();
        int s = state;
        if (s <= COMPLETING &&
                // 如果awaitDone()超时返回之后任务还没结束，则抛出异常
            (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            throw new TimeoutException();
        return report(s);
    }

    /**
     * Protected method invoked when this task transitions to state
     * {@code isDone} (whether normally or via cancellation). The
     * default implementation does nothing.  Subclasses may override
     * this method to invoke completion callbacks or perform
     * bookkeeping. Note that you can query status inside the
     * implementation of this method to determine whether this task
     * has been cancelled.
     */
    //钩子方法，默认无实现，用于在任务done的时候执行某些回调事件
    protected void done() { }

    /**
     * Sets the result of this future to the given value unless
     * this future has already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon successful completion of the computation.
     *
     * @param v the value
     */
    /*
    首先会CAS的把当前的状态从NEW变更为COMPLETING状态。
    把任务执行结果保存在outcome字段中。
    CAS的把当前任务状态从COMPLETING变更为NORMAL。这个状态转换对应着上图中的一。
    调用finishCompletion()。
     */
    protected void set(V v) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = v;
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL); // final state
            finishCompletion();
        }
    }

    /**
     * Causes this future to report an {@link ExecutionException}
     * with the given throwable as its cause, unless this future has
     * already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon failure of the computation.
     *
     * @param t the cause of failure
     */
    /*
    首先会CAS的把当前的状态从NEW变更为COMPLETING状态。
    把异常原因保存在outcome字段中，outcome字段用来保存任务执行结果或者异常原因。
    CAS的把当前任务状态从COMPLETING变更为EXCEPTIONAL
     */
    protected void setException(Throwable t) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = t;
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL); // final state
            finishCompletion();
        }
    }

    public void run() {
        // 1. 状态如果不是NEW，说明任务或者已经执行过，或者已经被取消，直接返回
        // 2. 状态如果是NEW，则尝试把当前执行线程保存在runner字段中
        // 如果赋值失败则直接返回
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return;
        try {
            Callable<V> c = callable;
            if (c != null && state == NEW) {
                V result;
                boolean ran;
                try {
                    // 3. 执行任务
                    result = c.call();
                    ran = true;
                } catch (Throwable ex) {
                    result = null;
                    ran = false;
                    // 4. 任务异常
                    setException(ex);
                }
                if (ran)
                    //// 4. 任务正常执行完毕
                    set(result);
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            //为了防止并发调用run()方法，runner在state被设置之前必须不为null
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            int s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
    }

    /**
     * Executes the computation without setting its result, and then
     * resets this future to initial state, failing to do so if the
     * computation encounters an exception or is cancelled.  This is
     * designed for use with tasks that intrinsically execute more
     * than once.
     *
     * @return {@code true} if successfully run and reset
     */
    //执行任务，但是不设置结果，并且重置future到初始状态，如果任务运行时
    protected boolean runAndReset() {
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        //返回 是否执行成功，并且 state是否还是为New
        return ran && s == NEW;
    }

    /**
     * Ensures that any interrupt from a possible cancel(true) is only
     * delivered to a task while in run or runAndReset.
     */
    //确保任何可能因为调用cancel(true)是发生的中断，在run()或者runAndReset()方法运行时出现，而不会在run()或者runAndReset()方法结束以后出现
    private void handlePossibleCancellationInterrupt(int s) {
        // It is possible for our interrupter to stall before getting a
        // chance to interrupt us.  Let's spin-wait patiently.
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                Thread.yield(); // wait out pending interrupt

        // assert state == INTERRUPTED;

        // We want to clear any interrupt we may have received from
        // cancel(true).  However, it is permissible to use interrupts
        // as an independent mechanism for a task to communicate with
        // its caller, and there is no way to clear only the
        // cancellation interrupt.
        //
        // Thread.interrupted();
    }

    /**
     * Simple linked list nodes to record waiting threads in a Treiber
     * stack.  See other classes such as Phaser and SynchronousQueue
     * for more detailed explanation.
     */
    /**
     * 因为Future的get()/get(timeout)在task处于非完成状态时是需要阻塞等待的，
     * 如果多个线程进行get操作，显然需要一个链表/队列来维护这些等待线程，
     * 这就是waiters的意义所在。
     */
    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;
        WaitNode() { thread = Thread.currentThread(); }
    }

    /**
     * Removes and signals all waiting threads, invokes done(), and
     * nulls out callable.
     */
    //唤醒所有在get()方法上等待的线程，设置callable = null，调用回调方法done()
    //当任务正常结束或者异常时，都会调用finishCompletion去唤醒等待线程
    private void finishCompletion() {
        //遍历等待节点
        // assert state > COMPLETING;
        for (WaitNode q; (q = waiters) != null;) {
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
                for (;;) {
                    Thread t = q.thread;
                    if (t != null) {
                        q.thread = null;
                        //唤醒等待线程
                        LockSupport.unpark(t);
                    }
                    WaitNode next = q.next;
                    if (next == null)
                        break;
                    q.next = null; // unlink to help gc
                    q = next;
                }
                break;
            }
        }

        //钩子方法,可以被覆盖
        done();
        //清空callable
        callable = null;        // to reduce footprint
    }

    /**
     * Awaits completion or aborts on interrupt or timeout.
     *
     * @param timed true if use timed waits
     * @param nanos time to wait, if timed
     * @return state upon completion
     */
    private int awaitDone(boolean timed, long nanos)
        throws InterruptedException {
        // 计算等待截止时间
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        WaitNode q = null;
        boolean queued = false;
        for (;;) {
            // 1. 判断阻塞线程是否被中断,如果被中断则在等待队
            // 列中删除该节点并抛出InterruptedException异常
            if (Thread.interrupted()) {
                removeWaiter(q);
                throw new InterruptedException();
            }

            // 2. 获取当前状态，如果状态大于COMPLETING
            // 说明任务已经结束(要么正常结束，要么异常结束，要么被取消)
            // 则把thread显示置空，并返回结果
            int s = state;
            if (s > COMPLETING) {
                if (q != null)
                    q.thread = null;
                return s;
            }
            // 3. 如果状态处于中间状态COMPLETING
            // 表示任务已经结束但是任务执行线程还没来得及给outcome赋值
            // 这个时候让出执行权让其他线程优先执行
            else if (s == COMPLETING) // cannot time out yet
                Thread.yield();
            else if (q == null)
                // 4. 如果等待节点为空，则构造一个等待节点
                q = new WaitNode();
            // 5. 如果还没有入队列，则把当前节点加入waiters首节点并替换原来waiters
            else if (!queued)
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                     q.next = waiters, q);
            else if (timed) {
                // 如果需要等待特定时间，则先计算要等待的时间
                // 如果已经超时，则删除对应节点并返回对应的状态
                nanos = deadline - System.nanoTime();
                if (nanos <= 0L) {
                    removeWaiter(q);
                    return state;
                }
                // 6. 阻塞等待特定时间
                LockSupport.parkNanos(this, nanos);
            }
            else
                ////这边看上去可能有并发问题，比如上面代码中获取到state为NEW，判断完state并且将节点放入无锁栈以后，但是还没有执行到这一步时，任务运行完成，此时唤醒所有等待线程
                //此时，再执行下面语句，线程可能会永远阻塞在下面代码上，但是，其实不会，因为LockSuppoer.unpark()和LockSuppoer.park()是以计数的方式实现的
                LockSupport.park(this);
        }
    }

    /**
     * Tries to unlink a timed-out or interrupted wait node to avoid
     * accumulating garbage.  Internal nodes are simply unspliced
     * without CAS since it is harmless if they are traversed anyway
     * by releasers.  To avoid effects of unsplicing from already
     * removed nodes, the list is retraversed in case of an apparent
     * race.  This is slow when there are a lot of nodes, but we don't
     * expect lists to be long enough to outweigh higher-overhead
     * schemes.
     */
    //删除超时或者interrupted的等待节点
    private void removeWaiter(WaitNode node) {
        if (node != null) {
            node.thread = null;
            retry:
            for (;;) {          // restart on removeWaiter race //重新开始 removeWaiter 竞争
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    s = q.next;
                    if (q.thread != null)
                        pred = q;
                    else if (pred != null) {
                        pred.next = s;
                        if (pred.thread == null) // check for race
                            continue retry;
                    }
                    else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                          q, s))
                        continue retry;
                }
                break;
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    private static final long runnerOffset;
    private static final long waitersOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
