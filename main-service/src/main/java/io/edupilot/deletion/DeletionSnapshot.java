package io.edupilot.deletion;

import java.time.Instant;

/** Trusted operator export/import boundary, never an HTTP request or user supplied proof. */
public record DeletionSnapshot(DeletionKind kind, String resourceKey, String sourceMaterialKey,
	Long sourceUserId, Instant accountCreatedAt, String originalEmailHash, Instant requestedAt) {
	void validate() {
		if (kind == null || requestedAt == null || !validKey(resourceKey)) { throw invalid(); }
		if (kind == DeletionKind.ACCOUNT) {
			if (sourceUserId == null || sourceUserId <= 0 || accountCreatedAt == null
				|| originalEmailHash == null || !originalEmailHash.matches("[0-9a-f]{64}")
				|| sourceMaterialKey != null || !resourceKey.equals(DeletionJournal.accountKey(
					sourceUserId, accountCreatedAt, originalEmailHash))) { throw invalid(); }
		} else {
			if (sourceUserId != null || accountCreatedAt != null || originalEmailHash != null
				|| (sourceMaterialKey != null && !validKey(sourceMaterialKey))) { throw invalid(); }
			if ((kind == DeletionKind.ORIGINAL_PDF || kind == DeletionKind.RENDERED_PAGES)
				&& !resourceKey.equals(sourceMaterialKey)) { throw invalid(); }
			if (kind == DeletionKind.AVATAR && sourceMaterialKey != null) { throw invalid(); }
		}
	}
	private static boolean validKey(String key) {
		return key != null && !key.isBlank() && key.length() <= 255 && key.chars().noneMatch(Character::isISOControl);
	}
	private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid deletion snapshot"); }
}
