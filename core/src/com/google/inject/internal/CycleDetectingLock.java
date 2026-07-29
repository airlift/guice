package com.google.inject.internal;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/**
 * Simplified version of {@link Lock} that is special due to how it handles deadlocks detection.
 *
 * <p>Is an inherent part of {@link SingletonScope}, moved into a upper level class due to its size
 * and complexity.
 *
 * @param <ID> Lock identification provided by the client, is returned unmodified to the client when
 *         lock cycle is detected to identify it. Only toString() needs to be implemented. Lock
 *         references this object internally, for the purposes of Garbage Collection you should not use
 *         heavy IDs. Lock is referenced by a lock factory as long as it's owned by a thread.
 * @see SingletonScope
 * @see com.google.inject.internal.CycleDetectingLock.CycleDetectingLockFactory
 * @author timofeyb (Timothy Basanov)
 */
interface CycleDetectingLock<ID>
{
    /**
     * Takes a lock in a blocking fashion in case no potential deadlocks are detected. If the lock was
     * successfully owned, returns an empty map indicating no detected potential deadlocks.
     *
     * <p>Otherwise, a map indicating threads involved in a potential deadlock are returned. Map is
     * ordered by dependency cycle and lists locks for each thread that are part of the loop in order,
     * the last lock in the list is the one that the thread is currently waiting for. Returned map is
     * created atomically.
     *
     * <p>In case no cycle is detected performance is O(threads creating singletons), in case cycle is
     * detected performance is O(singleton locks).
     */
    ListMultimap<Thread, ID> lockOrDetectPotentialLocksCycle();

    /**
     * Unlocks previously locked lock.
     */
    void unlock();

    /**
     * Wraps locks so they would never cause a deadlock. On each {@link
     * CycleDetectingLock#lockOrDetectPotentialLocksCycle} we check for dependency cycles within locks
     * created by the same factory. Either we detect a cycle and return it or take it atomically.
     *
     * <p>Important to note that we do not prevent deadlocks in the client code. As an example: Thread
     * A takes lock L and creates singleton class CA depending on the singleton class CB. Meanwhile
     * thread B is creating class CB and is waiting on the lock L. Issue happens due to client code
     * creating interdependent classes and using locks, where no guarantees on the creation order from
     * Guice are provided.
     *
     * <p>Instances of these locks are not intended to be exposed outside of {@link SingletonScope}.
     */
    class CycleDetectingLockFactory<ID>
    {
        /**
         * Registry of every thread's state, kept only for the assertion-gated invariant that an
         * unlocked lock is owned by no thread. Weak keys, so entries vanish with their threads.
         * Guarded by {@code CycleDetectingLockFactory.class}.
         */
        private static final Map<Thread, ThreadState> allThreadStates = new WeakHashMap<>();

        /**
         * Lock-graph state of the current thread. A ThreadLocal rather than a shared map: the cycle
         * walk follows {@code lockOwnerState} and {@code waitingOn} references directly and never
         * looks a thread up, so per-singleton bookkeeping is field writes and deque pushes with no
         * map churn under the global monitor. State dies with its thread.
         *
         * <p>The state's mutable fields are still guarded by {@code CycleDetectingLockFactory.class};
         * only the lookup is thread-local.
         */
        private static final ThreadLocal<ThreadState> THREAD_STATE =
                ThreadLocal.withInitial(
                        () -> {
                            ThreadState state = new ThreadState(Thread.currentThread());
                            if (ReentrantCycleDetectingLock.CHECK_INVARIANTS) {
                                synchronized (CycleDetectingLockFactory.class) {
                                    allThreadStates.put(state.thread, state);
                                }
                            }
                            return state;
                        });

        /**
         * Lock-graph state of one thread. Guarded by {@code CycleDetectingLockFactory.class}.
         */
        private static final class ThreadState
        {
            final Thread thread;

            /**
             * Lock this thread is blocked on, if any. Set before {@link Lock#lock} is called and
             * cleared after it returns. The same lock can be waited on by several threads.
             */
            ReentrantCycleDetectingLock<?> waitingOn;

            /**
             * Stack of locks this thread owns, pushed once per ownership on first acquisition;
             * reentrant acquisitions do not push again.
             */
            final ArrayDeque<ReentrantCycleDetectingLock<?>> ownedLocks = new ArrayDeque<>();

            ThreadState(Thread thread)
            {
                this.thread = thread;
            }
        }

        /**
         * Creates new lock within this factory context. We can guarantee that locks created by the same
         * factory would not deadlock.
         *
         * @param userLockId lock id that would be used to report lock cycles if detected
         */
        CycleDetectingLock<ID> create(ID userLockId)
        {
            return new ReentrantCycleDetectingLock<ID>(this, userLockId, new ReentrantLock());
        }

