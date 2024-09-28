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

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.Constants;
import org.springframework.lang.Nullable;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.InvalidTimeoutException;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSuspensionNotSupportedException;
import org.springframework.transaction.UnexpectedRollbackException;

/**
 * Abstract base class that implements Spring's standard transaction workflow,
 * serving as basis for concrete platform transaction managers like
 * {@link org.springframework.transaction.jta.JtaTransactionManager}.
 *
 * <p>This base class provides the following workflow handling:
 * <ul>
 * <li>determines if there is an existing transaction;
 * <li>applies the appropriate propagation behavior;
 * <li>suspends and resumes transactions if necessary;
 * <li>checks the rollback-only flag on commit;
 * <li>applies the appropriate modification on rollback
 * (actual rollback or setting rollback-only);
 * <li>triggers registered synchronization callbacks
 * (if transaction synchronization is active).
 * </ul>
 *
 * <p>Subclasses have to implement specific template methods for specific
 * states of a transaction, e.g.: begin, suspend, resume, commit, rollback.
 * The most important of them are abstract and must be provided by a concrete
 * implementation; for the rest, defaults are provided, so overriding is optional.
 *
 * <p>Transaction synchronization is a generic mechanism for registering callbacks
 * that get invoked at transaction completion time. This is mainly used internally
 * by the data access support classes for JDBC, Hibernate, JPA, etc when running
 * within a JTA transaction: They register resources that are opened within the
 * transaction for closing at transaction completion time, allowing e.g. for reuse
 * of the same Hibernate Session within the transaction. The same mechanism can
 * also be leveraged for custom synchronization needs in an application.
 *
 * <p>The state of this class is serializable, to allow for serializing the
 * transaction strategy along with proxies that carry a transaction interceptor.
 * It is up to subclasses if they wish to make their state to be serializable too.
 * They should implement the {@code java.io.Serializable} marker interface in
 * that case, and potentially a private {@code readObject()} method (according
 * to Java serialization rules) if they need to restore any transient state.
 *
 * @author Juergen Hoeller
 * @see #setTransactionSynchronization
 * @see TransactionSynchronizationManager
 * @see org.springframework.transaction.jta.JtaTransactionManager
 * @since 28.03.2003
 */
@SuppressWarnings("serial")
public abstract class AbstractPlatformTransactionManager implements PlatformTransactionManager, Serializable {

	/**
	 * Always activate transaction synchronization, even for "empty" transactions
	 * that result from PROPAGATION_SUPPORTS with no existing backend transaction.
	 *
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_SUPPORTS
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_NOT_SUPPORTED
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_NEVER
	 */
	public static final int SYNCHRONIZATION_ALWAYS = 0;

	/**
	 * Activate transaction synchronization only for actual transactions,
	 * that is, not for empty ones that result from PROPAGATION_SUPPORTS with
	 * no existing backend transaction.
	 *
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_REQUIRED
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_MANDATORY
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_REQUIRES_NEW
	 */
	public static final int SYNCHRONIZATION_ON_ACTUAL_TRANSACTION = 1;

	/**
	 * Never active transaction synchronization, not even for actual transactions.
	 */
	public static final int SYNCHRONIZATION_NEVER = 2;


	/**
	 * Constants instance for AbstractPlatformTransactionManager.
	 */
	private static final Constants constants = new Constants(AbstractPlatformTransactionManager.class);


	protected transient Log logger = LogFactory.getLog(getClass());

	private int transactionSynchronization = SYNCHRONIZATION_ALWAYS;

	private int defaultTimeout = TransactionDefinition.TIMEOUT_DEFAULT;

	private boolean nestedTransactionAllowed = false;

	private boolean validateExistingTransaction = false;

	private boolean globalRollbackOnParticipationFailure = true;

	private boolean failEarlyOnGlobalRollbackOnly = false;

	private boolean rollbackOnCommitFailure = false;


	/**
	 * Set the transaction synchronization by the name of the corresponding constant
	 * in this class, e.g. "SYNCHRONIZATION_ALWAYS".
	 *
	 * @param constantName name of the constant
	 * @see #SYNCHRONIZATION_ALWAYS
	 */
	public final void setTransactionSynchronizationName(String constantName) {
		setTransactionSynchronization(constants.asNumber(constantName).intValue());
	}

	/**
	 * Set when this transaction manager should activate the thread-bound
	 * transaction synchronization support. Default is "always".
	 * <p>Note that transaction synchronization isn't supported for
	 * multiple concurrent transactions by different transaction managers.
	 * Only one transaction manager is allowed to activate it at any time.
	 *
	 * @see #SYNCHRONIZATION_ALWAYS
	 * @see #SYNCHRONIZATION_ON_ACTUAL_TRANSACTION
	 * @see #SYNCHRONIZATION_NEVER
	 * @see TransactionSynchronizationManager
	 * @see TransactionSynchronization
	 */
	public final void setTransactionSynchronization(int transactionSynchronization) {
		this.transactionSynchronization = transactionSynchronization;
	}

	/**
	 * Return if this transaction manager should activate the thread-bound
	 * transaction synchronization support.
	 */
	public final int getTransactionSynchronization() {
		return this.transactionSynchronization;
	}

	/**
	 * Specify the default timeout that this transaction manager should apply
	 * if there is no timeout specified at the transaction level, in seconds.
	 * <p>Default is the underlying transaction infrastructure's default timeout,
	 * e.g. typically 30 seconds in case of a JTA provider, indicated by the
	 * {@code TransactionDefinition.TIMEOUT_DEFAULT} value.
	 *
	 * @see org.springframework.transaction.TransactionDefinition#TIMEOUT_DEFAULT
	 */
	public final void setDefaultTimeout(int defaultTimeout) {
		if (defaultTimeout < TransactionDefinition.TIMEOUT_DEFAULT) {
			throw new InvalidTimeoutException("Invalid default timeout", defaultTimeout);
		}
		this.defaultTimeout = defaultTimeout;
	}

	/**
	 * Return the default timeout that this transaction manager should apply
	 * if there is no timeout specified at the transaction level, in seconds.
	 * <p>Returns {@code TransactionDefinition.TIMEOUT_DEFAULT} to indicate
	 * the underlying transaction infrastructure's default timeout.
	 */
	public final int getDefaultTimeout() {
		return this.defaultTimeout;
	}

	/**
	 * Set whether nested transactions are allowed. Default is "false".
	 * <p>Typically initialized with an appropriate default by the
	 * concrete transaction manager subclass.
	 */
	public final void setNestedTransactionAllowed(boolean nestedTransactionAllowed) {
		this.nestedTransactionAllowed = nestedTransactionAllowed;
	}

	/**
	 * Return whether nested transactions are allowed.
	 */
	public final boolean isNestedTransactionAllowed() {
		return this.nestedTransactionAllowed;
	}

	/**
	 * Set whether existing transactions should be validated before participating
	 * in them.
	 * <p>When participating in an existing transaction (e.g. with
	 * PROPAGATION_REQUIRED or PROPAGATION_SUPPORTS encountering an existing
	 * transaction), this outer transaction's characteristics will apply even
	 * to the inner transaction scope. Validation will detect incompatible
	 * isolation level and read-only settings on the inner transaction definition
	 * and reject participation accordingly through throwing a corresponding exception.
	 * <p>Default is "false", leniently ignoring inner transaction settings,
	 * simply overriding them with the outer transaction's characteristics.
	 * Switch this flag to "true" in order to enforce strict validation.
	 *
	 * @since 2.5.1
	 */
	public final void setValidateExistingTransaction(boolean validateExistingTransaction) {
		this.validateExistingTransaction = validateExistingTransaction;
	}

	/**
	 * Return whether existing transactions should be validated before participating
	 * in them.
	 *
	 * @since 2.5.1
	 */
	public final boolean isValidateExistingTransaction() {
		return this.validateExistingTransaction;
	}

	/**
	 * Set whether to globally mark an existing transaction as rollback-only
	 * after a participating transaction failed.
	 * <p>Default is "true": If a participating transaction (e.g. with
	 * PROPAGATION_REQUIRED or PROPAGATION_SUPPORTS encountering an existing
	 * transaction) fails, the transaction will be globally marked as rollback-only.
	 * The only possible outcome of such a transaction is a rollback: The
	 * transaction originator <i>cannot</i> make the transaction commit anymore.
	 * <p>Switch this to "false" to let the transaction originator make the rollback
	 * decision. If a participating transaction fails with an exception, the caller
	 * can still decide to continue with a different path within the transaction.
	 * However, note that this will only work as long as all participating resources
	 * are capable of continuing towards a transaction commit even after a data access
	 * failure: This is generally not the case for a Hibernate Session, for example;
	 * neither is it for a sequence of JDBC insert/update/delete operations.
	 * <p><b>Note:</b>This flag only applies to an explicit rollback attempt for a
	 * subtransaction, typically caused by an exception thrown by a data access operation
	 * (where TransactionInterceptor will trigger a {@code PlatformTransactionManager.rollback()}
	 * call according to a rollback rule). If the flag is off, the caller can handle the exception
	 * and decide on a rollback, independent of the rollback rules of the subtransaction.
	 * This flag does, however, <i>not</i> apply to explicit {@code setRollbackOnly}
	 * calls on a {@code TransactionStatus}, which will always cause an eventual
	 * global rollback (as it might not throw an exception after the rollback-only call).
	 * <p>The recommended solution for handling failure of a subtransaction
	 * is a "nested transaction", where the global transaction can be rolled
	 * back to a savepoint taken at the beginning of the subtransaction.
	 * PROPAGATION_NESTED provides exactly those semantics; however, it will
	 * only work when nested transaction support is available. This is the case
	 * with DataSourceTransactionManager, but not with JtaTransactionManager.
	 *
	 * @see #setNestedTransactionAllowed
	 * @see org.springframework.transaction.jta.JtaTransactionManager
	 */
	public final void setGlobalRollbackOnParticipationFailure(boolean globalRollbackOnParticipationFailure) {
		this.globalRollbackOnParticipationFailure = globalRollbackOnParticipationFailure;
	}

