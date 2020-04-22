/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.netflix.zuul;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.netflix.zuul.FilterLoader;
import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.filters.FilterRegistry;
import com.netflix.zuul.filters.ZuulServletFilter;
import com.netflix.zuul.http.ZuulServlet;
import com.netflix.zuul.monitoring.CounterFactory;
import com.netflix.zuul.monitoring.TracerFactory;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.cloud.client.actuator.HasFeatures;
import org.springframework.cloud.client.discovery.event.HeartbeatEvent;
import org.springframework.cloud.client.discovery.event.HeartbeatMonitor;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;
import org.springframework.cloud.client.discovery.event.ParentHeartbeatEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.cloud.netflix.ribbon.SpringClientFactory;
import org.springframework.cloud.netflix.zuul.filters.CompositeRouteLocator;
import org.springframework.cloud.netflix.zuul.filters.RouteLocator;
import org.springframework.cloud.netflix.zuul.filters.SimpleRouteLocator;
import org.springframework.cloud.netflix.zuul.filters.ZuulProperties;
import org.springframework.cloud.netflix.zuul.filters.post.SendErrorFilter;
import org.springframework.cloud.netflix.zuul.filters.post.SendResponseFilter;
import org.springframework.cloud.netflix.zuul.filters.pre.DebugFilter;
import org.springframework.cloud.netflix.zuul.filters.pre.FormBodyWrapperFilter;
import org.springframework.cloud.netflix.zuul.filters.pre.Servlet30WrapperFilter;
import org.springframework.cloud.netflix.zuul.filters.pre.ServletDetectionFilter;
import org.springframework.cloud.netflix.zuul.filters.route.SendForwardFilter;
import org.springframework.cloud.netflix.zuul.metrics.DefaultCounterFactory;
import org.springframework.cloud.netflix.zuul.metrics.EmptyCounterFactory;
import org.springframework.cloud.netflix.zuul.metrics.EmptyTracerFactory;
import org.springframework.cloud.netflix.zuul.web.ZuulController;
import org.springframework.cloud.netflix.zuul.web.ZuulHandlerMapping;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static java.util.Collections.emptyList;

/**
 * @author Spencer Gibb
 * @author Dave Syer
 * @author Biju Kunjummen
 * 通过ZuulProperties进行配置路由寻址
 */
// 作为配置累加载，proxyBeanMethods = false，不能使用其@Bean标注的方法进行注入
@Configuration(proxyBeanMethods = false)
// 激活zuul属性
@EnableConfigurationProperties({ ZuulProperties.class })
// 判断ZuulServlet和ZuulServletFilter是否存在
@ConditionalOnClass({ ZuulServlet.class, ZuulServletFilter.class })
// 是否存在Bean判断是否应该加载zuul服务
@ConditionalOnBean(ZuulServerMarkerConfiguration.Marker.class)
// Make sure to get the ServerProperties from the same place as a normal web app would
// FIXME @Import(ServerPropertiesAutoConfiguration.class)
public class ZuulServerAutoConfiguration {

	@Autowired
	protected ZuulProperties zuulProperties;

	@Autowired
	protected ServerProperties server;

	@Autowired(required = false)
	private ErrorController errorController;

	private Map<String, CorsConfiguration> corsConfigurations;

	@Autowired(required = false)
	private List<WebMvcConfigurer> configurers = emptyList();

	@Bean
	public HasFeatures zuulFeature() {
		return HasFeatures.namedFeature("Zuul (Simple)",
				ZuulServerAutoConfiguration.class);
	}

	@Bean
	@Primary
	public CompositeRouteLocator primaryRouteLocator(
			Collection<RouteLocator> routeLocators) {
		return new CompositeRouteLocator(routeLocators);
	}

	@Bean
	@ConditionalOnMissingBean(SimpleRouteLocator.class)
	public SimpleRouteLocator simpleRouteLocator() {
		return new SimpleRouteLocator(this.server.getServlet().getContextPath(),
				this.zuulProperties);
	}

