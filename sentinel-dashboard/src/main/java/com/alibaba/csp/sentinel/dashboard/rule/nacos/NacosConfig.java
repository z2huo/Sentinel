/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.dashboard.rule.nacos;

import com.alibaba.csp.sentinel.dashboard.config.NacosProperties;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.gateway.ApiDefinitionEntity;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.gateway.GatewayFlowRuleEntity;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.*;
import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigFactory;
import com.alibaba.nacos.api.config.ConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Properties;

/**
 * @author Eric Zhao
 * @since 1.4.0
 */
@Configuration
@EnableConfigurationProperties(NacosProperties.class)
public class NacosConfig {

    @Autowired
    private NacosProperties nacosProperties;

    // region 流控规则

    @Bean
    public Converter<List<FlowRuleEntity>, String> flowRuleEntityEncoder() {
        return list -> JSON.toJSONString(list, SerializerFeature.PrettyFormat);
    }

    @Bean
    public Converter<String, List<FlowRuleEntity>> flowRuleEntityDecoder() {
        return jsonString -> JSON.parseArray(jsonString, FlowRuleEntity.class);
    }

    // endregion

    // region 授权规则

    @Bean
    public Converter<List<AuthorityRuleEntity>, String> authorRuleEntityEncoder() {
        return list -> JSON.toJSONString(list, SerializerFeature.PrettyFormat);
    }

    @Bean
    public Converter<String, List<AuthorityRuleEntity>> authorRuleEntityDecoder() {
        return jsonString -> JSON.parseArray(jsonString, AuthorityRuleEntity.class);
    }

    // endregion

    // region 降级规则

    @Bean
    public Converter<List<DegradeRuleEntity>, String> degradeRuleEntityEncoder() {
        return list -> JSON.toJSONString(list, SerializerFeature.PrettyFormat);
    }

    @Bean
    public Converter<String, List<DegradeRuleEntity>> degradeRuleEntityDecoder() {
        return jsonString -> JSON.parseArray(jsonString, DegradeRuleEntity.class);
    }

    // region 热点规则

    @Bean
    public Converter<List<ParamFlowRuleEntity>, String> paramRuleEntityEncoder() {
        return list -> JSON.toJSONString(list, SerializerFeature.PrettyFormat);
    }

    @Bean
    public Converter<String, List<ParamFlowRuleEntity>> paramRuleEntityDecoder() {
        return jsonString -> JSON.parseArray(jsonString, ParamFlowRuleEntity.class);
    }

    // endregion

    // region 系统规则

    @Bean
    public Converter<List<SystemRuleEntity>, String> systemRuleEntityEncoder() {
        return list -> JSON.toJSONString(list, SerializerFeature.PrettyFormat);
    }

    @Bean
    public Converter<String, List<SystemRuleEntity>> systemRuleEntityDecoder() {
        return jsonString -> JSON.parseArray(jsonString, SystemRuleEntity.class);
    }

    // endregion

    // region 网关 API 分组管理规则

    @Bean
    public Converter<List<ApiDefinitionEntity>, String> apiDefinitionEntityEncoder() {
        return list -> JSON.toJSONString(list, SerializerFeature.PrettyFormat);
    }

    @Bean
    public Converter<String, List<ApiDefinitionEntity>> apiDefinitionEntityDecoder() {
        return jsonString -> JSON.parseArray(jsonString, ApiDefinitionEntity.class);
    }

    // endregion

    // region 网关流控规则

    @Bean
    public Converter<List<GatewayFlowRuleEntity>, String> gatewayFlowRuleEntityEncoder() {
        return list -> JSON.toJSONString(list, SerializerFeature.PrettyFormat);
    }

    @Bean
    public Converter<String, List<GatewayFlowRuleEntity>> gatewayFlowRuleEntityDecoder() {
        return jsonString -> JSON.parseArray(jsonString, GatewayFlowRuleEntity.class);
    }

    // endregion

    @Bean
    public ConfigService nacosConfigService() throws Exception {

        Properties properties = new Properties();

        PropertyMapper propertyMapper = PropertyMapper.get();
        propertyMapper.from(nacosProperties::getAddress).whenHasText()
                .to(x -> properties.setProperty(PropertyKeyConst.SERVER_ADDR, x));
        propertyMapper.from(nacosProperties::getNamespace).whenHasText()
                .to(x -> properties.setProperty(PropertyKeyConst.NAMESPACE, x));
        propertyMapper.from(nacosProperties::getUserName).whenHasText()
                .to(x -> properties.setProperty(PropertyKeyConst.USERNAME, x));
        propertyMapper.from(nacosProperties::getPassword).whenHasText()
                .to(x -> properties.setProperty(PropertyKeyConst.PASSWORD, x));

        return ConfigFactory.createConfigService(properties);
    }
}
