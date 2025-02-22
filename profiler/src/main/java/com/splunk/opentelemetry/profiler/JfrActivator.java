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

package com.splunk.opentelemetry.profiler;

import static com.splunk.opentelemetry.profiler.Configuration.CONFIG_KEY_ENABLE_PROFILER;
import static com.splunk.opentelemetry.profiler.Configuration.CONFIG_KEY_KEEP_FILES;
import static com.splunk.opentelemetry.profiler.Configuration.CONFIG_KEY_PROFILER_DIRECTORY;
import static com.splunk.opentelemetry.profiler.Configuration.CONFIG_KEY_RECORDING_DURATION;
import static com.splunk.opentelemetry.profiler.JfrFileLifecycleEvents.buildOnFileFinished;
import static com.splunk.opentelemetry.profiler.JfrFileLifecycleEvents.buildOnNewRecording;
import static com.splunk.opentelemetry.profiler.util.Runnables.logUncaught;
import static java.util.logging.Level.WARNING;

import com.google.auto.service.AutoService;
import com.splunk.opentelemetry.profiler.Configuration.DataFormat;
import com.splunk.opentelemetry.profiler.allocation.exporter.AllocationEventExporter;
import com.splunk.opentelemetry.profiler.allocation.exporter.PlainTextAllocationEventExporter;
import com.splunk.opentelemetry.profiler.allocation.exporter.PprofAllocationEventExporter;
import com.splunk.opentelemetry.profiler.context.SpanContextualizer;
import com.splunk.opentelemetry.profiler.events.EventPeriods;
import com.splunk.opentelemetry.profiler.exporter.CpuEventExporter;
import com.splunk.opentelemetry.profiler.exporter.PlainTextCpuEventExporter;
import com.splunk.opentelemetry.profiler.exporter.PprofCpuEventExporter;
import com.splunk.opentelemetry.profiler.util.FileDeleter;
import com.splunk.opentelemetry.profiler.util.HelpfulExecutors;
import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.logs.LogEmitter;
import io.opentelemetry.sdk.logs.LogProcessor;
import io.opentelemetry.sdk.logs.SdkLogEmitterProvider;
import io.opentelemetry.sdk.logs.export.BatchLogProcessor;
import io.opentelemetry.sdk.logs.export.LogExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogProcessor;
import io.opentelemetry.sdk.resources.Resource;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.logging.Logger;

@AutoService(AgentListener.class)
public class JfrActivator implements AgentListener {

  private static final Logger logger = Logger.getLogger(JfrActivator.class.getName());
  private static final int MAX_BATCH_SIZE = 250;
  private static final Duration MAX_TIME_BETWEEN_BATCHES = Duration.ofSeconds(10);
  private final ExecutorService executor = HelpfulExecutors.newSingleThreadExecutor("JFR Profiler");
  private final ConfigurationLogger configurationLogger = new ConfigurationLogger();