	/**
	 * Return whether to globally mark an existing transaction as rollback-only
	 * after a participating transaction failed.
	 */
	public final boolean isGlobalRollbackOnParticipationFailure() {
		return this.globalRollbackOnParticipationFailure;
	}

	/**
	 * Set whether to fail early in case of the transaction being globally marked
	 * as rollback-only.
	 * <p>Default is "false", only causing an UnexpectedRollbackException at the
	 * outermost transaction boundary. Switch this flag on to cause an
	 * UnexpectedRollbackException as early as the global rollback-only marker
	 * has been first detected, even from within an inner transaction boundary.
	 * <p>Note that, as of Spring 2.0, the fail-early behavior for global
	 * rollback-only markers has been unified: All transaction managers will by
	 * default only cause UnexpectedRollbackException at the outermost transaction
	 * boundary. This allows, for example, to continue unit tests even after an
	 * operation failed and the transaction will never be completed. All transaction
	 * managers will only fail earlier if this flag has explicitly been set to "true".
	 *
	 * @see org.springframework.transaction.UnexpectedRollbackException
	 * @since 2.0
	 */
	public final void setFailEarlyOnGlobalRollbackOnly(boolean failEarlyOnGlobalRollbackOnly) {
		this.failEarlyOnGlobalRollbackOnly = failEarlyOnGlobalRollbackOnly;
	}

	/**
	 * Return whether to fail early in case of the transaction being globally marked
	 * as rollback-only.
	 *
	 * @since 2.0
	 */
	public final boolean isFailEarlyOnGlobalRollbackOnly() {
		return this.failEarlyOnGlobalRollbackOnly;
	}

	/**
	 * Set whether {@code doRollback} should be performed on failure of the
	 * {@code doCommit} call. Typically not necessary and thus to be avoided,
	 * as it can potentially override the commit exception with a subsequent
	 * rollback exception.
	 * <p>Default is "false".
	 *
	 * @see #doCommit
	 * @see #doRollback
	 */
	public final void setRollbackOnCommitFailure(boolean rollbackOnCommitFailure) {
		this.rollbackOnCommitFailure = rollbackOnCommitFailure;
	}

	/**
	 * Return whether {@code doRollback} should be performed on failure of the
	 * {@code doCommit} call.
	 */
	public final boolean isRollbackOnCommitFailure() {
		return this.rollbackOnCommitFailure;
	}


	//---------------------------------------------------------------------
	// Implementation of PlatformTransactionManager
	//---------------------------------------------------------------------

	/**
	 * This implementation handles propagation behavior. Delegates to
	 * {@code doGetTransaction}, {@code isExistingTransaction}
	 * and {@code doBegin}.
	 *
	 * @see #doGetTransaction
	 * @see #isExistingTransaction
	 * @see #doBegin
	 */
	@Override
	public final TransactionStatus getTransaction(@Nullable TransactionDefinition definition)
			throws TransactionException {

		// Use defaults if no transaction definition given.
		// 如果传入的 TransactionDefinition 为空，则使用默认的事务定义（TransactionDefinition.withDefaults()）。
		// 这确保了即使没有指定事务定义，事务仍然可以正确配置
		TransactionDefinition def = (definition != null ? definition : TransactionDefinition.withDefaults());

		// 获取当前事务对象。该方法依赖于具体的事务管理器实现，如 JDBC 或 JPA 事务管理器
		// transaction 对象封装了当前事务的状态信息。
		Object transaction = doGetTransaction();
		// debugEnabled 是一个布尔值，检查是否启用了调试日志
		boolean debugEnabled = logger.isDebugEnabled();

		// 调用 isExistingTransaction(transaction) 检查当前是否存在正在进行的事务
		if (isExistingTransaction(transaction)) {
			// Existing transaction found -> check propagation behavior to find out how to behave.
			// 如果发现现有事务，调用 handleExistingTransaction 处理现有事务，
			// 并根据传播行为（如 REQUIRED、SUPPORTS 等）决定如何处理现有事务。
			// TODO 进入
			return handleExistingTransaction(def, transaction, debugEnabled);
		}

		// Check definition settings for new transaction.
		// 检查事务定义中的超时时间是否有效。如果超时时间小于默认值（TIMEOUT_DEFAULT），
		// 则抛出 InvalidTimeoutException 异常，表示事务的超时设置无效
		if (def.getTimeout() < TransactionDefinition.TIMEOUT_DEFAULT) {
			throw new InvalidTimeoutException("Invalid transaction timeout", def.getTimeout());
		}

		// No existing transaction found -> check propagation behavior to find out how to proceed.
		// 如果传播行为是 PROPAGATION_MANDATORY，并且没有现有事务，则抛出 IllegalTransactionStateException
		if (def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_MANDATORY) {
			throw new IllegalTransactionStateException(
					"No existing transaction found for transaction marked with propagation 'mandatory'");
		}
		/**
		 * PROPAGATION_REQUIRED：如果没有现有事务，则启动一个新事务。
		 * PROPAGATION_REQUIRES_NEW：无论是否有现有事务，都会启动一个新事务。
		 * PROPAGATION_NESTED：支持嵌套事务
		 */
		else if (def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED ||
				def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW ||
				def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NESTED) {
			// 调用 suspend(null) 挂起当前事务（如果存在）。
			// 这通常在 PROPAGATION_REQUIRES_NEW 或 PROPAGATION_NESTED 的情况下使用，因为这些传播行为会启动新事务而不依赖现有事务
			// 挂起后，现有事务的上下文会被保存在 SuspendedResourcesHolder 中，以便在事务完成后恢复
			SuspendedResourcesHolder suspendedResources = suspend(null);
			if (debugEnabled) {
				logger.debug("Creating new transaction with name [" + def.getName() + "]: " + def);
			}
			try {
				// 调用 startTransaction 启动新事务。该方法会根据传入的事务定义、事务对象和挂起的资源创建新事务
				// TODO 进入
				return startTransaction(def, transaction, debugEnabled, suspendedResources);
			} catch (RuntimeException | Error ex) {
				// 如果在启动事务时抛出了 RuntimeException 或 Error，
				// 会调用 resume(null, suspendedResources) 恢复之前挂起的事务，并重新抛出异常
				resume(null, suspendedResources);
				throw ex;
			}
		}
		// 如果传播行为不要求实际事务（如 PROPAGATION_SUPPORTS 或 PROPAGATION_NOT_SUPPORTED），
		// 则创建一个“空”事务。空事务不会启动实际事务，但可能会进行事务同步
		else {
			// Create "empty" transaction: no actual transaction, but potentially synchronization.
			if (def.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT && logger.isWarnEnabled()) {
				logger.warn("Custom isolation level specified but no actual transaction initiated; " +
						"isolation level will effectively be ignored: " + def);
			}
			boolean newSynchronization = (getTransactionSynchronization() == SYNCHRONIZATION_ALWAYS);
			// 调用 prepareTransactionStatus 方法创建并返回 TransactionStatus，其中不包含实际的事务状态，但可能包含事务同步信息
			return prepareTransactionStatus(def, null, true, newSynchronization, debugEnabled, null);
		}
	}

	/**
	 * Start a new transaction.
	 */
	private TransactionStatus startTransaction(TransactionDefinition definition, Object transaction,
											   boolean debugEnabled, @Nullable SuspendedResourcesHolder suspendedResources) {
		/**
		 * 检查是否需要事务同步（TransactionSynchronization）。事务同步是指在事务的不同阶段（如开始、提交、回滚）执行相关的回调操作
		 *
		 * getTransactionSynchronization() 返回当前的同步策略：
		 * 		SYNCHRONIZATION_ALWAYS：总是开启事务同步。
		 * 		SYNCHRONIZATION_ON_ACTUAL_TRANSACTION：仅在有实际事务时开启事务同步。
		 * 		SYNCHRONIZATION_NEVER：从不启用事务同步
		 * 	TODO 进入
		 */
		boolean newSynchronization = (getTransactionSynchronization() != SYNCHRONIZATION_NEVER);
		/**
		 * 解释：调用 newTransactionStatus 方法创建一个 DefaultTransactionStatus 对象，该对象封装了当前事务的状态信息：
		 *
		 * definition: 事务定义，包含事务的传播行为、隔离级别等。
		 * transaction: 封装底层事务资源的对象。
		 * true: 表示这是一个新的事务。
		 * newSynchronization: 决定是否开启事务同步。
		 * debugEnabled: 是否启用了调试日志。
		 * suspendedResources: 挂起的资源对象，用于支持传播行为如 PROPAGATION_REQUIRES_NEW，当一个事务需要挂起当前事务并启动新事务时使用。
		 * DefaultTransactionStatus 是 TransactionStatus 的具体实现类，
		 * 除了表示事务是否激活外，还存储了与事务有关的上下文信息（如同步和挂起的资源）。
		 *
		 * TODO 进入
		 */
		DefaultTransactionStatus status = newTransactionStatus(
				definition, transaction, true, newSynchronization, debugEnabled, suspendedResources);
		/**
		 * 调用 doBegin 方法正式开始事务。这个方法是由具体的事务管理器（如 JDBC 或 JPA 事务管理器）实现的，
		 * 它负责根据传入的事务对象和事务定义开始实际的事务处理。例如：
		 *
		 * 在 JDBC 中，它可能会调用 Connection.setAutoCommit(false) 来开始一个新的事务。
		 * 在 JPA 中，它可能会调用 EntityTransaction.begin()
		 *
		 * TODO 进入
		 * TODO
		 *   查看 org.springframework.jdbc.datasource.DataSourceTransactionManager#doBegin
		 *   因为不同的渠道会有不同的事务管理器的实现
		 */
		doBegin(transaction, definition);
		/**
		 * 调用 prepareSynchronization 方法，准备事务同步。
		 * 如果 newSynchronization 为 true，则该方法会注册事务同步回调。
		 * 这些回调将在事务的不同阶段（如提交、回滚或完成）执行，用于确保事务的正确执行流程。例如：
		 *
		 * TransactionSynchronizationManager 用于维护线程本地的事务状态。
		 * 同步回调可能会在事务提交前调用某些方法，或在回滚时执行清理操作
		 *
		 * TODO 这个步骤确保了如果启用了事务同步，事务的生命周期事件（如开始、提交、回滚）都能够得到正确管理
		 */
		prepareSynchronization(status, definition);
		/**
		 * 最后返回 TransactionStatus 对象，该对象封装了事务的当前状态。
		 * 这个状态对象将在整个事务过程中用于管理事务的状态、判断是否需要回滚或提交、以及挂起和恢复事务等操作
		 */
		return status;
	}

