package io.edupilot.session;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ConversationSummaryConfig {

	@Bean(name = "conversationSummaryExecutor")
	ThreadPoolTaskExecutor conversationSummaryExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(100);
		executor.setThreadNamePrefix("conversation-summary-");
		executor.setRejectedExecutionHandler(
			new ThreadPoolExecutor.AbortPolicy()
		);
		executor.initialize();
		return executor;
	}
}
