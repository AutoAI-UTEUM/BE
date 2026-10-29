package io.edupilot.mail;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Migration-created singleton: serializes quota reservations across all worker processes. */
@Entity
@Table(name = "email_quota_lock")
public class EmailQuotaLock {
	@Id private Integer id;
	protected EmailQuotaLock() { }
	public static EmailQuotaLock initial() {
		EmailQuotaLock lock = new EmailQuotaLock();
		lock.id = 1;
		return lock;
	}
}