	/**
	 * zuulController, 包装了一个ZuulServlet类型的servlet, 实现对ZuulServlet类型的servlet的初始化.
	 * 用于HandlerMapping的映射
	 * @return
	 */
	@Bean
	public ZuulController zuulController() {
		return new ZuulController();
	}

	/**
	 * 类似SpringMvc的处理
	 * @param routes
	 * @param zuulController
	 * @return
	 */
	@Bean
	public ZuulHandlerMapping zuulHandlerMapping(RouteLocator routes,
			ZuulController zuulController) {
		ZuulHandlerMapping mapping = new ZuulHandlerMapping(routes, zuulController);
		mapping.setErrorController(this.errorController);
		mapping.setCorsConfigurations(getCorsConfigurations());
		return mapping;
	}

	protected final Map<String, CorsConfiguration> getCorsConfigurations() {
		if (this.corsConfigurations == null) {
			// 跨域配置
			ZuulCorsRegistry registry = new ZuulCorsRegistry();
			this.configurers.forEach(configurer -> configurer.addCorsMappings(registry));
			this.corsConfigurations = registry.getCorsConfigurations();
		}
		return this.corsConfigurations;
	}

	/**
	 * zuul 刷新监听创建
	 * @return
	 */
	@Bean
	public ApplicationListener<ApplicationEvent> zuulRefreshRoutesListener() {
		return new ZuulRefreshListener();
	}

	/**
	 * 注册 Servlet
	 * @return
	 */
	@Bean
	@ConditionalOnMissingBean(name = "zuulServlet")
	@ConditionalOnProperty(name = "zuul.use-filter", havingValue = "false",
			matchIfMissing = true)
	public ServletRegistrationBean zuulServlet() {
		ServletRegistrationBean<ZuulServlet> servlet = new ServletRegistrationBean<>(
				new ZuulServlet(), this.zuulProperties.getServletPattern());
		// The whole point of exposing this servlet is to provide a route that doesn't
		// buffer requests.
		servlet.addInitParameter("buffer-requests", "false");
		return servlet;
	}

	/**
	 * 注册Filter
	 * @return
	 */
	@Bean
	@ConditionalOnMissingBean(name = "zuulServletFilter")
	@ConditionalOnProperty(name = "zuul.use-filter", havingValue = "true",
			matchIfMissing = false)
	public FilterRegistrationBean zuulServletFilter() {
		final FilterRegistrationBean<ZuulServletFilter> filterRegistration = new FilterRegistrationBean<>();
		filterRegistration.setUrlPatterns(
				Collections.singleton(this.zuulProperties.getServletPattern()));
		filterRegistration.setFilter(new ZuulServletFilter());
		filterRegistration.setOrder(Ordered.LOWEST_PRECEDENCE);
		// The whole point of exposing this servlet is to provide a route that doesn't
		// buffer requests.
		filterRegistration.addInitParameter("buffer-requests", "false");
		return filterRegistration;
	}

	// pre filters 	前置过滤器

	/**
	 * 	pre filter 顺序-3，标记处理Servlet类型
	 */
	@Bean
	public ServletDetectionFilter servletDetectionFilter() {
		return new ServletDetectionFilter();
	}

	/**
	 * pre filter 顺序-1，包装请求体
 	 */
	@Bean
	@ConditionalOnMissingBean
	public FormBodyWrapperFilter formBodyWrapperFilter() {
		return new FormBodyWrapperFilter();
	}

	/**
	 * pre filter 顺序1，标记调试标志
	 */
	@Bean
	@ConditionalOnMissingBean
	public DebugFilter debugFilter() {
		return new DebugFilter();
	}

	/**
	 * pre filter 顺序-2，包装HttpServletRequest请求
	 */
	@Bean
	@ConditionalOnMissingBean
	public Servlet30WrapperFilter servlet30WrapperFilter() {
		return new Servlet30WrapperFilter();
	}

