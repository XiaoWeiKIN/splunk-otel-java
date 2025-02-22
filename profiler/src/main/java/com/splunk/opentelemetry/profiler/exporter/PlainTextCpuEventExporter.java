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

package com.splunk.opentelemetry.profiler.exporter;

import static com.splunk.opentelemetry.profiler.ProfilingSemanticAttributes.DATA_FORMAT;
import static com.splunk.opentelemetry.profiler.ProfilingSemanticAttributes.DATA_TYPE;

import com.splunk.opentelemetry.profiler.Configuration;
import com.splunk.opentelemetry.profiler.LogDataCommonAttributes;
import com.splunk.opentelemetry.profiler.ProfilingDataType;
import com.splunk.opentelemetry.profiler.context.StackToSpanLinkage;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.LogEmitter;
import io.opentelemetry.sdk.logs.LogRecordBuilder;

public class PlainTextCpuEventExporter implements CpuEventExporter {

  private final LogEmitter logEmitter;
  private final LogDataCommonAttributes commonAttributes;

  private PlainTextCpuEventExporter(Builder builder) {
    logEmitter = builder.logEmitter;
    commonAttributes = builder.commonAttributes;
  }

  @Override
  public void export(StackToSpanLinkage linkedStack) {
    Attributes attributes =
        commonAttributes
            .builder(linkedStack)
            .put(DATA_TYPE, ProfilingDataType.CPU.value())
            .put(DATA_FORMAT, Configuration.DataFormat.TEXT.value())
            .build();

    LogRecordBuilder logRecordBuilder =
        logEmitter
            .logRecordBuilder()
            .setEpoch(linkedStack.getTime())
            .setBody(linkedStack.getRawStack())
            .setAllAttributes(attributes);
    if (linkedStack.hasSpanInfo()) {
      logRecordBuilder.setContext(Context.root().with(Span.wrap(linkedStack.getSpanContext())));
    }
    logRecordBuilder.emit();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private LogEmitter logEmitter;
    private LogDataCommonAttributes commonAttributes;

    public PlainTextCpuEventExporter build() {
      return new PlainTextCpuEventExporter(this);
    }

    public Builder logEmitter(LogEmitter logEmitter) {
      this.logEmitter = logEmitter;
      return this;
    }

    public Builder commonAttributes(LogDataCommonAttributes commonAttributes) {
      this.commonAttributes = commonAttributes;
      return this;
    }
  }
}
