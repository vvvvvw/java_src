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

package java.lang.ref;

import sun.misc.Cleaner;
import sun.misc.JavaLangRefAccess;
import sun.misc.SharedSecrets;

/**
 * Abstract base class for reference objects.  This class defines the
 * operations common to all reference objects.  Because reference objects are
 * implemented in close cooperation with the garbage collector, this class may
 * not be subclassed directly.
 * 引用对象的抽象基类。此类定义了常用于所有引用对象的操作。因为引用对象是通过与垃圾回收器的密切合作来实现的，
 * 所以不能直接为此类创建子类。
 *
 * @author   Mark Reinhold
 * @since    1.2
 */

public abstract class Reference<T> {

    /* A Reference instance is in one of four possible internal states:
     *
     *     Active: Subject to special treatment by the garbage collector.  Some
     *     time after the collector detects that the reachability of the
     *     referent has changed to the appropriate state, it changes the
     *     instance's state to either Pending or Inactive, depending upon
     *     whether or not the instance was registered with a queue when it was
     *     created.  In the former case it also adds the instance to the
     *     pending-Reference list.  Newly-created instances are Active.
     *
     *     Pending: An element of the pending-Reference list, waiting to be
     *     enqueued by the Reference-handler thread.  Unregistered instances
     *     are never in this state.
     *
     *     Enqueued: An element of the queue with which the instance was
     *     registered when it was created.  When an instance is removed from
     *     its ReferenceQueue, it is made Inactive.  Unregistered instances are
     *     never in this state.
     *
     *     Inactive: Nothing more to do.  Once an instance becomes Inactive its
     *     state will never change again.
     *     /*
    todo
    Active：新创建的引用实例处于Active状态，但当GC检测到该实例引用的实际对象
    的可达性发生某些适当的改变(实际对象对于GC roots不可达)后，它的状态将会
    根据此实例是否注册在引用队列中而变成Pending或是Inactive。
    Pending：当引用实例被放置在pending-Reference list中时，它处于Pending状态。
    此时，该实例在等待一个叫Reference-handler的线程将此实例进行enqueue操作。
    如果某个引用实例没有注册在一个引用队列中，该实例将永远不会进入Pending状态。
    Enqueued： 当引用实例被添加到它注册在的引用队列中时，该实例处于Enqueued状态。
    当某个引用实例被从引用队列中删除后，该实例将从Enqueued状态变为Inactive状态。
    如果某个引用实例没有注册在一个引用队列中，该实例将永远不会进入Enqueued状态。
    Inactive：一旦某个引用实例处于Inactive状态，它的状态将不再会发生改变，
    同时说明该引用实例所指向的实际对象一定会被GC所回收。
     *
     * The state is encoded in the queue and next fields as follows:
     *
     *     Active: queue = ReferenceQueue with which instance is registered, or
     *     ReferenceQueue.NULL if it was not registered with a queue; next =
     *     null.
     *
     *     Pending: queue = ReferenceQueue with which instance is registered;
     *     next = this
     *
     *     Enqueued: queue = ReferenceQueue.ENQUEUED; next = Following instance
     *     in queue, or this if at end of list.
     *
     *     Inactive: queue = ReferenceQueue.NULL; next = this.
     *
     *     todo
     *     事实上Reference类并没有显示地定义内部状态值，JVM仅需要通过成员queue和next的值
     *     就可以判断当前引用实例处于哪个状态
    Active：queue为创建引用实例时传入的ReferenceQueue的实例或是ReferenceQueue.NULL；next为null
    Pending：queue为创建引用实例时传入的ReferenceQueue的实例；next为this
    Enqueued：queue为ReferenceQueue.ENQUEUED；next为队列中下一个需要被处理的实例
    或是this如果该实例为队列中的最后一个
    Inactive：queue为ReferenceQueue.NULL；next为this
     *
     * With this scheme the collector need only examine the next field in order
     * to determine whether a Reference instance requires special treatment: If
     * the next field is null then the instance is active; if it is non-null,
     * then the collector should treat the instance normally.
     *
     * To ensure that a concurrent collector can discover active Reference
     * objects without interfering with application threads that may apply
     * the enqueue() method to those objects, collectors should link
     * discovered objects through the discovered field. The discovered
     * field is also used for linking Reference objects in the pending list.
     */