  @Override
  public void afterAgent(AutoConfiguredOpenTelemetrySdk autoConfiguredOpenTelemetrySdk) {
    ConfigProperties config = autoConfiguredOpenTelemetrySdk.getConfig();
    if (!config.getBoolean(CONFIG_KEY_ENABLE_PROFILER, false)) {
      logger.fine("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
      logger.fine("xxxxxxxxx  JFR PROFILER DISABLED!  xxxxxxxxx");
      logger.fine("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
      return;
    }
    if (!JFR.instance.isAvailable()) {
      logger.warning(
          "JDK Flight Recorder (JFR) is not available in this JVM. Profiling is disabled.");
      return;
    }
    configurationLogger.log(config);
    logger.info("JFR profiler is active.");
    executor.submit(
        logUncaught(
            () -> activateJfrAndRunForever(config, autoConfiguredOpenTelemetrySdk.getResource())));
  }

  private void activateJfrAndRunForever(ConfigProperties config, Resource resource) {
    // can't be null, default value is set in Configuration.getProperties
    Duration recordingDuration = config.getDuration(CONFIG_KEY_RECORDING_DURATION, null);

    Path outputDir = Paths.get(config.getString(CONFIG_KEY_PROFILER_DIRECTORY));
    RecordingFileNamingConvention namingConvention = new RecordingFileNamingConvention(outputDir);

    int stackDepth = Configuration.getStackDepth(config);
    JFR.instance.setStackDepth(stackDepth);

    RecordingEscapeHatch recordingEscapeHatch =
        RecordingEscapeHatch.builder()
            .namingConvention(namingConvention)
            .configKeepsFilesOnDisk(keepFiles(config))
            .recordingDuration(recordingDuration)
            .build();
    Map<String, String> jfrSettings = buildJfrSettings(config);

    RecordedEventStream.Factory recordedEventStreamFactory =
        () -> new BasicJfrRecordingFile(JFR.instance);

    SpanContextualizer spanContextualizer = new SpanContextualizer();
    EventPeriods periods = new EventPeriods(jfrSettings::get);
    LogDataCommonAttributes commonAttributes = new LogDataCommonAttributes(periods);
    LogExporter logsExporter = LogExporterBuilder.fromConfig(config);

    DataFormat cpuDataFormat = Configuration.getCpuDataFormat(config);
    CpuEventExporter cpuEventExporter;
    if (cpuDataFormat == DataFormat.TEXT) {
      cpuEventExporter =
          PlainTextCpuEventExporter.builder()
              .logEmitter(buildLogEmitter(BatchLogProcessorHolder.get(logsExporter), resource))
              .commonAttributes(commonAttributes)
              .build();
    } else {
      cpuEventExporter =
          PprofCpuEventExporter.builder()
              .logEmitter(buildLogEmitter(SimpleLogProcessor.create(logsExporter), resource))
              .dataFormat(cpuDataFormat)
              .eventPeriods(periods)
              .stackDepth(stackDepth)
              .build();
    }

    StackTraceFilter stackTraceFilter = buildStackTraceFilter(config);
    ThreadDumpProcessor threadDumpProcessor =
        buildThreadDumpProcessor(spanContextualizer, cpuEventExporter, stackTraceFilter, config);

    DataFormat allocationDataFormat = Configuration.getAllocationDataFormat(config);
    AllocationEventExporter allocationEventExporter;
    if (allocationDataFormat == DataFormat.TEXT) {
      allocationEventExporter =
          PlainTextAllocationEventExporter.builder()
              .logEmitter(buildLogEmitter(BatchLogProcessorHolder.get(logsExporter), resource))
              .commonAttributes(commonAttributes)
              .stackDepth(stackDepth)
              .build();
    } else {
      allocationEventExporter =
          PprofAllocationEventExporter.builder()
              .logEmitter(buildLogEmitter(SimpleLogProcessor.create(logsExporter), resource))
              .dataFormat(allocationDataFormat)
              .stackDepth(stackDepth)
              .build();
    }

    TLABProcessor tlabProcessor =
        TLABProcessor.builder(config)
            .allocationEventExporter(allocationEventExporter)
            .spanContextualizer(spanContextualizer)
            .stackTraceFilter(stackTraceFilter)
            .build();
    EventProcessingChain eventProcessingChain =
        new EventProcessingChain(spanContextualizer, threadDumpProcessor, tlabProcessor);
    Consumer<Path> deleter = buildFileDeleter(config);
    JfrDirCleanup dirCleanup = new JfrDirCleanup(deleter);

    Consumer<Path> onFileFinished = buildOnFileFinished(deleter, dirCleanup);

    JfrPathHandler jfrPathHandler =
        JfrPathHandler.builder()
            .recordedEventStreamFactory(recordedEventStreamFactory)
            .eventProcessingChain(eventProcessingChain)
            .onFileFinished(onFileFinished)
            .build();

    Consumer<Path> onNewRecording = buildOnNewRecording(jfrPathHandler, dirCleanup);

    JfrRecorder recorder =
        JfrRecorder.builder()
            .settings(jfrSettings)
            .maxAgeDuration(recordingDuration.multipliedBy(10))
            .jfr(JFR.instance)
            .namingConvention(namingConvention)
            .onNewRecordingFile(onNewRecording)
            .build();

    RecordingSequencer sequencer =
        RecordingSequencer.builder()
            .recordingDuration(recordingDuration)
            .recordingEscapeHatch(recordingEscapeHatch)
            .recorder(recorder)
            .build();

    sequencer.start();
    dirCleanup.registerShutdownHook();
  }

  private LogEmitter buildLogEmitter(LogProcessor logProcessor, Resource resource) {
    return SdkLogEmitterProvider.builder()
        .addLogProcessor(logProcessor)
        .setResource(resource)
        .build()
        .logEmitterBuilder(ProfilingSemanticAttributes.OTEL_INSTRUMENTATION_NAME)
        .setInstrumentationVersion(ProfilingSemanticAttributes.OTEL_INSTRUMENTATION_VERSION)
        .build();
  }

  private ThreadDumpProcessor buildThreadDumpProcessor(
      SpanContextualizer spanContextualizer,
      CpuEventExporter profilingEventExporter,
      StackTraceFilter stackTraceFilter,
      ConfigProperties config) {
    return ThreadDumpProcessor.builder()
        .spanContextualizer(spanContextualizer)
        .cpuEventExporter(profilingEventExporter)
        .stackTraceFilter(stackTraceFilter)
        .onlyTracingSpans(Configuration.getTracingStacksOnly(config))
        .build();
  }

  /** Based on config, filters out agent internal stacks and/or JVM internal stacks */
  private StackTraceFilter buildStackTraceFilter(ConfigProperties config) {
    boolean includeAgentInternalStacks = Configuration.getIncludeAgentInternalStacks(config);
    boolean includeJVMInternalStacks = Configuration.getIncludeJvmInternalStacks(config);
    return new StackTraceFilter(includeAgentInternalStacks, includeJVMInternalStacks);
  }

  private Map<String, String> buildJfrSettings(ConfigProperties config) {
    JfrSettingsReader settingsReader = new JfrSettingsReader();
    Map<String, String> jfrSettings = settingsReader.read();
    JfrSettingsOverrides overrides = new JfrSettingsOverrides(config);
    return overrides.apply(jfrSettings);
  }

  private Consumer<Path> buildFileDeleter(ConfigProperties config) {
    if (keepFiles(config)) {
      logger.log(WARNING, "{0} is enabled, leaving JFR files on disk.", CONFIG_KEY_KEEP_FILES);
      return FileDeleter.noopFileDeleter();
    }
    return FileDeleter.newDeleter();
  }

  private boolean keepFiles(ConfigProperties config) {
    return config.getBoolean(CONFIG_KEY_KEEP_FILES, false);
  }

  private static class BatchLogProcessorHolder {
    private static LogProcessor INSTANCE;

    // initialize BatchLogProcessor only if it is needed and if it is needed initialize it only
    // once
    static LogProcessor get(LogExporter logsExporter) {

      if (INSTANCE == null) {
        INSTANCE =
            BatchLogProcessor.builder(logsExporter)
                .setScheduleDelay(MAX_TIME_BETWEEN_BATCHES)
                .setMaxExportBatchSize(MAX_BATCH_SIZE)
                .build();
        // BatchLogProcessor auto-starts in constructor. No need to invoke start()
      }

      return INSTANCE;
    }
  }
}
