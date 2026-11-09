package csd.tripsense.alert;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnThreading;
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.boot.thread.Threading;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Slow ingestion never occupies alert maintenance threads. */
@Configuration
public class AlertSchedulingConfig {
    // Scheduler beans are also Executors, which makes Boot back off its application executor.
    // Use Boot's configured builders so spring.task.execution.* and virtual threads still apply.
    @Bean(name={"applicationTaskExecutor", "taskExecutor"})
    @ConditionalOnMissingBean(name={"applicationTaskExecutor", "taskExecutor"})
    @ConditionalOnThreading(Threading.PLATFORM)
    ThreadPoolTaskExecutor applicationExecutor(ThreadPoolTaskExecutorBuilder builder) { return builder.build(); }

    @Bean(name={"applicationTaskExecutor", "taskExecutor"})
    @ConditionalOnMissingBean(name={"applicationTaskExecutor", "taskExecutor"})
    @ConditionalOnThreading(Threading.VIRTUAL)
    SimpleAsyncTaskExecutor virtualApplicationExecutor(SimpleAsyncTaskExecutorBuilder builder) { return builder.build(); }

    @Bean(name="taskScheduler")
    ThreadPoolTaskScheduler ingestionScheduler() { return scheduler("ingestion-",1); }
    @Bean(name="alertTaskScheduler")
    ThreadPoolTaskScheduler alertScheduler() { return scheduler("alert-maintenance-",3); }
    private ThreadPoolTaskScheduler scheduler(String prefix,int threads) {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix(prefix); scheduler.setPoolSize(threads); scheduler.setDaemon(true);
        return scheduler;
    }
}
