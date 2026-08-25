package io.edupilot.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientProperties;
import io.edupilot.ai.HttpAiClient;
import io.edupilot.ai.dto.AiHealthResponse;

class ReadinessServiceTest {

	@Test
	void reportsUpWhenDatabaseAndAiServiceAreUp() throws Exception {
		DatabaseFixture database = databaseUp();
		AiClient aiClient = mock(AiClient.class);
		when(aiClient.health()).thenReturn(new AiHealthResponse("UP"));

		ReadinessResponse response = new ReadinessService(
			database.provider(),
			aiClient
		).check();

		assertThat(response.status()).isEqualTo(ReadinessResponse.Status.UP);
		assertThat(response.checks().db())
			.isEqualTo(ReadinessResponse.CheckStatus.UP);
		assertThat(response.checks().aiService())
			.isEqualTo(ReadinessResponse.CheckStatus.UP);
		verify(database.connection()).prepareStatement("SELECT 1");
		verify(database.statement()).setQueryTimeout(2);
	}

	@Test
	void reportsDownWhenDatabaseCheckFails() throws Exception {
		@SuppressWarnings("unchecked")
		ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
		DataSource dataSource = mock(DataSource.class);
		when(provider.getIfAvailable()).thenReturn(dataSource);
		when(dataSource.getConnection()).thenThrow(
			new SQLException("database unavailable")
		);
		AiClient aiClient = mock(AiClient.class);
		when(aiClient.health()).thenReturn(new AiHealthResponse("UP"));

		ReadinessResponse response = new ReadinessService(
			provider,
			aiClient
		).check();

		assertThat(response.status()).isEqualTo(ReadinessResponse.Status.DOWN);
		assertThat(response.checks().db())
			.isEqualTo(ReadinessResponse.CheckStatus.DOWN);
		assertThat(response.checks().aiService())
			.isEqualTo(ReadinessResponse.CheckStatus.UP);
	}

	@Test
	void reportsDegradedWhenAiServerIsUnavailable() throws Exception {
		DatabaseFixture database = databaseUp();
		HttpAiClient aiClient = new HttpAiClient(
			properties(unusedLocalPort())
		);

		ReadinessResponse response = new ReadinessService(
			database.provider(),
			aiClient
		).check();

		assertThat(response.status())
			.isEqualTo(ReadinessResponse.Status.DEGRADED);
		assertThat(response.checks().db())
			.isEqualTo(ReadinessResponse.CheckStatus.UP);
		assertThat(response.checks().aiService())
			.isEqualTo(ReadinessResponse.CheckStatus.DOWN);
	}

	private DatabaseFixture databaseUp() throws Exception {
		@SuppressWarnings("unchecked")
		ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		PreparedStatement statement = mock(PreparedStatement.class);
		ResultSet result = mock(ResultSet.class);

		when(provider.getIfAvailable()).thenReturn(dataSource);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.prepareStatement("SELECT 1")).thenReturn(statement);
		when(statement.executeQuery()).thenReturn(result);
		when(result.next()).thenReturn(true);
		when(result.getInt(1)).thenReturn(1);
		return new DatabaseFixture(provider, connection, statement);
	}

	private AiClientProperties properties(int port) {
		Duration timeout = Duration.ofMillis(200);
		return new AiClientProperties(
			URI.create("http://127.0.0.1:" + port),
			"readiness-test-token",
			timeout,
			timeout,
			timeout,
			timeout,
			timeout,
			timeout,
			timeout,
			timeout,
			timeout,
			timeout,
			timeout,
			timeout,
			timeout,
			timeout,
			timeout,
			timeout,
			timeout,
			"/health"
		);
	}

	private int unusedLocalPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private record DatabaseFixture(
		ObjectProvider<DataSource> provider,
		Connection connection,
		PreparedStatement statement
	) {
	}
}
