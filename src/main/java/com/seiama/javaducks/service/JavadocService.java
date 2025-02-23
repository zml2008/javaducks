/*
 * This file is part of javaducks, licensed under the MIT License.
 *
 * Copyright (c) 2023 Seiama
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.seiama.javaducks.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.seiama.javaducks.configuration.AppConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Snapshot;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class JavadocService {
  private static final Logger LOGGER = LoggerFactory.getLogger(JavadocService.class);
  private static final long REFRESH_INITIAL_DELAY = 0; // in minutes
  private static final long REFRESH_RATE = 15; // in minutes
  private static final String USER_AGENT = "JavaDucks";
  private static final String MAVEN_METADATA = "maven-metadata.xml";
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final AppConfiguration configuration;
  private final LoadingCache<Key, FileSystem> contents;

  @Autowired
  public JavadocService(final AppConfiguration configuration) {
    this.configuration = configuration;
    this.contents = Caffeine.newBuilder()
      .refreshAfterWrite(Duration.ofMinutes(10))
      .removalListener((RemovalListener<Key, FileSystem>) (key, value, cause) -> {
        if (value != null) {
          try {
            value.close();
          } catch (final IOException e) {
            LOGGER.error("Could not close file system", e);
          }
        }
      })
      .build(key -> {
        final Path path = this.configuration.storage().resolve(key.project()).resolve(key.version() + ".jar");
        if (Files.isRegularFile(path)) {
          return FileSystems.newFileSystem(path);
        }
        return null;
      });
  }

  public @Nullable FileSystem contentsFor(final Key key) {
    return this.contents.get(key);
  }

  @Scheduled(
    initialDelay = REFRESH_INITIAL_DELAY,
    fixedRate = REFRESH_RATE,
    timeUnit = TimeUnit.MINUTES
  )
  public void refreshAll() {
    for (final AppConfiguration.EndpointConfiguration endpoint : this.configuration.endpoints()) {
      this.refreshOne(endpoint);
    }
  }

  private void refreshOne(final AppConfiguration.EndpointConfiguration config) {
    final Path basePath = this.configuration.storage().resolve(config.name());
    for (final AppConfiguration.EndpointConfiguration.Version version : config.versions()) {
      final @Nullable URI jar = switch (version.type()) {
        case SNAPSHOT -> {
          final Metadata metadata;
          try {
            final HttpResponse<InputStream> response = this.httpClient.send(
              HttpRequest.newBuilder()
                .GET()
                .uri(version.asset(MAVEN_METADATA))
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .build(),
              HttpResponse.BodyHandlers.ofInputStream()
            );
            try (final InputStream is = response.body()) {
              final MetadataXpp3Reader reader = new MetadataXpp3Reader();
              metadata = reader.read(is, true);
            }
          } catch (final InterruptedException | IOException | XmlPullParserException e) {
            LOGGER.info("Could not fetch metadata for {} {}", config.name(), version.name());
            yield null;
          }
          final @Nullable Snapshot snapshot = metadata.getVersioning().getSnapshot();
          if (snapshot != null) {
            yield version.asset(String.format(
              "%s-%s-%s-%d-javadoc.jar",
              metadata.getArtifactId(),
              metadata.getVersion().replace("-SNAPSHOT", ""),
              snapshot.getTimestamp(),
              snapshot.getBuildNumber()
            ));
          } else {
            LOGGER.info("Could not find latest version for {} {}", config.name(), version.name());
            yield null;
          }
        }
      };
      if (jar != null) {
        final Path versionPath = basePath.resolve(version.name() + ".jar");
        try {
          Files.createDirectories(versionPath.getParent());
        } catch (final IOException e) {
          LOGGER.info("Could not update javadoc for {} {}", config.name(), version.name());
          continue;
        }
        try {
          final HttpResponse<InputStream> response = this.httpClient.send(
            HttpRequest.newBuilder()
              .GET()
              .uri(jar)
              .header(HttpHeaders.USER_AGENT, USER_AGENT)
              .build(),
            HttpResponse.BodyHandlers.ofInputStream()
          );
          try (
            final InputStream is = response.body();
            final OutputStream os = Files.newOutputStream(versionPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
          ) {
            is.transferTo(os);
          }
        } catch (final IOException | InterruptedException e) {
          LOGGER.info("Could not update javadoc for {} {}", config.name(), version.name());
          continue;
        }
        LOGGER.info("Updated javadoc for {} {}", config.name(), version.name());
      }
    }
  }

  public record Key(
    String project,
    String version
  ) {
  }
}
