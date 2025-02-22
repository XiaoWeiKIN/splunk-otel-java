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

package com.splunk.opentelemetry.instrumentation.netty.v4_1;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.implementsInterface;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.opentelemetry.javaagent.bootstrap.CallDepth;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class ChannelPipelineInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed("io.netty.channel.ChannelPipeline");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("io.netty.channel.ChannelPipeline"));
  }

  @Override
  public void transform(TypeTransformer typeTransformer) {
    typeTransformer.applyAdviceToMethod(
        isMethod()
            .and(nameStartsWith("add"))
            .and(takesArgument(2, named("io.netty.channel.ChannelHandler"))),
        this.getClass().getName() + "$ChannelPipelineAddAdvice");
  }

  @SuppressWarnings("unused")
  public static class ChannelPipelineAddAdvice {
    @Advice.OnMethodEnter
    public static void trackCallDepth(@Advice.Local("splunkCallDepth") CallDepth callDepth) {
      callDepth = CallDepth.forClass(ServerTimingHandler.class);
      callDepth.getAndIncrement();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void addHandler(
        @Advice.Local("splunkCallDepth") CallDepth callDepth,
        @Advice.This ChannelPipeline pipeline,
        @Advice.Argument(2) ChannelHandler handler) {
      if (callDepth.decrementAndGet() > 0) {
        return;
      }

      try {
        if (handler instanceof HttpServerCodec || handler instanceof HttpResponseEncoder) {
          pipeline.addLast(ServerTimingHandler.class.getName(), new ServerTimingHandler());
        }
      } catch (IllegalArgumentException e) {
        // Prevented adding duplicate handlers.
      }
    }
  }
}
