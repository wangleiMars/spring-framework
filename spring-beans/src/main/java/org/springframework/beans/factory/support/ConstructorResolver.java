/*
 * Copyright 2002-2016 the original author or authors.
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

package org.springframework.beans.factory.support;

import java.beans.ConstructorProperties;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.util.ClassUtils;
import org.springframework.util.MethodInvoker;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Delegate for resolving constructors and factory methods.
 * Performs constructor resolution through argument matching.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @since 2.0
 * @see #autowireConstructor
 * @see #instantiateUsingFactoryMethod
 * @see AbstractAutowireCapableBeanFactory
 */
class ConstructorResolver {

	private static final NamedThreadLocal<InjectionPoint> currentInjectionPoint =
			new NamedThreadLocal<InjectionPoint>("Current injection point");

	private final AbstractAutowireCapableBeanFactory beanFactory;


	/**
	 * Create a new ConstructorResolver for the given factory and instantiation strategy.
	 * @param beanFactory the BeanFactory to work with
	 */
	public ConstructorResolver(AbstractAutowireCapableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}


	/**
	 * 构造函数自动注入
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param chosenCtors chosen candidate constructors (or {@code null} if none)
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper autowireConstructor(final String beanName, final RootBeanDefinition mbd,
			Constructor<?>[] chosenCtors, final Object[] explicitArgs) {
		/*确定构造方法的参数*/
		BeanWrapperImpl bw = new BeanWrapperImpl();
		this.beanFactory.initBeanWrapper(bw);
		// 保存最终需要的有参构造器和参数
		Constructor<?> constructorToUse = null;
		ArgumentsHolder argsHolderToUse = null;
		Object[] argsToUse = null; // 保存最后类型正确的参数
		//explicitArgs通过getBean方法传入￼
		//如果getBean方法调用的时候指定方法参数那么直接使用
		if (explicitArgs != null) {
			// 如果显式指定了参数，那么使用之
			argsToUse = explicitArgs;
		}
		else {
			//如果在getBean方法时候没有指定则尝试从配置文件中解析￼
			Object[] argsToResolve = null;
			//尝试从缓存中获取￼
			synchronized (mbd.constructorArgumentLock) {
				// 缓存应该使用的构造方法
				constructorToUse = (Constructor<?>) mbd.resolvedConstructorOrFactoryMethod;
				if (constructorToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached constructor...//从缓存中取￼
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						// 如果缓存中没有，则通过配置的（准备）构造函数参数来生成
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			//如果缓存中存在
			if (argsToResolve != null) {
				//解析参数类型，如给定方法的构造函数 A(int,int)则通过此方法后就会把配置中的 ("1","1")转换为（1,1）￼
				//缓存中的值可能是原始值也肯能是最终值￼
				// 对参数进行转换
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, constructorToUse, argsToResolve);
			}
		}
		//没有被缓存
		/*确定构造方法和构造参数*/
		if (constructorToUse == null) {
			// Need to resolve the constructor.
			// 判断是否需要对构造方法的参数进行注入
			boolean autowiring = (chosenCtors != null ||
					mbd.getResolvedAutowireMode() == RootBeanDefinition.AUTOWIRE_CONSTRUCTOR);
			ConstructorArgumentValues resolvedValues = null;

			int minNrOfArgs;
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			else {
				//提取配置文件中的配置的构造函数参数￼
				ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
				//用于承载解析后的构造函数参数的值
				resolvedValues = new ConstructorArgumentValues();
				//能解析到的参数个数￼
				minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
			}

			// Take specified constructors, if any.
			Constructor<?>[] candidates = chosenCtors;
			// 将当前bean所属的类的所有构造方法作为候选构造方法
			if (candidates == null) {
				Class<?> beanClass = mbd.getBeanClass();
				try {
					candidates = (mbd.isNonPublicAccessAllowed() ?
							beanClass.getDeclaredConstructors() : beanClass.getConstructors());
				}
				catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Resolution of declared constructors on bean Class [" + beanClass.getName() +
							"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
				}
			}
			//排序给定的构造函数，public构造函数优先参数数量降序、非public构造函数参数数量降序￼
			AutowireUtils.sortConstructors(candidates);
			int minTypeDiffWeight = Integer.MAX_VALUE;
			Set<Constructor<?>> ambiguousConstructors = null;
			LinkedList<UnsatisfiedDependencyException> causes = null;

			for (Constructor<?> candidate : candidates) {
				// 获取每个构造函数其所有的参数类型组成数组（不去重）
				Class<?>[] paramTypes = candidate.getParameterTypes();
				
				// 如果候选构造方法只有默认构造方法，并且没有显式地指定构造参数，并且没有显式地指定构造参数
				// 那么会直接使用该构造方法来实例化bean
				if (constructorToUse != null && argsToUse.length > paramTypes.length) {
					// Already found greedy constructor that can be satisfied ->
					// do not look any further, there are only less greedy constructors left.
					//如果已经找到选用的构造函数或者需要的参数个数小于当前的构造函数参数个数则终止,因为已经按照参数个数降序排列￼
					// 如果已经找到该用的构造函数并且能利用的参数大于构造函数可以传入的（因为降序排序，所以一旦大于，后面都会大于）
					break;
				}
				if (paramTypes.length < minNrOfArgs) {
					//参数个数不相等
					continue;
				}

				ArgumentsHolder argsHolder;
				if (resolvedValues != null) {
					//有参数则根据值构造对应参数类型的参数
					try {
						//注释上获取参数名称￼
						String[] paramNames = ConstructorPropertiesChecker.evaluate(candidate, paramTypes.length);
						if (paramNames == null) {
							//获取参数名称探索器
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								//获取指定构造函数的参数名称
								paramNames = pnd.getParameterNames(candidate);
							}
						}
						//根据名称和数据类型创建参数持有者
						argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw, paramTypes, paramNames,
								getUserDeclaredConstructor(candidate), autowiring);
					}
					catch (UnsatisfiedDependencyException ex) {
						if (this.beanFactory.logger.isTraceEnabled()) {
							this.beanFactory.logger.trace(
									"Ignoring constructor [" + candidate + "] of bean '" + beanName + "': " + ex);
						}
						// Swallow and try next constructor.
						if (causes == null) {
							causes = new LinkedList<UnsatisfiedDependencyException>();
						}
						causes.add(ex);
						continue;
					}
				}
				else {
					// Explicit arguments given -> arguments length must match exactly.
					if (paramTypes.length != explicitArgs.length) {
						continue;
					}
					//构造函数没有参数的情况￼
					argsHolder = new ArgumentsHolder(explicitArgs);
				}
				//探测是否有不确定性的构造函数存在，例如不同构造函数的参数为父子关系
				int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
						argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
				// Choose this constructor if it represents the closest match.
				//如果它代表着当前最接近的匹配则选择作为构造函数￼
				if (typeDiffWeight < minTypeDiffWeight) {
					constructorToUse = candidate;
					argsHolderToUse = argsHolder;
					argsToUse = argsHolder.arguments;
					minTypeDiffWeight = typeDiffWeight;
					ambiguousConstructors = null;
				}
				else if (constructorToUse != null && typeDiffWeight == minTypeDiffWeight) {
					if (ambiguousConstructors == null) {
						ambiguousConstructors = new LinkedHashSet<Constructor<?>>();
						ambiguousConstructors.add(constructorToUse);
					}
					ambiguousConstructors.add(candidate);
				}
			}