	// post filters 后置过滤器

	/**
	 * post filter 10000 处理正常处理的请求
	 */
	@Bean
	public SendResponseFilter sendResponseFilter(ZuulProperties properties) {
		return new SendResponseFilter(zuulProperties);
	}

	/**
	 * post filter 顺序0 处理有错误的请求
	 */
	@Bean
	public SendErrorFilter sendErrorFilter() {
		return new SendErrorFilter();
	}

	/**
	 * route filter 顺序500 forward 请求转发
	 */
	@Bean
	public SendForwardFilter sendForwardFilter() {
		return new SendForwardFilter();
	}

	/**
	 * ribbon
	 * @param springClientFactory
	 * @return
	 */
	@Bean
	@ConditionalOnProperty("zuul.ribbon.eager-load.enabled")
	public ZuulRouteApplicationContextInitializer zuulRoutesApplicationContextInitiazer(
			SpringClientFactory springClientFactory) {
		return new ZuulRouteApplicationContextInitializer(springClientFactory,
				zuulProperties);
	}

	@Configuration(proxyBeanMethods = false)
	protected static class ZuulFilterConfiguration {

		// 过滤器注入，用于后续ZuulRunner处理filter使用
		@Autowired
		private Map<String, ZuulFilter> filters;

		@Bean
		public ZuulFilterInitializer zuulFilterInitializer(CounterFactory counterFactory,
				TracerFactory tracerFactory) {
			FilterLoader filterLoader = FilterLoader.getInstance();
			FilterRegistry filterRegistry = FilterRegistry.instance();
			return new ZuulFilterInitializer(this.filters, counterFactory, tracerFactory,
					filterLoader, filterRegistry);
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(MeterRegistry.class)
	protected static class ZuulCounterFactoryConfiguration {

		@Bean
		@ConditionalOnBean(MeterRegistry.class)
		@ConditionalOnMissingBean(CounterFactory.class)
		public CounterFactory counterFactory(MeterRegistry meterRegistry) {
			return new DefaultCounterFactory(meterRegistry);
		}

	}

	@Configuration(proxyBeanMethods = false)
	protected static class ZuulMetricsConfiguration {

		@Bean
		@ConditionalOnMissingClass("io.micrometer.core.instrument.MeterRegistry")
		@ConditionalOnMissingBean(CounterFactory.class)
		public CounterFactory counterFactory() {
			return new EmptyCounterFactory();
		}

		@ConditionalOnMissingBean(TracerFactory.class)
		@Bean
		public TracerFactory tracerFactory() {
			return new EmptyTracerFactory();
		}

	}

	private static class ZuulRefreshListener
			implements ApplicationListener<ApplicationEvent> {

		@Autowired
		private ZuulHandlerMapping zuulHandlerMapping;

		private HeartbeatMonitor heartbeatMonitor = new HeartbeatMonitor();

		@Override
		public void onApplicationEvent(ApplicationEvent event) {
			if (event instanceof ContextRefreshedEvent
					|| event instanceof RefreshScopeRefreshedEvent
					|| event instanceof RoutesRefreshedEvent
					|| event instanceof InstanceRegisteredEvent) {
				reset();
			}
			else if (event instanceof ParentHeartbeatEvent) {
				ParentHeartbeatEvent e = (ParentHeartbeatEvent) event;
				resetIfNeeded(e.getValue());
			}
			else if (event instanceof HeartbeatEvent) {
				HeartbeatEvent e = (HeartbeatEvent) event;
				resetIfNeeded(e.getValue());
			}
		}

		private void resetIfNeeded(Object value) {
			if (this.heartbeatMonitor.update(value)) {
				reset();
			}
		}

		private void reset() {
			this.zuulHandlerMapping.setDirty(true);
		}

	}

	private static class ZuulCorsRegistry extends CorsRegistry {

		@Override
		protected Map<String, CorsConfiguration> getCorsConfigurations() {
			return super.getCorsConfigurations();
		}

	}

}
