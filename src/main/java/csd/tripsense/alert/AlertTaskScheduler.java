package csd.tripsense.alert;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** A named scheduler, deliberately not an Executor that could disable Boot's application executor. */
final class AlertTaskScheduler implements TaskScheduler, InitializingBean, DisposableBean {
    private final ThreadPoolTaskScheduler delegate = new ThreadPoolTaskScheduler();

    AlertTaskScheduler() {
        delegate.setThreadNamePrefix("alert-maintenance-");
        delegate.setPoolSize(3);
        delegate.setDaemon(true);
    }

    @Override public void afterPropertiesSet() { delegate.initialize(); }
    @Override public void destroy() { delegate.shutdown(); }
    @Override public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        return delegate.schedule(task, trigger);
    }
    @Override public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        return delegate.schedule(task, startTime);
    }
    @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
        return delegate.scheduleAtFixedRate(task, startTime, period);
    }
    @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        return delegate.scheduleAtFixedRate(task, period);
    }
    @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
        return delegate.scheduleWithFixedDelay(task, startTime, delay);
    }
    @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        return delegate.scheduleWithFixedDelay(task, delay);
    }
}
