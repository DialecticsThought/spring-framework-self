/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.aop.aspectj.annotation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.aspectj.lang.reflect.PerClauseKind;

import org.springframework.aop.Advisor;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Helper for retrieving @AspectJ beans from a BeanFactory and building
 * Spring Advisors based on them, for use with auto-proxying.
 *
 * @author Juergen Hoeller
 * @since 2.0.2
 * @see AnnotationAwareAspectJAutoProxyCreator
 */
public class BeanFactoryAspectJAdvisorsBuilder {

	private final ListableBeanFactory beanFactory;

	private final AspectJAdvisorFactory advisorFactory;

	@Nullable
	private volatile List<String> aspectBeanNames;

	private final Map<String, List<Advisor>> advisorsCache = new ConcurrentHashMap<>();

	private final Map<String, MetadataAwareAspectInstanceFactory> aspectFactoryCache = new ConcurrentHashMap<>();


	/**
	 * Create a new BeanFactoryAspectJAdvisorsBuilder for the given BeanFactory.
	 * @param beanFactory the ListableBeanFactory to scan
	 */
	public BeanFactoryAspectJAdvisorsBuilder(ListableBeanFactory beanFactory) {
		this(beanFactory, new ReflectiveAspectJAdvisorFactory(beanFactory));
	}

	/**
	 * Create a new BeanFactoryAspectJAdvisorsBuilder for the given BeanFactory.
	 * @param beanFactory the ListableBeanFactory to scan
	 * @param advisorFactory the AspectJAdvisorFactory to build each Advisor with
	 */
	public BeanFactoryAspectJAdvisorsBuilder(ListableBeanFactory beanFactory, AspectJAdvisorFactory advisorFactory) {
		Assert.notNull(beanFactory, "ListableBeanFactory must not be null");
		Assert.notNull(advisorFactory, "AspectJAdvisorFactory must not be null");
		this.beanFactory = beanFactory;
		this.advisorFactory = advisorFactory;
	}


	/**
	 * Look for AspectJ-annotated aspect beans in the current bean factory,
	 * and return to a list of Spring AOP Advisors representing them.
	 * <p>Creates a Spring Advisor for each AspectJ advice method.
	 * @return the list of {@link org.springframework.aop.Advisor} beans
	 * @see #isEligibleBean
	 */
	public List<Advisor> buildAspectJAdvisors() {
		// 1.获取aspectBeanNames缓存，如果为空，则会触发切面的解析工作
		List<String> aspectNames = this.aspectBeanNames;
		// 解析切面，注意实例化第一个单实例bean的时候就会触发解析切面
		if (aspectNames == null) {
			synchronized (this) {
				aspectNames = this.aspectBeanNames;
				if (aspectNames == null) {
					// 用于保存所有解析出来的Advisor增强器
					List<Advisor> advisors = new ArrayList<>();
					// 用于保存切面名称
					aspectNames = new ArrayList<>();
					// 2.获取工厂中所有的beanNames（bean名称集合）
					String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
							this.beanFactory, Object.class, true, false);
					// 3.循环所有的beanNames（bean名称集合），找到增强器
					for (String beanName : beanNames) {
						// 跳过不符合条件的beanName
						if (!isEligibleBean(beanName)) {
							continue;
						}
						// We must be careful not to instantiate beans eagerly as in this case they
						// would be cached by the Spring container but would not have been weaved.

						// 获取beanName对应的bean的类型
						Class<?> beanType = this.beanFactory.getType(beanName, false);
						if (beanType == null) {
							continue;
						}
						// 4.如果存在@Aspect注解的话，则进行解析
						if (this.advisorFactory.isAspect(beanType)) {
							// 如果是切面类的话，则将beanName加入到aspectNames中
							aspectNames.add(beanName);
							// 根据beanType、beanName创建切面元数据
							AspectMetadata amd = new AspectMetadata(beanType, beanName);
							// 如果获取per-clause的类型是SINGLETON
							if (amd.getAjType().getPerClause().getKind() == PerClauseKind.SINGLETON) {

								// 创建MetadataAwareAspectInstanceFactory，主要用来创建切面对象实例
								MetadataAwareAspectInstanceFactory factory =
										new BeanFactoryAspectInstanceFactory(this.beanFactory, beanName);

								// 5.获取标记了@AspectJ相关注解的增强方法
								List<Advisor> classAdvisors = this.advisorFactory.getAdvisors(factory);
								if (this.beanFactory.isSingleton(beanName)) {
									// 6.如果当前bean是单例的，则将对应的增强方法加入advisorsCache缓存中
									this.advisorsCache.put(beanName, classAdvisors);
								}
								else {
									// 7.如果当前bean不是单例的，则将对应的工厂factory加入到aspectFactoryCache缓存中，后面可以通过factory来解析增强方法
									this.aspectFactoryCache.put(beanName, factory);
								}
								// 获取标记@AspectJ注解的增强方法
								advisors.addAll(classAdvisors);
							}
							else {
								// Per target or per this.
								if (this.beanFactory.isSingleton(beanName)) {
									throw new IllegalArgumentException("Bean with name '" + beanName +
											"' is a singleton, but aspect instantiation model is not singleton");
								}
								MetadataAwareAspectInstanceFactory factory =
										new PrototypeAspectInstanceFactory(this.beanFactory, beanName);
								this.aspectFactoryCache.put(beanName, factory);
								advisors.addAll(this.advisorFactory.getAdvisors(factory));
							}
						}
					}
					// 将解析出来的切面beanName放到缓存aspectBeanNames
					this.aspectBeanNames = aspectNames;
					return advisors;
				}
			}
		}
		// 如果经过上述的解析后，缓存aspectNames还是空列表的话，则直接返回一个空列表出去
		if (aspectNames.isEmpty()) {
			return Collections.emptyList();
		}
		// 存放所有的增强器的集合
		List<Advisor> advisors = new ArrayList<>();
		// 8.循环遍历所有解析到的切面名称
		for (String aspectName : aspectNames) {
			// 9.根据切面名称从缓存advisorsCache中查找对应的增强器
			List<Advisor> cachedAdvisors = this.advisorsCache.get(aspectName);
			if (cachedAdvisors != null) {
				// 10.如果缓存中已经存在解析好的Advisor，则直接添加到advisors中
				advisors.addAll(cachedAdvisors);
			}
			else {
				// 11.缓存中没有，则根据切面beanName从缓存aspectFactoryCache中查找对应的工厂，然后解析出增强器，并添加到advisors中
				MetadataAwareAspectInstanceFactory factory = this.aspectFactoryCache.get(aspectName);
				//TODO 查看this.advisorFactory.getAdvisors该方法
				advisors.addAll(this.advisorFactory.getAdvisors(factory));
			}
		}
		// 返回增强器集合
		return advisors;
	}

	/**
	 * Return whether the aspect bean with the given name is eligible.
	 * @param beanName the name of the aspect bean
	 * @return whether the bean is eligible
	 */
	protected boolean isEligibleBean(String beanName) {
		return true;
	}

}
