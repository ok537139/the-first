package com.hmdp.service.consistency;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hmdp.consistency")
public class ConsistencyProperties {
    private long doubleDeleteDelayMs = 1000;
    private int retrySeconds = 5;
    private int batchSize = 100;
    private int fanoutBatchSize = 500;
    private int bigAuthorThreshold = 10000;
    private int feedMaxSize = 1000;
    private int followBackfillSize = 20;
    private int feedPageSize = 10;
    private int cacheSeconds = 600;
}
