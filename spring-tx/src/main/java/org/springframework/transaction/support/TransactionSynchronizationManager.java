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

package org.springframework.transaction.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.NamedThreadLocal;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Central delegate that manages resources and transaction synchronizations per thread.
 * To be used by resource management code but not by typical application code.
 *
 * <p>Supports one resource per key without overwriting, that is, a resource needs
 * to be removed before a new one can be set for the same key.
 * Supports a list of transaction synchronizations if synchronization is active.
 *
 * <p>Resource management code should check for thread-bound resources, e.g. JDBC
 * Connections or Hibernate Sessions, via {@code getResource}. Such code is
 * normally not supposed to bind resources to threads, as this is the responsibility
 * of transaction managers. A further option is to lazily bind on first use if
 * transaction synchronization is active, for performing transactions that span
 * an arbitrary number of resources.
 *
 * <p>Transaction synchronization must be activated and deactivated by a transaction
 * manager via {@link #initSynchronization()} and {@link #clearSynchronization()}.
 * This is automatically supported by {@link AbstractPlatformTransactionManager},
 * and thus by all standard Spring transaction managers, such as
 * {@link org.springframework.transaction.jta.JtaTransactionManager} and
 * {@link org.springframework.jdbc.datasource.DataSourceTransactionManager}.
 *
 * <p>Resource management code should only register synchronizations when this
 * manager is active, which can be checked via {@link #isSynchronizationActive};
 * it should perform immediate resource cleanup else. If transaction synchronization
 * isn't active, there is either no current transaction, or the transaction manager
 * doesn't support transaction synchronization.
 *
 * <p>Synchronization is for example used to always return the same resources
 * within a JTA transaction, e.g. a JDBC Connection or a Hibernate Session for
 * any given DataSource or SessionFactory, respectively.
 *
 * @author Juergen Hoeller
 * @see #isSynchronizationActive
 * @see #registerSynchronization
 * @see TransactionSynchronization
 * @see AbstractPlatformTransactionManager#setTransactionSynchronization
 * @see org.springframework.transaction.jta.JtaTransactionManager
 * @see org.springframework.jdbc.datasource.DataSourceTransactionManager
 * @see org.springframework.jdbc.datasource.DataSourceUtils#getConnection
 * @since 02.06.2003
 */
public abstract class TransactionSynchronizationManager {

	private static final Log logger = LogFactory.getLog(TransactionSynchronizationManager.class);

	/**
	 * 作用：用于存储当前线程的事务资源，比如数据库连接（ConnectionHolder）、JPA 会话（SessionHolder）等。
	 * 事务开始时，资源被绑定到当前线程，确保在同一线程中可以使用相同的资源。
	 * 场景：当某个线程在同一个事务中执行多个操作时，可以确保它们使用同一个数据库连接或其他资源
	 */
	private static final ThreadLocal<Map<Object, Object>> resources =
			new NamedThreadLocal<>("Transactional resources");
	/**
	 * 存储当前线程中所有与事务同步相关的回调函数（TransactionSynchronization），这些回调会在事务提交、回滚或完成时被执行
	 * <p>
	 * 事务同步（Transaction Synchronization）是指在事务的生命周期过程中，执行特定的回调操作，
	 * 比如在事务开始、提交、回滚等关键时刻执行一些自定义的逻辑。
	 * 这种机制允许你在事务执行的不同阶段做一些额外的操作
	 * 比如清理资源、刷新缓存、或者处理和事务相关的异步操作
	 *
	 * <pre>
	 *    假设你有一个电子商务应用，用户在购物时提交订单（Order），
	 *    每个订单都涉及到多个操作，如扣减库存、记录订单详情、发送确认邮件等。
	 *    在这种情况下，你可以使用事务来确保这些操作的一致性：要么所有操作都成功，要么所有操作都失败。
	 *
	 *    现在，你希望在事务提交成功后，发送订单确认邮件。
	 *    但是，你不想在订单提交的事务内部发送邮件，
	 *    因为这样会延长事务的生命周期。你可以通过事务同步机制，
	 *    在事务提交成功后，再去发送邮件
	 *    public class OrderService {
	 *     public void createOrder(Order order) {
	 *         // 开始一个事务 (通过 Spring 的事务管理器)
	 *         // 以下假设的代码是在一个事务上下文中运行
	 *
	 *         // 1. 执行订单创建操作，保存订单信息
	 *         saveOrder(order);
	 *         // 2. 执行扣减库存操作
	 *         updateInventory(order);
	 *         // 3. 注册事务同步回调，确保在事务提交后发送邮件
	 *         TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
	 *             @Override
	 *             public void afterCommit() {
	 *                 // 事务成功提交后才发送确认邮件
	 *                 sendConfirmationEmail(order);
	 *             }
	 *             @Override
	 *             public void afterCompletion(int status) {
	 *                 if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
	 *                     // 如果事务回滚了，执行相应的回滚逻辑，比如记录日志
	 *                     logTransactionFailure(order);
	 *                 }
	 *             }
	 *         });
	 *
	 *         // 事务会自动管理提交或回滚
	 *      }
	 *      private void saveOrder(Order order) {
	 *         // 模拟保存订单的操作
	 *      }
	 *      private void updateInventory(Order order) {
	 *         // 模拟扣减库存的操作
	 *      }
	 *      private void sendConfirmationEmail(Order order) {
	 *         // 模拟发送确认邮件
	 *      }
	 *     private void logTransactionFailure(Order order) {
	 *         // 模拟记录事务失败的日志
	 *      }
	 *   }
	 *   事务同步：
	 *   TransactionSynchronizationManager.registerSynchronization：
	 *   		这里注册了一个事务同步回调，用于监听事务的提交或回滚事件。
	 *   afterCommit() 回调：
	 *   		在事务成功提交后才会调用 afterCommit()，发送订单确认邮件。这避免了在事务还未成功时就发出确认邮件。
	 *   afterCompletion() 回调：
	 *   		在事务结束时（不论是提交还是回滚）都会调用此回调。
	 *   		如果事务回滚了（status == TransactionSynchronization.STATUS_ROLLED_BACK），会记录事务失败的日志。
	 * </pre>
	 */
	private static final ThreadLocal<Set<TransactionSynchronization>> synchronizations =
			new NamedThreadLocal<>("Transaction synchronizations");
	/**
	 * 作用：存储当前事务的名称，通常在创建新事务时设置。如果事务没有指定名称，可能为空。
	 * 场景：用于调试和日志记录，方便识别当前线程中正在运行的事务。
	 */
	private static final ThreadLocal<String> currentTransactionName =
			new NamedThreadLocal<>("Current transaction name");
	/**
	 * 作用：存储当前事务是否为只读事务。只读事务不会进行数据修改操作，只进行查询。
	 * 场景：在事务定义中可以标记事务为只读，数据库可以优化这种事务，避免锁定资源或进行不必要的检查。
	 */
	private static final ThreadLocal<Boolean> currentTransactionReadOnly =
			new NamedThreadLocal<>("Current transaction read-only status");
	/**
	 * 作用：存储当前事务的隔离级别，隔离级别决定了多个事务并发执行时如何相互影响。常见的隔离级别有 READ_COMMITTED、SERIALIZABLE 等。
	 * 场景：可以根据业务需求设置不同的隔离级别，确保事务的一致性和隔离性。
	 */
	private static final ThreadLocal<Integer> currentTransactionIsolationLevel =
			new NamedThreadLocal<>("Current transaction isolation level");
	/**
	 * 作用：存储当前事务是否处于活动状态。事务活动状态表示当前线程中有一个正在进行的事务。
	 * 场景：在执行数据库操作时，可以根据事务是否激活来判断是否应该将操作绑定到事务中。
	 */
	private static final ThreadLocal<Boolean> actualTransactionActive =
			new NamedThreadLocal<>("Actual transaction active");


	//-------------------------------------------------------------------------
	// Management of transaction-associated resource handles
	//-------------------------------------------------------------------------

	/**
	 * Return all resources that are bound to the current thread.
	 * <p>Mainly for debugging purposes. Resource managers should always invoke
	 * {@code hasResource} for a specific resource key that they are interested in.
	 *
	 * @return a Map with resource keys (usually the resource factory) and resource
	 * values (usually the active resource object), or an empty Map if there are
	 * currently no resources bound
	 * @see #hasResource
	 */
	public static Map<Object, Object> getResourceMap() {
		Map<Object, Object> map = resources.get();
		return (map != null ? Collections.unmodifiableMap(map) : Collections.emptyMap());
	}

	/**
	 * Check if there is a resource for the given key bound to the current thread.
	 *
	 * @param key the key to check (usually the resource factory)
	 * @return if there is a value bound to the current thread
	 * @see ResourceTransactionManager#getResourceFactory()
	 */
	public static boolean hasResource(Object key) {
		Object actualKey = TransactionSynchronizationUtils.unwrapResourceIfNecessary(key);
		Object value = doGetResource(actualKey);
		return (value != null);
	}

	/**
	 * Retrieve a resource for the given key that is bound to the current thread.
	 *
	 * @param key the key to check (usually the resource factory)
	 * @return a value bound to the current thread (usually the active
	 * resource object), or {@code null} if none
	 * @see ResourceTransactionManager#getResourceFactory()
	 */
	@Nullable
	public static Object getResource(Object key) {
		Object actualKey = TransactionSynchronizationUtils.unwrapResourceIfNecessary(key);
		Object value = doGetResource(actualKey);
		if (value != null && logger.isTraceEnabled()) {
			logger.trace("Retrieved value [" + value + "] for key [" + actualKey + "] bound to thread [" +
					Thread.currentThread().getName() + "]");
		}
		return value;
	}

	/**
	 * Actually check the value of the resource that is bound for the given key.
	 */
	@Nullable
	private static Object doGetResource(Object actualKey) {
		Map<Object, Object> map = resources.get();
		if (map == null) {
			return null;
		}
		Object value = map.get(actualKey);
		// Transparently remove ResourceHolder that was marked as void...
		if (value instanceof ResourceHolder && ((ResourceHolder) value).isVoid()) {
			map.remove(actualKey);
			// Remove entire ThreadLocal if empty...
			if (map.isEmpty()) {
				resources.remove();
			}
			value = null;
		}
		return value;
	}

	/**
	 * Bind the given resource for the given key to the current thread.
	 *
	 * @param key   the key to bind the value to (usually the resource factory)
	 * @param value the value to bind (usually the active resource object)
	 * @throws IllegalStateException if there is already a value bound to the thread
	 * @see ResourceTransactionManager#getResourceFactory()
	 * <p>
	 * TODO 该方法用于将某个资源（value）与某个键（key）绑定到当前线程中，以确保后续在同一线程中可以使用该资源
	 */
	public static void bindResource(Object key, Object value) throws IllegalStateException {
		// 解包可能被包装的资源键。这是为了确保 key 是实际的资源键，而不是某种代理或包装器
		// 解包后的键存储在 actualKey 中，它将作为当前线程资源的标识符
		Object actualKey = TransactionSynchronizationUtils.unwrapResourceIfNecessary(key);
		Assert.notNull(value, "Value must not be null");
		// 通过 resources.get() 从 ThreadLocal<Map<Object, Object>> resources 中获取当前线程的资源映射。
		// 如果当前线程没有任何绑定的资源，这里会返回 null
		Map<Object, Object> map = resources.get();
		// set ThreadLocal Map if none found
		// 如果当前线程没有已绑定的资源映射（即 map == null），则创建一个新的 HashMap 对象用于存储资源
		if (map == null) {
			map = new HashMap<>();
			// 然后通过 resources.set(map) 将新创建的资源映射绑定到当前线程的 ThreadLocal 中
			resources.set(map);
		}
		// 调用 map.put(actualKey, value) 将资源 value 与键 actualKey 绑定到当前线程的资源映射中
		// 如果该键已经有绑定的资源，则 map.put() 方法会返回先前绑定的资源 oldValue；如果没有先前的绑定，则返回 null
		Object oldValue = map.put(actualKey, value);
		// Transparently suppress a ResourceHolder that was marked as void...
		// 检查 oldValue 是否是一个 ResourceHolder 对象，并且该对象的 isVoid() 方法返回 true
		// 处理一些特殊情况，避免使用已经标记为无效的资源（void 的资源通常表示它已经完成或不可再用）
		if (oldValue instanceof ResourceHolder && ((ResourceHolder) oldValue).isVoid()) {
			// 如果 oldValue 是一个无效的资源持有者（即资源已标记为无效），则将 oldValue 设置为 null
			oldValue = null;
		}
		// 如果 oldValue 不为 null，说明已经有资源与该键绑定，抛出 IllegalStateException 异常
		// 避免同一个键绑定多个资源，因为这会导致资源混乱和事务问题
		if (oldValue != null) {
			throw new IllegalStateException("Already value [" + oldValue + "] for key [" +
					actualKey + "] bound to thread [" + Thread.currentThread().getName() + "]");
		}
		if (logger.isTraceEnabled()) {
			logger.trace("Bound value [" + value + "] for key [" + actualKey + "] to thread [" +
					Thread.currentThread().getName() + "]");
		}
	}

	/**
	 * Unbind a resource for the given key from the current thread.
	 *
	 * @param key the key to unbind (usually the resource factory)
	 * @return the previously bound value (usually the active resource object)
	 * @throws IllegalStateException if there is no value bound to the thread
	 * @see ResourceTransactionManager#getResourceFactory()
	 */
	public static Object unbindResource(Object key) throws IllegalStateException {
		// unwrapResourceIfNecessary 是一个工具方法，用于解包可能被代理或包装的资源键，以确保使用的是实际的资源键
		Object actualKey = TransactionSynchronizationUtils.unwrapResourceIfNecessary(key);
		// 调用 doUnbindResource(actualKey)，从当前线程的上下文中解绑与 actualKey 相关的资源，并返回这个资源
		// 它会从当前线程的 ThreadLocal 存储中解除绑定，并返回与该键相关的值（资源）。
		// TODO 进入
		Object value = doUnbindResource(actualKey);
		if (value == null) {
			throw new IllegalStateException(
					"No value for key [" + actualKey + "] bound to thread [" + Thread.currentThread().getName() + "]");
		}
		return value;
	}

	/**
	 * Unbind a resource for the given key from the current thread.
	 *
	 * @param key the key to unbind (usually the resource factory)
	 * @return the previously bound value, or {@code null} if none bound
	 */
	@Nullable
	public static Object unbindResourceIfPossible(Object key) {
		Object actualKey = TransactionSynchronizationUtils.unwrapResourceIfNecessary(key);
		return doUnbindResource(actualKey);
	}

	/**
	 * Actually remove the value of the resource that is bound for the given key.
	 */
	@Nullable
	private static Object doUnbindResource(Object actualKey) {
		// 通过 resources.get() 获取当前线程的资源映射。
		// resources 是一个 ThreadLocal 变量，存储当前线程的资源绑定信息，它是一个 Map，键为资源标识符，值为资源对象（如数据库连接）
		Map<Object, Object> map = resources.get();
		// 如果 map 为 null，表示当前线程中没有任何资源绑定，因此直接返回 null
		if (map == null) {
			return null;
		}
		// 调用 map.remove(actualKey)，从当前线程的资源映射中移除与 actualKey 相关联的资源，并将其赋值给 value
		Object value = map.remove(actualKey);
		// Remove entire ThreadLocal if empty...
		// 检查当前线程的资源映射是否为空（map.isEmpty()）。如果映射为空，说明该线程不再有任何资源绑定
		if (map.isEmpty()) {
			// 调用 resources.remove() 将整个 ThreadLocal 变量从当前线程中移除，释放不再需要的资源存储
			resources.remove();
		}
		// Transparently suppress a ResourceHolder that was marked as void...
		/**
		 * 检查 value 是否是 ResourceHolder 的实例，并且调用其 isVoid() 方法检查该资源是否标记为无效。
		 * 如果 value 是无效的 ResourceHolder，则将 value 设置为 null，表示该资源无效
		 */
		if (value instanceof ResourceHolder && ((ResourceHolder) value).isVoid()) {
			value = null;
		}
		if (value != null && logger.isTraceEnabled()) {
			logger.trace("Removed value [" + value + "] for key [" + actualKey + "] from thread [" +
					Thread.currentThread().getName() + "]");
		}
		return value;
	}


	//-------------------------------------------------------------------------
	// Management of transaction synchronizations
	//-------------------------------------------------------------------------

	/**
	 * Return if transaction synchronization is active for the current thread.
	 * Can be called before register to avoid unnecessary instance creation.
	 *
	 * @see #registerSynchronization
	 */
	public static boolean isSynchronizationActive() {
		return (synchronizations.get() != null);
	}

	/**
	 * Activate transaction synchronization for the current thread.
	 * Called by a transaction manager on transaction begin.
	 *
	 * @throws IllegalStateException if synchronization is already active
	 */
	public static void initSynchronization() throws IllegalStateException {
		if (isSynchronizationActive()) {
			throw new IllegalStateException("Cannot activate transaction synchronization - already active");
		}
		logger.trace("Initializing transaction synchronization");
		synchronizations.set(new LinkedHashSet<>());
	}

	/**
	 * Register a new transaction synchronization for the current thread.
	 * Typically called by resource management code.
	 * <p>Note that synchronizations can implement the
	 * {@link org.springframework.core.Ordered} interface.
	 * They will be executed in an order according to their order value (if any).
	 *
	 * @param synchronization the synchronization object to register
	 * @throws IllegalStateException if transaction synchronization is not active
	 * @see org.springframework.core.Ordered
	 */
	public static void registerSynchronization(TransactionSynchronization synchronization)
			throws IllegalStateException {

		Assert.notNull(synchronization, "TransactionSynchronization must not be null");
		Set<TransactionSynchronization> synchs = synchronizations.get();
		if (synchs == null) {
			throw new IllegalStateException("Transaction synchronization is not active");
		}
		synchs.add(synchronization);
	}

	/**
	 * Return an unmodifiable snapshot list of all registered synchronizations
	 * for the current thread.
	 *
	 * @return unmodifiable List of TransactionSynchronization instances
	 * @throws IllegalStateException if synchronization is not active
	 * @see TransactionSynchronization
	 */
	public static List<TransactionSynchronization> getSynchronizations() throws IllegalStateException {
		Set<TransactionSynchronization> synchs = synchronizations.get();
		if (synchs == null) {
			throw new IllegalStateException("Transaction synchronization is not active");
		}
		// Return unmodifiable snapshot, to avoid ConcurrentModificationExceptions
		// while iterating and invoking synchronization callbacks that in turn
		// might register further synchronizations.
		if (synchs.isEmpty()) {
			return Collections.emptyList();
		} else {
			// Sort lazily here, not in registerSynchronization.
			List<TransactionSynchronization> sortedSynchs = new ArrayList<>(synchs);
			AnnotationAwareOrderComparator.sort(sortedSynchs);
			return Collections.unmodifiableList(sortedSynchs);
		}
	}

	/**
	 * Deactivate transaction synchronization for the current thread.
	 * Called by the transaction manager on transaction cleanup.
	 *
	 * @throws IllegalStateException if synchronization is not active
	 */
	public static void clearSynchronization() throws IllegalStateException {
		if (!isSynchronizationActive()) {
			throw new IllegalStateException("Cannot deactivate transaction synchronization - not active");
		}
		logger.trace("Clearing transaction synchronization");
		synchronizations.remove();
	}


	//-------------------------------------------------------------------------
	// Exposure of transaction characteristics
	//-------------------------------------------------------------------------

	/**
	 * Expose the name of the current transaction, if any.
	 * Called by the transaction manager on transaction begin and on cleanup.
	 *
	 * @param name the name of the transaction, or {@code null} to reset it
	 * @see org.springframework.transaction.TransactionDefinition#getName()
	 */
	public static void setCurrentTransactionName(@Nullable String name) {
		currentTransactionName.set(name);
	}

	/**
	 * Return the name of the current transaction, or {@code null} if none set.
	 * To be called by resource management code for optimizations per use case,
	 * for example to optimize fetch strategies for specific named transactions.
	 *
	 * @see org.springframework.transaction.TransactionDefinition#getName()
	 */
	@Nullable
	public static String getCurrentTransactionName() {
		return currentTransactionName.get();
	}

	/**
	 * Expose a read-only flag for the current transaction.
	 * Called by the transaction manager on transaction begin and on cleanup.
	 *
	 * @param readOnly {@code true} to mark the current transaction
	 *                 as read-only; {@code false} to reset such a read-only marker
	 * @see org.springframework.transaction.TransactionDefinition#isReadOnly()
	 */
	public static void setCurrentTransactionReadOnly(boolean readOnly) {
		currentTransactionReadOnly.set(readOnly ? Boolean.TRUE : null);
	}

	/**
	 * Return whether the current transaction is marked as read-only.
	 * To be called by resource management code when preparing a newly
	 * created resource (for example, a Hibernate Session).
	 * <p>Note that transaction synchronizations receive the read-only flag
	 * as argument for the {@code beforeCommit} callback, to be able
	 * to suppress change detection on commit. The present method is meant
	 * to be used for earlier read-only checks, for example to set the
	 * flush mode of a Hibernate Session to "FlushMode.MANUAL" upfront.
	 *
	 * @see org.springframework.transaction.TransactionDefinition#isReadOnly()
	 * @see TransactionSynchronization#beforeCommit(boolean)
	 */
	public static boolean isCurrentTransactionReadOnly() {
		return (currentTransactionReadOnly.get() != null);
	}

	/**
	 * Expose an isolation level for the current transaction.
	 * Called by the transaction manager on transaction begin and on cleanup.
	 *
	 * @param isolationLevel the isolation level to expose, according to the
	 *                       JDBC Connection constants (equivalent to the corresponding Spring
	 *                       TransactionDefinition constants), or {@code null} to reset it
	 * @see java.sql.Connection#TRANSACTION_READ_UNCOMMITTED
	 * @see java.sql.Connection#TRANSACTION_READ_COMMITTED
	 * @see java.sql.Connection#TRANSACTION_REPEATABLE_READ
	 * @see java.sql.Connection#TRANSACTION_SERIALIZABLE
	 * @see org.springframework.transaction.TransactionDefinition#ISOLATION_READ_UNCOMMITTED
	 * @see org.springframework.transaction.TransactionDefinition#ISOLATION_READ_COMMITTED
	 * @see org.springframework.transaction.TransactionDefinition#ISOLATION_REPEATABLE_READ
	 * @see org.springframework.transaction.TransactionDefinition#ISOLATION_SERIALIZABLE
	 * @see org.springframework.transaction.TransactionDefinition#getIsolationLevel()
	 */
	public static void setCurrentTransactionIsolationLevel(@Nullable Integer isolationLevel) {
		currentTransactionIsolationLevel.set(isolationLevel);
	}

	/**
	 * Return the isolation level for the current transaction, if any.
	 * To be called by resource management code when preparing a newly
	 * created resource (for example, a JDBC Connection).
	 *
	 * @return the currently exposed isolation level, according to the
	 * JDBC Connection constants (equivalent to the corresponding Spring
	 * TransactionDefinition constants), or {@code null} if none
	 * @see java.sql.Connection#TRANSACTION_READ_UNCOMMITTED
	 * @see java.sql.Connection#TRANSACTION_READ_COMMITTED
	 * @see java.sql.Connection#TRANSACTION_REPEATABLE_READ
	 * @see java.sql.Connection#TRANSACTION_SERIALIZABLE
	 * @see org.springframework.transaction.TransactionDefinition#ISOLATION_READ_UNCOMMITTED
	 * @see org.springframework.transaction.TransactionDefinition#ISOLATION_READ_COMMITTED
	 * @see org.springframework.transaction.TransactionDefinition#ISOLATION_REPEATABLE_READ
	 * @see org.springframework.transaction.TransactionDefinition#ISOLATION_SERIALIZABLE
	 * @see org.springframework.transaction.TransactionDefinition#getIsolationLevel()
	 */
	@Nullable
	public static Integer getCurrentTransactionIsolationLevel() {
		return currentTransactionIsolationLevel.get();
	}

	/**
	 * Expose whether there currently is an actual transaction active.
	 * Called by the transaction manager on transaction begin and on cleanup.
	 *
	 * @param active {@code true} to mark the current thread as being associated
	 *               with an actual transaction; {@code false} to reset that marker
	 */
	public static void setActualTransactionActive(boolean active) {
		actualTransactionActive.set(active ? Boolean.TRUE : null);
	}

	/**
	 * Return whether there currently is an actual transaction active.
	 * This indicates whether the current thread is associated with an actual
	 * transaction rather than just with active transaction synchronization.
	 * <p>To be called by resource management code that wants to discriminate
	 * between active transaction synchronization (with or without backing
	 * resource transaction; also on PROPAGATION_SUPPORTS) and an actual
	 * transaction being active (with backing resource transaction;
	 * on PROPAGATION_REQUIRED, PROPAGATION_REQUIRES_NEW, etc).
	 *
	 * @see #isSynchronizationActive()
	 */
	public static boolean isActualTransactionActive() {
		return (actualTransactionActive.get() != null);
	}


	/**
	 * Clear the entire transaction synchronization state for the current thread:
	 * registered synchronizations as well as the various transaction characteristics.
	 *
	 * @see #clearSynchronization()
	 * @see #setCurrentTransactionName
	 * @see #setCurrentTransactionReadOnly
	 * @see #setCurrentTransactionIsolationLevel
	 * @see #setActualTransactionActive
	 */
	public static void clear() {
		synchronizations.remove();
		currentTransactionName.remove();
		currentTransactionReadOnly.remove();
		currentTransactionIsolationLevel.remove();
		actualTransactionActive.remove();
	}

}
