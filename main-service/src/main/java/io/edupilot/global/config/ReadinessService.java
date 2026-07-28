package io.edupilot.global.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import io.edupilot.ai.AiClient;

@Service
public class ReadinessService {

	private static final String DATABASE_CHECK_QUERY = "SELECT 1";
	private static final int DATABASE_QUERY_TIMEOUT_SECONDS = 2;

	private final ObjectProvider<DataSource> dataSourceProvider;
	private final AiClient aiClient;

	public ReadinessService(
		ObjectProvider<DataSource> dataSourceProvider,
		AiClient aiClient
	) {
		this.dataSourceProvider = dataSourceProvider;
		this.aiClient = aiClient;
	}

	public ReadinessResponse check() {
		boolean databaseUp = databaseUp();
		boolean aiServiceUp = aiServiceUp();
		return ReadinessResponse.of(databaseUp, aiServiceUp);
	}

	private boolean databaseUp() {
		DataSource dataSource = dataSourceProvider.getIfAvailable();
		if (dataSource == null) {
			return false;
		}

		try (
			Connection connection = dataSource.getConnection();
			PreparedStatement statement = connection.prepareStatement(
				DATABASE_CHECK_QUERY
			)
		) {
			statement.setQueryTimeout(DATABASE_QUERY_TIMEOUT_SECONDS);
			try (ResultSet result = statement.executeQuery()) {
				return result.next() && result.getInt(1) == 1;
			}
		} catch (SQLException exception) {
			return false;
		}
	}

	private boolean aiServiceUp() {
		try {
			return "UP".equals(aiClient.health().status());
		} catch (RuntimeException exception) {
			return false;
		}
	}
}
