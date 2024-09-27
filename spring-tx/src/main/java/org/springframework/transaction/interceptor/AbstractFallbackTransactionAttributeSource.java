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

package org.springframework.transaction.interceptor;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.support.AopUtils;
import org.springframework.core.MethodClassKey;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

/**
 * Abstract implementation of {@link TransactionAttributeSource} that caches
 * attributes for methods and implements a fallback policy: 1. specific target
 * method; 2. target class; 3. declaring method; 4. declaring class/interface.
 *
 * <p>Defaults to using the target class's transaction attribute if none is
 * associated with the target method. Any transaction attribute associated with
 * the target method completely overrides a class transaction attribute.
 * If none found on the target class, the interface that the invoked method
 * has been called through (in case of a JDK proxy) will be checked.
 *
 * <p>This implementation caches attributes by method after they are first used.
 * If it is ever desirable to allow dynamic changing of transaction attributes
 * (which is very unlikely), caching could be made configurable. Caching is
 * desirable because of the cost of evaluating rollback rules.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 1.1
 */
public abstract class AbstractFallbackTransactionAttributeSource implements TransactionAttributeSource {

	/**
	 * Canonical value held in cache to indicate no transaction attribute was
	 * found for this method, and we don't need to look again.
	 */
	@SuppressWarnings("serial")
	private static final TransactionAttribute NULL_TRANSACTION_ATTRIBUTE = new DefaultTransactionAttribute() {
		@Override
		public String toString() {
			return "null";
		}
	};


	/**
	 * Logger available to subclasses.
	 * <p>As this base class is not marked Serializable, the logger will be recreated
	 * after serialization - provided that the concrete subclass is Serializable.
	 */
	protected final Log logger = LogFactory.getLog(getClass());

	/**
	 * Cache of TransactionAttributes, keyed by method on a specific target class.
	 * <p>As this base class is not marked Serializable, the cache will be recreated
	 * after serialization - provided that the concrete subclass is Serializable.
	 */
	private final Map<Object, TransactionAttribute> attributeCache = new ConcurrentHashMap<>(1024);


	/**
	 * Determine the transaction attribute for this method invocation.
	 * <p>Defaults to the class's transaction attribute if no method attribute is found.
	 *
	 * @param method      the method for the current invocation (never {@code null})
	 * @param targetClass the target class for this invocation (may be {@code null})
	 * @return a TransactionAttribute for this method, or {@code null} if the method
	 * is not transactional
	 * <p>
	 * TODO 方法的目的是根据给定的方法和目标类获取对应的事务属性。它支持缓存机制，以提高性能，避免重复计算
	 */
	@Override
	@Nullable
	public TransactionAttribute getTransactionAttribute(Method method, @Nullable Class<?> targetClass) {
		// 如果方法的声明类是 Object（即系统自带的方法），则返回 null，表示该方法没有事务属性
		if (method.getDeclaringClass() == Object.class) {
			return null;
		}

		// First, see if we have a cached value.
		// 调用 getCacheKey 方法，生成用于缓存的唯一键，以便后续查找
		Object cacheKey = getCacheKey(method, targetClass);
		TransactionAttribute cached = this.attributeCache.get(cacheKey);
		if (cached != null) {// 如果缓存中存在值，接下来会判断这个值是一个实际的事务属性，还是指示没有事务属性的标记
			// Value will either be canonical value indicating there is no transaction attribute,
			// or an actual transaction attribute.
			if (cached == NULL_TRANSACTION_ATTRIBUTE) {// 如果缓存的值等于 NULL_TRANSACTION_ATTRIBUTE，则返回 null，表示该方法没有事务属性
				return null;
			} else {
				return cached;// 如果缓存值是实际的事务属性，直接返回该属性
			}
		} else {
			// We need to work it out.
			// 如果缓存中没有找到事务属性，则调用 computeTransactionAttribute 方法根据方法和目标类计算事务属性
			// TODO 进入
			TransactionAttribute txAttr = computeTransactionAttribute(method, targetClass);
			// Put it in the cache.
			if (txAttr == null) {// 如果计算得到的事务属性为 null，则在缓存中存入 NULL_TRANSACTION_ATTRIBUTE，用于后续快速返回
				this.attributeCache.put(cacheKey, NULL_TRANSACTION_ATTRIBUTE);
			} else {// 如果计算得到的事务属性不为 null，则使用 ClassUtils.getQualifiedMethodName 获取方法的完全限定名（包括类名和方法名）
				String methodIdentification = ClassUtils.getQualifiedMethodName(method, targetClass);
				// 果事务属性是 DefaultTransactionAttribute 的实例，则调用 setDescriptor 方法设置方法的描述符，以便后续日志和调试使用
				if (txAttr instanceof DefaultTransactionAttribute) {
					((DefaultTransactionAttribute) txAttr).setDescriptor(methodIdentification);
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Adding transactional method '" + methodIdentification + "' with attribute: " + txAttr);
				}
				// 将计算得到的事务属性存入缓存，方便后续使用
				this.attributeCache.put(cacheKey, txAttr);
			}
			// 最终返回计算得到的事务属性
			return txAttr;
		}
	}

