plugins {
  id("splunk.instrumentation-conventions")
  id("splunk.muzzle-conventions")
}

// TODO: our netty instrumentation depends on the upstream netty instrumentation and muzzle-check does not like it
// enable muzzle once it's somehow fixed

dependencies {
  compileOnly("io.netty:netty-codec-http:4.1.0.Final")
  compileOnly("io.opentelemetry.javaagent.instrumentation:opentelemetry-javaagent-netty-4.1")
  compileOnly("io.opentelemetry.javaagent.instrumentation:opentelemetry-javaagent-netty-4-common")

  // add as muzzle codegen dependency too
  add("codegen", "io.opentelemetry.javaagent.instrumentation:opentelemetry-javaagent-netty-4.1")

  implementation(project(":instrumentation:common"))

  testInstrumentation("io.opentelemetry.javaagent.instrumentation:opentelemetry-javaagent-netty-3.8")
  testInstrumentation("io.opentelemetry.javaagent.instrumentation:opentelemetry-javaagent-netty-4.0")
  testInstrumentation("io.opentelemetry.javaagent.instrumentation:opentelemetry-javaagent-netty-4.1")
  testInstrumentation(project(":instrumentation:netty-3.8"))
  testInstrumentation(project(":instrumentation:netty-4.0"))

  testImplementation("io.netty:netty-codec-http:4.1.0.Final")
}