			if (constructorToUse == null) {
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Could not resolve matching constructor " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities)");
			}
			else if (ambiguousConstructors != null && !mbd.isLenientConstructorResolution()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous constructor matches found in bean '" + beanName + "' " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousConstructors);
			}

			if (explicitArgs == null) {
				//将解析的构造函数加入缓存￼
				argsHolderToUse.storeCache(mbd, constructorToUse);
			}
		}

		try {
			Object beanInstance;

			if (System.getSecurityManager() != null) {
				final Constructor<?> ctorToUse = constructorToUse;
				final Object[] argumentsToUse = argsToUse;
				beanInstance = AccessController.doPrivileged(new PrivilegedAction<Object>() {
					@Override
					public Object run() {
						return beanFactory.getInstantiationStrategy().instantiate(
								mbd, beanName, beanFactory, ctorToUse, argumentsToUse);
					}
				}, beanFactory.getAccessControlContext());
			}
			else {
				beanInstance = this.beanFactory.getInstantiationStrategy().instantiate(
						mbd, beanName, this.beanFactory, constructorToUse, argsToUse);
			}
			//将构建的实例加入BeanWrapper中
			bw.setBeanInstance(beanInstance);
			return bw;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via constructor failed", ex);
		}
	}

	/**
	 * Resolve the factory method in the specified bean definition, if possible.
	 * {@link RootBeanDefinition#getResolvedFactoryMethod()} can be checked for the result.
	 * @param mbd the bean definition to check
	 */
	public void resolveFactoryMethodIfPossible(RootBeanDefinition mbd) {
		Class<?> factoryClass;
		boolean isStatic;
		if (mbd.getFactoryBeanName() != null) {
			factoryClass = this.beanFactory.getType(mbd.getFactoryBeanName());
			isStatic = false;
		}
		else {
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}
		factoryClass = ClassUtils.getUserClass(factoryClass);

		Method[] candidates = getCandidateMethods(factoryClass, mbd);
		Method uniqueCandidate = null;
		for (Method candidate : candidates) {
			if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
				if (uniqueCandidate == null) {
					uniqueCandidate = candidate;
				}
				else if (!Arrays.equals(uniqueCandidate.getParameterTypes(), candidate.getParameterTypes())) {
					uniqueCandidate = null;
					break;
				}
			}
		}
		synchronized (mbd.constructorArgumentLock) {
			mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
		}
	}

	/**
	 * Retrieve all candidate methods for the given class, considering
	 * the {@link RootBeanDefinition#isNonPublicAccessAllowed()} flag.
	 * Called as the starting point for factory method determination.
	 */
	private Method[] getCandidateMethods(final Class<?> factoryClass, final RootBeanDefinition mbd) {
		if (System.getSecurityManager() != null) {
			return AccessController.doPrivileged(new PrivilegedAction<Method[]>() {
				@Override
				public Method[] run() {
					return (mbd.isNonPublicAccessAllowed() ?
							ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods());
				}
			});
		}
		else {
			return (mbd.isNonPublicAccessAllowed() ?
					ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods());
		}
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * bean definition parameter specifies a class, rather than a "factory-bean", or
	 * an instance variable on a factory object itself configured using Dependency Injection.
	 * <p>Implementation requires iterating over the static or instance methods with the
	 * name specified in the RootBeanDefinition (the method may be overloaded) and trying
	 * to match with the parameters. We don't have the types attached to constructor args,
	 * so trial and error is the only way to go here. The explicitArgs array may contain
	 * argument values passed in programmatically via the corresponding getBean method.
	 * 使用命名工厂方法实例化bean。
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean
	 * method, or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper instantiateUsingFactoryMethod(
			final String beanName, final RootBeanDefinition mbd, final Object[] explicitArgs) {
		/*确定工厂对象*/
		BeanWrapperImpl bw = new BeanWrapperImpl();
		this.beanFactory.initBeanWrapper(bw);

		Object factoryBean;
		Class<?> factoryClass;
		boolean isStatic;

		String factoryBeanName = mbd.getFactoryBeanName();
		if (factoryBeanName != null) {
			// <1> 当前bean设置了factoryBeanName属性
			// 如果factoryBeanName和当前bean相同，那么直接报错
			if (factoryBeanName.equals(beanName)) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"factory-bean reference points back to the same bean definition");
			}
			// 从缓存中获取factory bean
			factoryBean = this.beanFactory.getBean(factoryBeanName);
			if (factoryBean == null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"factory-bean '" + factoryBeanName + "' (or a BeanPostProcessor involved) returned null");
			}
			// 当前容器中已经存在需要创建的bean，报错
			if (mbd.isSingleton() && this.beanFactory.containsSingleton(beanName)) {
				throw new IllegalStateException("About-to-be-created singleton instance implicitly appeared " +
						"through the creation of the factory bean that its bean definition points to");
			}
			factoryClass = factoryBean.getClass();
			isStatic = false;
		}
		else {
			// <2> 当前bean没有设置factoryBeanName
			// It's a static factory method on the bean class.
			if (!mbd.hasBeanClass()) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"bean definition declares neither a bean class nor a factory-bean reference");
			}
			factoryBean = null;
			// 工厂类是当前bean的类型
			factoryClass = mbd.getBeanClass();
			// 使用的是当前bean类型的静态工厂方法
			isStatic = true;
		}

		Method factoryMethodToUse = null;
		ArgumentsHolder argsHolderToUse = null;
		Object[] argsToUse = null;
		/*确认构造参数*/
		if (explicitArgs != null) {
			// <1> 如果在调用getBean方法时传入了参数，那么就确定使用该参数
			argsToUse = explicitArgs;
		}
		else {
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				// <2> 从缓存中获取，这些缓存的结果是在之后的代码中解析的，使用缓存避免重复解析
				// 获取缓存中的构造方法或者工厂方法
				factoryMethodToUse = (Method) mbd.resolvedConstructorOrFactoryMethod;
				if (factoryMethodToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached factory method...
					// 从缓存中获取使用的参数
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						// 获取缓存中的包可见字段
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			if (argsToResolve != null) {
				// 对参数值进一步转换
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, factoryMethodToUse, argsToResolve);
			}
		}
		/*确定工厂方法和解析参数*/
		if (factoryMethodToUse == null || argsToUse == null) {
			// Need to determine the factory method...
			// Try all methods with this name to see if they match the given arguments.
			factoryClass = ClassUtils.getUserClass(factoryClass);
			// 遍历工厂类的所有方法
			// 然后进行过滤
			// 首先过滤掉方法名不同的候选方法
			// 然后，如果调用的是静态工厂方法，那么会过滤掉非静态方法；如果是普通工厂方法，那么会过滤掉静态方法
			Method[] rawCandidates = getCandidateMethods(factoryClass, mbd);
			List<Method> candidateSet = new ArrayList<Method>();
			for (Method candidate : rawCandidates) {
				if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
					candidateSet.add(candidate);
				}
			}
			Method[] candidates = candidateSet.toArray(new Method[candidateSet.size()]);
			// 首先按照是否public进行排序，public的排在前面
			// 然后按照参数个数进行排序，参数多的排在前面
			AutowireUtils.sortFactoryMethods(candidates);

			ConstructorArgumentValues resolvedValues = null;
			boolean autowiring = (mbd.getResolvedAutowireMode() == RootBeanDefinition.AUTOWIRE_CONSTRUCTOR);
			int minTypeDiffWeight = Integer.MAX_VALUE;
			Set<Method> ambiguousFactoryMethods = null;

			int minNrOfArgs;
			//  下面计算参数的个数
			if (explicitArgs != null) {
				// 如果显式指定了使用的参数，那么直接使用该参数的长度
				minNrOfArgs = explicitArgs.length;
			}
			else {
				// We don't have arguments passed in programmatically, so we need to resolve the
				// arguments specified in the constructor arguments held in the bean definition.
				ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
				resolvedValues = new ConstructorArgumentValues();
				minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
			}

			LinkedList<UnsatisfiedDependencyException> causes = null;
			// 遍历候选方法
			for (Method candidate : candidates) {
				Class<?>[] paramTypes = candidate.getParameterTypes();

				if (paramTypes.length >= minNrOfArgs) {
					ArgumentsHolder argsHolder;

					if (resolvedValues != null) {
						// Resolved constructor arguments: type conversion and/or autowiring necessary.
						try {
							String[] paramNames = null;
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
							// 使用解析好的构造参数见构建argsHolder
							argsHolder = createArgumentArray(
									beanName, mbd, resolvedValues, bw, paramTypes, paramNames, candidate, autowiring);
						}
						catch (UnsatisfiedDependencyException ex) {
							if (this.beanFactory.logger.isTraceEnabled()) {
								this.beanFactory.logger.trace("Ignoring factory method [" + candidate +
										"] of bean '" + beanName + "': " + ex);
							}
							// Swallow and try next overloaded factory method.
							if (causes == null) {
								causes = new LinkedList<UnsatisfiedDependencyException>();
							}
							causes.add(ex);
							continue;
						}
					}

					else {
						// 如果显式指定了使用的参数，则候选方法的参数个数和显式参数的个数必须一致
						// Explicit arguments given -> arguments length must match exactly.
						if (paramTypes.length != explicitArgs.length) {
							continue;
						}
						argsHolder = new ArgumentsHolder(explicitArgs);
					}
					// 比较当前候选方法的参数和提供的参数
					// 如果是宽松模式，会调用getTypeDifferenceWeight，使用最接近的模式进行匹配，
					// 会计算候选方法每个位置上的参数和提供参数对应位置上参数的类型来计算一个累计值，值越小，匹配度越高
					// 如果是严格模式，会调用getAssignabilityWeight，必须所有参数都匹配
					int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
							argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
					// Choose this factory method if it represents the closest match.
					// 当前候选方法更加匹配
					if (typeDiffWeight < minTypeDiffWeight) {
						factoryMethodToUse = candidate;
						argsHolderToUse = argsHolder;
						argsToUse = argsHolder.arguments;
						minTypeDiffWeight = typeDiffWeight;
						ambiguousFactoryMethods = null;
					}
					// Find out about ambiguity: In case of the same type difference weight
					// for methods with the same number of parameters, collect such candidates
					// and eventually raise an ambiguity exception.
					// However, only perform that check in non-lenient constructor resolution mode,
					// and explicitly ignore overridden methods (with the same parameter signature).
					else if (factoryMethodToUse != null && typeDiffWeight == minTypeDiffWeight &&
							!mbd.isLenientConstructorResolution() &&
							paramTypes.length == factoryMethodToUse.getParameterTypes().length &&
							!Arrays.equals(paramTypes, factoryMethodToUse.getParameterTypes())) {
						if (ambiguousFactoryMethods == null) {
							ambiguousFactoryMethods = new LinkedHashSet<Method>();
							ambiguousFactoryMethods.add(factoryMethodToUse);
						}
						ambiguousFactoryMethods.add(candidate);
					}
				}
			}

			if (factoryMethodToUse == null) {
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				List<String> argTypes = new ArrayList<String>(minNrOfArgs);
				if (explicitArgs != null) {
					for (Object arg : explicitArgs) {
						argTypes.add(arg != null ? arg.getClass().getSimpleName() : "null");
					}
				}
				else {
					Set<ValueHolder> valueHolders = new LinkedHashSet<ValueHolder>(resolvedValues.getArgumentCount());
					valueHolders.addAll(resolvedValues.getIndexedArgumentValues().values());
					valueHolders.addAll(resolvedValues.getGenericArgumentValues());
					for (ValueHolder value : valueHolders) {
						String argType = (value.getType() != null ? ClassUtils.getShortName(value.getType()) :
								(value.getValue() != null ? value.getValue().getClass().getSimpleName() : "null"));
						argTypes.add(argType);
					}
				}
				String argDesc = StringUtils.collectionToCommaDelimitedString(argTypes);
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"No matching factory method found: " +
						(mbd.getFactoryBeanName() != null ?
							"factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
						"factory method '" + mbd.getFactoryMethodName() + "(" + argDesc + ")'. " +
						"Check that a method with the specified name " +
						(minNrOfArgs > 0 ? "and arguments " : "") +
						"exists and that it is " +
						(isStatic ? "static" : "non-static") + ".");
			}
			else if (void.class == factoryMethodToUse.getReturnType()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid factory method '" + mbd.getFactoryMethodName() +
						"': needs to have a non-void return type!");
			}
			else if (ambiguousFactoryMethods != null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous factory method matches found in bean '" + beanName + "' " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousFactoryMethods);
			}

			if (explicitArgs == null && argsHolderToUse != null) {
				// 将胜出的工厂方法加入到缓存中
				argsHolderToUse.storeCache(mbd, factoryMethodToUse);
			}
		}
		/*实例化*/
		try {
			Object beanInstance;

			if (System.getSecurityManager() != null) {
				final Object fb = factoryBean;
				final Method factoryMethod = factoryMethodToUse;
				final Object[] args = argsToUse;
				beanInstance = AccessController.doPrivileged(new PrivilegedAction<Object>() {
					@Override
					public Object run() {
						return beanFactory.getInstantiationStrategy().instantiate(
								mbd, beanName, beanFactory, fb, factoryMethod, args);
					}
				}, beanFactory.getAccessControlContext());
			}
			else {
				beanInstance = this.beanFactory.getInstantiationStrategy().instantiate(
						mbd, beanName, this.beanFactory, factoryBean, factoryMethodToUse, argsToUse);
			}

			if (beanInstance == null) {
				return null;
			}
			bw.setBeanInstance(beanInstance);
			return bw;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via factory method failed", ex);
		}
	}

	/**
	 * Resolve the constructor arguments for this bean into the resolvedValues object.
	 * This may involve looking up other beans.
	 * <p>This method is also used for handling invocations of static factory methods.
	 * 确定构造函数参数，并保存到resolvedValues中
	 * @param beanName bean名称
	 * @param mbd 配置文件信息
	 * @param bw beanwrapper
	 * @param cargs 配置文件中解析出的构造函数信息
	 * @param resolvedValues 保存解析出的参数值信息
	 * @return 解析到参数的个数
	 */
	private int resolveConstructorArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			ConstructorArgumentValues cargs, ConstructorArgumentValues resolvedValues) {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);

		int minNrOfArgs = cargs.getArgumentCount();
		// 遍历提供的带有索引的构造参数
		for (Map.Entry<Integer, ConstructorArgumentValues.ValueHolder> entry : cargs.getIndexedArgumentValues().entrySet()) {
			int index = entry.getKey();
			if (index < 0) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid constructor argument index: " + index);
			}
			if (index > minNrOfArgs) {
				minNrOfArgs = index + 1;
			}
			ConstructorArgumentValues.ValueHolder valueHolder = entry.getValue();
			// 如果当前参数值已经转换过，那么直接添加到resolvedValues中
			if (valueHolder.isConverted()) {
				resolvedValues.addIndexedArgumentValue(index, valueHolder);
			}
			else {
				// 当前遍历的参数值没有转换过，那么对参数值进行转换，然后添加到resolvedValues中
				// 这里的参数值转换主要根据提供的参数的类型的不同执行不同的转换操作，比如如果参数是对另外一个bean的引用，那么这里会将参数转换成引用的bean实例
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				resolvedValues.addIndexedArgumentValue(index, resolvedValueHolder);
			}
		}
		// 不带有索引的，按照类型进行匹配的参数
		for (ConstructorArgumentValues.ValueHolder valueHolder : cargs.getGenericArgumentValues()) {
			// 判断是否转换过，转换过之前添加到resolvedValues中
			if (valueHolder.isConverted()) {
				resolvedValues.addGenericArgumentValue(valueHolder);
			}
			else {
				// 没有转换过，首先进行转换，然后添加到resolvedValues中
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				resolvedValues.addGenericArgumentValue(resolvedValueHolder);
			}
		}

		return minNrOfArgs;
	}

	/**
	 * Create an array of arguments to invoke a constructor or factory method,
	 * given the resolved constructor argument values.
	 * 将确定的构造函数转换为对应的参数类型
	 */
	private ArgumentsHolder createArgumentArray(
			String beanName, RootBeanDefinition mbd, ConstructorArgumentValues resolvedValues,
			BeanWrapper bw, Class<?>[] paramTypes, String[] paramNames, Object methodOrCtor,
			boolean autowiring) throws UnsatisfiedDependencyException {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);

		ArgumentsHolder args = new ArgumentsHolder(paramTypes.length);
		Set<ConstructorArgumentValues.ValueHolder> usedValueHolders =
				new HashSet<ConstructorArgumentValues.ValueHolder>(paramTypes.length);
		Set<String> autowiredBeanNames = new LinkedHashSet<String>(4);
		// 遍历给定构造方法或者工厂方法的参数
		for (int paramIndex = 0; paramIndex < paramTypes.length; paramIndex++) {
			Class<?> paramType = paramTypes[paramIndex];
			String paramName = (paramNames != null ? paramNames[paramIndex] : "");
			// Try to find matching constructor argument value, either indexed or generic.
			// 这里首先会根据索引列表中的位置到resolvedValues中去找到参数值，然后判断该参数是否和paramType的类型匹配，并且名称和paramName相同
			// 如果上面的操作没有匹配成功，那么会尝试通过参数类型和参数名称来从没有指定下标的resolvedValues中进行查找
			ConstructorArgumentValues.ValueHolder valueHolder =
					resolvedValues.getArgumentValue(paramIndex, paramType, paramName, usedValueHolders);
			// If we couldn't find a direct match and are not supposed to autowire,
			// let's try the next generic, untyped argument value as fallback:
			// it could match after type conversion (for example, String -> int).
			// 尝试使用参数位置或者参数类型来为当前遍历的参数寻找合适的参数值
			// 上面两种方式都没有找到匹配的参数，并且当前不允许自动注入
			// 那么会从resolvedValues中没有指定索引的那部分参数值中寻找既没有指定参数名称也没有指定参数类型的参数值
			if (valueHolder == null && (!autowiring || paramTypes.length == resolvedValues.getArgumentCount())) {
				valueHolder = resolvedValues.getGenericArgumentValue(null, null, usedValueHolders);
			}
			if (valueHolder != null) {
				// We found a potential match - let's give it a try.
				// Do not consider the same value definition multiple times!
				// 找到了满足条件的参数值，将参数值加入到usedValueHolders中，避免重复使用
				usedValueHolders.add(valueHolder);
				Object originalValue = valueHolder.getValue();
				Object convertedValue;
				// 判断参数是否需要转换，如果需要转换转换之，然后设置ArgumentHolders
				if (valueHolder.isConverted()) {
					convertedValue = valueHolder.getConvertedValue();
					args.preparedArguments[paramIndex] = convertedValue;
				}
				else {
					ConstructorArgumentValues.ValueHolder sourceHolder =
							(ConstructorArgumentValues.ValueHolder) valueHolder.getSource();
					Object sourceValue = sourceHolder.getValue();
					MethodParameter methodParam = MethodParameter.forMethodOrConstructor(methodOrCtor, paramIndex);
					try {
						convertedValue = converter.convertIfNecessary(originalValue, paramType, methodParam);
						// TODO re-enable once race condition has been found (SPR-7423)
						/*
						if (originalValue == sourceValue || sourceValue instanceof TypedStringValue) {
							// Either a converted value or still the original one: store converted value.
							sourceHolder.setConvertedValue(convertedValue);
							args.preparedArguments[paramIndex] = convertedValue;
						}
						else {
						*/
							args.resolveNecessary = true;
							args.preparedArguments[paramIndex] = sourceValue;
						// }
					}
					catch (TypeMismatchException ex) {
						throw new UnsatisfiedDependencyException(
								mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
								"Could not convert argument value of type [" +
								ObjectUtils.nullSafeClassName(valueHolder.getValue()) +
								"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
					}
				}
				args.arguments[paramIndex] = convertedValue;
				args.rawArguments[paramIndex] = originalValue;
			}
			else {
				// 没有从resolvedValues中找到满足条件的参数值
				MethodParameter methodParam = MethodParameter.forMethodOrConstructor(methodOrCtor, paramIndex);
				// No explicit match found: we're either supposed to autowire or
				// have to fail creating an argument array for the given constructor.
				// 如果当前不支持自动注入，直接报错
				if (!autowiring) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
							"Ambiguous argument values for parameter of type [" + paramType.getName() +
							"] - did you specify the correct bean references as arguments?");
				}
				try {
					// 根据参数信息，从容器中获取对应的bean
					Object autowiredArgument =
							resolveAutowiredArgument(methodParam, beanName, autowiredBeanNames, converter);
					args.rawArguments[paramIndex] = autowiredArgument;
					args.arguments[paramIndex] = autowiredArgument;
					args.preparedArguments[paramIndex] = new AutowiredArgumentMarker();
					args.resolveNecessary = true;
				}
				catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam), ex);
				}
			}
		}
		// 将自动注入的bean作为当前bean的依赖bean
		for (String autowiredBeanName : autowiredBeanNames) {
			this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
			if (this.beanFactory.logger.isDebugEnabled()) {
				this.beanFactory.logger.debug("Autowiring by type from bean name '" + beanName +
						"' via " + (methodOrCtor instanceof Constructor ? "constructor" : "factory method") +
						" to bean named '" + autowiredBeanName + "'");
			}
		}

		return args;
	}

	/**
	 * Resolve the prepared arguments stored in the given bean definition.
	 * 解析存储在给定bean定义中的准备好的参数。
	 */
	private Object[] resolvePreparedArguments(
			String beanName, RootBeanDefinition mbd, BeanWrapper bw, Member methodOrCtor, Object[] argsToResolve) {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);
		Class<?>[] paramTypes = (methodOrCtor instanceof Method ?
				((Method) methodOrCtor).getParameterTypes() : ((Constructor<?>) methodOrCtor).getParameterTypes());

		Object[] resolvedArgs = new Object[argsToResolve.length];
		// 遍历通过createArgumentArray方法生成的参数数组，针对不同的参数类型进行不同的处理
		for (int argIndex = 0; argIndex < argsToResolve.length; argIndex++) {
			Object argValue = argsToResolve[argIndex];
			MethodParameter methodParam = MethodParameter.forMethodOrConstructor(methodOrCtor, argIndex);
			GenericTypeResolver.resolveParameterType(methodParam, methodOrCtor.getDeclaringClass());
			if (argValue instanceof AutowiredArgumentMarker) {
				argValue = resolveAutowiredArgument(methodParam, beanName, null, converter);
			}
			else if (argValue instanceof BeanMetadataElement) {
				argValue = valueResolver.resolveValueIfNecessary("constructor argument", argValue);
			}
			else if (argValue instanceof String) {
				argValue = this.beanFactory.evaluateBeanDefinitionString((String) argValue, mbd);
			}
			Class<?> paramType = paramTypes[argIndex];
			try {
				resolvedArgs[argIndex] = converter.convertIfNecessary(argValue, paramType, methodParam);
			}
			catch (TypeMismatchException ex) {
				throw new UnsatisfiedDependencyException(
						mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
						"Could not convert argument value of type [" + ObjectUtils.nullSafeClassName(argValue) +
						"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
			}
		}
		return resolvedArgs;
	}

	protected Constructor<?> getUserDeclaredConstructor(Constructor<?> constructor) {
		Class<?> declaringClass = constructor.getDeclaringClass();
		Class<?> userClass = ClassUtils.getUserClass(declaringClass);
		if (userClass != declaringClass) {
			try {
				return userClass.getDeclaredConstructor(constructor.getParameterTypes());
			}
			catch (NoSuchMethodException ex) {
				// No equivalent constructor on user class (superclass)...
				// Let's proceed with the given constructor as we usually would.
			}
		}
		return constructor;
	}

	/**
	 * Template method for resolving the specified argument which is supposed to be autowired.
	 */
	protected Object resolveAutowiredArgument(
			MethodParameter param, String beanName, Set<String> autowiredBeanNames, TypeConverter typeConverter) {

		if (InjectionPoint.class.isAssignableFrom(param.getParameterType())) {
			InjectionPoint injectionPoint = currentInjectionPoint.get();
			if (injectionPoint == null) {
				throw new IllegalStateException("No current InjectionPoint available for " + param);
			}
			return injectionPoint;
		}
		return this.beanFactory.resolveDependency(
				new DependencyDescriptor(param, true), beanName, autowiredBeanNames, typeConverter);
	}



	static InjectionPoint setCurrentInjectionPoint(InjectionPoint injectionPoint) {
		InjectionPoint old = currentInjectionPoint.get();
		if (injectionPoint != null) {
			currentInjectionPoint.set(injectionPoint);
		}
		else {
			currentInjectionPoint.remove();
		}
		return old;
	}


	/**
	 * Private inner class for holding argument combinations.
	 */
	private static class ArgumentsHolder {

		public final Object[] rawArguments;

		public final Object[] arguments;

		public final Object[] preparedArguments;

		public boolean resolveNecessary = false;

		public ArgumentsHolder(int size) {
			this.rawArguments = new Object[size];
			this.arguments = new Object[size];
			this.preparedArguments = new Object[size];
		}

		public ArgumentsHolder(Object[] args) {
			this.rawArguments = args;
			this.arguments = args;
			this.preparedArguments = args;
		}

		public int getTypeDifferenceWeight(Class<?>[] paramTypes) {
			// If valid arguments found, determine type difference weight.
			// Try type difference weight on both the converted arguments and
			// the raw arguments. If the raw weight is better, use it.
			// Decrease raw weight by 1024 to prefer it over equal converted weight.
			int typeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.arguments);
			int rawTypeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.rawArguments) - 1024;
			return (rawTypeDiffWeight < typeDiffWeight ? rawTypeDiffWeight : typeDiffWeight);
		}

		public int getAssignabilityWeight(Class<?>[] paramTypes) {
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.arguments[i])) {
					return Integer.MAX_VALUE;
				}
			}
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.rawArguments[i])) {
					return Integer.MAX_VALUE - 512;
				}
			}
			return Integer.MAX_VALUE - 1024;
		}

		public void storeCache(RootBeanDefinition mbd, Object constructorOrFactoryMethod) {
			synchronized (mbd.constructorArgumentLock) {
				mbd.resolvedConstructorOrFactoryMethod = constructorOrFactoryMethod;
				mbd.constructorArgumentsResolved = true;
				if (this.resolveNecessary) {
					mbd.preparedConstructorArguments = this.preparedArguments;
				}
				else {
					mbd.resolvedConstructorArguments = this.arguments;
				}
			}
		}
	}


	/**
	 * Marker for autowired arguments in a cached argument array.
 	 */
	private static class AutowiredArgumentMarker {
	}


	/**
	 * Delegate for checking Java 6's {@link ConstructorProperties} annotation.
	 */
	private static class ConstructorPropertiesChecker {

		public static String[] evaluate(Constructor<?> candidate, int paramCount) {
			ConstructorProperties cp = candidate.getAnnotation(ConstructorProperties.class);
			if (cp != null) {
				String[] names = cp.value();
				if (names.length != paramCount) {
					throw new IllegalStateException("Constructor annotated with @ConstructorProperties but not " +
							"corresponding to actual number of parameters (" + paramCount + "): " + candidate);
				}
				return names;
			}
			else {
				return null;
			}
		}
	}

}
