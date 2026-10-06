package io.edupilot.user;

import org.hibernate.LockMode;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.proxy.HibernateProxy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/** Reads the current state of an already locked User without changing its managed identity. */
public final class UserCurrentStateRefresh {

	private UserCurrentStateRefresh() { }

	/** Call immediately after the repository locking query, which flushes pending User changes. */
	public static void refreshLocked(EntityManager entityManager, User account, LockModeType requested) {
		if (requested != LockModeType.PESSIMISTIC_READ && requested != LockModeType.PESSIMISTIC_WRITE) {
			throw new IllegalArgumentException("A pessimistic User read or write lock is required.");
		}
		if (!entityManager.isJoinedToTransaction()) {
			throw new IllegalStateException("An active transaction with an already locked User is required.");
		}
		var session = entityManager.unwrap(SessionImplementor.class);
		var context = session.getPersistenceContextInternal();
		User managed = account;
		var initializer = HibernateProxy.extractLazyInitializer(account);
		if (initializer != null) {
			// Resolve only this session's already-loaded User; never initialize or reassociate a proxy here.
			if (initializer.getSession() != session) {
				throw new IllegalStateException("An already locked managed User from this session is required.");
			}
			if (!(initializer.getImplementation(session) instanceof User implementation)) {
				throw new IllegalStateException("An already locked managed User from this session is required.");
			}
			managed = implementation;
		}
		var entry = context.getEntry(managed);
		if (entry == null || (entry.getLockMode() != LockMode.PESSIMISTIC_READ
			&& entry.getLockMode() != LockMode.PESSIMISTIC_WRITE && entry.getLockMode() != LockMode.WRITE)) {
			throw new IllegalStateException("An already locked managed User is required.");
		}
		LockMode previous = entry.getLockMode();
		if (requested == LockModeType.PESSIMISTIC_WRITE
			&& previous != LockMode.PESSIMISTIC_WRITE && previous != LockMode.WRITE) {
			throw new IllegalStateException("The User write lock must be acquired before refresh.");
		}
		// Hibernate 7.4 omits the locking clause when refresh requests the same held mode.
		// A User flushed by the locking query has the stronger internal WRITE mode instead.
		// Only bookkeeping changes here: the database lock remains held until transaction end.
		entry.setLockMode(LockMode.READ);
		try {
			entityManager.refresh(managed, requested);
		} finally {
			// Refresh can replace EntityEntry, so restore the mode on its current entry.
			var refreshed = context.getEntry(managed);
			if (refreshed != null && previous.greaterThan(refreshed.getLockMode())) {
				refreshed.setLockMode(previous);
			}
		}
	}
}
