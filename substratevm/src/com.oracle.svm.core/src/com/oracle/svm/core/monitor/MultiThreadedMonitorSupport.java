/*
 * Copyright (c) 2016, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.monitor;

import java.lang.ref.ReferenceQueue;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import org.graalvm.compiler.core.common.SuppressFBWarnings;
import org.graalvm.compiler.serviceprovider.JavaVersionUtil;
import org.graalvm.compiler.word.BarrieredAccess;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.WeakIdentityHashMap;
import com.oracle.svm.core.annotate.RestrictHeapAccess;
import com.oracle.svm.core.annotate.RestrictHeapAccess.Access;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.hub.DynamicHubCompanion;
import com.oracle.svm.core.jdk.JDK17OrEarlier;
import com.oracle.svm.core.monitor.JavaMonitorQueuedSynchronizer.JavaMonitorConditionObject;
import com.oracle.svm.core.snippets.SubstrateForeignCallTarget;
import com.oracle.svm.core.stack.StackOverflowCheck;
import com.oracle.svm.core.thread.ThreadStatus;
import com.oracle.svm.core.thread.VMOperationControl;
import com.oracle.svm.core.util.VMError;

import jdk.internal.misc.Unsafe;

/**
 * Implementation of synchronized-related operations.
 *
 * Most objects used in synchronization operations have a dedicated memory in the object to store a
 * {@link JavaMonitor}. The offset of this memory slot is not fixed, but stored separately for each
 * class, see {@link #getMonitorOffset}. The monitor is implemented with a {@link JavaMonitor}. The
 * first synchronization operation on an object lazily initializes the memory slot with a new
 * {@link JavaMonitor}.
 *
 * There are a few exceptions: Some classes {@link String} and {@link DynamicHub} never have a
 * monitor slot because we want instances in the image heap to be immutable. Arrays never have a
 * monitor slot because it would increase the size of every array and it is not possible to
 * distinguish between arrays with different header sizes. See
 * {@code UniverseBuilder.getImmutableTypes()} for details.
 * 
 * Synchronization on {@link String}, arrays, and other types not having a monitor slot fall back to
 * a monitor stored in {@link #additionalMonitors}. Synchronization of such objects is very slow and
 * not scaling well with more threads because the {@link #additionalMonitorsLock additional monitor
 * map lock} is a point of contention.
 *
 * Since {@link DynamicHub} is also the {@link java.lang.Class} object at run time and static
 * synchronized methods in Java synchronize on the {@link Class} object, using the additional
 * monitor map for {@link DynamicHub} is not an option. Therefore, {@link #replaceObject} replaces
 * {@link DynamicHub} instances with their {@link DynamicHubCompanion} instance (which is mutable)
 * and performs synchronization on the {@link DynamicHubCompanion}.
 *
 * Classes that might be synchronized by the code accessing the additional monitor map must never
 * use the additional monitor map themselves, otherwise recursive map manipulation can corrupt the
 * map. {@link #FORCE_MONITOR_SLOT_TYPES} contains all classes that must have a monitor slot
 * themselves for such correctness reasons.
 *
 * {@link Condition} objects are used to implement {@link #wait()} and {@link #notify()}. When an
 * object monitor needs a condition object, it is atomically swapped into its
 * {@link JavaMonitorConditionObject} field.
 */
public class MultiThreadedMonitorSupport extends MonitorSupport {

    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    /**
     * Types that are used to implement the secondary storage for monitor slots cannot themselves
     * use the additionalMonitors map. That could result in recursive manipulation of the
     * additionalMonitors map which could lead to table corruptions and double insertion of a
     * monitor for the same object. Therefore these types will always get a monitor slot.
     */
    @Platforms(Platform.HOSTED_ONLY.class)//
    public static final Set<Class<?>> FORCE_MONITOR_SLOT_TYPES;

