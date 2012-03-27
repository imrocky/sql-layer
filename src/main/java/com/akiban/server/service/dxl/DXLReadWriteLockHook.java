/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.server.service.dxl;

import com.akiban.server.service.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class DXLReadWriteLockHook implements DXLFunctionsHook {

    private static final Logger LOGGER = LoggerFactory.getLogger(DXLReadWriteLockHook.class);
    private static final Session.StackKey<Lock> SCHEMA_LOCK_KEY = Session.StackKey.stackNamed("SCHEMA_LOCK");
    private static final Session.StackKey<Lock> DATA_LOCK_KEY = Session.StackKey.stackNamed("DATA_LOCK");
    private static final Session.Key<Boolean> WRITE_LOCK_TAKEN = Session.Key.named("WRITE_LOCK_TAKEN");
    static final String WRITE_LOCK_TAKEN_MESSAGE = "Another thread has the write lock! Writes are supposed to be single-threaded";

    private final ReentrantReadWriteLock schemaLock = new ReentrantReadWriteLock(true);
    private final ReentrantReadWriteLock dataLock = new ReentrantReadWriteLock(true);

    private final static DXLReadWriteLockHook INSTANCE = new DXLReadWriteLockHook();
    private final static boolean DML_LOCK;
    static
    {
        String dmlLockProperty = System.getProperty("dml.lock");
        // FALSE by default
        DML_LOCK = dmlLockProperty != null && dmlLockProperty.equals("true");
    }

    public static DXLReadWriteLockHook only() {
        return INSTANCE;
    }

    private DXLReadWriteLockHook() {
        // Having multiple of these introduces the possibility of a deadlock, for all the usual deadlocky reasons
    }

    public boolean isEnabled() {
        return DML_LOCK;
    }

    @Override
    public void hookFunctionIn(Session session, DXLFunction function) {
        lockSchema(session, function);
        lockDataIfNecessary(session, function);
    }

    @Override
    public void hookFunctionCatch(Session session, DXLFunction function, Throwable throwable) {
        // nothing to do
    }

    @Override
    public void hookFunctionFinally(Session session, DXLFunction function, Throwable t) {
        unlockSchema(session, t);
        unlockDataIfNecessary(session, function, t);
    }

    private void lockSchema(Session session, DXLFunction function)
    {
        Lock lock;
        if (DXLType.DDL_FUNCTIONS_WRITE.equals(function.getType())) {
            if (schemaLock.isWriteLocked() && (!schemaLock.isWriteLockedByCurrentThread())) {
                session.put(WRITE_LOCK_TAKEN, true);
                throw new IllegalStateException(WRITE_LOCK_TAKEN_MESSAGE);
            }
            lock = schemaLock.writeLock();
        }
        else {
            lock = schemaLock.readLock();
        }
        session.push(SCHEMA_LOCK_KEY, lock);
        lock.lock();
    }

    private void lockDataIfNecessary(Session session, DXLFunction function)
    {
        if (DML_LOCK) {
            Lock lock = null;
            switch (function.getType()) {
                case DML_FUNCTIONS_WRITE:
                    lock = dataLock.writeLock();
                    break;
                case DML_FUNCTIONS_READ:
                    lock = dataLock.readLock();
                    break;
                default:
                    break;
            }
            if (lock != null) {
                session.push(DATA_LOCK_KEY, lock);
                lock.lock();
            }
        }
    }

    private void unlockSchema(Session session, Throwable t)
    {
        Lock lock = session.pop(SCHEMA_LOCK_KEY);
        if (lock == null) {
            Boolean writeLockWasTaken = session.remove(WRITE_LOCK_TAKEN);
            if (writeLockWasTaken != null && writeLockWasTaken) {
                return; // assertion was thrown
            }
            throw new LockNotSetException(t);
        }
        lock.unlock();
    }

    private void unlockDataIfNecessary(Session session, DXLFunction function, Throwable t)
    {
        if (DML_LOCK) {
            Lock lock;
            switch (function.getType()) {
                case DML_FUNCTIONS_WRITE:
                case DML_FUNCTIONS_READ:
                    lock = session.pop(DATA_LOCK_KEY);
                    if (lock == null) {
                        throw new LockNotSetException(t);
                    }
                    lock.unlock();
                    break;
                default:
                    break;
            }
        }
    }

    private static class LockNotSetException extends RuntimeException {
        private static final String ERR_STRING = "Lock was null! Some lock has escaped, and this could be a deadlock!";
        private LockNotSetException(Throwable cause) {
            super(ERR_STRING, cause);
            LOGGER.error(ERR_STRING);
        }
    }
}
