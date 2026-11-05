package io.edupilotops.deletion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import io.edupilot.deletion.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Restricted operator format. Never an HTTP DTO or a user-supplied proof. */
public final class LedgerCodec {
	public static final int MAX_BYTES = 16 * 1024 * 1024;
	public static final int MAX_ENTRIES = 100_000;
	static final String UUID = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
	private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
	private static final Set<String> FIELDS = Set.of("kind","resourceKey","sourceMaterialKey","sourceUserId","accountCreatedAt","originalEmailHash","requestedAt","retainUntil");
	private LedgerCodec() { }
	public record Document(String sourceSha, String sourceUuid, String catalogSha256, Instant exportedAtUtc, List<DeletionSnapshot> snapshots) { }
	public static byte[] encode(Document document) {
		require(document.sourceSha()!=null && document.sourceSha().matches("[a-f0-9]{40}")
			&& document.sourceUuid()!=null && document.sourceUuid().matches(UUID)
			&& document.catalogSha256()!=null && document.catalogSha256().matches("[a-f0-9]{64}")
			&& document.exportedAtUtc()!=null && document.exportedAtUtc().getNano()%1000==0
			&& document.snapshots()!=null && document.snapshots().size()<=MAX_ENTRIES);
		List<Map<String,Object>> rows = document.snapshots().stream().map(LedgerCodec::snapshot).toList();
		Map<String,Object> value = new LinkedHashMap<>();
		value.put("kind","EDUPILOT_TRUSTED_DELETION_LEDGER_V1"); value.put("sourceSha",document.sourceSha());
		value.put("sourceUuid",document.sourceUuid()); value.put("catalogSha256",document.catalogSha256());
		value.put("exportedAtUtc",document.exportedAtUtc().toString()); value.put("snapshots",rows);
		byte[] bytes = (JSON.writeValueAsString(value)+"\n").getBytes(StandardCharsets.UTF_8);
		require(bytes.length<=MAX_BYTES && rows.size()<=MAX_ENTRIES);
		decode(bytes,sha256(bytes)); // The producer must satisfy the same strict format as the importer.
		return bytes;
	}
	public static Document decode(byte[] bytes, String expectedDigest) {
		require(bytes.length>0 && bytes.length<=MAX_BYTES && sha256(bytes).equals(expectedDigest));
		JsonNode root=JSON.readTree(bytes);
		exact(root,Set.of("kind","sourceSha","sourceUuid","catalogSha256","exportedAtUtc","snapshots"));
		require("EDUPILOT_TRUSTED_DELETION_LEDGER_V1".equals(text(root,"kind")));
		String sha=text(root,"sourceSha"),uuid=text(root,"sourceUuid"),catalog=text(root,"catalogSha256");
		require(sha.matches("[a-f0-9]{40}") && uuid.matches(UUID) && catalog.matches("[a-f0-9]{64}"));
		Instant at=Instant.parse(text(root,"exportedAtUtc"));
		require(at.getNano()%1000==0);
		JsonNode entries=root.get("snapshots"); require(entries.isArray() && entries.size()<=MAX_ENTRIES);
		List<DeletionSnapshot> result=new ArrayList<>(); Set<String> keys=new HashSet<>();
		for(JsonNode row:entries) {
			exact(row,FIELDS);
			JsonNode id=row.get("sourceUserId"); require(id.isNull() || id.isIntegralNumber() && id.canConvertToLong());
			var value=new DeletionSnapshot(DeletionKind.valueOf(text(row,"kind")),text(row,"resourceKey"),nullable(row,"sourceMaterialKey"),
				id.isNull()?null:id.longValue(),instant(row,"accountCreatedAt"),nullable(row,"originalEmailHash"),instant(row,"requestedAt"),instant(row,"retainUntil"));
			value.validate(); require(keys.add(value.kind()+"\n"+value.resourceKey())); result.add(value);
		}
		return new Document(sha,uuid,catalog,at,List.copyOf(result));
	}
	private static Map<String,Object> snapshot(DeletionSnapshot s) {
		s.validate(); Map<String,Object> row=new LinkedHashMap<>();
		row.put("kind",s.kind().name()); row.put("resourceKey",s.resourceKey()); row.put("sourceMaterialKey",s.sourceMaterialKey());
		row.put("sourceUserId",s.sourceUserId()); row.put("accountCreatedAt",s.accountCreatedAt()==null?null:s.accountCreatedAt().toString());
		row.put("originalEmailHash",s.originalEmailHash()); row.put("requestedAt",s.requestedAt().toString());
		row.put("retainUntil",s.retainUntil()==null?null:s.retainUntil().toString()); return row;
	}
	private static Instant instant(JsonNode node,String key) {
		String value=nullable(node,key); if(value==null)return null;
		Instant time=Instant.parse(value); require(time.getNano()%1000==0); return time;
	}
	private static String nullable(JsonNode node,String key) { return node.get(key).isNull()?null:text(node,key); }
	private static String text(JsonNode node,String key) { require(node.get(key)!=null && node.get(key).isTextual()); return node.get(key).textValue(); }
	private static void exact(JsonNode node,Set<String> fields) {
		require(node!=null && node.isObject()); Set<String> actual=new HashSet<>(); node.propertyNames().forEach(actual::add); require(actual.equals(fields));
	}
	public static String sha256(byte[] bytes) {
		try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
		catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA256_UNAVAILABLE");}
	}
	static void require(boolean value) { if(!value)throw new IllegalArgumentException("OPERATOR_LEDGER_INPUT_REJECTED"); }
}