    static {
        try {
            /*
             * The com.oracle.svm.core.WeakIdentityHashMap used to model the
             * com.oracle.svm.core.monitor.MultiThreadedMonitorSupport#additionalMonitors map uses
             * java.lang.ref.ReferenceQueue internally.
             */
            HashSet<Class<?>> monitorTypes = new HashSet<>();
            if (JavaVersionUtil.JAVA_SPEC <= 17) {
                /*
                 * Until JDK 17, the ReferenceQueue uses the inner static class Lock for all its
                 * locking needs.
                 */
                monitorTypes.add(Class.forName("java.lang.ref.ReferenceQueue$Lock"));
            }
            /* The WeakIdentityHashMap also synchronizes on its internal ReferenceQueue field. */
            monitorTypes.add(java.lang.ref.ReferenceQueue.class);

            /*
             * Whenever the monitor allocation in
             * MultiThreadedMonitorSupport.getOrCreateMonitorFromMap() is done via
             * ThreadLocalAllocation.slowPathNewInstance() then
             * LinuxPhysicalMemory$PhysicalMemorySupportImpl.sizeFromCGroup() is called which
             * triggers file IO using the synchronized java.io.FileDescriptor.attach().
             */
            monitorTypes.add(java.io.FileDescriptor.class);

            /*
             * LinuxPhysicalMemory$PhysicalMemorySupportImpl.sizeFromCGroup() also calls
             * java.io.FileInputStream.close() which synchronizes on a 'Object closeLock = new
             * Object()' object. We cannot modify the type of the monitor since it is in JDK code.
             * Adding a monitor slot to java.lang.Object doesn't impact any subtypes.
             *
             * This should also take care of the synchronization in
             * ReferenceInternals.processPendingReferences().
             */
            monitorTypes.add(java.lang.Object.class);

            /*
             * The map access in MultiThreadedMonitorSupport.getOrCreateMonitorFromMap() calls
             * System.identityHashCode() which on the slow path calls
             * IdentityHashCodeSupport.generateIdentityHashCode(). The hashcode generation calls
             * SplittableRandomAccessors.initialize() which synchronizes on an instance of
             * SplittableRandomAccessors.
             */
            monitorTypes.add(Class.forName("com.oracle.svm.core.jdk.SplittableRandomAccessors"));

            if (JavaVersionUtil.JAVA_SPEC >= 11) {
                /*
                 * PhantomCleanable.remove() synchronizes on an instance of PhantomCleanable. When
                 * the secondary storage monitors map is modified it can trigger a
                 * slow-path-new-instance allocation which in turn can trigger a GC which processes
                 * all the pending cleaners.
                 */
                monitorTypes.add(Class.forName("jdk.internal.ref.PhantomCleanable"));
            }

            /*
             * Use as the delegate for locking on {@link Class} (i.e. {@link DynamicHub}) since the
             * hub itself must be immutable.
             */
            monitorTypes.add(DynamicHubCompanion.class);

            FORCE_MONITOR_SLOT_TYPES = Collections.unmodifiableSet(monitorTypes);
        } catch (ClassNotFoundException e) {
            throw VMError.shouldNotReachHere("Error building the list of types that always need a monitor slot.", e);
        }
    }

    /**
     * Secondary storage for monitor slots. Synchronized to prevent concurrent access and
     * modification.
     */
    private final Map<Object, JavaMonitor> additionalMonitors = new WeakIdentityHashMap<>();
    private final ReentrantLock additionalMonitorsLock = new ReentrantLock();

