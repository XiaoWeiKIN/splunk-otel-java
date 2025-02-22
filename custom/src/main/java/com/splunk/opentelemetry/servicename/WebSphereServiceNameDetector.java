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

package com.splunk.opentelemetry.servicename;

import static java.util.logging.Level.FINE;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class WebSphereServiceNameDetector extends AppServerServiceNameDetector {

  private static final Logger logger =
      Logger.getLogger(WebSphereServiceNameDetector.class.getName());

  WebSphereServiceNameDetector(ResourceLocator locator) {
    super(locator, "com.ibm.wsspi.bootstrap.WSPreLauncher", true);
  }

  @Override
  boolean isValidAppName(Path path) {
    // query.ear is bundled with websphere
    String name = path.getFileName().toString();
    return !"query.ear".equals(name);
  }

  @Override
  Path getDeploymentDir() {
    // not used
    return null;
  }

  @Override
  String detect() throws Exception {
    if (serverClass == null) {
      return null;
    }

    String programArguments = System.getProperty("sun.java.command");
    logger.log(FINE, "Started with arguments '{0}'.", programArguments);
    if (programArguments == null) {
      return null;
    }

    Pattern pattern =
        Pattern.compile(
            "com\\.ibm\\.wsspi\\.bootstrap\\.WSPreLauncher (.*) com\\.ibm\\.ws\\.runtime\\.WsServer (.+) ([^ ]+) ([^ ]+) ([^ ]+)");
    Matcher matcher = pattern.matcher(programArguments);
    if (!matcher.matches()) {
      logger.fine("Failed to parse arguments.");
      return null;
    }

    // in docker image it is /opt/IBM/WebSphere/AppServer/profiles/AppSrv01/config
    Path configDirectory = Paths.get(matcher.group(2));
    if (!Files.isDirectory(configDirectory)) {
      logger.log(FINE, "Missing configuration directory '{0}'.", configDirectory);
      return null;
    }

    String cell = matcher.group(3);
    String node = matcher.group(4);
    String server = matcher.group(5);

    if (logger.isLoggable(FINE)) {
      logger.log(
          FINE,
          "Parsed arguments: cell '{0}', node '{1}', server '{2}', configuration directory '{3}'.",
          new Object[] {cell, node, server, configDirectory});
    }

    // construct installedApps directory path based on the config path
    // in docker image it is
    // /opt/IBM/WebSphere/AppServer/profiles/AppSrv01/installedApps/DefaultCell01
    // NOTE: installedApps directory location is configurable
    Path cellApplications = configDirectory.getParent().resolve("installedApps").resolve(cell);
    if (Files.isDirectory(cellApplications)) {
      logger.log(FINE, "Looking for deployments in '{0}'.", cellApplications);

      try (Stream<Path> stream = Files.list(cellApplications)) {
        for (Path path : stream.collect(Collectors.toList())) {
          String fullName = path.getFileName().toString();
          // websphere deploys all applications as ear
          if (!fullName.endsWith(".ear") || !isValidAppName(path)) {
            logger.log(FINE, "Skipping '{0}'.", path);
            continue;
          }
          logger.log(FINE, "Attempting service name detection in '{0}'.", path);

          // strip ear suffix
          String name = fullName.substring(0, fullName.length() - 4);
          // if there is only one war file with the same name as the ear then the app was probably
          // really a war file and the surrounding ear was generated during deployment
          List<Path> wars;
          try (Stream<Path> warStream = Files.list(path)) {
            wars =
                warStream
                    .filter(p -> p.getFileName().toString().endsWith(".war"))
                    .collect(Collectors.toList());
          }
          boolean maybeWarDeployment =
              wars.size() == 1 && wars.get(0).getFileName().toString().equals(name + ".war");
          if (maybeWarDeployment) {
            String result = handleExplodedWar(wars.get(0));
            if (result != null) {
              return result;
            }
          }
          String result = handleExplodedEar(path);
          // Auto-generated display-name in our testapp is app182ceb797ea, ignore similar names
          if (result != null && (!maybeWarDeployment || !result.startsWith(name))) {
            return result;
          }
        }
      }
    }

    return null;
  }
}