    // 用于保存对象的引用，GC会根据不同Reference来特别对待
    private T referent;         /* Treated specially by GC */

/*
一个构造函数带需要注册到的引用队列，一个不带。
带queue的意义在于我们可以从外部通过对queue的操作来了解到引用实例
所指向的实际对象是否被回收了，同时我们也可以通过queue对引用实例进行
一些额外的操作；但如果我们的引用实例在创建时没有指定一个引用队列，
那我们要想知道实际对象是否被回收，就只能够不停地轮询引用实例的get()方法
是否为空了。值得注意的是虚引用PhantomReference，由于它的get()方法永远返回null，
因此它的构造函数必须指定一个引用队列。这两种查询实际对象是否被回收的方法都有应用，
如weakHashMap中就选择去查询queue的数据，来判定是否有对象将被回收；
而ThreadLocalMap，则采用判断get()是否为null来作处理。
*/
    // 如果需要通知机制，则保存的对对应的队列
    volatile ReferenceQueue<? super T> queue;

    /* When active:   NULL
     *     pending:   this
     *    Enqueued:   next reference in queue (or this if last)
     *    Inactive:   this
     */
    /* todo 可以用来判断节点处于哪个状态，这个用于实现一个单向循环链表，用以将保存需要由ReferenceHandler处理的引用 */
    //next用来表示当前引用实例的下一个需要被处理的引用实例
    @SuppressWarnings("rawtypes")
    Reference next;

    /* When active:   next element in a discovered reference list maintained by GC (or this if last)
     *     pending:   next element in the pending list (or null if last)
     *   otherwise:   NULL
     */
    //用来表示下一个需要被处理的实例
    transient private Reference<T> discovered;  /* used by VM */


    /* Object used to synchronize with the garbage collector.  The collector
     * must acquire this lock at the beginning of each collection cycle.  It is
     * therefore critical that any code holding this lock complete as quickly
     * as possible, allocate no new objects, and avoid calling user code.
     */
    static private class Lock { }
    // 锁，用于同步pending队列的进队和出队
    private static Lock lock = new Lock();


    /* List of References waiting to be enqueued.  The collector adds
     * References to this list, while the Reference-handler thread removes
     * them.  This list is protected by the above lock object. The
     * list uses the discovered field to link its elements.
     * pending list使用discovered来链接元素
     */
    //GC检测到某个引用实例指向的实际对象不可达后，会将该pending指向该引用实例（todo gc维护的应该是discovered字段）
    /*
    todo 和discover配合组成一个list，
    因为GC检测到某个引用实例指向的实际对象不可达后，
    会将该pending指向该引用实例，discovered字段则是用来表示
    下一个需要被处理的实例 （todo gc维护的应该是discovered字段），因此我们只要不断地在处理完当前pending之后，
    将discovered指向的实例赋予给pending即可。所以这个static字段pending其实就是一个链表。
     */
    private static Reference<Object> pending = null;

    /* High-priority thread to enqueue pending References
     */
    // 此线程在静态块中启动，即一旦使用了Reference，则会启动该线程
    private static class ReferenceHandler extends Thread {

        private static void ensureClassInitialized(Class<?> clazz) {
            try {
                Class.forName(clazz.getName(), true, clazz.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw (Error) new NoClassDefFoundError(e.getMessage()).initCause(e);
            }
        }

        static {
            // pre-load and initialize InterruptedException and Cleaner classes
            // so that we don't get into trouble later in the run loop if there's
            // memory shortage while loading/initializing them lazily.
            ensureClassInitialized(InterruptedException.class);
            ensureClassInitialized(Cleaner.class);
        }

        ReferenceHandler(ThreadGroup g, String name) {
            super(g, name);
        }

        public void run() {
            while (true) {
                tryHandlePending(true);
            }
        }
    }

