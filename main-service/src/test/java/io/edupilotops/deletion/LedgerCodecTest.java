package io.edupilotops.deletion;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import io.edupilot.deletion.*;

class LedgerCodecTest {
	private static final Instant AT=Instant.parse("2026-11-05T10:00:00.123456Z");
	private static final String UUID="12345678-1234-1234-1234-123456789abc";
	private LedgerCodec.Document document(List<DeletionSnapshot> entries) {
		return new LedgerCodec.Document("a".repeat(40),UUID,LedgerDatabase.V64,AT,entries);
	}
	private DeletionSnapshot file(Instant retain) {
		return new DeletionSnapshot(DeletionKind.ORIGINAL_PDF,"synthetic.pdf","synthetic.pdf",null,null,null,AT,retain);
	}
	private LedgerCodec.Document decode(String value) {
		byte[] raw=value.getBytes(StandardCharsets.UTF_8);return LedgerCodec.decode(raw,LedgerCodec.sha256(raw));
	}
	@Test void exactRoundTripPreservesAllEightFieldsMicrosecondsAndUnknownRetention() {
		String hash=DeletionJournal.hash("synthetic@example.test");
		String key=DeletionJournal.hash(10L+"\n"+AT+"\n"+hash);
		var account=new DeletionSnapshot(DeletionKind.ACCOUNT,key,null,10L,AT,hash,AT,null);
		var expected=document(List.of(account,file(AT.plusSeconds(86400)),
			new DeletionSnapshot(DeletionKind.EXTERNAL_AI,"synthetic-ai",null,null,null,null,AT,null)));
		byte[] raw=LedgerCodec.encode(expected);
		assertThat(LedgerCodec.decode(raw,LedgerCodec.sha256(raw))).isEqualTo(expected);
		assertThat(new String(raw,StandardCharsets.UTF_8)).doesNotContain("synthetic@example.test");
	}
	@Test void unknownFieldMissingFieldAndDuplicateJsonKeyAreRejected() {
		String raw=new String(LedgerCodec.encode(document(List.of(file(null)))),StandardCharsets.UTF_8);
		for(String value:List.of(raw.replace("\"kind\":\"ORIGINAL_PDF\"","\"kind\":\"ORIGINAL_PDF\",\"extra\":true"),
			raw.replace("\"retainUntil\":null", "\"retainUntil\":null,\"retainUntil\":null"),
			raw.replace(",\"retainUntil\":null", ""),raw.replace("\"retainUntil\":null", "\"other\":null"))) {
			assertThatThrownBy(()->decode(value)).isInstanceOf(RuntimeException.class);
		}
	}
	@Test void invalidAccountIdentityAndFractionalUserIdAreRejectedBeforeReplay() {
		String raw=new String(LedgerCodec.encode(document(List.of(file(null)))),StandardCharsets.UTF_8);
		assertThatThrownBy(()->decode(raw.replace("\"sourceUserId\":null","\"sourceUserId\":1.5"))).isInstanceOf(RuntimeException.class);
		assertThatThrownBy(()->decode(raw.replace("\"kind\":\"ORIGINAL_PDF\"","\"kind\":\"ACCOUNT\""))).isInstanceOf(RuntimeException.class);
	}
	@Test void invalidPrecisionRetentionAndDuplicateResourceAreRejectedByProducerToo() {
		assertThatThrownBy(()->LedgerCodec.encode(document(List.of(file(AT.minusSeconds(1)))))).isInstanceOf(RuntimeException.class);
		var nano=new DeletionSnapshot(DeletionKind.AVATAR,"synthetic.png",null,null,null,null,AT.plusNanos(1),null);
		assertThatThrownBy(()->LedgerCodec.encode(document(List.of(nano)))).isInstanceOf(RuntimeException.class);
		assertThatThrownBy(()->LedgerCodec.encode(document(List.of(file(null),file(null))))).isInstanceOf(RuntimeException.class);
	}
	@Test void exactDigestSizeCapAndUuidAreRequired() {
		byte[] raw=LedgerCodec.encode(document(List.of()));
		assertThatThrownBy(()->LedgerCodec.decode(raw,"0".repeat(64))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(()->LedgerCodec.decode(new byte[LedgerCodec.MAX_BYTES+1],"0".repeat(64))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(()->LedgerCodec.encode(new LedgerCodec.Document("a".repeat(40),"-".repeat(36),LedgerDatabase.V64,AT,List.of()))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(()->LedgerCodec.encode(new LedgerCodec.Document("a".repeat(40),UUID,LedgerDatabase.V64,AT.plusNanos(1),List.of()))).isInstanceOf(IllegalArgumentException.class);
	}
}