	/**
	 * Create a TransactionStatus for an existing transaction.
	 * <p>
	 * TODO 处理当前已经存在的事务，并根据传入的 TransactionDefinition 进行相应的操作（如挂起、创建新事务或加入现有事务）
	 */
	private TransactionStatus handleExistingTransaction(
			TransactionDefinition definition, Object transaction, boolean debugEnabled)
			throws TransactionException {
		/**
		 * 如果事务定义的传播行为为 PROPAGATION_NEVER，意味着该方法不应该在事务上下文中执行。
		 * 如果发现当前线程中已经存在事务，则抛出 IllegalTransactionStateException
		 */
		if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NEVER) {
			throw new IllegalTransactionStateException(
					"Existing transaction found for transaction marked with propagation 'never'");
		}
		// 如果传播行为为 PROPAGATION_NOT_SUPPORTED，当前事务应被挂起，不参与事务
		if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NOT_SUPPORTED) {
			if (debugEnabled) {
				logger.debug("Suspending current transaction");
			}
			// 挂起当前事务，并创建一个“无事务”的 TransactionStatus，该状态不包含任何事务，但可能需要同步
			Object suspendedResources = suspend(transaction);
			boolean newSynchronization = (getTransactionSynchronization() == SYNCHRONIZATION_ALWAYS);
			// 开启一个新的空事务
			return prepareTransactionStatus(
					definition, null, false, newSynchronization, debugEnabled, suspendedResources);
		}
		// 表示需要启动一个新的事务，无论当前是否有事务存在
		if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
			if (debugEnabled) {
				logger.debug("Suspending current transaction, creating new transaction with name [" +
						definition.getName() + "]");
			}
			// 挂起当前事务
			SuspendedResourcesHolder suspendedResources = suspend(transaction);
			try {
				// 启动一个新的事务
				return startTransaction(definition, transaction, debugEnabled, suspendedResources);
			} catch (RuntimeException | Error beginEx) {
				// 如果在启动新事务时抛出异常，则调用 resumeAfterBeginException() 恢复挂起的事务
				resumeAfterBeginException(transaction, suspendedResources, beginEx);
				throw beginEx;
			}
		}
		// 表示当前事务是嵌套事务，允许在现有事务中创建子事务
		if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NESTED) {
			// 首先检查事务管理器是否允许嵌套事务 (isNestedTransactionAllowed())，如果不允许，抛出异常
			if (!isNestedTransactionAllowed()) {
				throw new NestedTransactionNotSupportedException(
						"Transaction manager does not allow nested transactions by default - " +
								"specify 'nestedTransactionAllowed' property with value 'true'");
			}
			if (debugEnabled) {
				logger.debug("Creating nested transaction with name [" + definition.getName() + "]");
			}
			// 并通过 useSavepointForNestedTransaction() 检查是否应该使用保存点
			if (useSavepointForNestedTransaction()) {
				// Create savepoint within existing Spring-managed transaction,
				// through the SavepointManager API implemented by TransactionStatus.
				// Usually uses JDBC 3.0 savepoints. Never activates Spring synchronization.
				// 通过 prepareTransactionStatus() 创建事务状态
				DefaultTransactionStatus status =
						prepareTransactionStatus(definition, transaction, false, false, debugEnabled, null);
				// 调用 status.createAndHoldSavepoint() 创建并持有保存点
				status.createAndHoldSavepoint();
				return status;
			} else {
				// Nested transaction through nested begin and commit/rollback calls.
				// Usually only for JTA: Spring synchronization might get activated here
				// in case of a pre-existing JTA transaction.
				// 如果不使用保存点，则启动一个新的嵌套事务
				return startTransaction(definition, transaction, debugEnabled, null);
			}
		}
		//来到这里是一下几种情况 才是真正的嵌套事务
		//0:PROPAGATION_REQUIRED 一定要以事务的方式运行 内外层事务绑定
		//1:PROPAGATION_SUPPORTS 当前存在事务 则加入事务 不存在事务就以非事务运行
		//2:PROPAGATION_MANDATORY 当前存在事务 则加入事务 不存在事务抛出异常

		// Assumably PROPAGATION_SUPPORTS or PROPAGATION_REQUIRED.
		if (debugEnabled) {
			logger.debug("Participating in existing transaction");
		}
		// 检查当前事务是否需要对现有事务进行验证。
		// 该方法通常是事务管理器的配置项，用于控制是否在加入现有事务之前对其进行验证
		// 目的：确保在参与现有事务之前，事务的配置与现有事务的状态保持一致
		if (isValidateExistingTransaction()) {
			// 首先检查当前事务定义中的隔离级别是否不是 ISOLATION_DEFAULT（即未指定隔离级别）
			if (definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT) {
				// 获取当前事务的隔离级别
				Integer currentIsolationLevel = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
				// 如果现有事务的隔离级别与当前事务定义的隔离级别不一致，抛出 IllegalTransactionStateException 异常
				if (currentIsolationLevel == null || currentIsolationLevel != definition.getIsolationLevel()) {
					Constants isoConstants = DefaultTransactionDefinition.constants;
					throw new IllegalTransactionStateException("Participating transaction with definition [" +
							definition + "] specifies isolation level which is incompatible with existing transaction: " +
							(currentIsolationLevel != null ?
									// isoConstants.toCode() 将隔离级别转化为更可读的格式（如 ISOLATION_READ_COMMITTED）并加入异常信息中
									isoConstants.toCode(currentIsolationLevel, DefaultTransactionDefinition.PREFIX_ISOLATION) :
									"(unknown)"));
				}
			}
			/**
			 * 如果现有事务是只读的，而当前事务不是，只读属性存在冲突，
			 * 抛出 IllegalTransactionStateException 异常，
			 * 指出事务定义与现有事务的只读属性不兼容
			 */
			if (!definition.isReadOnly()) {// 如果当前事务定义中的 isReadOnly() 返回 false（即该事务不是只读事务），则继续检查现有事务的只读状态
				// 检查当前已存在的事务是否为只读事务
				// //TODO 查看 TransactionSynchronizationManager
				if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
					throw new IllegalTransactionStateException("Participating transaction with definition [" +
							definition + "] is not marked as read-only but existing transaction is");
				}
			}
		}
		// 调用 getTransactionSynchronization() 方法，检查当前是否需要事务同步
		// 如果返回值不等于 SYNCHRONIZATION_NEVER，则 newSynchronization 被设置为 true，
		// 表示当前事务参与需要事务同步（即可以在事务的不同阶段执行回调操作）
		boolean newSynchronization = (getTransactionSynchronization() != SYNCHRONIZATION_NEVER);
		/**
		 * 调用 prepareTransactionStatus() 方法，创建一个新的 TransactionStatus 对象
		 *
		 * 参数说明：
		 * definition: 当前事务的定义。
		 * transaction: 当前的事务对象。
		 * false: 表示当前事务不是一个新事务，而是参与了已有事务。
		 * newSynchronization: 根据之前的计算，表示是否需要事务同步。
		 * debugEnabled: 调试日志是否启用。
		 * null: 表示没有挂起的资源（因为没有挂起的事务）
		 */
		return prepareTransactionStatus(definition, transaction, false, newSynchronization, debugEnabled, null);
	}

	/**
	 * Create a new TransactionStatus for the given arguments,
	 * also initializing transaction synchronization as appropriate.
	 *
	 * @see #newTransactionStatus
	 * @see #prepareTransactionStatus
	 */
	protected final DefaultTransactionStatus prepareTransactionStatus(
			TransactionDefinition definition, @Nullable Object transaction, boolean newTransaction,
			boolean newSynchronization, boolean debug, @Nullable Object suspendedResources) {

		DefaultTransactionStatus status = newTransactionStatus(
				definition, transaction, newTransaction, newSynchronization, debug, suspendedResources);
		prepareSynchronization(status, definition);
		return status;
	}

	/**
	 * Create a TransactionStatus instance for the given arguments.
	 * <p>
	 * 参数
	 * TransactionDefinition definition: 事务定义，包含事务的传播行为、隔离级别、只读属性等。
	 * Object transaction: 当前事务对象，可能为 null，由具体的事务管理器实现管理。
	 * boolean newTransaction: 一个布尔值，表示是否是一个新事务。
	 * boolean newSynchronization: 一个布尔值，表示是否需要启用事务同步。
	 * boolean debug: 一个布尔值，表示是否启用调试日志。
	 * Object suspendedResources: 挂起的资源对象，可能为 null，如果当前事务挂起，则这些资源可以被恢复。
	 */
	protected DefaultTransactionStatus newTransactionStatus(
			TransactionDefinition definition, @Nullable Object transaction, boolean newTransaction,
			boolean newSynchronization, boolean debug, @Nullable Object suspendedResources) {
		/**
		 * newSynchronization 参数表示调用方希望为当前事务启用事务同步
		 *
		 * TransactionSynchronizationManager.isSynchronizationActive() 方法检查当前线程是否已经启用了事务同步
		 *
		 * 如果 newSynchronization 为 true 且当前线程尚未激活事务同步，
		 * 		则 actualNewSynchronization 设为 true，表示需要为该事务启用同步。
		 *
		 * 如果事务同步已经启用，或者 newSynchronization 为 false，
		 * 		则 actualNewSynchronization 将为 false
		 */
		boolean actualNewSynchronization = newSynchronization &&
				//TODO 查看 TransactionSynchronizationManager
				!TransactionSynchronizationManager.isSynchronizationActive();
		/**
		 * 调用 DefaultTransactionStatus 构造方法，创建一个新的事务状态对象，传入以下参数：
		 * transaction: 当前的事务对象，可能为 null。
		 * newTransaction: 布尔值，表示该事务是否是新创建的事务。
		 * actualNewSynchronization: 布尔值，表示是否启用了新的事务同步（通过上一步计算得出）。
		 * definition.isReadOnly(): 检查事务定义是否标记为只读。只读事务通常不会修改数据，适用于查询操作。
		 * debug: 布尔值，表示是否启用了调试模式。如果调试模式启用，Spring 会记录更多的日志信息。
		 * suspendedResources: 挂起的资源对象（如果有）。这些资源可能在事务开始之前挂起，在事务完成后需要恢复
		 *
		 * DefaultTransactionStatus 作用：
		 *
		 * DefaultTransactionStatus 对象封装了当前事务的详细状态信息，
		 * Spring 使用它来管理事务的生命周期。它包含的信息包括事务是否新建、是否启用了事务同步、事务是否只读等
		 */
		return new DefaultTransactionStatus(
				transaction, newTransaction, actualNewSynchronization,
				definition.isReadOnly(), debug, suspendedResources);
	}

	/**
	 * Initialize transaction synchronization as appropriate.
	 * <p>
	 * 方法签名：该方法接受两个参数：
	 * DefaultTransactionStatus status: 当前事务的状态对象，它包含了事务的所有信息，例如是否是新事务、是否启用了同步等。
	 * TransactionDefinition definition: 事务定义，包含事务的传播行为、隔离级别、超时、只读属性等。
	 * 该方法没有返回值，它的目的是为当前线程准备事务同步上下文
	 */
	protected void prepareSynchronization(DefaultTransactionStatus status, TransactionDefinition definition) {
		// 检查当前事务状态对象是否需要新的同步。如果事务状态对象表明需要同步，进入同步准备流程。
		// isNewSynchronization() 返回 true 表示当前事务需要新的事务同步处理。
		if (status.isNewSynchronization()) {
			// status.hasTransaction() 返回 true 表示当前事务是一个实际的事务
			// 如果没有实际的事务（如只执行了同步操作而不涉及事务提交或回滚），则设置为 false
			// 将当前事务的激活状态设置到 TransactionSynchronizationManager 中
			// //TODO 查看 TransactionSynchronizationManager
			TransactionSynchronizationManager.setActualTransactionActive(status.hasTransaction());
			// definition.getIsolationLevel() 返回事务定义中的隔离级别
			// 如果隔离级别不等于 TransactionDefinition.ISOLATION_DEFAULT（即事务没有使用默认的隔离级别），
			// 		则将事务定义中的隔离级别传递给 setCurrentTransactionIsolationLevel()
			// 如果使用的是默认的隔离级别，则传入 null，表示不设置特定的隔离级别
			TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
					definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT ?
							definition.getIsolationLevel() : null);
			// definition.isReadOnly() 返回事务是否被标记为只读
			// 如果事务是只读的（通常用于查询操作），则 TransactionSynchronizationManager 将标记事务为只读，避免进行任何修改操作
			// 根据事务定义设置事务的只读状态
			TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly());
			// definition.getName() 返回事务的名称，如果事务定义中没有指定名称，则设置为 null
			// 事务名称通常用于调试或日志记录
			// 设置当前事务的名称
			TransactionSynchronizationManager.setCurrentTransactionName(definition.getName());
			// 调用 TransactionSynchronizationManager.initSynchronization() 初始化事务同步。
			// 这一步确保事务同步机制已经准备就绪，用于管理事务生命周期中的回调（如事务提交、回滚、清理等操作）

			// 事务同步：在事务生命周期中的关键时刻（如提交或回滚时），事务同步机制会调用注册的回调函数，确保资源的正确管理和释放
			TransactionSynchronizationManager.initSynchronization();
		}
	}

	/**
	 * Determine the actual timeout to use for the given definition.
	 * Will fall back to this manager's default timeout if the
	 * transaction definition doesn't specify a non-default value.
	 *
	 * @param definition the transaction definition
	 * @return the actual timeout to use
	 * @see org.springframework.transaction.TransactionDefinition#getTimeout()
	 * @see #setDefaultTimeout
	 */
	protected int determineTimeout(TransactionDefinition definition) {
		if (definition.getTimeout() != TransactionDefinition.TIMEOUT_DEFAULT) {
			return definition.getTimeout();
		}
		return getDefaultTimeout();
	}


	/**
	 * Suspend the given transaction. Suspends transaction synchronization first,
	 * then delegates to the {@code doSuspend} template method.
	 *
	 * @param transaction the current transaction object
	 *                    (or {@code null} to just suspend active synchronizations, if any)
	 *                                       TODO 需要挂起的事务对象，可能为 null
	 * @return an object that holds suspended resources
	 * 			TODO 返回一个 SuspendedResourcesHolder 对象，封装了挂起的资源、同步信息、事务名称、只读状态等
	 * (or {@code null} if neither transaction nor synchronization active)
	 * @see #doSuspend
	 * @see #resume
	 * <p>
	 * TODO 方法目的：挂起当前事务和事务同步（如果有），并将当前事务的相关状态信息保存到 SuspendedResourcesHolder 中，方便后续恢复。
	 */
	@Nullable
	protected final SuspendedResourcesHolder suspend(@Nullable Object transaction) throws TransactionException {
		/**
		 * 调用 TransactionSynchronizationManager.isSynchronizationActive() 检查当前线程的事务同步是否激活。
		 * 事务同步表示在事务的各个生命周期阶段（如提交、回滚）执行回调操作
		 *
		 * 如果事务同步处于激活状态，则需要挂起这些同步回调
		 */
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			/**
			 * 解释：
			 * 调用 doSuspendSynchronization() 方法挂起当前线程中所有已注册的事务同步回调，并将它们存储在 suspendedSynchronizations 列表中。
			 * 这些同步回调会在之后恢复事务时重新绑定到线程。
			 * 目的：确保事务同步能够在挂起事务时保存，并在之后恢复事务时重新激活
			 */
			List<TransactionSynchronization> suspendedSynchronizations = doSuspendSynchronization();
			try {
				Object suspendedResources = null;
				if (transaction != null) {// 如果当前有事务对象
					// 调用 doSuspend(transaction) 挂起与事务相关的资源（如数据库连接）
					// 确保事务的资源（如数据库连接）在挂起事务时被正确保存，以便在恢复事务时能够继续使用这些资源
					// TODO 进入
					// TODO 可以看 org.springframework.jdbc.datasource.DataSourceTransactionManager.doSuspend
					suspendedResources = doSuspend(transaction);
				}
				// 保存当前事务的名称（如果有），然后将事务名称设为 null
				String name = TransactionSynchronizationManager.getCurrentTransactionName();
				TransactionSynchronizationManager.setCurrentTransactionName(null);
				// 保存当前事务是否为只读状态，然后将只读状态设为 false
				boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
				TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
				// 保存当前事务的隔离级别，然后将隔离级别设为 null
				Integer isolationLevel = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
				TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
				// 保存当前事务是否处于活动状态，然后将事务活动状态设为 false
				boolean wasActive = TransactionSynchronizationManager.isActualTransactionActive();
				TransactionSynchronizationManager.setActualTransactionActive(false);
				/**
				 * 解释：
				 * 创建一个 SuspendedResourcesHolder 对象，封装挂起的事务资源、同步回调、事务名称、只读状态、隔离级别和活动状态。
				 * 这个对象会被用于之后恢复事务时恢复这些信息。
				 * 目的：将挂起的资源和事务上下文信息封装在一个对象中，便于在事务恢复时使用
				 */
				return new SuspendedResourcesHolder(
						suspendedResources, suspendedSynchronizations, name, readOnly, isolationLevel, wasActive);
			} catch (RuntimeException | Error ex) {
				// doSuspend failed - original transaction is still active...
				doResumeSynchronization(suspendedSynchronizations);
				throw ex;
			}
		} else if (transaction != null) {
			// Transaction active but no synchronization active.
			Object suspendedResources = doSuspend(transaction);
			return new SuspendedResourcesHolder(suspendedResources);
		} else {
			// Neither transaction nor synchronization active.
			return null;
		}
	}

	/**
	 * Resume the given transaction. Delegates to the {@code doResume}
	 * template method first, then resuming transaction synchronization.
	 *
	 * @param transaction     the current transaction object
	 *                           TODO 前正在运行的事务对象，可能是 null
	 * @param resourcesHolder the object that holds suspended resources,
	 *                        as returned by {@code suspend} (or {@code null} to just
	 *                        resume synchronizations, if any)
	 *                           TODO 持有挂起事务资源的对象，可能包含挂起的资源和事务同步对象，可能为 null
	 * @see #doResume
	 * @see #suspend
	 * TODO
	 *  方法的主要作用是在事务完成（或挂起）后恢复事务的上下文和资源。
	 *  这通常用于恢复之前被挂起的事务资源，例如在嵌套事务或事务传播规则为 PROPAGATION_REQUIRES_NEW 时。
	 *  当一个新的事务完成后，可能需要恢复之前的挂起事务
	 */
	protected final void resume(@Nullable Object transaction, @Nullable SuspendedResourcesHolder resourcesHolder)
			throws TransactionException {
		// resourcesHolder 是 SuspendedResourcesHolder 类型，它封装了在事务挂起时保存的事务同步和资源
		// 如果为 null，则表示没有挂起的资源需要恢复，方法结束
		if (resourcesHolder != null) {
			// 获取挂起的资源（如数据库连接、文件句柄等）。这些资源在事务挂起时被保存起来，以便事务完成后恢复
			Object suspendedResources = resourcesHolder.suspendedResources;
			if (suspendedResources != null) {
				// 调用 doResume() 方法恢复这些挂起的资源
				// doResume 是一个模板方法
				// TODO 进入
				doResume(transaction, suspendedResources);
			}
			// 获取挂起时保存的事务同步回调对象。
			// 这些回调对象通常用于管理事务的生命周期，例如在事务提交或回滚时执行特定的操作
			List<TransactionSynchronization> suspendedSynchronizations = resourcesHolder.suspendedSynchronizations;
			if (suspendedSynchronizations != null) {
				// resourcesHolder.wasActive：指示在事务挂起之前，事务是否处于活动状态（即事务是否正在进行中）
				// 将事务的活动状态恢复为挂起之前的状态。如果挂起之前事务处于活动状态，则恢复为活动状态；如果没有活动事务，则恢复为非活动状态
				TransactionSynchronizationManager.setActualTransactionActive(resourcesHolder.wasActive);
				// resourcesHolder.isolationLevel：保存了事务挂起之前的隔离级别。如果事务中更改了数据库连接的隔离级别，事务挂起时会保存下来
				// 恢复事务的隔离级别，将其设置为挂起之前的隔离级别
				TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(resourcesHolder.isolationLevel);
				// 保存了事务挂起之前的只读状态
				// 恢复事务的只读状态。如果挂起之前事务是只读的，则恢复为只读状态；否则，恢复为非只读状态
				TransactionSynchronizationManager.setCurrentTransactionReadOnly(resourcesHolder.readOnly);
				// 保存了事务挂起之前的事务名称
				// 将事务名称恢复为挂起之前的名称。事务名称通常用于标识特定的事务
				TransactionSynchronizationManager.setCurrentTransactionName(resourcesHolder.name);
				// 恢复挂起的事务同步回调，重新注册这些回调对象，以便它们可以继续参与事务的生命周期管理
				doResumeSynchronization(suspendedSynchronizations);
			}
		}
	}

	/**
	 * Resume outer transaction after inner transaction begin failed.
	 */
	private void resumeAfterBeginException(
			Object transaction, @Nullable SuspendedResourcesHolder suspendedResources, Throwable beginEx) {

		try {
			resume(transaction, suspendedResources);
		} catch (RuntimeException | Error resumeEx) {
			String exMessage = "Inner transaction begin exception overridden by outer transaction resume exception";
			logger.error(exMessage, beginEx);
			throw resumeEx;
		}
	}

	/**
	 * Suspend all current synchronizations and deactivate transaction
	 * synchronization for the current thread.
	 *
	 * @return the List of suspended TransactionSynchronization objects
	 */
	private List<TransactionSynchronization> doSuspendSynchronization() {
		List<TransactionSynchronization> suspendedSynchronizations =
				TransactionSynchronizationManager.getSynchronizations();
		for (TransactionSynchronization synchronization : suspendedSynchronizations) {
			synchronization.suspend();
		}
		TransactionSynchronizationManager.clearSynchronization();
		return suspendedSynchronizations;
	}

	/**
	 * Reactivate transaction synchronization for the current thread
	 * and resume all given synchronizations.
	 *
	 * @param suspendedSynchronizations a List of TransactionSynchronization objects
	 */
	private void doResumeSynchronization(List<TransactionSynchronization> suspendedSynchronizations) {
		TransactionSynchronizationManager.initSynchronization();
		for (TransactionSynchronization synchronization : suspendedSynchronizations) {
			synchronization.resume();
			TransactionSynchronizationManager.registerSynchronization(synchronization);
		}
	}


	/**
	 * This implementation of commit handles participating in existing
	 * transactions and programmatic rollback requests.
	 * Delegates to {@code isRollbackOnly}, {@code doCommit}
	 * and {@code rollback}.
	 *
	 * @see org.springframework.transaction.TransactionStatus#isRollbackOnly()
	 * @see #doCommit
	 * @see #rollback
	 */
	@Override
	public final void commit(TransactionStatus status) throws TransactionException {
		// 检查事务状态，判断当前事务是否已经完成
		// 如果事务已经提交或回滚，调用 isCompleted() 会返回 true
		if (status.isCompleted()) {
			throw new IllegalTransactionStateException(
					"Transaction is already completed - do not call commit or rollback more than once per transaction");
		}
		// DefaultTransactionStatus 是 TransactionStatus 的具体实现类，包含更多关于事务的详细信息和操作方法
		DefaultTransactionStatus defStatus = (DefaultTransactionStatus) status;
		// 检查当前事务是否被标记为只回滚
		// 这意味着事务内的代码已经显式要求回滚，通常通过 setRollbackOnly() 方法设置
		if (defStatus.isLocalRollbackOnly()) {
			if (defStatus.isDebug()) {
				logger.debug("Transactional code has requested rollback");
			}
			// 调用 processRollback(defStatus, false) 执行回滚操作，并结束方法
			// TODO 进入
			processRollback(defStatus, false);
			return;
		}
		// defStatus.isGlobalRollbackOnly()：检查事务是否在全局范围内被标记为只回滚。
		// 		这可能是因为外部事务或其他系统原因导致整个事务被标记为回滚。
		// shouldCommitOnGlobalRollbackOnly()：检查当前事务管理器是否允许在全局回滚标记为 rollback-only 时提交事务。
		// 		通常情况下，这个值是 false，表示如果事务被标记为全局回滚，则不应提交
		if (!shouldCommitOnGlobalRollbackOnly() && defStatus.isGlobalRollbackOnly()) {
			if (defStatus.isDebug()) {
				logger.debug("Global transaction is marked as rollback-only but transactional code requested commit");
			}
			// 调用 processRollback(defStatus, true) 执行回滚操作，并结束方法
			processRollback(defStatus, true);
			return;
		}
		// 如果事务没有被标记为回滚，则调用 processCommit(defStatus) 处理事务的提交过程。
		// processCommit 方法负责实际的事务提交，包括触发事务同步回调、提交底层事务（如数据库的 commit）、以及处理提交过程中的任何异常
		processCommit(defStatus);
	}

	/**
	 * Process an actual commit.
	 * Rollback-only flags have already been checked and applied.
	 *
	 * @param status object representing the transaction
	 * @throws TransactionException in case of commit failure
	 */
	private void processCommit(DefaultTransactionStatus status) throws TransactionException {
		try {
			boolean beforeCompletionInvoked = false;

			try {
				boolean unexpectedRollback = false;
				prepareForCommit(status);
				triggerBeforeCommit(status);
				triggerBeforeCompletion(status);
				beforeCompletionInvoked = true;

				if (status.hasSavepoint()) {
					if (status.isDebug()) {
						logger.debug("Releasing transaction savepoint");
					}
					unexpectedRollback = status.isGlobalRollbackOnly();
					status.releaseHeldSavepoint();
				} else if (status.isNewTransaction()) {
					if (status.isDebug()) {
						logger.debug("Initiating transaction commit");
					}
					unexpectedRollback = status.isGlobalRollbackOnly();
					doCommit(status);
				} else if (isFailEarlyOnGlobalRollbackOnly()) {
					unexpectedRollback = status.isGlobalRollbackOnly();
				}

				// Throw UnexpectedRollbackException if we have a global rollback-only
				// marker but still didn't get a corresponding exception from commit.
				if (unexpectedRollback) {
					throw new UnexpectedRollbackException(
							"Transaction silently rolled back because it has been marked as rollback-only");
				}
			} catch (UnexpectedRollbackException ex) {
				// can only be caused by doCommit
				triggerAfterCompletion(status, TransactionSynchronization.STATUS_ROLLED_BACK);
				throw ex;
			} catch (TransactionException ex) {
				// can only be caused by doCommit
				if (isRollbackOnCommitFailure()) {
					doRollbackOnCommitException(status, ex);
				} else {
					triggerAfterCompletion(status, TransactionSynchronization.STATUS_UNKNOWN);
				}
				throw ex;
			} catch (RuntimeException | Error ex) {
				if (!beforeCompletionInvoked) {
					triggerBeforeCompletion(status);
				}
				doRollbackOnCommitException(status, ex);
				throw ex;
			}

			// Trigger afterCommit callbacks, with an exception thrown there
			// propagated to callers but the transaction still considered as committed.
			try {
				triggerAfterCommit(status);
			} finally {
				triggerAfterCompletion(status, TransactionSynchronization.STATUS_COMMITTED);
			}

		} finally {
			cleanupAfterCompletion(status);
		}
	}

	/**
	 * This implementation of rollback handles participating in existing
	 * transactions. Delegates to {@code doRollback} and
	 * {@code doSetRollbackOnly}.
	 *
	 * @see #doRollback
	 * @see #doSetRollbackOnly
	 */
	@Override
	public final void rollback(TransactionStatus status) throws TransactionException {
		/**
		 * 这是 TransactionStatus 接口中的一个方法，用于检查事务是否已经完成（即是否已经提交或回滚）。
		 * 返回 true 表示事务已经完成，false 表示事务尚未完成
		 */
		if (status.isCompleted()) {
			/**
			 * 这是一个运行时异常，表示事务状态不合法。
			 * 异常信息：“Transaction is already completed - do not call commit or rollback more than once per transaction”
			 * 意味着事务已经完成，不应再次调用 commit 或 rollback
			 */
			throw new IllegalTransactionStateException(
					"Transaction is already completed - do not call commit or rollback more than once per transaction");
		}

		// 将传入的 TransactionStatus 对象强制转换为 DefaultTransactionStatus 类型
		// DefaultTransactionStatus 是 TransactionStatus 接口的一个具体实现类，提供了事务的详细状态信息和操作方法
		// DefaultTransactionStatus 包含了更多内部使用的信息和方法，这些在 rollback 操作中是必要的
		DefaultTransactionStatus defStatus = (DefaultTransactionStatus) status;
		/**
		 * defStatus：已经转换为 DefaultTransactionStatus 的事务状态对象，包含了事务的详细信息。
		 * false：这个布尔值通常用于指示是否是由于应用程序异常而进行的回滚。
		 * 在 Spring 的实现中，第二个参数通常用于表示是否在回滚时抛出异常或进行其他特定处理
		 *
		 * TODO 进入
		 */
		processRollback(defStatus, false);
	}

	/**
	 * Process an actual rollback.
	 * The completed flag has already been checked.
	 *
	 * @param status object representing the transaction
	 * @throws TransactionException in case of rollback failure
	 */
	private void processRollback(DefaultTransactionStatus status, boolean unexpected) {
		try {
			// 将传入的 unexpected 参数赋值给本地变量 unexpectedRollback，用于在方法内部追踪是否发生了意外回滚
			boolean unexpectedRollback = unexpected;

			try {
				// 调用 triggerBeforeCompletion(status)，触发事务同步回调中的 beforeCompletion() 方法。
				// 这些回调是在事务即将完成时（无论提交还是回滚）执行的，通常用于释放资源或进行其他清理操作
				triggerBeforeCompletion(status);

				// 保存点允许部分回滚，即回滚到某个事务点而不是整个事务
				if (status.hasSavepoint()) {// 检查事务状态是否包含保存点
					if (status.isDebug()) {
						logger.debug("Rolling back transaction to savepoint");
					}
					// 如果有保存点，调用该方法将事务回滚到保存点
					status.rollbackToHeldSavepoint();

				} else if (status.isNewTransaction()) {// 检查事务是否为新事务。如果当前事务是一个新事务（而不是参与现有事务的一部分），执行完整回滚
					if (status.isDebug()) {
						logger.debug("Initiating transaction rollback");
					}
					// 调用 doRollback 方法执行实际的回滚操作。
					// doRollback 是一个抽象方法，由具体的事务管理器（如 DataSourceTransactionManager）实现，负责与底层资源（如数据库）的回滚
					doRollback(status);
				} else {
					// Participating in larger transaction
					// 如果当前事务不是新事务而是参与到一个更大事务中的子事务，进入这一部分逻辑
					if (status.hasTransaction()) {// 检查是否存在当前参与的事务
						// isLocalRollbackOnly() 如果当前事务设置为只回滚本地事务，则执行回滚
						// isGlobalRollbackOnParticipationFailure()：如果全局回滚标志设置为 true，则标记整个事务为回滚
						if (status.isLocalRollbackOnly() || isGlobalRollbackOnParticipationFailure()) {
							if (status.isDebug()) {
								logger.debug("Participating transaction failed - marking existing transaction as rollback-only");
							}
							// 将事务标记为只回滚。这样，虽然事务本身未回滚，但参与的事务标记为回滚状态
							// 当子事务发生失败时，虽然子事务无法直接回滚整个事务，但可以通过将事务标记为 "只回滚" 来确保最终事务在提交时执行回滚操作
							// 将当前事务标记为 "只回滚"。这意味着事务在后续将无法提交，只能回滚
							// TODO 进入 org.springframework.jdbc.datasource.DataSourceTransactionManager.doSetRollbackOnly
							doSetRollbackOnly(status);
						} else {
							if (status.isDebug()) {
								logger.debug("Participating transaction failed - letting transaction originator decide on rollback");
							}
						}
					} else {
						logger.debug("Should roll back transaction but cannot - no transaction available");
					}
					// Unexpected rollback only matters here if we're asked to fail early
					// 检查是否在全局回滚标记设置为 rollback-only 时立即失败
					if (!isFailEarlyOnGlobalRollbackOnly()) {
						// 如果isFailEarlyOnGlobalRollbackOnly()返回 false，则将 unexpectedRollback 设置为 false，表示回滚不再被视为意外
						unexpectedRollback = false;
					}
				}
			} catch (RuntimeException | Error ex) {
				// 首先触发 afterCompletion 回调并将事务状态标记为未知
				// TODO 进入
				triggerAfterCompletion(status, TransactionSynchronization.STATUS_UNKNOWN);
				throw ex;
			}
			// triggerAfterCompletion(status, TransactionSynchronization.STATUS_ROLLED_BACK)，通知所有已注册的事务同步回调事务已回滚完成
			triggerAfterCompletion(status, TransactionSynchronization.STATUS_ROLLED_BACK);

			// Raise UnexpectedRollbackException if we had a global rollback-only marker
			if (unexpectedRollback) {
				throw new UnexpectedRollbackException(
						"Transaction rolled back because it has been marked as rollback-only");
			}
		} finally {
			// 最终，无论回滚是否成功，调用 cleanupAfterCompletion(status) 清理事务状态。
			// 该方法会清理事务相关的上下文信息，例如解除线程局部变量的绑定，释放资源等
			// TODO 进入
			cleanupAfterCompletion(status);
		}
	}

	/**
	 * Invoke {@code doRollback}, handling rollback exceptions properly.
	 *
	 * @param status object representing the transaction
	 * @param ex     the thrown application exception or error
	 * @throws TransactionException in case of rollback failure
	 * @see #doRollback
	 */
	private void doRollbackOnCommitException(DefaultTransactionStatus status, Throwable ex) throws TransactionException {
		try {
			if (status.isNewTransaction()) {
				if (status.isDebug()) {
					logger.debug("Initiating transaction rollback after commit exception", ex);
				}
				doRollback(status);
			} else if (status.hasTransaction() && isGlobalRollbackOnParticipationFailure()) {
				if (status.isDebug()) {
					logger.debug("Marking existing transaction as rollback-only after commit exception", ex);
				}
				doSetRollbackOnly(status);
			}
		} catch (RuntimeException | Error rbex) {
			logger.error("Commit exception overridden by rollback exception", ex);
			triggerAfterCompletion(status, TransactionSynchronization.STATUS_UNKNOWN);
			throw rbex;
		}
		triggerAfterCompletion(status, TransactionSynchronization.STATUS_ROLLED_BACK);
	}


	/**
	 * Trigger {@code beforeCommit} callbacks.
	 *
	 * @param status object representing the transaction
	 */
	protected final void triggerBeforeCommit(DefaultTransactionStatus status) {
		if (status.isNewSynchronization()) {
			if (status.isDebug()) {
				logger.trace("Triggering beforeCommit synchronization");
			}
			TransactionSynchronizationUtils.triggerBeforeCommit(status.isReadOnly());
		}
	}

	/**
	 * Trigger {@code beforeCompletion} callbacks.
	 *
	 * @param status object representing the transaction
	 */
	protected final void triggerBeforeCompletion(DefaultTransactionStatus status) {
		if (status.isNewSynchronization()) {
			if (status.isDebug()) {
				logger.trace("Triggering beforeCompletion synchronization");
			}
			TransactionSynchronizationUtils.triggerBeforeCompletion();
		}
	}

	/**
	 * Trigger {@code afterCommit} callbacks.
	 *
	 * @param status object representing the transaction
	 */
	private void triggerAfterCommit(DefaultTransactionStatus status) {
		if (status.isNewSynchronization()) {
			if (status.isDebug()) {
				logger.trace("Triggering afterCommit synchronization");
			}
			TransactionSynchronizationUtils.triggerAfterCommit();
		}
	}

	/**
	 * Trigger {@code afterCompletion} callbacks.
	 *
	 * @param status           object representing the transaction
	 *                                                   TODO  当前事务的状态对象，包含了事务的详细信息和属性
	 * @param completionStatus completion status according to TransactionSynchronization constants
	 *                                                   TODO 表示事务的完成状态。可以是 STATUS_COMMITTED（提交完成）或 STATUS_ROLLED_BACK（回滚完成
	 */
	private void triggerAfterCompletion(DefaultTransactionStatus status, int completionStatus) {
		// 检查当前事务是否有新的同步回调
		// 通常是在事务开始时注册了新的同步回调
		// 确保只有在事务注册了新的同步回调的情况下，才会触发事务完成后的操作
		if (status.isNewSynchronization()) {
			// 获取当前线程中已注册的所有同步回调
			// 一个包含 TransactionSynchronization 对象的列表，这些对象在事务的各个生命周期阶段执行回调
			List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
			//  清除当前线程中的同步回调信息，表示事务已经完成，清理上下文
			TransactionSynchronizationManager.clearSynchronization();
			// status.hasTransaction()：检查当前事务状态中是否存在实际的事务。如果返回 false，表示当前事务状态下没有绑定事务
			// status.isNewTransaction()：检查当前事务是否为新事务。如果返回 true，表示这是一个新事务
			if (!status.hasTransaction() || status.isNewTransaction()) {// 该条件为 true 的场景：当前没有事务或者当前是一个新事务
				if (status.isDebug()) {
					logger.trace("Triggering afterCompletion synchronization");
				}
				// No transaction or new transaction for the current scope ->
				// invoke the afterCompletion callbacks immediately
				/**
				 * 该方法
				 * 对每个 TransactionSynchronization 对象调用其 afterCompletion() 方法，执行事务完成后的操作。
				 * completionStatus 会传递给回调，指示事务是提交完成（STATUS_COMMITTED）还是回滚完成（STATUS_ROLLED_BACK）
				 */
				// TODO 进入
				invokeAfterCompletion(synchronizations, completionStatus);
			} else if (!synchronizations.isEmpty()) {// 如果当前事务不是新事务 且存在事务同步回调
				// Existing transaction that we participate in, controlled outside
				// of the scope of this Spring transaction manager -> try to register
				// an afterCompletion callback with the existing (JTA) transaction.
				/**
				 * 如果当前事务是参与到一个更大事务（eg: JTA 事务）的一部分，
				 * 调用 registerAfterCompletionWithExistingTransaction() 方法，
				 * 将同步回调注册到外部事务中，以便在外部事务完成时触发这些回调。
				 *
				 * status.getTransaction()：获取当前参与的事务对象
				 * synchronizations：传入已注册的同步回调列表，以便在外部事务完成时执行这些回调
				 * TODO
				 *  目的：处理当前事务不是独立事务，而是参与到外部事务的情况。
				 * 	在这种情况下，Spring 无法直接触发 afterCompletion，
				 * 	而是需要注册回调到外部事务的管理范围内
				 */
				registerAfterCompletionWithExistingTransaction(status.getTransaction(), synchronizations);
			}
		}
	}

	/**
	 * Actually invoke the {@code afterCompletion} methods of the
	 * given Spring TransactionSynchronization objects.
	 * <p>To be called by this abstract manager itself, or by special implementations
	 * of the {@code registerAfterCompletionWithExistingTransaction} callback.
	 *
	 * @param synchronizations a List of TransactionSynchronization objects
	 * @param completionStatus the completion status according to the
	 *                         constants in the TransactionSynchronization interface
	 * @see #registerAfterCompletionWithExistingTransaction(Object, java.util.List)
	 * @see TransactionSynchronization#STATUS_COMMITTED
	 * @see TransactionSynchronization#STATUS_ROLLED_BACK
	 * @see TransactionSynchronization#STATUS_UNKNOWN
	 */
	protected final void invokeAfterCompletion(List<TransactionSynchronization> synchronizations, int completionStatus) {
		TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations, completionStatus);
	}

	/**
	 * Clean up after completion, clearing synchronization if necessary,
	 * and invoking doCleanupAfterCompletion.
	 *
	 * @param status object representing the transaction
	 *                                TODO 当前事务的状态对象，包含了事务的详细信息，包括事务资源、是否是新事务、是否有挂起的资源等
	 * @see #doCleanupAfterCompletion
	 * <p>
	 * TODO 清理事务完成后（提交或回滚）的上下文信息，释放资源并恢复任何被挂起的事务资源
	 */
	private void cleanupAfterCompletion(DefaultTransactionStatus status) {
		// 调用 status.setCompleted() 将事务状态标记为已完成。
		// 这个方法会将 DefaultTransactionStatus 中的 completed 标志设置为 true，表示事务已经结束（无论是提交还是回滚）
		status.setCompleted();

		// 检查事务是否有新注册的同步回调（TransactionSynchronization）。通常这些同步回调会在事务提交或回滚时触发
		if (status.isNewSynchronization()) {
			// 如果有新的同步回调，
			// 调用 TransactionSynchronizationManager.clear() 清理事务同步上下文，包括事务同步回调、当前事务的名称、事务的只读状态等
			TransactionSynchronizationManager.clear();
		}
		if (status.isNewTransaction()) {// 检查当前事务是否是一个新事务。新事务是指由当前事务管理器创建并管理的事务，而不是参与到外部事务的事务
			// 执行具体的清理操作。
			// doCleanupAfterCompletion 是一个抽象方法，通常由子类实现，用于清理底层资源（如数据库连接、JDBC 事务等）
			// TODO 进入 org.springframework.jdbc.datasource.DataSourceTransactionManager.doCleanupAfterCompletion
			doCleanupAfterCompletion(status.getTransaction());
		}
		// 检查是否有挂起的事务资源。
		// 挂起的事务资源通常是当一个事务嵌套在另一个事务中时，外部事务的资源会被暂时挂起（如数据库连接、事务状态等），等内部事务完成后再恢复
		if (status.getSuspendedResources() != null) {
			if (status.isDebug()) {
				logger.debug("Resuming suspended transaction after completion of inner transaction");
			}
			// status.hasTransaction()：检查当前事务状态中是否有事务对象。如果有，获取事务对象 status.getTransaction()；
			// 否则，设置 transaction 为 null
			Object transaction = (status.hasTransaction() ? status.getTransaction() : null);
			// 用 resume() 方法恢复挂起的事务资源，resume() 方法会将挂起的资源重新绑定到当前线程中，恢复事务之前的状态
			// 挂起的资源对象，通常包含事务持有的连接、事务同步等信息
			// TODO 进入
			resume(transaction, (SuspendedResourcesHolder) status.getSuspendedResources());
		}
	}


	//---------------------------------------------------------------------
	// Template methods to be implemented in subclasses
	//---------------------------------------------------------------------

	/**
	 * Return a transaction object for the current transaction state.
	 * <p>The returned object will usually be specific to the concrete transaction
	 * manager implementation, carrying corresponding transaction state in a
	 * modifiable fashion. This object will be passed into the other template
	 * methods (e.g. doBegin and doCommit), either directly or as part of a
	 * DefaultTransactionStatus instance.
	 * <p>The returned object should contain information about any existing
	 * transaction, that is, a transaction that has already started before the
	 * current {@code getTransaction} call on the transaction manager.
	 * Consequently, a {@code doGetTransaction} implementation will usually
	 * look for an existing transaction and store corresponding state in the
	 * returned transaction object.
	 *
	 * @return the current transaction object
	 * @throws org.springframework.transaction.CannotCreateTransactionException if transaction support is not available
	 * @throws TransactionException                                             in case of lookup or system errors
	 * @see #doBegin
	 * @see #doCommit
	 * @see #doRollback
	 * @see DefaultTransactionStatus#getTransaction
	 */
	protected abstract Object doGetTransaction() throws TransactionException;

	/**
	 * Check if the given transaction object indicates an existing transaction
	 * (that is, a transaction which has already started).
	 * <p>The result will be evaluated according to the specified propagation
	 * behavior for the new transaction. An existing transaction might get
	 * suspended (in case of PROPAGATION_REQUIRES_NEW), or the new transaction
	 * might participate in the existing one (in case of PROPAGATION_REQUIRED).
	 * <p>The default implementation returns {@code false}, assuming that
	 * participating in existing transactions is generally not supported.
	 * Subclasses are of course encouraged to provide such support.
	 *
	 * @param transaction the transaction object returned by doGetTransaction
	 * @return if there is an existing transaction
	 * @throws TransactionException in case of system errors
	 * @see #doGetTransaction
	 */
	protected boolean isExistingTransaction(Object transaction) throws TransactionException {
		return false;
	}

	/**
	 * Return whether to use a savepoint for a nested transaction.
	 * <p>Default is {@code true}, which causes delegation to DefaultTransactionStatus
	 * for creating and holding a savepoint. If the transaction object does not implement
	 * the SavepointManager interface, a NestedTransactionNotSupportedException will be
	 * thrown. Else, the SavepointManager will be asked to create a new savepoint to
	 * demarcate the start of the nested transaction.
	 * <p>Subclasses can override this to return {@code false}, causing a further
	 * call to {@code doBegin} - within the context of an already existing transaction.
	 * The {@code doBegin} implementation needs to handle this accordingly in such
	 * a scenario. This is appropriate for JTA, for example.
	 *
	 * @see DefaultTransactionStatus#createAndHoldSavepoint
	 * @see DefaultTransactionStatus#rollbackToHeldSavepoint
	 * @see DefaultTransactionStatus#releaseHeldSavepoint
	 * @see #doBegin
	 */
	protected boolean useSavepointForNestedTransaction() {
		return true;
	}

	/**
	 * Begin a new transaction with semantics according to the given transaction
	 * definition. Does not have to care about applying the propagation behavior,
	 * as this has already been handled by this abstract manager.
	 * <p>This method gets called when the transaction manager has decided to actually
	 * start a new transaction. Either there wasn't any transaction before, or the
	 * previous transaction has been suspended.
	 * <p>A special scenario is a nested transaction without savepoint: If
	 * {@code useSavepointForNestedTransaction()} returns "false", this method
	 * will be called to start a nested transaction when necessary. In such a context,
	 * there will be an active transaction: The implementation of this method has
	 * to detect this and start an appropriate nested transaction.
	 *
	 * @param transaction the transaction object returned by {@code doGetTransaction}
	 * @param definition  a TransactionDefinition instance, describing propagation
	 *                    behavior, isolation level, read-only flag, timeout, and transaction name
	 * @throws TransactionException                                                   in case of creation or system errors
	 * @throws org.springframework.transaction.NestedTransactionNotSupportedException if the underlying transaction does not support nesting
	 */
	protected abstract void doBegin(Object transaction, TransactionDefinition definition)
			throws TransactionException;

	/**
	 * Suspend the resources of the current transaction.
	 * Transaction synchronization will already have been suspended.
	 * <p>The default implementation throws a TransactionSuspensionNotSupportedException,
	 * assuming that transaction suspension is generally not supported.
	 *
	 * @param transaction the transaction object returned by {@code doGetTransaction}
	 * @return an object that holds suspended resources
	 * (will be kept unexamined for passing it into doResume)
	 * @throws org.springframework.transaction.TransactionSuspensionNotSupportedException if suspending is not supported by the transaction manager implementation
	 * @throws TransactionException                                                       in case of system errors
	 * @see #doResume
	 */
	protected Object doSuspend(Object transaction) throws TransactionException {
		throw new TransactionSuspensionNotSupportedException(
				"Transaction manager [" + getClass().getName() + "] does not support transaction suspension");
	}

	/**
	 * Resume the resources of the current transaction.
	 * Transaction synchronization will be resumed afterwards.
	 * <p>The default implementation throws a TransactionSuspensionNotSupportedException,
	 * assuming that transaction suspension is generally not supported.
	 *
	 * @param transaction        the transaction object returned by {@code doGetTransaction}
	 * @param suspendedResources the object that holds suspended resources,
	 *                           as returned by doSuspend
	 * @throws org.springframework.transaction.TransactionSuspensionNotSupportedException if resuming is not supported by the transaction manager implementation
	 * @throws TransactionException                                                       in case of system errors
	 * @see #doSuspend
	 */
	protected void doResume(@Nullable Object transaction, Object suspendedResources) throws TransactionException {
		throw new TransactionSuspensionNotSupportedException(
				"Transaction manager [" + getClass().getName() + "] does not support transaction suspension");
	}

	/**
	 * Return whether to call {@code doCommit} on a transaction that has been
	 * marked as rollback-only in a global fashion.
	 * <p>Does not apply if an application locally sets the transaction to rollback-only
	 * via the TransactionStatus, but only to the transaction itself being marked as
	 * rollback-only by the transaction coordinator.
	 * <p>Default is "false": Local transaction strategies usually don't hold the rollback-only
	 * marker in the transaction itself, therefore they can't handle rollback-only transactions
	 * as part of transaction commit. Hence, AbstractPlatformTransactionManager will trigger
	 * a rollback in that case, throwing an UnexpectedRollbackException afterwards.
	 * <p>Override this to return "true" if the concrete transaction manager expects a
	 * {@code doCommit} call even for a rollback-only transaction, allowing for
	 * special handling there. This will, for example, be the case for JTA, where
	 * {@code UserTransaction.commit} will check the read-only flag itself and
	 * throw a corresponding RollbackException, which might include the specific reason
	 * (such as a transaction timeout).
	 * <p>If this method returns "true" but the {@code doCommit} implementation does not
	 * throw an exception, this transaction manager will throw an UnexpectedRollbackException
	 * itself. This should not be the typical case; it is mainly checked to cover misbehaving
	 * JTA providers that silently roll back even when the rollback has not been requested
	 * by the calling code.
	 *
	 * @see #doCommit
	 * @see DefaultTransactionStatus#isGlobalRollbackOnly()
	 * @see DefaultTransactionStatus#isLocalRollbackOnly()
	 * @see org.springframework.transaction.TransactionStatus#setRollbackOnly()
	 * @see org.springframework.transaction.UnexpectedRollbackException
	 * @see javax.transaction.UserTransaction#commit()
	 * @see javax.transaction.RollbackException
	 */
	protected boolean shouldCommitOnGlobalRollbackOnly() {
		return false;
	}

	/**
	 * Make preparations for commit, to be performed before the
	 * {@code beforeCommit} synchronization callbacks occur.
	 * <p>Note that exceptions will get propagated to the commit caller
	 * and cause a rollback of the transaction.
	 *
	 * @param status the status representation of the transaction
	 * @throws RuntimeException in case of errors; will be <b>propagated to the caller</b>
	 *                          (note: do not throw TransactionException subclasses here!)
	 */
	protected void prepareForCommit(DefaultTransactionStatus status) {
	}

	/**
	 * Perform an actual commit of the given transaction.
	 * <p>An implementation does not need to check the "new transaction" flag
	 * or the rollback-only flag; this will already have been handled before.
	 * Usually, a straight commit will be performed on the transaction object
	 * contained in the passed-in status.
	 *
	 * @param status the status representation of the transaction
	 * @throws TransactionException in case of commit or system errors
	 * @see DefaultTransactionStatus#getTransaction
	 */
	protected abstract void doCommit(DefaultTransactionStatus status) throws TransactionException;

	/**
	 * Perform an actual rollback of the given transaction.
	 * <p>An implementation does not need to check the "new transaction" flag;
	 * this will already have been handled before. Usually, a straight rollback
	 * will be performed on the transaction object contained in the passed-in status.
	 *
	 * @param status the status representation of the transaction
	 * @throws TransactionException in case of system errors
	 * @see DefaultTransactionStatus#getTransaction
	 */
	protected abstract void doRollback(DefaultTransactionStatus status) throws TransactionException;

	/**
	 * Set the given transaction rollback-only. Only called on rollback
	 * if the current transaction participates in an existing one.
	 * <p>The default implementation throws an IllegalTransactionStateException,
	 * assuming that participating in existing transactions is generally not
	 * supported. Subclasses are of course encouraged to provide such support.
	 *
	 * @param status the status representation of the transaction
	 * @throws TransactionException in case of system errors
	 */
	protected void doSetRollbackOnly(DefaultTransactionStatus status) throws TransactionException {
		throw new IllegalTransactionStateException(
				"Participating in existing transactions is not supported - when 'isExistingTransaction' " +
						"returns true, appropriate 'doSetRollbackOnly' behavior must be provided");
	}

	/**
	 * Register the given list of transaction synchronizations with the existing transaction.
	 * <p>Invoked when the control of the Spring transaction manager and thus all Spring
	 * transaction synchronizations end, without the transaction being completed yet. This
	 * is for example the case when participating in an existing JTA or EJB CMT transaction.
	 * <p>The default implementation simply invokes the {@code afterCompletion} methods
	 * immediately, passing in "STATUS_UNKNOWN". This is the best we can do if there's no
	 * chance to determine the actual outcome of the outer transaction.
	 *
	 * @param transaction      the transaction object returned by {@code doGetTransaction}
	 * @param synchronizations a List of TransactionSynchronization objects
	 * @throws TransactionException in case of system errors
	 * @see #invokeAfterCompletion(java.util.List, int)
	 * @see TransactionSynchronization#afterCompletion(int)
	 * @see TransactionSynchronization#STATUS_UNKNOWN
	 */
	protected void registerAfterCompletionWithExistingTransaction(
			Object transaction, List<TransactionSynchronization> synchronizations) throws TransactionException {

		logger.debug("Cannot register Spring after-completion synchronization with existing transaction - " +
				"processing Spring after-completion callbacks immediately, with outcome status 'unknown'");
		invokeAfterCompletion(synchronizations, TransactionSynchronization.STATUS_UNKNOWN);
	}

	/**
	 * Cleanup resources after transaction completion.
	 * <p>Called after {@code doCommit} and {@code doRollback} execution,
	 * on any outcome. The default implementation does nothing.
	 * <p>Should not throw any exceptions but just issue warnings on errors.
	 *
	 * @param transaction the transaction object returned by {@code doGetTransaction}
	 */
	protected void doCleanupAfterCompletion(Object transaction) {
	}


	//---------------------------------------------------------------------
	// Serialization support
	//---------------------------------------------------------------------

	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
		// Rely on default serialization; just initialize state after deserialization.
		ois.defaultReadObject();

		// Initialize transient fields.
		this.logger = LogFactory.getLog(getClass());
	}


	/**
	 * Holder for suspended resources.
	 * Used internally by {@code suspend} and {@code resume}.
	 */
	protected static final class SuspendedResourcesHolder {

		@Nullable
		private final Object suspendedResources;

		@Nullable
		private List<TransactionSynchronization> suspendedSynchronizations;

		@Nullable
		private String name;

		private boolean readOnly;

		@Nullable
		private Integer isolationLevel;

		private boolean wasActive;

		private SuspendedResourcesHolder(Object suspendedResources) {
			this.suspendedResources = suspendedResources;
		}

		private SuspendedResourcesHolder(
				@Nullable Object suspendedResources, List<TransactionSynchronization> suspendedSynchronizations,
				@Nullable String name, boolean readOnly, @Nullable Integer isolationLevel, boolean wasActive) {

			this.suspendedResources = suspendedResources;
			this.suspendedSynchronizations = suspendedSynchronizations;
			this.name = name;
			this.readOnly = readOnly;
			this.isolationLevel = isolationLevel;
			this.wasActive = wasActive;
		}
	}

}