    /**
     * Try handle pending {@link Reference} if there is one.<p>
     * Return {@code true} as a hint that there might be another
     * {@link Reference} pending or {@code false} when there are no more pending
     * {@link Reference}s at the moment and the program can do some other
     * useful work instead of looping.
     *
     * @param waitForNotify if {@code true} and there was no pending
     *                      {@link Reference}, wait until notified from VM
     *                      or interrupted; if {@code false}, return immediately
     *                      when there is no pending {@link Reference}.
     * @return {@code true} if there was a {@link Reference} pending and it
     *         was processed, or we waited for notification and either got it
     *         or thread was interrupted before being notified;
     *         {@code false} otherwise.
     */
    static boolean tryHandlePending(boolean waitForNotify) {
        Reference<Object> r;
        Cleaner c;
        try {
            //和gc配合，因此需要锁
            synchronized (lock) {
                if (pending != null) {
                    r = pending;
                    // 'instanceof' might throw OutOfMemoryError sometimes
                    // so do this before un-linking 'r' from the 'pending' chain...
                    //todo 为cleaner实例不入队列，直接调用clean方法
                    c = r instanceof Cleaner ? (Cleaner) r : null;
                    // unlink 'r' from 'pending' chain
                    pending = r.discovered;
                    r.discovered = null;
                } else {
                    // The waiting on the lock may cause an OutOfMemoryError
                    // because it may try to allocate exception objects.
                    if (waitForNotify) {
                        lock.wait();
                    }
                    // retry if waited
                    return waitForNotify;
                }
            }
        } catch (OutOfMemoryError x) {
            // Give other threads CPU time so they hopefully drop some live references
            // and GC reclaims some space.
            // Also prevent CPU intensive spinning in case 'r instanceof Cleaner' above
            // persistently throws OOME for some time...
            Thread.yield();
            // retry
            return true;
        } catch (InterruptedException x) {
            // retry
            return true;
        }

        // Fast path for cleaners
        if (c != null) {
            c.clean();
            return true;
        }

        ReferenceQueue<? super Object> q = r.queue;
        if (q != ReferenceQueue.NULL) q.enqueue(r); //入队列，如果成功则设值queue为ReferenceQueue.ENQUEUED
        return true;
    }

    static {
        ThreadGroup tg = Thread.currentThread().getThreadGroup();
        for (ThreadGroup tgn = tg;
             tgn != null;
             tg = tgn, tgn = tg.getParent());
        Thread handler = new ReferenceHandler(tg, "Reference Handler");
        /* If there were a special system-only priority greater than
         * MAX_PRIORITY, it would be used here
         */
        //优先级最高
        handler.setPriority(Thread.MAX_PRIORITY);
        handler.setDaemon(true);
        handler.start();

        // provide access in SharedSecrets
        SharedSecrets.setJavaLangRefAccess(new JavaLangRefAccess() {
            @Override
            public boolean tryHandlePendingReference() {
                return tryHandlePending(false);
            }
        });
    }

    /* -- Referent accessor and setters -- */

    /**
     * Returns this reference object's referent.  If this reference object has
     * been cleared, either by the program or by the garbage collector, then
     * this method returns <code>null</code>.
     *
     * @return   The object to which this reference refers, or
     *           <code>null</code> if this reference object has been cleared
     */
    //简单的返回引用实例所引用的实际对象，如果该对象被回收了或者该引用实例被clear了则返回null
    public T get() {
        return this.referent;
    }

    /**
     * Clears this reference object.  Invoking this method will not cause this
     * object to be enqueued.
     *
     * <p> This method is invoked only by Java code; when the garbage collector
     * clears references it does so directly, without invoking this method.
     */
    /*
    调用此方法不会导致此对象入队。此方法仅由Java代码调用；当垃圾收集器清除引用时，它直接执行，而不调用此方法。
clear的方法本质上就是将referent置为null，清除引用实例所引用的实际对象，这样通过get()方法就不能再访问到实际对象了。
     */
    public void clear() {
        this.referent = null;
    }


    /* -- Queue operations -- */

    /**
     * Tells whether or not this reference object has been enqueued, either by
     * the program or by the garbage collector.  If this reference object was
     * not registered with a queue when it was created, then this method will
     * always return <code>false</code>.
     *
     * @return   <code>true</code> if and only if this reference object has
     *           been enqueued
     */
    //判断此引用实例是否已经被放入队列中是通过引用队列实例是否等于ReferenceQueue.ENQUEUED来得知的。
    public boolean isEnqueued() {
        return (this.queue == ReferenceQueue.ENQUEUED);
    }

    /**
     * Adds this reference object to the queue with which it is registered,
     * if any.
     *
     * <p> This method is invoked only by Java code; when the garbage collector
     * enqueues references it does so directly, without invoking this method.
     *
     * @return   <code>true</code> if this reference object was successfully
     *           enqueued; <code>false</code> if it was already enqueued or if
     *           it was not registered with a queue when it was created
     */
    //enqueue()方法能够手动将引用实例加入到引用队列当中去。
    public boolean enqueue() {
        return this.queue.enqueue(this);
    }


    /* -- Constructors -- */

    Reference(T referent) {
        this(referent, null);
    }

    Reference(T referent, ReferenceQueue<? super T> queue) {
        this.referent = referent;
        //没有传入队列，则使用ReferenceQueue.NULL
        this.queue = (queue == null) ? ReferenceQueue.NULL : queue;
    }

}
