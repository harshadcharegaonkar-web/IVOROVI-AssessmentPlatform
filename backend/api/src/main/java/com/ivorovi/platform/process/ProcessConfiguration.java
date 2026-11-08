package com.ivorovi.platform.process;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
class ProcessConfiguration {

    @Bean
    @ConditionalOnWebApplication
    FilterRegistrationBean<ProcessBoundaryFilter> processBoundaryFilter(ProcessProperties props) {
        var bean = new FilterRegistrationBean<>(new ProcessBoundaryFilter(props.role()));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        bean.addUrlPatterns("/*");
        return bean;
    }
}
