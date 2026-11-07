package csd.tripsense.alert;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Slow ingestion never occupies alert maintenance threads. */
@Configuration
public class AlertSchedulingConfig {
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
