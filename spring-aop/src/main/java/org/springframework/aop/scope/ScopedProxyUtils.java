/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.aop.scope;

import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;

/**
 * Utility class for creating a scoped proxy.
 * Used by ScopedProxyBeanDefinitionDecorator and ClassPathBeanDefinitionScanner.
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @since 2.5
 */
public abstract class ScopedProxyUtils {

	private static final String TARGET_NAME_PREFIX = "scopedTarget.";


	/**
	 * Generate a scoped proxy for the supplied target bean, registering the target
	 * bean with an internal name and setting 'targetBeanName' on the scoped proxy.
	 * @param definition the original bean definition
	 * @param registry the bean definition registry
	 * @param proxyTargetClass whether to create a target class proxy
	 * @return the scoped proxy definition
	 */
	public static BeanDefinitionHolder createScopedProxy(BeanDefinitionHolder definition,
			BeanDefinitionRegistry registry, boolean proxyTargetClass) {

		String originalBeanName = definition.getBeanName();
		//目标bean的bean声明
		BeanDefinition targetDefinition = definition.getBeanDefinition();
		//从原始beanName生成目标beanName 为“scopedTarget.”拼接上originalBeanName
		String targetBeanName = getTargetBeanName(originalBeanName);

		// Create a scoped proxy definition for the original bean name, 为原始bean名称创建一个作用域代理定义，
		// "hiding" the target bean in an internal target definition. 在内部目标定义中“隐藏”目标bean。
		RootBeanDefinition proxyDefinition = new RootBeanDefinition(ScopedProxyFactoryBean.class);
		//设置proxyDefinition的装饰的bean声明为原来的myTestBean声明及其他一些属性;
		proxyDefinition.setDecoratedDefinition(new BeanDefinitionHolder(targetDefinition, targetBeanName));
		proxyDefinition.setOriginatingBeanDefinition(targetDefinition);
		proxyDefinition.setSource(definition.getSource());
		proxyDefinition.setRole(targetDefinition.getRole());

		proxyDefinition.getPropertyValues().add("targetBeanName", targetBeanName);
		if (proxyTargetClass) {
			targetDefinition.setAttribute(AutoProxyUtils.PRESERVE_TARGET_CLASS_ATTRIBUTE, Boolean.TRUE);
			// ScopedProxyFactoryBean's "proxyTargetClass" default is TRUE, so we don't need to set it explicitly here.
			// ScopedProxyFactoryBean的“proxyTargetClass”默认值为TRUE，因此我们不需要在这里显式设置它。
		}
		else {
			proxyDefinition.getPropertyValues().add("proxyTargetClass", Boolean.FALSE);
		}

		// Copy autowire settings from original bean definition. 从原始bean定义复制autowire设置。
		proxyDefinition.setAutowireCandidate(targetDefinition.isAutowireCandidate());
		proxyDefinition.setPrimary(targetDefinition.isPrimary());
		if (targetDefinition instanceof AbstractBeanDefinition) {
			proxyDefinition.copyQualifiersFrom((AbstractBeanDefinition) targetDefinition);
		}

		// The target bean should be ignored in favor of the scoped proxy. 应该忽略目标bean，而不是作用域代理。
		targetDefinition.setAutowireCandidate(false);
		targetDefinition.setPrimary(false);
		//向beanFactory中注册名为“scopedTarget.myTestBean”实际上是myTestBean的声明。
		// Register the target bean as separate bean in the factory. 在工厂中将目标bean注册为单独的bean。
		registry.registerBeanDefinition(targetBeanName, targetDefinition);

		// Return the scoped proxy definition as primary bean definition 将作用域代理定义返回为主bean定义
		// (potentially an inner bean). (可能是内部bean）。
		return new BeanDefinitionHolder(proxyDefinition, originalBeanName, definition.getAliases());
	}

	/**
	 * Generate the bean name that is used within the scoped proxy to reference the target bean.
	 * @param originalBeanName the original name of bean
	 * @return the generated bean to be used to reference the target bean
	 */
	public static String getTargetBeanName(String originalBeanName) {
		return TARGET_NAME_PREFIX + originalBeanName;
	}

	/**
	 * Specify if the {@code beanName} is the name of a bean that references the target
	 * bean within a scoped proxy.
	 * @since 4.1.4
	 */
	public static boolean isScopedTarget(String beanName) {
		return (beanName != null && beanName.startsWith(TARGET_NAME_PREFIX));
	}

}
