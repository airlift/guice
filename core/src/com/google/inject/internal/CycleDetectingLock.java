package com.google.inject.internal;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;

import java.util.ArrayList;
import java.util.List;
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
     *
     * <h2>Concurrency design</h2>
     *
     * <p>Uncontended acquisition and release - the entirety of eager singleton creation when
     * injectors are built on independent threads - runs without the global monitor. That is safe
     * because cycle <i>detection</i> (only reachable once a lock is contended) follows exactly two
     * kinds of edges: a lock's {@link ReentrantCycleDetectingLock#lockOwnerState} (volatile) and a
     * thread's {@link ThreadState#waitingOn} (only ever written under the global monitor, because
     * only contended threads wait). The per-thread owned-locks chain is intrusive
     * ({@link ThreadState#topOwned} / {@link ReentrantCycleDetectingLock#prevOwned}) and written
     * only by its owner thread with a publication discipline walkers can rely on: a lock is linked
     * into the chain <i>before</i> its owner is published, and its owner is cleared <i>before</i>
     * it is unlinked. A walker that read a lock's owner therefore always finds the lock in that
     * owner's chain, unless the lock was concurrently released - in which case the deadlock premise
     * has vanished and the walk simply stops. The chain contents only decorate the reported cycle.
     *
     * <p>When assertions are enabled every path takes the global monitor, keeping the internal
     * invariant checks exact for tests.
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
         * looks a thread up. State dies with its thread.
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
         * Lock-graph state of one thread. {@link #waitingOn} is guarded by {@code
         * CycleDetectingLockFactory.class}; {@link #topOwned} is written only by the owning thread,
         * volatile so walkers see a consistently linked chain.
         */
        private static final class ThreadState
        {
            final Thread thread;

            /**
             * Lock this thread is blocked on, if any. Set before {@link Lock#lock} is called and
             * cleared after it returns. The same lock can be waited on by several threads. Guarded
             * by {@code CycleDetectingLockFactory.class}: only contended acquisitions ever wait.
             */
            ReentrantCycleDetectingLock<?> waitingOn;

            /**
             * Head (most recently acquired) of the intrusive chain of locks this thread owns,
             * linked through {@link ReentrantCycleDetectingLock#prevOwned}. Pushed once per
             * ownership on first acquisition; reentrant acquisitions do not push again. Written
             * only by the owning thread.
             */
            volatile ReentrantCycleDetectingLock<?> topOwned;

            ThreadState(Thread thread)
            {
                this.thread = thread;
            }

            /**
             * Owner thread only. Links before the caller publishes ownership.
             */
            void push(ReentrantCycleDetectingLock<?> lock)
            {
                lock.prevOwned = topOwned;
                topOwned = lock;
            }

            /**
             * Owner thread only. The caller has already cleared the lock's ownership. Releases are
             * nested in practice, so the lock is almost always the head; the fallback handles any
             * out-of-order release.
             */
            void unlink(ReentrantCycleDetectingLock<?> lock)
            {
                if (topOwned == lock) {
                    topOwned = lock.prevOwned;
                }
                else {
                    ReentrantCycleDetectingLock<?> node = topOwned;
                    while (node != null && node.prevOwned != lock) {
                        node = node.prevOwned;
                    }
                    checkState(
                            node != null,
                            "Internal error: Can not find this lock in locks owned by a current thread");
                    node.prevOwned = lock.prevOwned;
                }
                lock.prevOwned = null;
            }

            /**
             * Snapshot of the owned chain, oldest acquisition first. Safe to call from walker
             * threads; may reflect a mix of before and after states of concurrent releases, which
             * the caller must tolerate.
             */
            List<ReentrantCycleDetectingLock<?>> ownedOldestFirst()
            {
                List<ReentrantCycleDetectingLock<?>> owned = new ArrayList<>();
                for (ReentrantCycleDetectingLock<?> node = topOwned; node != null; node = node.prevOwned) {
                    owned.add(node);
                }
                return owned.reversed();
            }

            boolean owns(ReentrantCycleDetectingLock<?> lock)
            {
                for (ReentrantCycleDetectingLock<?> node = topOwned; node != null; node = node.prevOwned) {
                    if (node == lock) {
                        return true;
                    }
                }
                return false;
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
             * State of the thread that owns this lock. Nullable. Published after the lock is linked
             * into the owner's chain and cleared before it is unlinked; volatile so the cycle walk
             * can follow it without the monitor.
             */
            private volatile ThreadState lockOwnerState;

            /**
             * Next-older link of the owner's intrusive owned-locks chain. Written only by the owner
             * thread; see {@link ThreadState#topOwned}.
             */
            ReentrantCycleDetectingLock<?> prevOwned;

            /**
             * Number of times that thread owned this lock. Written only by the owner thread.
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
                // nobody else can be waiting through this lock. Publish ownership with the
                // chain-first ordering the cycle walk relies on; no monitor needed. With assertions
                // enabled the monitor path below runs instead so invariant checks stay exact.
                if (!CHECK_INVARIANTS && lockImplementation.tryLock()) {
                    publishOwnership();
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
                    publishOwnership();
                }
                // no deadlock is found, locking successful
                return ImmutableListMultimap.of();
            }

            /**
             * Owner thread only. Records one acquisition and, on the first one, publishes
             * ownership chain-first, the ordering the cycle walk relies on.
             */
            private void publishOwnership()
            {
                if (lockReentranceCount++ == 0) {
                    ThreadState current = THREAD_STATE.get();
                    current.push(this);
                    lockOwnerState = current;
                }
            }

            @Override
            public void unlock()
            {
                if (!CHECK_INVARIANTS) {
                    releaseOwnership();
                    return;
                }
                // be sure to release the lock synchronously with updating internal state
                synchronized (CycleDetectingLockFactory.class) {
                    checkInvariants();
                    releaseOwnership();
                }
            }

            /**
             * Owner thread only. Records one release and, on the last one, clears ownership
             * before unlinking so walkers that still see the chain link no longer see an owner.
             */
            private void releaseOwnership()
            {
                ThreadState owner = lockOwnerState;
                checkState(
                        owner != null, "Thread is trying to unlock a lock that is not locked");
                checkState(
                        owner.thread == Thread.currentThread(),
                        "Thread is trying to unlock a lock owned by another thread");
                lockImplementation.unlock();
                if (--lockReentranceCount == 0) {
                    lockOwnerState = null;
                    owner.unlink(this);
                }
            }

            private Thread ownerThread()
            {
                ThreadState owner = lockOwnerState;
                return owner == null ? null : owner.thread;
            }

            /**
             * Runs the internal-consistency checks only when assertions are enabled: they guard
             * against bugs in this class, not user error. When enabled, all lock and unlock paths
             * take the global factory monitor so the checks observe exact state. Surefire enables
             * assertions by default, so every test execution exercises them.
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
                ThreadState owner = lockOwnerState;
                if (owner != null) {
                    // check state of a locked lock
                    checkState(
                            lockReentranceCount >= 0,
                            "Internal error: Lock ownership and reentrance count internal states do not match");
                    checkState(
                            owner.owns(this),
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
                                !state.owns(this),
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
                ThreadState firstOwner = lockOwnerState;
                if (firstOwner == null || firstOwner.thread == currentThread) {
                    // if nobody owns this lock, lock cycle is impossible
                    // if a current thread owns this lock, we let Guice to handle it
                    return ImmutableListMultimap.of();
                }

                ListMultimap<Thread, ID> potentialLocksCycle =
                        MultimapBuilder.linkedHashKeys().arrayListValues().build();
                // lock that is a part of a potential locks cycle, starts with current lock
                ReentrantCycleDetectingLock<?> lockOwnerWaitingOn = this;
                // try to find a dependency path between lock's owner thread and a current thread
                while (lockOwnerWaitingOn != null) {
                    ThreadState ownerState = lockOwnerWaitingOn.lockOwnerState;
                    if (ownerState == null) {
                        // The lock was released while we walked - the deadlock premise vanished.
                        break;
                    }
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
                for (ReentrantCycleDetectingLock<?> ownedLock : state.ownedOldestFirst()) {
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
                if (!found) {
                    // The lock was released between reading its owner and walking the owner's
                    // chain; without the lock still held there is no deadlock through it.
                    return null;
                }
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