        /**
         * The implementation for {@link CycleDetectingLock}.
         */
        static class ReentrantCycleDetectingLock<ID>
                implements CycleDetectingLock<ID>
        {
            /**
             * Underlying lock used for actual waiting when no potential deadlocks are detected.
             */
            private final Lock lockImplementation;
            /**
             * User id for this lock.
             */
            private final ID userLockId;
            /**
             * Factory that was used to create this lock.
             */
            private final CycleDetectingLockFactory<ID> lockFactory;
            /**
             * State of the thread that owns this lock. Nullable. Guarded by {@code
             * CycleDetectingLockFactory.class}. Holding the state instead of the thread lets the
             * cycle walk follow owner references without map lookups.
             */
            private ThreadState lockOwnerState;

            /**
             * Number of times that thread owned this lock. Guarded by {@code
             * CycleDetectingLockFactory.this}.
             */
            private int lockReentranceCount;

            ReentrantCycleDetectingLock(
                    CycleDetectingLockFactory<ID> lockFactory,
                    ID userLockId,
                    Lock lockImplementation)
            {
                this.lockFactory = lockFactory;
                this.userLockId = requireNonNull(userLockId, "userLockId");
                this.lockImplementation =
                        requireNonNull(lockImplementation, "lockImplementation");
            }

            @Override
            public ListMultimap<Thread, ID> lockOrDetectPotentialLocksCycle()
            {
                final Thread currentThread = Thread.currentThread();
                // Fast path: an uncontended (or reentrant) acquisition cannot create a lock cycle -
                // nobody else owns the lock, so no thread can be waiting on us through it. Skip the
                // waiting-mark and cycle walk, and take the global monitor once for ownership
                // bookkeeping instead of twice. Threads that fail the tryLock are genuinely
                // contended and take the slow path below, so cycle detection sees every waiter.
                // The window in which the underlying lock is held but lockOwnerState is not yet
                // published is identical to the one the slow path always had between acquiring the
                // lock and entering its second synchronized block.
                if (lockImplementation.tryLock()) {
                    synchronized (CycleDetectingLockFactory.class) {
                        checkInvariants();
                        ThreadState current = THREAD_STATE.get();
                        lockOwnerState = current;
                        if (lockReentranceCount++ == 0) {
                            current.ownedLocks.addLast(this);
                        }
                    }
                    return ImmutableListMultimap.of();
                }
                synchronized (CycleDetectingLockFactory.class) {
                    checkInvariants();
                    // Only do work if this thread doesn't already own the lock.
                    // If we're attempting to re-enter our own lock, then we're not going to wait to lock.
                    // Otherwise, if we mark ourselves as waiting, another thread attempting to
                    // lock may end up looping forever while detecting cycles (which will OOM).  See
                    // https://github.com/google/guice/issues/1510 &
                    // https://github.com/google/guice/pull/1635.
                    // Note that it's not possible for the owner thread to concurrently relinquish the lock,
                    // because _this_ is the owner thread, and the next line of code this thread will execute
                    // is `lockImplementation.lock()`.
                    if (ownerThread() != currentThread) {
                        // Mark this thread as waiting so the lock is included in any reported cycle.
                        ThreadState current = THREAD_STATE.get();
                        current.waitingOn = this;
                        ListMultimap<Thread, ID> locksInCycle = detectPotentialLocksCycle(currentThread);
                        if (!locksInCycle.isEmpty()) {
                            // We aren't actually going to wait for this lock.
                            current.waitingOn = null;
                            // potential deadlock is found, we don't try to take this lock
                            return locksInCycle;
                        }
                    }
                }

                // this may be blocking, but we don't expect it to cause a deadlock
                lockImplementation.lock();

                synchronized (CycleDetectingLockFactory.class) {
                    // current thread is no longer waiting on this lock
                    ThreadState current = THREAD_STATE.get();
                    current.waitingOn = null;
                    checkInvariants();

                    // mark it as owned by us
                    lockOwnerState = current;
                    // add this lock to the stack of locks owned by a current thread, once per ownership
                    if (lockReentranceCount++ == 0) {
                        current.ownedLocks.addLast(this);
                    }
                }
                // no deadlock is found, locking successful
                return ImmutableListMultimap.of();
            }

            @Override
            public void unlock()
            {
                final Thread currentThread = Thread.currentThread();
                synchronized (CycleDetectingLockFactory.class) {
                    checkInvariants();
                    checkState(
                            lockOwnerState != null, "Thread is trying to unlock a lock that is not locked");
                    checkState(
                            lockOwnerState.thread == currentThread,
                            "Thread is trying to unlock a lock owned by another thread");

                    // releasing underlying lock
                    lockImplementation.unlock();

                    // be sure to release the lock synchronously with updating internal state
                    lockReentranceCount--;
                    if (lockReentranceCount == 0) {
                        // we no longer own this lock
                        ThreadState owner = lockOwnerState;
                        lockOwnerState = null;
                        checkState(
                                owner.ownedLocks.removeLastOccurrence(this),
                                "Internal error: Can not find this lock in locks owned by a current thread");
                    }
                }
            }

            private Thread ownerThread()
            {
                return lockOwnerState == null ? null : lockOwnerState.thread;
            }

            /**
             * Runs the internal-consistency checks only when assertions are enabled: they guard
             * against bugs in this class, not user error, and they run under the global factory
             * monitor twice per singleton lock and once per unlock. Surefire enables assertions by
             * default, so every test execution still exercises them.
             */
            private static final boolean CHECK_INVARIANTS =
                    CycleDetectingLock.class.desiredAssertionStatus();

            /**
             * Check consistency of an internal state.
             */
            void checkInvariants()
                    throws IllegalStateException
            {
                if (!CHECK_INVARIANTS) {
                    return;
                }
                ThreadState current = THREAD_STATE.get();
                checkState(
                        current.waitingOn == null,
                        "Internal error: Thread should not be in a waiting thread on a lock now");
                if (lockOwnerState != null) {
                    // check state of a locked lock
                    checkState(
                            lockReentranceCount >= 0,
                            "Internal error: Lock ownership and reentrance count internal states do not match");
                    checkState(
                            lockOwnerState.ownedLocks.contains(this),
                            "Internal error: Set of locks owned by a current thread and lock "
                                    + "ownership status do not match");
                }
                else {
                    // check state of a non locked lock
                    checkState(
                            lockReentranceCount == 0,
                            "Internal error: Reentrance count of a non locked lock is expect to be zero");
                    for (ThreadState state : allThreadStates.values()) {
                        checkState(
                                !state.ownedLocks.contains(this),
                                "Internal error: Non locked lock should not be owned by any thread");
                    }
                }
            }

            /**
             * Algorithm to detect a potential lock cycle.
             *
             * <p>For lock's thread owner check which lock is it trying to take. Repeat recursively. When
             * current thread is found a potential cycle is detected.
             *
             * @see CycleDetectingLock#lockOrDetectPotentialLocksCycle()
             */
            private ListMultimap<Thread, ID> detectPotentialLocksCycle(Thread currentThread)
            {
                if (lockOwnerState == null || lockOwnerState.thread == currentThread) {
                    // if nobody owns this lock, lock cycle is impossible
                    // if a current thread owns this lock, we let Guice to handle it
                    return ImmutableListMultimap.of();
                }

                ListMultimap<Thread, ID> potentialLocksCycle =
                        MultimapBuilder.linkedHashKeys().arrayListValues().build();
                // lock that is a part of a potential locks cycle, starts with current lock
                ReentrantCycleDetectingLock<?> lockOwnerWaitingOn = this;
                // try to find a dependency path between lock's owner thread and a current thread
                while (lockOwnerWaitingOn != null && lockOwnerWaitingOn.lockOwnerState != null) {
                    ThreadState ownerState = lockOwnerWaitingOn.lockOwnerState;
                    // in case locks cycle exists lock we're waiting for is part of it
                    lockOwnerWaitingOn =
                            addAllLockIdsAfter(ownerState, lockOwnerWaitingOn, potentialLocksCycle);
                    if (ownerState.thread == currentThread) {
                        // owner thread depends on current thread, cycle detected
                        return potentialLocksCycle;
                    }
                }
                // no dependency path from an owner thread to a current thread
                return ImmutableListMultimap.of();
            }

            /**
             * Adds all locks held by the given thread that are after the given lock and then returns the
             * lock the thread is currently waiting on, if any
             */
            private ReentrantCycleDetectingLock<?> addAllLockIdsAfter(
                    ThreadState state,
                    ReentrantCycleDetectingLock<?> lock,
                    ListMultimap<Thread, ID> potentialLocksCycle)
            {
                boolean found = false;
                requireNonNull(
                        state, "Internal error: No locks were found taken by a thread");
                for (ReentrantCycleDetectingLock<?> ownedLock : state.ownedLocks) {
                    if (ownedLock == lock) {
                        found = true;
                    }
                    if (found && ownedLock.lockFactory == this.lockFactory) {
                        // All locks are stored in a shared map therefore there is no way to
                        // enforce type safety. We know that our cast is valid as we check for a lock's
                        // factory. If the lock was generated by the
                        // same factory it has to have same type as the current lock.
                        @SuppressWarnings("unchecked")
                        ID userLockId = (ID) ownedLock.userLockId;
                        potentialLocksCycle.put(state.thread, userLockId);
                    }
                }
                checkState(
                        found, "Internal error: We can not find locks that created a cycle that we detected");
                ReentrantCycleDetectingLock<?> unownedLock = state.waitingOn;
                // If this thread is waiting for a lock add it to the cycle and return it
                if (unownedLock != null && unownedLock.lockFactory == this.lockFactory) {
                    @SuppressWarnings("unchecked")
                    ID typed = (ID) unownedLock.userLockId;
                    potentialLocksCycle.put(state.thread, typed);
                }
                return unownedLock;
            }

            @Override
            public String toString()
            {
                // copy is made to prevent a data race
                // no synchronization is used, potentially stale data, should be good enough
                ThreadState ownerState = this.lockOwnerState;
                if (ownerState != null) {
                    return "%s[%s][locked by %s]".formatted(super.toString(), userLockId, ownerState.thread);
                }
                else {
                    return "%s[%s][unlocked]".formatted(super.toString(), userLockId);
                }
            }
        }
    }
}
