package com.lumira.finance;

import com.lumira.plugin.sdk.PluginResources;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One SDK-owned low-frequency local worker; each lease/result/failure uses an explicit transaction.
 */
@Configuration(proxyBeanMethods = false)
public class FinanceJobs {
    @Bean(destroyMethod = "close")
    AutoCloseable financeLocalProjectionJob(
            PluginResources resources,
            FinanceOutbox queue,
            PlatformTransactionManager transactions) {
        var tx = new TransactionTemplate(transactions);
        AtomicInteger empty = new AtomicInteger();
        AtomicLong next = new AtomicLong();
        return resources.schedule(
                "finance-local-projection",
                Duration.ofSeconds(5),
                () -> {
                    long now = System.currentTimeMillis();
                    if (now < next.get()) return;
                    try {
                        var claimed = tx.execute(status -> queue.claim(20));
                        if (claimed == null || claimed.isEmpty()) {
                            int skips = Math.min(6, empty.incrementAndGet());
                            next.set(now + skips * 5000L);
                            return;
                        }
                        empty.set(0);
                        next.set(0);
                        for (var row : claimed) {
                            UUID id = (UUID) row.get("id"), token = (UUID) row.get("lease_token");
                            try {
                                tx.executeWithoutResult(status -> queue.consume(id, token));
                            } catch (RuntimeException failed) {
                                try {
                                    tx.executeWithoutResult(status -> queue.failed(id, token));
                                } catch (RuntimeException ignored) {
                                    /* Committed lease remains recoverable after expiry; never fabricate success. */
                                }
                            }
                        }
                    } catch (RuntimeException unavailable) {
                        next.set(now + 30000L);
                    }
                });
    }
}
