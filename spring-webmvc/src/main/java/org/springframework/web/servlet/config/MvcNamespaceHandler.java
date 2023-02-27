/*
 * Copyright 2002-2015 the original author or authors.
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

package org.springframework.web.servlet.config;

import org.springframework.beans.factory.xml.NamespaceHandler;
import org.springframework.beans.factory.xml.NamespaceHandlerSupport;

/**
 * {@link NamespaceHandler} for Spring MVC configuration namespace.
 *
 * @author Keith Donald
 * @author Jeremy Grelle
 * @author Sebastien Deleuze
 * @since 3.0
 */
public class MvcNamespaceHandler extends NamespaceHandlerSupport {

	@Override
	public void init() {
		// 注册 annotation-driven 标签的解析器 AnnotationDrivenBeanDefinitionParser，用来解析 Controller
		registerBeanDefinitionParser("annotation-driven", new AnnotationDrivenBeanDefinitionParser());
		// 注册 default-servlet-handler 标签的解析器 DefaultServletHandlerBeanDefinitionParser，用来解析静态资源文件（html、jsp、js和css等）
		registerBeanDefinitionParser("default-servlet-handler", new DefaultServletHandlerBeanDefinitionParser());
		// 注册 interceptors 标签的解析器 InterceptorsBeanDefinitionParser，用来解析拦截器
		registerBeanDefinitionParser("interceptors", new InterceptorsBeanDefinitionParser());
		// 注册 resources 标签的解析器 ResourcesBeanDefinitionParser，用来解析资源文件
		registerBeanDefinitionParser("resources", new ResourcesBeanDefinitionParser());
		// 注册 view-controller、redirect-view-controller、status-controller 标签的解析器 ViewControllerBeanDefinitionParser，用来解析视图view
		registerBeanDefinitionParser("view-controller", new ViewControllerBeanDefinitionParser());
		registerBeanDefinitionParser("redirect-view-controller", new ViewControllerBeanDefinitionParser());
		registerBeanDefinitionParser("status-controller", new ViewControllerBeanDefinitionParser());
		// 注册 view-resolvers 标签的解析器 ViewResolversBeanDefinitionParser，用来解析视图解析器
		registerBeanDefinitionParser("view-resolvers", new ViewResolversBeanDefinitionParser());
		registerBeanDefinitionParser("tiles-configurer", new TilesConfigurerBeanDefinitionParser());
		// 注册 freemarker-configurer 标签的解析器 FreeMarkerConfigurerBeanDefinitionParser，用来解析 freemarker 配置
		registerBeanDefinitionParser("freemarker-configurer", new FreeMarkerConfigurerBeanDefinitionParser());
		registerBeanDefinitionParser("velocity-configurer", new VelocityConfigurerBeanDefinitionParser());
		// 注册 groovy-configurer 标签的解析器 GroovyMarkupConfigurerBeanDefinitionParser，用来解析 groovy 配置
		registerBeanDefinitionParser("groovy-configurer", new GroovyMarkupConfigurerBeanDefinitionParser());
		// 注册 script-template-configurer 标签的解析器 ScriptTemplateConfigurerBeanDefinitionParser，用来解析 script 脚本模板配置
		registerBeanDefinitionParser("script-template-configurer", new ScriptTemplateConfigurerBeanDefinitionParser());
		// 注册 cors 标签的解析器 CorsBeanDefinitionParser，用来解析跨域
		registerBeanDefinitionParser("cors", new CorsBeanDefinitionParser());
	}

}
