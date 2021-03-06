/*
 * Copyright 2018 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.gradle;

import com.google.cloud.tools.jib.JibLogger;
import com.google.cloud.tools.jib.configuration.BuildConfiguration;
import com.google.cloud.tools.jib.configuration.CacheConfiguration;
import com.google.cloud.tools.jib.configuration.ContainerConfiguration;
import com.google.cloud.tools.jib.configuration.ImageConfiguration;
import com.google.cloud.tools.jib.configuration.credentials.Credential;
import com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory;
import com.google.cloud.tools.jib.frontend.ExposedPortsParser;
import com.google.cloud.tools.jib.frontend.JavaEntrypointConstructor;
import com.google.cloud.tools.jib.image.ImageReference;
import com.google.cloud.tools.jib.image.InvalidImageReferenceException;
import com.google.cloud.tools.jib.plugins.common.ConfigurationPropertyValidator;
import com.google.cloud.tools.jib.plugins.common.DefaultCredentialRetrievers;
import com.google.cloud.tools.jib.registry.RegistryClient;
import java.time.Instant;
import java.util.List;
import javax.annotation.Nullable;
import org.gradle.api.GradleException;

/** Configures and provides builders for the image building tasks. */
class PluginConfigurationProcessor {

  /** {@code User-Agent} header suffix to send to the registry. */
  private static final String USER_AGENT_SUFFIX = "jib-gradle-plugin";

  /**
   * Sets up {@link BuildConfiguration} that is common among the image building tasks. This includes
   * setting up the base image reference/authorization, container configuration, cache
   * configuration, and layer configuration.
   *
   * @param logger the logger used to display messages.
   * @param jibExtension the {@link JibExtension} providing the configuration data
   * @param projectProperties used for providing additional information
   * @return a new {@link PluginConfigurationProcessor} containing pre-configured builders
   * @throws InvalidImageReferenceException if parsing the base image configuration fails
   */
  static PluginConfigurationProcessor processCommonConfiguration(
      JibLogger logger, JibExtension jibExtension, GradleProjectProperties projectProperties)
      throws InvalidImageReferenceException {
    jibExtension.handleDeprecatedParameters(logger);
    ConfigurationPropertyValidator.checkHttpTimeoutProperty(GradleException::new);

    // TODO: Instead of disabling logging, have authentication credentials be provided
    GradleJibLogger.disableHttpLogging();
    RegistryClient.setUserAgentSuffix(USER_AGENT_SUFFIX);

    ImageReference baseImage = ImageReference.parse(jibExtension.getBaseImage());

    if (Boolean.getBoolean("sendCredentialsOverHttp")) {
      logger.warn(
          "Authentication over HTTP is enabled. It is strongly recommended that you do not enable "
              + "this on a public network!");
    }
    DefaultCredentialRetrievers defaultCredentialRetrievers =
        DefaultCredentialRetrievers.init(CredentialRetrieverFactory.forImage(baseImage, logger));
    Credential fromCredential =
        ConfigurationPropertyValidator.getImageCredential(
            logger,
            "jib.from.auth.username",
            "jib.from.auth.password",
            jibExtension.getFrom().getAuth());
    if (fromCredential != null) {
      defaultCredentialRetrievers.setKnownCredential(fromCredential, "jib.from.auth");
    }
    defaultCredentialRetrievers.setCredentialHelperSuffix(jibExtension.getFrom().getCredHelper());

    ImageConfiguration.Builder baseImageConfigurationBuilder =
        ImageConfiguration.builder(baseImage)
            .setCredentialRetrievers(defaultCredentialRetrievers.asList());

    List<String> entrypoint = jibExtension.getContainer().getEntrypoint();
    if (entrypoint.isEmpty()) {
      String mainClass = projectProperties.getMainClass(jibExtension);
      entrypoint =
          JavaEntrypointConstructor.makeDefaultEntrypoint(jibExtension.getJvmFlags(), mainClass);
    } else if (jibExtension.getMainClass() != null || !jibExtension.getJvmFlags().isEmpty()) {
      logger.warn("mainClass and jvmFlags are ignored when entrypoint is specified");
    }
    ContainerConfiguration.Builder containerConfigurationBuilder =
        ContainerConfiguration.builder()
            .setEntrypoint(entrypoint)
            .setProgramArguments(jibExtension.getArgs())
            .setExposedPorts(ExposedPortsParser.parse(jibExtension.getExposedPorts()))
            .setLabels(jibExtension.getLabels());
    if (jibExtension.getUseCurrentTimestamp()) {
      logger.warn(
          "Setting image creation time to current time; your image may not be reproducible.");
      containerConfigurationBuilder.setCreationTime(Instant.now());
    }

    BuildConfiguration.Builder buildConfigurationBuilder =
        BuildConfiguration.builder(logger)
            .setCreatedBy("jib-gradle-plugin")
            .setAllowInsecureRegistries(jibExtension.getAllowInsecureRegistries())
            .setLayerConfigurations(
                projectProperties.getJavaLayerConfigurations().getLayerConfigurations());
    CacheConfiguration applicationLayersCacheConfiguration =
        CacheConfiguration.forPath(projectProperties.getCacheDirectory());
    buildConfigurationBuilder.setApplicationLayersCacheConfiguration(
        applicationLayersCacheConfiguration);
    if (jibExtension.getUseOnlyProjectCache()) {
      buildConfigurationBuilder.setBaseImageLayersCacheConfiguration(
          applicationLayersCacheConfiguration);
    }

    return new PluginConfigurationProcessor(
        buildConfigurationBuilder,
        baseImageConfigurationBuilder,
        containerConfigurationBuilder,
        fromCredential);
  }

  private final BuildConfiguration.Builder buildConfigurationBuilder;
  private final ImageConfiguration.Builder baseImageConfigurationBuilder;
  private final ContainerConfiguration.Builder containerConfigurationBuilder;
  @Nullable private final Credential baseImageCredential;

  private PluginConfigurationProcessor(
      BuildConfiguration.Builder buildConfigurationBuilder,
      ImageConfiguration.Builder baseImageConfigurationBuilder,
      ContainerConfiguration.Builder containerConfigurationBuilder,
      @Nullable Credential baseImageCredential) {
    this.buildConfigurationBuilder = buildConfigurationBuilder;
    this.baseImageConfigurationBuilder = baseImageConfigurationBuilder;
    this.containerConfigurationBuilder = containerConfigurationBuilder;
    this.baseImageCredential = baseImageCredential;
  }

  BuildConfiguration.Builder getBuildConfigurationBuilder() {
    return buildConfigurationBuilder;
  }

  ImageConfiguration.Builder getBaseImageConfigurationBuilder() {
    return baseImageConfigurationBuilder;
  }

  ContainerConfiguration.Builder getContainerConfigurationBuilder() {
    return containerConfigurationBuilder;
  }

  @Nullable
  Credential getBaseImageCredential() {
    return baseImageCredential;
  }
}