	/**
	 * Determine a cache key for the given method and target class.
	 * <p>Must not produce same key for overloaded methods.
	 * Must produce same key for different instances of the same method.
	 *
	 * @param method      the method (never {@code null})
	 * @param targetClass the target class (may be {@code null})
	 * @return the cache key (never {@code null})
	 */
	protected Object getCacheKey(Method method, @Nullable Class<?> targetClass) {
		return new MethodClassKey(method, targetClass);
	}

	/**
	 * Same signature as {@link #getTransactionAttribute}, but doesn't cache the result.
	 * {@link #getTransactionAttribute} is effectively a caching decorator for this method.
	 * <p>As of 4.1.8, this method can be overridden.
	 *
	 * @see #getTransactionAttribute
	 * @since 4.1.8
	 */
	@Nullable
	protected TransactionAttribute computeTransactionAttribute(Method method, @Nullable Class<?> targetClass) {
		// Don't allow no-public methods as required.
		// 如果只允许公共方法且当前方法不是公共的，返回 null，表示该方法不具备事务属性
		if (allowPublicMethodsOnly() && !Modifier.isPublic(method.getModifiers())) {
			return null;
		}

		// The method may be on an interface, but we need attributes from the target class.
		// If the target class is null, the method will be unchanged.
		// 使用 AopUtils.getMostSpecificMethod 方法获取目标类中最具体的方法。此方法考虑了方法可能在接口中，但需要从目标类中获取属性
		Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);

		// First try is the method in the target class.
		// 首先尝试在目标类的特定方法中查找事务属性，调用 findTransactionAttribute 方法
		// TODO 进入
		// AnnotationTransactionAttributeSource#findTransactionAttribute
		// 		方法就是用事务注解解析器解析注解得到事务属性信息，方法本身没有什么逻辑
		TransactionAttribute txAttr = findTransactionAttribute(specificMethod);
		// 如果找到事务属性，立即返回
		if (txAttr != null) {
			return txAttr;
		}

		// Second try is the transaction attribute on the target class.
		// 如果第一步未找到属性，则尝试在特定方法的声明类（即目标类）中查找事务属性
		txAttr = findTransactionAttribute(specificMethod.getDeclaringClass());
		// 如果找到属性且方法是用户级方法（非内部方法），则返回该属性
		if (txAttr != null && ClassUtils.isUserLevelMethod(method)) {
			return txAttr;
		}
		// 如果特定方法与原始方法不同，则尝试在原始方法中查找事务属性
		if (specificMethod != method) {
			// Fallback is to look at the original method.
			// 如果第一步未找到属性，则尝试在特定方法的声明类（即目标类）中查找事务属性
			txAttr = findTransactionAttribute(method);
			// 如果找到事务属性，则立即返回
			if (txAttr != null) {
				return txAttr;
			}
			// Last fallback is the class of the original method.
			// 最后尝试在原始方法的声明类中查找事务属性
			txAttr = findTransactionAttribute(method.getDeclaringClass());
			// 如果找到属性且方法是用户级方法（非内部方法），则返回该属性
			if (txAttr != null && ClassUtils.isUserLevelMethod(method)) {
				return txAttr;
			}
		}

		return null;
	}


	/**
	 * Subclasses need to implement this to return the transaction attribute for the
	 * given class, if any.
	 *
	 * @param clazz the class to retrieve the attribute for
	 * @return all transaction attribute associated with this class, or {@code null} if none
	 */
	@Nullable
	protected abstract TransactionAttribute findTransactionAttribute(Class<?> clazz);

	/**
	 * Subclasses need to implement this to return the transaction attribute for the
	 * given method, if any.
	 *
	 * @param method the method to retrieve the attribute for
	 * @return all transaction attribute associated with this method, or {@code null} if none
	 */
	@Nullable
	protected abstract TransactionAttribute findTransactionAttribute(Method method);

	/**
	 * Should only public methods be allowed to have transactional semantics?
	 * <p>The default implementation returns {@code false}.
	 */
	protected boolean allowPublicMethodsOnly() {
		return false;
	}

}
