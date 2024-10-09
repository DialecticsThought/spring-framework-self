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

package org.springframework.context.annotation;

import org.springframework.core.type.AnnotationMetadata;
import org.springframework.lang.Nullable;

/**
 * A variation of {@link ImportSelector} that runs after all {@code @Configuration} beans
 * have been processed. This type of selector can be particularly useful when the selected
 * imports are {@code @Conditional}.
 *
 * <p>Implementations can also extend the {@link org.springframework.core.Ordered}
 * interface or use the {@link org.springframework.core.annotation.Order} annotation to
 * indicate a precedence against other {@link DeferredImportSelector DeferredImportSelectors}.
 *
 * <p>Implementations may also provide an {@link #getImportGroup() import group} which
 * can provide additional sorting and filtering logic across different selectors.
 *
 * <pre>
 *   TODO
 *     用于延迟导入配置类，它可以根据某些条件决定是否导入指定的类
 *     主要目的是允许开发者在 Spring 容器启动时延迟导入配置类，并且可以在其他 @Configuration 类已经被处理之后才执行
 * </pre>
 *
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @since 4.0
 */
public interface DeferredImportSelector extends ImportSelector {

	/**
	 * Return a specific import group.
	 * <p>The default implementations return {@code null} for no grouping required.
	 * TODO
	 * 	返回的是一个 Group 类型的类，这个类用于分组处理多个导入选择器的结果
	 * 	Class<? extends Group>: 这个泛型表示返回的类必须是实现了 Group 接口的某个子类
	 *
	 * @return the import group class, or {@code null} if none
	 * @since 5.0
	 */
	@Nullable
	default Class<? extends Group> getImportGroup() {
		return null;
	}


	/**
	 * Interface used to group results from different import selectors.
	 * TODO
	 * 	用于将不同的 DeferredImportSelector 的结果进行分组。实现这个接口可以将多个导入选择器的导入结果进行整合和统一管理
	 *
	 * @since 5.0
	 */
	interface Group {

		/**
		 * Process the {@link AnnotationMetadata} of the importing @{@link Configuration}
		 * class using the specified {@link DeferredImportSelector}.
		 * <p>
		 * TODO
		 * 	用于处理导入的 @Configuration 类的 AnnotationMetadata（注解元数据）和 DeferredImportSelector。
		 * 	该方法可以根据导入选择器和元数据做一些处理逻辑
		 *  <pre>
		 *      AnnotationMetadata 保存了声明使用 @Import 注解的那个类（通常是某个 @Configuration 类）的注解和类结构信息
		 *      eg:
		 *     	 1.有一个类
		 *     		@Configuration
		 * 			@Import(CustomImportSelector.class)
		 * 			public class MainConfiguration {
		 *     			// 其他 Bean 定义
		 * 			}
		 * 		==> Entry得到的是 MainConfiguration的注解@Import(CustomImportSelector.class)和@Configuration
		 * 		  2.有一个类
		 * 			public class CustomImportSelector implements DeferredImportSelector {
		 * 				@Override
		 *     			public String[] selectImports(AnnotationMetadata importingClassMetadata) {
		 *         			// 假设通过某种方式检查是否启用了缓存
		 *         			boolean useCache = Boolean.parseBoolean(System.getProperty("useCache"));
		 *         			// 根据条件决定导入的配置类
		 *        			 if (useCache) {
		 *             		return new String[] { "com.example.CacheConfiguration" };
		 *         			} else {
		 *             			return new String[0]; // 不导入任何配置类
		 *         			}
		 *     			}
		 *     			@Nullable
		 *     			@Override
		 *     			public Class<? extends Group> getImportGroup() {
		 *         			return CustomGroup.class; // 返回分组处理类
		 *     			}
		 * 			}
		 * 		==> Entry得到的是 importClassName = com.example.CacheConfiguration
		 *  </pre>
		 */
		void process(AnnotationMetadata metadata, DeferredImportSelector selector);

		/**
		 * Return the {@link Entry entries} of which class(es) should be imported
		 * for this group.
		 * TODO
		 * 	返回一组 Entry。Entry 是 Group 中的内部类，用于封装导入的配置类及其相关的元数据
		 */
		Iterable<Entry> selectImports();


		/**
		 * An entry that holds the {@link AnnotationMetadata} of the importing
		 * {@link Configuration} class and the class name to import.
		 */
		class Entry {
			// 保存了导入配置类的注解元数据。它包含了诸如类上的注解、方法等信息
			private final AnnotationMetadata metadata;
			// 保存了要导入的类的全限定名，即包括包名和类名
			private final String importClassName;

			public Entry(AnnotationMetadata metadata, String importClassName) {
				this.metadata = metadata;
				this.importClassName = importClassName;
			}

			/**
			 * Return the {@link AnnotationMetadata} of the importing
			 * {@link Configuration} class.
			 */
			public AnnotationMetadata getMetadata() {
				return this.metadata;
			}

			/**
			 * Return the fully qualified name of the class to import.
			 *
			 * TODO 这是 Entry 的另一个 getter 方法，返回要导入的类的全限定名
			 */
			public String getImportClassName() {
				return this.importClassName;
			}

			@Override
			public boolean equals(@Nullable Object other) {
				if (this == other) {
					return true;
				}
				if (other == null || getClass() != other.getClass()) {
					return false;
				}
				Entry entry = (Entry) other;
				return (this.metadata.equals(entry.metadata) && this.importClassName.equals(entry.importClassName));
			}

			@Override
			public int hashCode() {
				return (this.metadata.hashCode() * 31 + this.importClassName.hashCode());
			}

			@Override
			public String toString() {
				return this.importClassName;
			}
		}
	}

}
