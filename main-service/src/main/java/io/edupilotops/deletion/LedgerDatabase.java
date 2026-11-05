package io.edupilotops.deletion;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.zip.CRC32;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Fixed loopback JDBC endpoint inside the wrapper-verified DB namespace, never an arbitrary URL. */
public final class LedgerDatabase extends AbstractDataSource {
	public static final String V64="75af39b82695df2088c3916f6ffc3a2b9c71627fb4cf2fcca3b080cb3335d228";
	public static final String V65="83b8357cd5b2ab07f5d01a857323af1107a1718eae9fb1dfcf0daaa5de7467da";
	private final String user,password,uuid,liveUuid;
	private final boolean readOnly;
	private final boolean migration;
	public LedgerDatabase(String user,String password,String uuid,String liveUuid,boolean readOnly) {
		this(user,password,uuid,liveUuid,readOnly,false);
	}
	public LedgerDatabase(String user,String password,String uuid,String liveUuid,boolean readOnly,boolean migration) {
		LedgerCodec.require(user!=null && !user.isBlank() && !"root".equalsIgnoreCase(user) && password!=null && !password.isBlank());
		LedgerCodec.require(uuid!=null && uuid.matches(LedgerCodec.UUID) && liveUuid!=null && liveUuid.matches(LedgerCodec.UUID));
		LedgerCodec.require(readOnly ? uuid.equals(liveUuid) : !uuid.equals(liveUuid));
		LedgerCodec.require(!migration || !readOnly);
		this.user=user;this.password=password;this.uuid=uuid;this.liveUuid=liveUuid;this.readOnly=readOnly;this.migration=migration;
	}
	@Override public Connection getConnection() throws SQLException {
		var connection=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/edupilot?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=5000&socketTimeout=30000",user,password);
		try {
			connection.setReadOnly(true);
			try(var statement=connection.createStatement()) {
				statement.execute("SET SESSION time_zone = '+00:00'");
				try(var rows=statement.executeQuery("SELECT @@server_uuid, VERSION(), DATABASE()")) {
					LedgerCodec.require(rows.next() && uuid.equals(rows.getString(1)) && rows.getString(2).equals("8.0.46") && "edupilot".equals(rows.getString(3)));
				}
			}
			String catalog=history(connection);
			LedgerCodec.require(sourceCatalog().equals(V65));
			LedgerCodec.require(readOnly || migration ? Set.of(V64,V65).contains(catalog) : V65.equals(catalog));
			connection.setReadOnly(readOnly);
			if(readOnly)try(var statement=connection.createStatement()){statement.execute("SET SESSION TRANSACTION READ ONLY");}
			return connection;
		} catch(Exception error){connection.close();throw new SQLException("OPERATOR_DB_IDENTITY_OR_CATALOG_REJECTED");}
	}
	@Override public Connection getConnection(String user,String password)throws SQLException {throw new SQLException("ALTERNATE_CREDENTIALS_REJECTED");}
	public static String history(Connection connection)throws SQLException {
		Map<String,Integer> map=new TreeMap<>();
		try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank")) {
			while(rows.next()) {String version=rows.getString(1);int checksum=rows.getInt(2);
				LedgerCodec.require(version!=null && version.matches("[1-9][0-9]*") && !rows.wasNull() && rows.getBoolean(3) && !rows.wasNull() && map.put(version,checksum)==null);
			}
		}
		LedgerCodec.require(map.size()==64 || map.size()==65);
		for(int n=1;n<=map.size();n++)LedgerCodec.require(map.containsKey(Integer.toString(n)));
		return catalogHash(map);
	}
	public static String sourceCatalog() throws IOException {
		Map<String,Integer> map=new TreeMap<>();
		for(var resource:new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql")) {
			String name=resource.getFilename();if(name==null || !name.matches("V[1-9][0-9]*__.*\\.sql"))throw new IOException("MIGRATION_NAME");
			String version=name.substring(1,name.indexOf("__")); CRC32 crc=new CRC32();
			try(var reader=new BufferedReader(new InputStreamReader(resource.getInputStream(),StandardCharsets.UTF_8))) {
				String line;boolean first=true;while((line=reader.readLine())!=null){if(first && line.startsWith("\ufeff"))line=line.substring(1);first=false;crc.update(line.getBytes(StandardCharsets.UTF_8));}
			}
			if(map.put(version,(int)crc.getValue())!=null)throw new IOException("DUPLICATE_MIGRATION");
		}
		LedgerCodec.require(map.size()==65);return catalogHash(map);
	}
	private static String catalogHash(Map<String,Integer> map) {
		StringJoiner value=new StringJoiner(",","{","}");map.forEach((key,checksum)->value.add("\""+key+"\":"+checksum));
		return LedgerCodec.sha256(value.toString().getBytes(StandardCharsets.UTF_8));
	}
}
