/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.transaction.annotation;

import java.io.Serializable;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.transaction.interceptor.NoRollbackRuleAttribute;
import org.springframework.transaction.interceptor.RollbackRuleAttribute;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttribute;

/**
 * Strategy implementation for parsing Spring's {@link Transactional} annotation.
 *
 * @author Juergen Hoeller
 * @since 2.5
 */
@SuppressWarnings("serial")
public class SpringTransactionAnnotationParser implements TransactionAnnotationParser, Serializable {

	@Override
	public boolean isCandidateClass(Class<?> targetClass) {
		return AnnotationUtils.isCandidateClass(targetClass, Transactional.class);
	}

	@Override
	@Nullable
	public TransactionAttribute parseTransactionAnnotation(AnnotatedElement element) {
		AnnotationAttributes attributes = AnnotatedElementUtils.findMergedAnnotationAttributes(
				element, Transactional.class, false, false);
		if (attributes != null) {
			// TODO 进入
			return parseTransactionAnnotation(attributes);
		}
		else {
			return null;
		}
	}

	public TransactionAttribute parseTransactionAnnotation(Transactional ann) {
		// TODO 进入
		return parseTransactionAnnotation(AnnotationUtils.getAnnotationAttributes(ann, false, false));
	}

	/**
	 * TODO
	 *  解析注解中的各种事务属性（如传播行为、隔离级别、超时、只读状态及回滚规则），
	 *  并将它们封装在 RuleBasedTransactionAttribute 对象中，最终返回该对象。
	 *  这使得 Spring 可以根据注解配置的事务属性正确管理事务
	 * @param attributes
	 * @return
	 */
	protected TransactionAttribute parseTransactionAnnotation(AnnotationAttributes attributes) {
		// 实例化一个 RuleBasedTransactionAttribute 对象，用于存储解析后的事务属性
		RuleBasedTransactionAttribute rbta = new RuleBasedTransactionAttribute();
		// 从注解属性中获取 propagation 的值，并设置到 rbta 中，定义事务的传播行为
		Propagation propagation = attributes.getEnum("propagation");
		rbta.setPropagationBehavior(propagation.value());
		// 从注解属性中获取 isolation 的值，并设置到 rbta 中，定义事务的隔离级别
		Isolation isolation = attributes.getEnum("isolation");
		rbta.setIsolationLevel(isolation.value());
		// 从注解属性中获取 timeout，并将其转换为整数后设置到 rbta 中
		rbta.setTimeout(attributes.getNumber("timeout").intValue());
		// 从注解属性中获取 readOnly 的布尔值，并设置到 rbta 中，指示事务是否为只读
		rbta.setReadOnly(attributes.getBoolean("readOnly"));
		// 从注解属性中获取 value 字符串，并设置到 rbta 中，用于标识事务
		rbta.setQualifier(attributes.getString("value"));
		// 创建一个空的 RollbackRuleAttribute 列表，用于存储回滚规则
		List<RollbackRuleAttribute> rollbackRules = new ArrayList<>();
		// 从注解属性中获取 rollbackFor 的类数组，遍历每个类，并将其封装为 RollbackRuleAttribute 添加到回滚规则列表中
		for (Class<?> rbRule : attributes.getClassArray("rollbackFor")) {
			rollbackRules.add(new RollbackRuleAttribute(rbRule));
		}
		// 从注解属性中获取 rollbackForClassName 的字符串数组，遍历每个类名，并将其封装为 RollbackRuleAttribute 添加到回滚规则列表中
		for (String rbRule : attributes.getStringArray("rollbackForClassName")) {
			rollbackRules.add(new RollbackRuleAttribute(rbRule));
		}
		// 从注解属性中获取 noRollbackFor 的类数组，遍历每个类，并将其封装为 NoRollbackRuleAttribute 添加到回滚规则列表中，表示这些异常不应导致事务回滚
		for (Class<?> rbRule : attributes.getClassArray("noRollbackFor")) {
			rollbackRules.add(new NoRollbackRuleAttribute(rbRule));
		}
		// 从注解属性中获取 noRollbackForClassName 的字符串数组，遍历每个类名，并将其封装为 NoRollbackRuleAttribute 添加到回滚规则列表中
		for (String rbRule : attributes.getStringArray("noRollbackForClassName")) {
			rollbackRules.add(new NoRollbackRuleAttribute(rbRule));
		}
		rbta.setRollbackRules(rollbackRules);

		return rbta;
	}


	@Override
	public boolean equals(@Nullable Object other) {
		return (this == other || other instanceof SpringTransactionAnnotationParser);
	}

	@Override
	public int hashCode() {
		return SpringTransactionAnnotationParser.class.hashCode();
	}

}
