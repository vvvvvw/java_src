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
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A synchronization aid that allows a set of threads to all wait for
 * each other to reach a common barrier point.  CyclicBarriers are
 * useful in programs involving a fixed sized party of threads that
 * must occasionally wait for each other. The barrier is called
 * <em>cyclic</em> because it can be re-used after the waiting threads
 * are released.
 * 一个同步辅助类，它允许一组线程互相等待，直到到达某个公共屏障点 (common barrier point)。
 * 在涉及一组固定大小的线程的程序中，这些线程必须不时地互相等待，此时 CyclicBarrier 很有用。
 * 因为该 barrier 在释放等待线程后可以重用，所以称它为循环 的 barrier。
 * <p>A {@code CyclicBarrier} supports an optional {@link Runnable} command
 * that is run once per barrier point, after the last thread in the party
 * arrives, but before any threads are released.
 * This <em>barrier action</em> is useful
 * for updating shared-state before any of the parties continue.
 * CyclicBarrier 支持一个可选的 Runnable 命令，在一组线程中的 最后一个线程到达 之后
 * （ 但在释放所有线程之前 ），该命令在每个屏障点运行一次。
 * 若在继续所有参与线程之前更新共享状态，此屏障操作 很有用。
 * <p><b>Sample usage:</b> Here is an example of using a barrier in a
 * parallel decomposition design:
 *
 *  <pre> {@code
 * class Solver {
 *   final int N;
 *   final float[][] data;
 *   final CyclicBarrier barrier;
 *
 *   class Worker implements Runnable {
 *     int myRow;
 *     Worker(int row) { myRow = row; }
 *     public void run() {
 *       while (!done()) {
 *         processRow(myRow);
 *
 *         try {
 *           barrier.await();
 *         } catch (InterruptedException ex) {
 *           return;
 *         } catch (BrokenBarrierException ex) {
 *           return;
 *         }
 *       }
 *     }
 *   }
 *
 *   public Solver(float[][] matrix) {
 *     data = matrix;
 *     N = matrix.length;
 *     Runnable barrierAction =
 *       new Runnable() { public void run() { mergeRows(...); }};
 *     barrier = new CyclicBarrier(N, barrierAction);
 *
 *     List<Thread> threads = new ArrayList<Thread>(N);
 *     for (int i = 0; i < N; i++) {
 *       Thread thread = new Thread(new Worker(i));
 *       threads.add(thread);
 *       thread.start();
 *     }
 *
 *     // wait until done
 *     for (Thread thread : threads)
 *       thread.join();
 *   }
 * }}</pre>
 *
 * Here, each worker thread processes a row of the matrix then waits at the
 * barrier until all rows have been processed. When all rows are processed
 * the supplied {@link Runnable} barrier action is executed and merges the
 * rows. If the merger
 * determines that a solution has been found then {@code done()} will return
 * {@code true} and each worker will terminate.
 *在这个例子中，每个 worker 线程处理矩阵的一行，在处理完所有的行之前，该线程将一直
 * 在屏障处等待。处理完所有的行之后，将执行所提供的 Runnable 屏障操作，并合并这些行。
 * 如果合并者确定已经找到了一个解决方案，那么 done() 将返回 true，所有的 worker 线程都将终止。
 * <p>If the barrier action does not rely on the parties being suspended when
 * it is executed, then any of the threads in the party could execute that
 * action when it is released. To facilitate this, each invocation of
 * {@link #await} returns the arrival index of that thread at the barrier.
 * You can then choose which thread should execute the barrier action, for
 * example:
 * 如果屏障操作在执行时不依赖于正挂起的线程，则线程组中的任何线程在获得释放时都能执行该操作。
 * 为方便此操作，每次调用 await() 都将返回能到达屏障处的线程的索引。
 * 然后，您可以选择哪个线程应该执行屏障操作，例如：
 *  <pre> {@code
 * if (barrier.await() == 0) {
 *   // log the completion of this iteration
 * }}</pre>
 *
 * <p>The {@code CyclicBarrier} uses an all-or-none breakage model
 * for failed synchronization attempts: If a thread leaves a barrier
 * point prematurely because of interruption, failure, or timeout, all
 * other threads waiting at that barrier point will also leave
 * abnormally via {@link BrokenBarrierException} (or
 * {@link InterruptedException} if they too were interrupted at about
 * the same time).
 *对于失败的同步尝试，CyclicBarrier 使用了一种要么全部要么全不 (all-or-none) 的破坏模式：
 * 如果因为中断、失败或者超时等原因，导致线程过早地离开了屏障点，那么在该屏障点等待的其他
 * 所有线程也将通过 BrokenBarrierException（如果它们几乎同时被中断，
 * 则用 InterruptedException）以反常的方式离开。
 * <p>Memory consistency effects: Actions in a thread prior to calling
 * {@code await()}
 * <a href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * actions that are part of the barrier action, which in turn
 * <i>happen-before</i> actions following a successful return from the
 * corresponding {@code await()} in other threads.
 *内存一致性效果：线程中调用 await() 之前的操作 happen-before 那些是屏障操作的一部份的操作，
 * 后者依次happen-before 紧跟在从另一个线程中对应 await() 成功返回的操作。
 * @since 1.5
 * @see CountDownLatch
 *
 * @author Doug Lea
 */
/*
实现原理：在CyclicBarrier的内部定义了一个Lock对象，每当一个线程调用
CyclicBarrier的await方法时，将剩余拦截的线程数减1，然后判断剩余拦截数是否为0，
如果不是，进入Lock对象的条件队列等待。如果是，执行barrierAction对象的Runnable方法，
然后将锁的条件队列中的所有线程放入锁等待队列中，这些线程会依次的获取锁、释放锁，
接着先从await方法返回，再从CyclicBarrier的await方法中返回。
 */
public class CyclicBarrier {
    /**
     * Each use of the barrier is represented as a generation instance.
     * The generation changes whenever the barrier is tripped, or
     * is reset. There can be many generations associated with threads
     * using the barrier - due to the non-deterministic way the lock
     * may be allocated to waiting threads - but only one of these
     * can be active at a time (the one to which {@code count} applies)
     * and all the rest are either broken or tripped.
     * There need not be an active generation if there has been a break
     * but no subsequent reset.
     */
    /*
    屏障的每一次使用都被表示为一个generation实例。 当屏障开闸或重置时，generation都会改变。
    可能有许多generations与使用屏障的线程相关联 - 由于非确定性的方式，锁可能被分配给等待
    的线程 - 但是其中每一次只有一个可以是激活的（count参数被应用的线程） ），其余的都是broken状态（由于其他特殊原因打破了CyclicBarrier（也就是当前CyclicBarrier无效了））或tripped(开闸状态)。
     如果有一个中断，但没有后续的重置，则不需要激活的generation实例。
     */
    private static class Generation {
        boolean broken = false;
    }

    /** The lock for guarding barrier entry */
    private final ReentrantLock lock = new ReentrantLock();////所有方法都通过这个锁来同步。之所以不使用内置锁主要是因为需要抛出异常。此外这里需要的实际上是共享锁，而内置锁不能实现共享锁。
    /** Condition to wait on until tripped */
    private final Condition trip = lock.newCondition();////通过lock得到的一个状态变量
    /** The number of parties */
    private final int parties;////通过构造器传入的参数，表示总的等待线程的数量。
    /* The command to run when tripped */
    private final Runnable barrierCommand;////当屏障正常打开后运行的程序，通过最后一个调用await的线程来执行。
    /** The current generation */
    private Generation generation = new Generation();//当前的Generation。每当屏障失效或者开闸之后都会自动替换掉。从而实现重置的功能。

    /**
     * Number of parties still waiting. Counts down from parties to 0
     * on each generation.  It is reset to parties on each new
     * generation or when broken.
     */
    //等待线程数量。在每一个generation实例都会从parties减小到0。当创建一个新的generation或者broken（失效），则将count重置为parties
    private int count;

    /**
     * Updates state on barrier trip and wakes up everyone.
     * Called only while holding lock.
     */
    //在屏障开闸之后重置状态。以待下一次调用。
    //更新barrier状态并唤醒所有等待线程
    //只有在持有锁的情况下调用
    private void nextGeneration() {
        // signal completion of last generation
        trip.signalAll();
        // set up next generation
        //重置count
        count = parties;
        generation = new Generation();
    }

    /**
     * Sets current barrier generation as broken and wakes up everyone.
     * Called only while holding lock.
     */
    //在屏障打破之后设定打破状态，以唤醒其他线程并通知。
    //设置当前屏障的generation状态为broken，并且唤醒所有等待线程
    //只在持有锁的情况下调用
    private void breakBarrier() {
        generation.broken = true;
        count = parties;
        trip.signalAll();
    }

    /**
     * Main barrier code, covering the various policies.
     */
    private int dowait(boolean timed, long nanos)
        throws InterruptedException, BrokenBarrierException,
               TimeoutException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final Generation g = generation;

            if (g.broken)//如果当前Generation是处于打破状态则传播这个BrokenBarrierExcption
                throw new BrokenBarrierException();

            //如果当前线程被中断则使得当前generation处于打破状态，重置剩余count。并且唤醒状态变量。这时候其他线程会传播
            // BrokenBarrierException.
            if (Thread.interrupted()) {
                breakBarrier();
                throw new InterruptedException();
            }
            //尝试降低当前count
            int index = --count;
            // tripped//如果当前状态将为0，则Generation处于开闸状态。运行可能存在的command，
            // 设置下一个Generation。相当于每次开闸之后都进行了一次reset
            //如果count减 小到0，则表示当前需要开闸，运行 屏障操作命令
            if (index == 0) {  // tripped
                boolean ranAction = false;
                try {
                    final Runnable command = barrierCommand;
                    if (command != null)
                        command.run();
                    ranAction = true;
                    nextGeneration();
                    return 0;
                } finally {
                    //如果执行屏障操作命令发生异常
                    if (!ranAction)
                        breakBarrier();
                }
            }

            // loop until tripped, broken, interrupted, or timed out
            //循环直到开闸、失效、中断或超时
            for (;;) {
                try {
                    //如果没有设置超时，则阻塞在当前的状态变量。
                    if (!timed)
                        trip.await();
                    else if (nanos > 0L)
                        nanos = trip.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    //如果当前线程被中断了则使得屏障被打破。并抛出异常。
                    if (g == generation && ! g.broken) {
                        breakBarrier();
                        throw ie;
                    } else {
                        // We're about to finish waiting even if we had not
                        // been interrupted, so this interrupt is deemed to
                        // "belong" to subsequent execution.
                        //这种捕获了InterruptException之后调用Thread.currentThread().interrupt()是一种通用的方式。但是之前源码中好像都没有体现。我第一次见这个好像是java并发实践中。这样做的目的是什么？其实就是为了保存中断状态，从而让其他更高层次的代码注意到这个中断。但是需要注意的是这里需要其他代码予以配合才行否则这样做其实是比较危险的一种方式，因为这相当于吞了这个异常。
                        Thread.currentThread().interrupt();
                    }
                }
                //从阻塞恢复之后，需要重新判断当前的状态。
                if (g.broken)
                    throw new BrokenBarrierException();

                //如果generation已经更改，返回index
                if (g != generation)
                    return index;
                //如果超时
                if (timed && nanos <= 0L) {
                    breakBarrier();
                    throw new TimeoutException();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Creates a new {@code CyclicBarrier} that will trip when the
     * given number of parties (threads) are waiting upon it, and which
     * will execute the given barrier action when the barrier is tripped,
     * performed by the last thread entering the barrier.
     *
     * @param parties the number of threads that must invoke {@link #await}
     *        before the barrier is tripped
     * @param barrierAction the command to execute when the barrier is
     *        tripped, or {@code null} if there is no action
     * @throws IllegalArgumentException if {@code parties} is less than 1
     */
    public CyclicBarrier(int parties, Runnable barrierAction) {
        if (parties <= 0) throw new IllegalArgumentException();
        this.parties = parties;
        this.count = parties;
        this.barrierCommand = barrierAction;
    }

    /**
     * Creates a new {@code CyclicBarrier} that will trip when the
     * given number of parties (threads) are waiting upon it, and
     * does not perform a predefined action when the barrier is tripped.
     *
     * @param parties the number of threads that must invoke {@link #await}
     *        before the barrier is tripped
     * @throws IllegalArgumentException if {@code parties} is less than 1
     */
    public CyclicBarrier(int parties) {
        this(parties, null);
    }

    /**
     * Returns the number of parties required to trip this barrier.
     *
     * @return the number of parties required to trip this barrier
     */
    public int getParties() {
        return parties;
    }

    /**
     * Waits until all {@linkplain #getParties parties} have invoked
     * {@code await} on this barrier.
     * 在所有 参与者都已经在此 barrier 上调用 await 方法之前，将一直等待。
     * <p>If the current thread is not the last to arrive then it is
     * disabled for thread scheduling purposes and lies dormant until
     * one of the following things happens:
     * 如果当前线程不是将到达的最后一个线程，出于调度目的，将禁用它，且在发生以下情况之一前，该线程将一直处于休眠状态：
     * <ul>
     * <li>The last thread arrives; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * one of the other waiting threads; or
     * <li>Some other thread times out while waiting for barrier; or
     * <li>Some other thread invokes {@link #reset} on this barrier.
     * </ul>
     *最后一个线程到达；或者
     其他某个线程中断当前线程；或者
     其他某个线程中断另一个等待线程；或者
     其他某个线程在等待 barrier 时超时；或者
     其他某个线程在此 barrier 上调用 reset()。
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     *如果当前线程：
     * 在进入此方法时已经设置了该线程的中断状态；或者
     在等待时被中断
     则抛出 InterruptedException，并且清除当前线程的已中断状态。
     * <p>If the barrier is {@link #reset} while any thread is waiting,
     * or if the barrier {@linkplain #isBroken is broken} when
     * {@code await} is invoked, or while any thread is waiting, then
     * {@link BrokenBarrierException} is thrown.
     *如果在线程处于等待状态时 barrier 被 reset()，或者在调用 await 时 barrier 被损坏，抑或任意一个线程正处于等待状态，
     * 则抛出 BrokenBarrierException 异常。
     * <p>If any thread is {@linkplain Thread#interrupt interrupted} while waiting,
     * then all other waiting threads will throw
     * {@link BrokenBarrierException} and the barrier is placed in the broken
     * state.
     *如果任何线程在等待时被 中断，则其他所有等待线程都将抛出 BrokenBarrierException 异常，
     * 并将 barrier 置于损坏状态。
     * <p>If the current thread is the last thread to arrive, and a
     * non-null barrier action was supplied in the constructor, then the
     * current thread runs the action before allowing the other threads to
     * continue.
     * If an exception occurs during the barrier action then that exception
     * will be propagated in the current thread and the barrier is placed in
     * the broken state.
     *如果当前线程是最后一个将要到达的线程，并且构造方法中提供了一个非空的屏障操作，
     * 则在允许其他线程继续运行之前，当前线程将运行该操作。如果在执行屏障操作过程中发生异常，
     * 则该异常将传播到当前线程中，并将 barrier 置于损坏状态。
     * @return the arrival index of the current thread, where index
     *         {@code getParties() - 1} indicates the first
     *         to arrive and zero indicates the last to arrive
     * @throws InterruptedException if the current thread was interrupted
     *         while waiting
     * @throws BrokenBarrierException if <em>another</em> thread was
     *         interrupted or timed out while the current thread was
     *         waiting, or the barrier was reset, or the barrier was
     *         broken when {@code await} was called, or the barrier
     *         action (if present) failed due to an exception
     */
    public int await() throws InterruptedException, BrokenBarrierException {
        try {
            return dowait(false, 0L);
        } catch (TimeoutException toe) {
            throw new Error(toe); // cannot happen
        }
    }

    /**
     * Waits until all {@linkplain #getParties parties} have invoked
     * {@code await} on this barrier, or the specified waiting time elapses.
     *
     * <p>If the current thread is not the last to arrive then it is
     * disabled for thread scheduling purposes and lies dormant until
     * one of the following things happens:
     * <ul>
     * <li>The last thread arrives; or
     * <li>The specified timeout elapses; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * one of the other waiting threads; or
     * <li>Some other thread times out while waiting for barrier; or
     * <li>Some other thread invokes {@link #reset} on this barrier.
     * </ul>
     *
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     *
     * <p>If the specified waiting time elapses then {@link TimeoutException}
     * is thrown. If the time is less than or equal to zero, the
     * method will not wait at all.
     *
     * <p>If the barrier is {@link #reset} while any thread is waiting,
     * or if the barrier {@linkplain #isBroken is broken} when
     * {@code await} is invoked, or while any thread is waiting, then
     * {@link BrokenBarrierException} is thrown.
     *
     * <p>If any thread is {@linkplain Thread#interrupt interrupted} while
     * waiting, then all other waiting threads will throw {@link
     * BrokenBarrierException} and the barrier is placed in the broken
     * state.
     *
     * <p>If the current thread is the last thread to arrive, and a
     * non-null barrier action was supplied in the constructor, then the
     * current thread runs the action before allowing the other threads to
     * continue.
     * If an exception occurs during the barrier action then that exception
     * will be propagated in the current thread and the barrier is placed in
     * the broken state.
     *
     * @param timeout the time to wait for the barrier
     * @param unit the time unit of the timeout parameter
     * @return the arrival index of the current thread, where index
     *         {@code getParties() - 1} indicates the first
     *         to arrive and zero indicates the last to arrive
     * @throws InterruptedException if the current thread was interrupted
     *         while waiting
     * @throws TimeoutException if the specified timeout elapses.
     *         In this case the barrier will be broken.
     * @throws BrokenBarrierException if <em>another</em> thread was
     *         interrupted or timed out while the current thread was
     *         waiting, or the barrier was reset, or the barrier was broken
     *         when {@code await} was called, or the barrier action (if
     *         present) failed due to an exception
     */
    public int await(long timeout, TimeUnit unit)
        throws InterruptedException,
               BrokenBarrierException,
               TimeoutException {
        return dowait(true, unit.toNanos(timeout));
    }

    /**
     * Queries if this barrier is in a broken state.
     *
     * @return {@code true} if one or more parties broke out of this
     *         barrier due to interruption or timeout since
     *         construction or the last reset, or a barrier action
     *         failed due to an exception; {@code false} otherwise.
     */
    public boolean isBroken() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return generation.broken;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resets the barrier to its initial state.  If any parties are
     * currently waiting at the barrier, they will return with a
     * {@link BrokenBarrierException}. Note that resets <em>after</em>
     * a breakage has occurred for other reasons can be complicated to
     * carry out; threads need to re-synchronize in some other way,
     * and choose one to perform the reset.  It may be preferable to
     * instead create a new barrier for subsequent use.
     */
    /*
    注意一下要先打破当前屏蔽，然后再重建一个新的屏蔽。否则的话可能会导致信号丢失。
     */
    /*
    将屏障重置为其初始状态。如果所有参与者目前都在屏障处等待，则它们将返回，
    同时抛出一个 BrokenBarrierException。注意，在由于其他原因造成损坏 之后，
    reset操作可能会变得很复杂；此时需要使用其他方式重新同步线程，并选择其中一个线程来执行reset。
    todo 为后续使用创建一个新 barrier可能更好一些。
     */
    public void reset() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            breakBarrier();   // break the current generation
            nextGeneration(); // start a new generation
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of parties currently waiting at the barrier.
     * This method is primarily useful for debugging and assertions.
     *
     * @return the number of parties currently blocked in {@link #await}
     */
    public int getNumberWaiting() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return parties - count;
        } finally {
            lock.unlock();
        }
    }
}
