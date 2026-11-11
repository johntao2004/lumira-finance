package com.lumira.finance;

import com.lumira.plugin.sdk.PluginEntry;

import org.pf4j.PluginWrapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

public final class FinancePlugin extends PluginEntry {
    public FinancePlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public Class<?> configuration() {
        return Configuration.class;
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ComponentScan("com.lumira.finance")
    @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass = true)
    public static class Configuration {

        @Bean
        @ConditionalOnMissingBean(ObjectMapper.class)
        public ObjectMapper objectMapper() {
            return FinanceStrictJson.MAPPER;
        }

        @Bean
        @ConditionalOnMissingBean(DataSource.class)
        public DataSource dataSource() {
            return new org.postgresql.ds.PGSimpleDataSource();
        }

        @Bean
        @ConditionalOnMissingBean(JdbcClient.class)
        public JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }
    }
}
