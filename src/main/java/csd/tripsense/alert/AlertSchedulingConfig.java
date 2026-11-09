package csd.tripsense.alert;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keep Boot's configured application executor and default scheduler intact. */
@Configuration(proxyBeanMethods = false)
public class AlertSchedulingConfig {
    @Bean(name = "alertTaskScheduler", defaultCandidate = false)
    AlertTaskScheduler alertScheduler() { return new AlertTaskScheduler(); }
}
