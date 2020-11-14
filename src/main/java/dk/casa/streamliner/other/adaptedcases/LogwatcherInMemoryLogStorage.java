package dk.casa.streamliner.other.adaptedcases;

import dk.casa.streamliner.stream.PushStream;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;

import static java.util.stream.Collectors.toList;

class InMemoryLogStorage implements LogStorage {

	private final List<LogEntry> entries = new ArrayList<>();
	private final ReadWriteLock lock = new ReentrantReadWriteLock();
	private final Lock writeLock = lock.writeLock();
	private final Lock readLock = lock.readLock();

	// Added
	private boolean isMatching(LogEntry entry, Collection<Predicate<LogEntry>> criteria) {
		// adapted stream constructor and added explicit type
		return PushStream.of(criteria).reduce((Predicate<LogEntry>)(e) -> true, Predicate::and).test(entry);
	}

	public void writeEntry(final LogEntry entry) throws LogStorageException {
		withLock(writeLock, () -> {
			entries.add(entry);
			return null;
		});
	}

	public int removeOldEntries(final Date date) throws LogStorageException {
		return withLock(writeLock, () -> {
			Iterator<LogEntry> iterator = entries.iterator();
			int removed = 0;
			while (iterator.hasNext()) {
				LogEntry entry = iterator.next();
				if (entry.getDate().before(date)) {
					iterator.remove();
					removed++;
				}
			}
			return removed;
		});
	}

	public List<LogEntry> findEntries(final Collection<Predicate<LogEntry>> criteria, int limit) {
		// adapted stream constructor
		return withLock(readLock, () -> PushStream.of(entries)
				.filter(entry -> isMatching(entry, criteria))
				.limit(limit)
				.collect(toList())
		);
	}

	public void walk(final Collection<Predicate<LogEntry>> criteria, int limit, final Visitor<LogEntry, ?> visitor) {
		// adapted stream constructor
		withLock(readLock, () -> {
			PushStream.of(entries)
					.filter(entry -> isMatching(entry, criteria))
					.limit(limit)
					.forEach(visitor::visit);
			return null;
		});
	}

	public int countEntries(final Collection<Predicate<LogEntry>> criteria) {
		return withLock(readLock, () -> (int)entries.stream().filter(e -> isMatching(e, criteria)).count());
	}

	public void removeEntriesWithChecksum(final String checksum) throws LogStorageException {
		withLock(writeLock, () -> {
			Iterator<LogEntry> iterator = entries.iterator();
			while (iterator.hasNext()) {
				LogEntry entry = iterator.next();
				if (checksum.equals(entry.getCheckSum())) {
					iterator.remove();
				}
			}
			return null;
		});
	}

	private static <T> T withLock(Lock lock, Callable<T> task) throws LogStorageException {
		lock.lock();
		try {
			return task.call();
		} catch (Exception e) {
			throw new LogStorageException(e);
		} finally {
			lock.unlock();
		}
	}
}

///

interface LogStorage {

	void writeEntry(LogEntry entry) throws LogStorageException;

	int removeOldEntries(Date date) throws LogStorageException;

	int countEntries(Collection<Predicate<LogEntry>> criteria)
			throws LogStorageException, InvalidCriteriaException;

	void removeEntriesWithChecksum(String checksum) throws LogStorageException;

	List<LogEntry> findEntries(Collection<Predicate<LogEntry>> criteria, int limit)
			throws LogStorageException, InvalidCriteriaException;

	void walk(Collection<Predicate<LogEntry>> criteria, int limit, Visitor<LogEntry, ?> visitor)
			throws LogStorageException, InvalidCriteriaException;

}

interface LogEntry {
	Date getDate();

	String getCheckSum();
}

interface Visitor<T, R> {
	R visit(T t);
}

class LogStorageException extends RuntimeException {
	public LogStorageException(Exception e) {
		super();
	}
}

class InvalidCriteriaException extends Exception {}