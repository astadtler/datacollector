/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.datacollector.restapi.bean;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.streamsets.pipeline.api.impl.Utils;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PipelineConfigurationJson implements Serializable{

  private final com.streamsets.datacollector.config.PipelineConfiguration pipelineConfiguration;

  @SuppressWarnings("unchecked")
  public PipelineConfigurationJson(
    @JsonProperty("schemaVersion") int schemaVersion,
    @JsonProperty("version") int version,
    @JsonProperty("uuid") UUID uuid,
    @JsonProperty("description") String description,
    @JsonProperty("configuration") List<ConfigConfigurationJson> configuration,
    @JsonProperty("uiInfo") Map<String, Object> uiInfo,
    @JsonProperty("stages") List<StageConfigurationJson> stages,
    @JsonProperty("errorStage") StageConfigurationJson errorStage,
    @JsonProperty("info") PipelineInfoJson pipelineInfo) {
    version = (version == 0) ? 1 : version;
    this.pipelineConfiguration = new com.streamsets.datacollector.config.PipelineConfiguration(schemaVersion, version,
      uuid, description,
      BeanHelper.unwrapConfigConfiguration(configuration), uiInfo, BeanHelper.unwrapStageConfigurations(stages),
      BeanHelper.unwrapStageConfiguration(errorStage));
    this.pipelineConfiguration.setPipelineInfo(BeanHelper.unwrapPipelineInfo(pipelineInfo));
  }

  public PipelineConfigurationJson(com.streamsets.datacollector.config.PipelineConfiguration pipelineConfiguration) {
    Utils.checkNotNull(pipelineConfiguration, "pipelineConfiguration");
    this.pipelineConfiguration = pipelineConfiguration;
  }

  public int getSchemaVersion() {
    return pipelineConfiguration.getSchemaVersion();
  }

  public int getVersion() {
    return pipelineConfiguration.getVersion();
  }

  public String getDescription() {
    return pipelineConfiguration.getDescription();
  }

  public PipelineInfoJson getInfo() {
    return BeanHelper.wrapPipelineInfo(pipelineConfiguration.getInfo());
  }

  public List<StageConfigurationJson> getStages() {
    return BeanHelper.wrapStageConfigurations(pipelineConfiguration.getStages());
  }

  public StageConfigurationJson getErrorStage() {
    return BeanHelper.wrapStageConfiguration(pipelineConfiguration.getErrorStage());
  }

  public UUID getUuid() {
    return pipelineConfiguration.getUuid();
  }

  public IssuesJson getIssues() {
    return BeanHelper.wrapIssues(pipelineConfiguration.getIssues());
  }

  public boolean isValid() {
    return pipelineConfiguration.isValid();
  }

  public boolean isPreviewable() {
    return pipelineConfiguration.isPreviewable();
  }

  public List<ConfigConfigurationJson> getConfiguration() {
    return BeanHelper.wrapConfigConfiguration(pipelineConfiguration.getConfiguration());
  }

  public Map<String, Object> getUiInfo() {
    return pipelineConfiguration.getUiInfo();
  }

  @JsonIgnore
  public com.streamsets.datacollector.config.PipelineConfiguration getPipelineConfiguration() {
    return pipelineConfiguration;
  }
}