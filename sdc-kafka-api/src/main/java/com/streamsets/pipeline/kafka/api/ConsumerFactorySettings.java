/**
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.kafka.api;

import com.streamsets.pipeline.api.Source;

import java.util.Map;

public class ConsumerFactorySettings {

  private final String zookeeperConnect;
  private final String topic;
  private final int maxWaitTime;
  private final Source.Context context;
  private final Map<String, String> kafkaConsumerConfigs;
  private final String consumerGroup;

  public ConsumerFactorySettings(
    String zookeeperConnect,
    String topic,
    int maxWaitTime,
    Source.Context context,
    Map<String, String> kafkaConsumerConfigs,
    String consumerGroup
  ) {
    this.zookeeperConnect = zookeeperConnect;
    this.topic = topic;
    this.maxWaitTime = maxWaitTime;
    this.context = context;
    this.kafkaConsumerConfigs = kafkaConsumerConfigs;
    this.consumerGroup = consumerGroup;
  }

  public String getZookeeperConnect() {
    return zookeeperConnect;
  }

  public String getTopic() {
    return topic;
  }

  public int getMaxWaitTime() {
    return maxWaitTime;
  }

  public Source.Context getContext() {
    return context;
  }

  public Map<String, String> getKafkaConsumerConfigs() {
    return kafkaConsumerConfigs;
  }

  public String getConsumerGroup() {
    return consumerGroup;
  }
}