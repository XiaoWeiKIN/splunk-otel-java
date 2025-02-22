/*
 * Copyright Splunk Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.splunk.opentelemetry.instrumentation.tomcatjdbc;

import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.tomcat.jdbc.pool.DataSourceProxy;

class DataSourceProxyInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.apache.tomcat.jdbc.pool.DataSourceProxy");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        isPublic().and(named("createPool")).and(takesNoArguments()),
        this.getClass().getName() + "$CreatePoolAdvice");

    transformer.applyAdviceToMethod(
        isPublic().and(named("close")).and(takesArguments(1)),
        this.getClass().getName() + "$CloseAdvice");
  }

  @SuppressWarnings("unused")
  public static class CreatePoolAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This DataSourceProxy dataSource) {
      ConnectionPoolMetrics.registerMetrics(dataSource);
    }
  }

  @SuppressWarnings("unused")
  public static class CloseAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This DataSourceProxy dataSource) {
      ConnectionPoolMetrics.unregisterMetrics(dataSource);
    }
  }
}