    @Override
    public int maybeAdjustNewParkStatus(int status) {
        Object blocker = LockSupport.getBlocker(Thread.currentThread());
        if (blocker instanceof JavaMonitorConditionObject) {
            // Blocked on one of the condition objects that implement Object.wait()
            if (status == ThreadStatus.PARKED_TIMED) {
                return ThreadStatus.IN_OBJECT_WAIT_TIMED;
            }
            return ThreadStatus.IN_OBJECT_WAIT;
        } else if (blocker instanceof JavaMonitor) {
            // Blocked directly on the lock
            return ThreadStatus.BLOCKED_ON_MONITOR_ENTER;
        }
        return status;
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    @Uninterruptible(reason = "Avoid stack overflow error before yellow zone has been activated", calleeMustBe = false)
    private static void slowPathMonitorEnter(Object obj) {
        /*
         * A stack overflow error in the locking code would be reported as a fatal error, since
         * there must not be any exceptions flowing out of the monitor code. Enabling the yellow
         * zone prevents stack overflows.
         */
        StackOverflowCheck.singleton().makeYellowZoneAvailable();
        VMOperationControl.guaranteeOkayToBlock("No Java synchronization must be performed within a VMOperation: if the object is already locked, the VM is deadlocked");
        try {
            singleton().monitorEnter(obj);

        } catch (OutOfMemoryError ex) {
            /*
             * Exposing OutOfMemoryError to application. Note that since the foreign call from
             * snippets to this method does not have an exception edge, it is possible this throw
             * will miss the proper exception handler.
             */
            throw ex;
        } catch (Throwable ex) {
            /*
             * The foreign call from snippets to this method does not have an exception edge. So we
             * could miss an exception handler if we unwind an exception from this method.
             *
             * The only exception that the monitorenter bytecode is specified to throw is a
             * NullPointerException, and the null check already happens beforehand. So any exception
             * would be surprising to users anyway.
             *
             * Finally, it would not be clear whether the monitor is locked or unlocked in case of
             * an exception.
             */
            throw VMError.shouldNotReachHere("Unexpected exception in MonitorSupport.monitorEnter", ex);

        } finally {
            StackOverflowCheck.singleton().protectYellowZone();
        }
    }

    protected static final String NO_LONGER_UNINTERRUPTIBLE = "The monitor snippet slow path is uninterruptible to avoid stack overflow errors being thrown. " +
                    "Now the yellow zone is enabled and we are no longer uninterruptible, and allocation is allowed again too";

    @RestrictHeapAccess(reason = NO_LONGER_UNINTERRUPTIBLE, access = Access.UNRESTRICTED)
    @Override
    public void monitorEnter(Object obj) {
        JavaMonitor lockObject = getOrCreateMonitor(obj, true);
        lockObject.monitorEnter(obj);
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    @Uninterruptible(reason = "Avoid stack overflow error before yellow zone has been activated", calleeMustBe = false)
    private static void slowPathMonitorExit(Object obj) {
        StackOverflowCheck.singleton().makeYellowZoneAvailable();
        try {
            singleton().monitorExit(obj);

        } catch (OutOfMemoryError ex) {
            /*
             * Exposing OutOfMemoryError to application. Note that since the foreign call from
             * snippets to this method does not have an exception edge, it is possible this throw
             * will miss the proper exception handler.
             */
            throw ex;
        } catch (Throwable ex) {
            /*
             * The foreign call from snippets to this method does not have an exception edge. So we
             * could miss an exception handler if we unwind an exception from this method.
             *
             * Graal enforces structured locking and unlocking. This is a restriction compared to
             * the Java Virtual Machine Specification, but it ensures that we never need to throw an
             * IllegalMonitorStateException.
             */
            throw VMError.shouldNotReachHere("Unexpected exception in MonitorSupport.monitorExit", ex);

        } finally {
            StackOverflowCheck.singleton().protectYellowZone();
        }
    }

    @RestrictHeapAccess(reason = NO_LONGER_UNINTERRUPTIBLE, access = Access.UNRESTRICTED)
    @Override
    public void monitorExit(Object obj) {
        JavaMonitor lockObject = getOrCreateMonitor(obj, true);
        lockObject.monitorExit();
    }

    @Override
    public Object prepareRelockObject(Object obj) {
        /*
         * We ensure that the lock for the object exists, so that the actual re-locking during
         * deoptimization can be uninterruptible.
         *
         * Unfortunately, we cannot do any assertion checking in this method: deoptimization can run
         * in any thread, i.e., not necessarily in the thread that the lock will be for. And while
         * the frame that is deoptimized must have had the object locked, the thread could have
         * given up the lock as part of a wait() - so at this time any thread is allowed to hold the
         * lock.
         *
         * Because any thread can hold the lock at this time, there is no way we can patch any
         * internal state of the lock immediately here. The actual state patching therefore happens
         * later in doRelockObject.
         */
        return getOrCreateMonitor(obj, true);
    }

    @Uninterruptible(reason = "called during deoptimization")
    @Override
    public void doRelockObject(Object obj, Object lockData) {
        JavaMonitor lock = (JavaMonitor) lockData;
        lock.relockObject();
    }

    @Override
    public boolean isLockedByCurrentThread(Object obj) {
        JavaMonitor lockObject = getOrCreateMonitor(obj, false);
        return lockObject != null && lockObject.isHeldByCurrentThread();
    }

    @Override
    public boolean isLockedByAnyThread(Object obj) {
        JavaMonitor lockObject = getOrCreateMonitor(obj, false);
        return lockObject != null && lockObject.isLocked();
    }

    @SuppressFBWarnings(value = {"WA_AWAIT_NOT_IN_LOOP"}, justification = "This method is a wait implementation.")
    @Override
    protected void doWait(Object obj, long timeoutMillis) throws InterruptedException {
        /*
         * Ensure that the current thread holds the lock. Required by the specification of
         * Object.wait, and also required for our implementation.
         */
        JavaMonitor lock = ensureLocked(obj);
        JavaMonitorConditionObject condition = lock.getOrCreateCondition(true);
        if (timeoutMillis == 0L) {
            condition.await();
        } else {
            condition.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void notify(Object obj, boolean notifyAll) {
        /* Make sure the current thread holds the lock on the receiver. */
        JavaMonitor lock = ensureLocked(obj);
        /* Find the wait/notify condition of the receiver. */
        JavaMonitorConditionObject condition = lock.getOrCreateCondition(false);
        /* If the receiver does not have a condition, then it has not been waited on. */
        if (condition != null) {
            if (notifyAll) {
                condition.signalAll();
            } else {
                condition.signal();
            }
        }
    }

    /** Returns the lock of the object. */
    protected JavaMonitor ensureLocked(Object obj) {
        JavaMonitor lockObject = getOrCreateMonitor(obj, true);
        if (!lockObject.isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException("Receiver is not locked by the current thread.");
        }
        return lockObject;
    }

    protected static int getMonitorOffset(Object obj) {
        return DynamicHub.fromClass(obj.getClass()).getMonitorOffset();
    }

    protected static Object replaceObject(Object unreplacedObject) {
        if (unreplacedObject instanceof DynamicHub) {
            /*
             * Classes (= DynamicHub) never have a monitor slot because they must be immutable.
             * Since the companion object is never exposed to user code, we can use it as a
             * replacement object that is mutable and is marked to always have a monitor slot.
             */
            return ((DynamicHub) unreplacedObject).getCompanion();
        }
        return unreplacedObject;
    }

    protected final JavaMonitor getOrCreateMonitor(Object unreplacedObject, boolean createIfNotExisting) {
        Object obj = replaceObject(unreplacedObject);
        assert obj != null;
        int monitorOffset = getMonitorOffset(obj);
        if (monitorOffset != 0) {
            /* The common case: pointer to the monitor reserved in the object. */
            return getOrCreateMonitorFromObject(obj, createIfNotExisting, monitorOffset);
        } else {
            /* No memory reserved for a lock in the object, fall back to secondary storage. */
            return getOrCreateMonitorFromMap(obj, createIfNotExisting);
        }
    }

    protected JavaMonitor getOrCreateMonitorFromObject(Object obj, boolean createIfNotExisting, int monitorOffset) {
        JavaMonitor existingMonitor = (JavaMonitor) BarrieredAccess.readObject(obj, monitorOffset);
        if (existingMonitor != null || !createIfNotExisting) {
            return existingMonitor;
        }
        /* Atomically put a new lock in place of the null at the monitorOffset. */
        JavaMonitor newMonitor = newMonitorLock();
        if (UNSAFE.compareAndSetObject(obj, monitorOffset, null, newMonitor)) {
            return newMonitor;
        }
        /* We lost the race, use the lock some other thread installed. */
        return (JavaMonitor) BarrieredAccess.readObject(obj, monitorOffset);
    }

    protected JavaMonitor getOrCreateMonitorFromMap(Object obj, boolean createIfNotExisting) {
        assert JavaVersionUtil.JAVA_SPEC > 19 ||
                        obj.getClass() != Target_java_lang_ref_ReferenceQueue_Lock.class : "ReferenceQueue.Lock must have a monitor field or we can deadlock accessing WeakIdentityHashMap below";
        VMError.guarantee(!additionalMonitorsLock.isHeldByCurrentThread(),
                        "Recursive manipulation of the additionalMonitors map can lead to table corruptions and double insertion of a monitor for the same object");

        /*
         * Lock the monitor map and maybe add a monitor for this object. This serialization might be
         * a scalability problem.
         */
        additionalMonitorsLock.lock();
        try {
            JavaMonitor existingMonitor = additionalMonitors.get(obj);
            if (existingMonitor != null || !createIfNotExisting) {
                return existingMonitor;
            }
            JavaMonitor newMonitor = newMonitorLock();
            JavaMonitor previousEntry = additionalMonitors.put(obj, newMonitor);
            VMError.guarantee(previousEntry == null, "Replaced monitor in secondary storage map");
            return newMonitor;
        } finally {
            additionalMonitorsLock.unlock();
        }
    }

    protected static JavaMonitor newMonitorLock() {
        return new JavaMonitor();
    }
}

@TargetClass(value = ReferenceQueue.class, innerClass = "Lock", onlyWith = JDK17OrEarlier.class)
final class Target_java_lang_ref_ReferenceQueue_Lock {
}
