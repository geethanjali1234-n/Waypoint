package com.waypoint.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The ONE cumulative spending cap and ledger for every paid Claude call Waypoint makes: Wavy chat, the agent, routing,
 * cited policy answers and timesheet document extraction all receive this same instance. Its current estimates do not
 * establish a hard cap, so paid requests are blocked pending a reviewed reservation approach.
 */
@Configuration(proxyBeanMethods = false)
class ClaudeSpendConfiguration {

    @Bean
    ClaudeSpendLimiter claudeSpendLimiter(@Value("${waypoint.myhr.agent.spend-cap-usd:0}") double capUsd,
                                          @Value("${waypoint.myhr.agent.spend-ledger-file:}") String ledgerFile) {
        return ClaudeSpendLimiter.fromSettings(capUsd, ledgerFile);
    }
}
