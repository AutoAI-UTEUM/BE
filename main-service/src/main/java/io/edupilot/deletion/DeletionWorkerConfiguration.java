package io.edupilot.deletion;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods=false)
public class DeletionWorkerConfiguration {
	@Bean(name="deletionExecutor")
	public ThreadPoolTaskExecutor deletionExecutor() {
		ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1); executor.setMaxPoolSize(2); executor.setQueueCapacity(25);
		executor.setThreadNamePrefix("deletion-");
		executor.setWaitForTasksToCompleteOnShutdown(false);
		return executor;
	}
}
