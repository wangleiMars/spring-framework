/*
 * Copyright 2002-2018 the original author or authors.
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

import java.lang.reflect.Modifier;

import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.aop.framework.ProxyConfig;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DelegatingIntroductionInterceptor;
import org.springframework.aop.target.SimpleBeanTargetSource;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.FactoryBeanNotInitializedException;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.util.ClassUtils;

/**
 * Convenient proxy factory bean for scoped objects.
 *
 * <p>Proxies created using this factory bean are thread-safe singletons
 * and may be injected into shared objects, with transparent scoping behavior.
 *
 * <p>Proxies returned by this class implement the {@link ScopedObject} interface.
 * This presently allows for removing the corresponding object from the scope,
 * seamlessly creating a new instance in the scope on next access.
 *
 * <p>Please note that the proxies created by this factory are
 * <i>class-based</i> proxies by default. This can be customized
 * through switching the "proxyTargetClass" property to "false".
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 2.0
 * @see #setProxyTargetClass
 */
@SuppressWarnings("serial")
public class ScopedProxyFactoryBean extends ProxyConfig
		implements FactoryBean<Object>, BeanFactoryAware, AopInfrastructureBean {

	/** The TargetSource that manages scoping */
	//定义一个新的SimpleBeanTargetSource对象，该对象包含beanFactory和targetBeanName对象。
	//实际上代理在调用MyTestBean的任意方法时,会从该对象的的beanFactory中重新获取targetBeanName的对象，
	// 即我们的scope为prototype的MyTestBean实例。这样就保证了每一次使用时都是一个新的MyTestBean实例
	private final SimpleBeanTargetSource scopedTargetSource = new SimpleBeanTargetSource();

	/** The name of the target bean */
	//再注册该bean声明时，添加了该属性的值，这里为“scopedTarget.myTestBean”
	private String targetBeanName;

	/** The cached singleton proxy */
	//最终的生成的代理对象
	private Object proxy;


	/**
	 * Create a new ScopedProxyFactoryBean instance.
	 */
	public ScopedProxyFactoryBean() {
		setProxyTargetClass(true);
	}


	/**
	 * Set the name of the bean that is to be scoped.
	 * //注入targetBeanName，这里为“scopedTarget.myTestBean”
	 */
	public void setTargetBeanName(String targetBeanName) {
		this.targetBeanName = targetBeanName;
		this.scopedTargetSource.setTargetBeanName(targetBeanName);
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		if (!(beanFactory instanceof ConfigurableBeanFactory)) {
			throw new IllegalStateException("Not running in a ConfigurableBeanFactory: " + beanFactory);
		}
		ConfigurableBeanFactory cbf = (ConfigurableBeanFactory) beanFactory;

		this.scopedTargetSource.setBeanFactory(beanFactory);
		//新建一个代理工厂
		ProxyFactory pf = new ProxyFactory();
		pf.copyFrom(this);
		pf.setTargetSource(this.scopedTargetSource);

		Class<?> beanType = beanFactory.getType(this.targetBeanName);
		if (beanType == null) {
			throw new IllegalStateException("Cannot create scoped proxy for bean '" + this.targetBeanName +
					"': Target type could not be determined at the time of proxy creation.");
		}
		if (!isProxyTargetClass() || beanType.isInterface() || Modifier.isPrivate(beanType.getModifiers())) {
			pf.setInterfaces(ClassUtils.getAllInterfacesForClass(beanType, cbf.getBeanClassLoader()));
		}

		// Add an introduction that implements only the methods on ScopedObject.
		ScopedObject scopedObject = new DefaultScopedObject(cbf, this.scopedTargetSource.getTargetBeanName());
		//代理工厂设置Advice通知
		pf.addAdvice(new DelegatingIntroductionInterceptor(scopedObject));

		// Add the AopInfrastructureBean marker to indicate that the scoped proxy
		// itself is not subject to auto-proxying! Only its target bean is.
		pf.addInterface(AopInfrastructureBean.class);
		//生成代理对象
		this.proxy = pf.getProxy(cbf.getBeanClassLoader());
	}
	
	//实现了FactoryBean接口，spring获取该对象实例时会调用该接口的getObject()
	//这里将代理对象proxy对象返回
	@Override
	public Object getObject() {
		if (this.proxy == null) {
			throw new FactoryBeanNotInitializedException();
		}
		return this.proxy;
	}

	@Override
	public Class<?> getObjectType() {
		if (this.proxy != null) {
			return this.proxy.getClass();
		}
		return this.scopedTargetSource.getTargetClass();
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

}
