/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.central.repo;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.BaseEncoding;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.repo.AgentDao.AgentConfigUpdate;
import org.glowroot.central.util.Cache;
import org.glowroot.central.util.Cache.CacheLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Sessions;
import org.glowroot.common.repo.ConfigRepository.OptimisticLockException;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertKind;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;

import static com.google.common.base.Preconditions.checkNotNull;

// TODO agent config records never expire for abandoned agent rollup ids
public class ConfigDao {

    private static final Logger logger = LoggerFactory.getLogger(ConfigDao.class);

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private static final Random random = new Random();

    private final Session session;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;
    private final PreparedStatement readForUpdatePS;
    private final PreparedStatement updatePS;
    private final PreparedStatement markUpdatedPS;

    private final Cache<String, Optional<AgentConfig>> agentConfigCache;

    public ConfigDao(Session session, ClusterManager clusterManager) throws Exception {
        this.session = session;

        Sessions.execute(session, "create table if not exists config (agent_rollup_id varchar,"
                + " config blob, config_update boolean, config_update_token uuid, primary key"
                + " (agent_rollup_id)) " + WITH_LCS);
        // secondary index is needed for Cassandra 2.x (to avoid error on readUpdatePS)
        Sessions.execute(session,
                "create index if not exists config_update_idx on config (config_update)");

        insertPS = session.prepare("insert into config (agent_rollup_id, config, config_update,"
                + " config_update_token) values (?, ?, ?, ?)");
        updatePS = session.prepare("update config set config = ?, config_update = ?,"
                + " config_update_token = ? where agent_rollup_id = ? if config = ?");
        readPS = session.prepare("select config from config where agent_rollup_id = ?");

        readForUpdatePS = session.prepare("select config, config_update_token from config"
                + " where agent_rollup_id = ? and config_update = true allow filtering");
        markUpdatedPS = session.prepare("update config set config_update = false,"
                + " config_update_token = null where agent_rollup_id = ?"
                + " if config_update_token = ?");

        agentConfigCache =
                clusterManager.createCache("agentConfigCache", new AgentConfigCacheLoader());
    }

    public AgentConfig store(String agentId, @Nullable String agentRollupId,
            AgentConfig agentConfig) throws Exception {
        AgentConfig existingAgentConfig = read(agentId);
        AgentConfig updatedAgentConfig;
        if (existingAgentConfig == null) {
            updatedAgentConfig = generateNewIds(agentConfig);
        } else {
            // sync list of plugin properties, central property values win
            Map<String, PluginConfig> existingPluginConfigs = Maps.newHashMap();
            for (PluginConfig existingPluginConfig : existingAgentConfig.getPluginConfigList()) {
                existingPluginConfigs.put(existingPluginConfig.getId(), existingPluginConfig);
            }
            List<PluginConfig> pluginConfigs = Lists.newArrayList();
            for (PluginConfig agentPluginConfig : agentConfig.getPluginConfigList()) {
                PluginConfig existingPluginConfig =
                        existingPluginConfigs.get(agentPluginConfig.getId());
                if (existingPluginConfig == null) {
                    pluginConfigs.add(agentPluginConfig);
                    continue;
                }
                Map<String, PluginProperty> existingProperties = Maps.newHashMap();
                for (PluginProperty existingProperty : existingPluginConfig.getPropertyList()) {
                    existingProperties.put(existingProperty.getName(), existingProperty);
                }
                List<PluginProperty> properties = Lists.newArrayList();
                for (PluginProperty agentProperty : agentPluginConfig.getPropertyList()) {
                    PluginProperty existingProperty =
                            existingProperties.get(agentProperty.getName());
                    if (existingProperty == null) {
                        properties.add(agentProperty);
                        continue;
                    }
                    // overlay existing property value
                    properties.add(agentProperty.toBuilder()
                            .setValue(existingProperty.getValue())
                            .build());
                }
                pluginConfigs.add(PluginConfig.newBuilder()
                        .setId(agentPluginConfig.getId())
                        .setName(agentPluginConfig.getName())
                        .addAllProperty(properties)
                        .build());
            }
            updatedAgentConfig = existingAgentConfig.toBuilder()
                    .clearPluginConfig()
                    .addAllPluginConfig(pluginConfigs)
                    .build();
        }
        if (existingAgentConfig == null || !updatedAgentConfig.equals(agentConfig)) {
            BoundStatement boundStatement = insertPS.bind();
            int i = 0;
            boundStatement.setString(i++, agentId);
            boundStatement.setBytes(i++, ByteBuffer.wrap(updatedAgentConfig.toByteArray()));
            // setting config_update to false as this method is only called by collectInit(), and
            // agent will not consider collectInit() to be successful until it receives updated
            // agent config
            boundStatement.setBool(i++, false);
            boundStatement.setToNull(i++);
            Sessions.execute(session, boundStatement);
            agentConfigCache.invalidate(agentId);
        }
        if (agentRollupId != null) {
            List<String> agentRollupIds = AgentDao.getAgentRollupIds(agentRollupId);
            for (String loopAgentRollupId : agentRollupIds) {
                if (read(loopAgentRollupId) != null) {
                    continue;
                }
                // there is no config for rollup yet
                // so insert initial config propagating ui config and advanced config properties
                // that pertain to rollups
                BoundStatement boundStatement = insertPS.bind();
                int i = 0;
                boundStatement.setString(i++, loopAgentRollupId);
                AdvancedConfig advancedConfig = updatedAgentConfig.getAdvancedConfig();
                boundStatement.setBytes(i++, ByteBuffer.wrap(AgentConfig.newBuilder()
                        .setUiConfig(updatedAgentConfig.getUiConfig())
                        .setAdvancedConfig(AdvancedConfig.newBuilder()
                                .setMaxAggregateQueriesPerType(
                                        advancedConfig.getMaxAggregateQueriesPerType())
                                .setMaxAggregateServiceCallsPerType(
                                        advancedConfig.getMaxAggregateServiceCallsPerType()))
                        .build()
                        .toByteArray()));
                boundStatement.setBool(i++, false);
                boundStatement.setToNull(i++);
                Sessions.execute(session, boundStatement);
                agentConfigCache.invalidate(loopAgentRollupId);
            }
        }
        return updatedAgentConfig;
    }

    void update(String agentRollupId, AgentConfigUpdater agentConfigUpdater)
            throws Exception {
        for (int j = 0; j < 10; j++) {
            BoundStatement boundStatement = readPS.bind();
            boundStatement.setString(0, agentRollupId);
            ResultSet results = Sessions.execute(session, boundStatement);
            Row row = checkNotNull(results.one());
            ByteString currValue = ByteString.copyFrom(checkNotNull(row.getBytes(0)));
            AgentConfig currAgentConfig = AgentConfig.parseFrom(currValue);

            AgentConfig updatedAgentConfig = agentConfigUpdater.updateAgentConfig(currAgentConfig);

            boundStatement = updatePS.bind();
            int i = 0;
            boundStatement.setBytes(i++, ByteBuffer.wrap(updatedAgentConfig.toByteArray()));
            boundStatement.setBool(i++, true);
            boundStatement.setUUID(i++, UUIDs.random());
            boundStatement.setString(i++, agentRollupId);

            boundStatement.setBytes(i++, ByteBuffer.wrap(currValue.toByteArray()));
            results = Sessions.execute(session, boundStatement);
            row = checkNotNull(results.one());
            boolean applied = row.getBool("[applied]");
            if (applied) {
                agentConfigCache.invalidate(agentRollupId);
                return;
            }
            Thread.sleep(200);
        }
        throw new OptimisticLockException();
    }

    @Nullable
    AgentConfig read(String agentRollupId) throws Exception {
        return agentConfigCache.get(agentRollupId).orNull();
    }

    // does not apply to agent rollups
    public @Nullable AgentConfigUpdate readForUpdate(String agentId) throws Exception {
        BoundStatement boundStatement = readForUpdatePS.bind();
        boundStatement.setString(0, agentId);
        ResultSet results = Sessions.execute(session, boundStatement);
        Row row = results.one();
        if (row == null) {
            // no pending config update for this agent (or agent has been manually deleted)
            return null;
        }
        ByteBuffer bytes = checkNotNull(row.getBytes(0));
        UUID configUpdateToken = checkNotNull(row.getUUID(1));
        return ImmutableAgentConfigUpdate.builder()
                .config(AgentConfig.parseFrom(ByteString.copyFrom(bytes)))
                .configUpdateToken(configUpdateToken)
                .build();
    }

    // does not apply to agent rollups
    public void markUpdated(String agentId, UUID configUpdateToken) throws Exception {
        BoundStatement boundStatement = markUpdatedPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setUUID(i++, configUpdateToken);
        Sessions.execute(session, boundStatement);
    }

    static String generateNewId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return BaseEncoding.base16().lowerCase().encode(bytes);
    }

    // generate new synthetic monitor ids (which are stored agent side in order to reference
    // from alerts) to allow copying from another deployment
    @VisibleForTesting
    static AgentConfig generateNewIds(AgentConfig agentConfig) {
        AgentConfig.Builder builder = AgentConfig.newBuilder(agentConfig);
        builder.clearSyntheticMonitorConfig();
        Map<String, String> syntheticMonitorIdMap = Maps.newHashMap();
        for (AgentConfig.SyntheticMonitorConfig config : agentConfig
                .getSyntheticMonitorConfigList()) {
            String newId = generateNewId();
            builder.addSyntheticMonitorConfig(config.toBuilder()
                    .setId(newId));
            syntheticMonitorIdMap.put(config.getId(), newId);
        }
        builder.clearAlertConfig();
        for (AgentConfig.AlertConfig alertConfig : agentConfig.getAlertConfigList()) {
            if (alertConfig.getKind() == AlertKind.SYNTHETIC_MONITOR) {
                String id = alertConfig.getSyntheticMonitorId();
                String newId = syntheticMonitorIdMap.get(id);
                if (newId == null) {
                    logger.warn("synthetic monitor id not found: {}", id);
                    continue;
                }
                builder.addAlertConfig(alertConfig.toBuilder()
                        .setSyntheticMonitorId(newId));
            } else {
                builder.addAlertConfig(alertConfig);
            }
        }
        return builder.build();
    }

    private class AgentConfigCacheLoader implements CacheLoader<String, Optional<AgentConfig>> {
        @Override
        public Optional<AgentConfig> load(String agentRollupId) throws Exception {
            BoundStatement boundStatement = readPS.bind();
            boundStatement.setString(0, agentRollupId);
            ResultSet results = Sessions.execute(session, boundStatement);
            Row row = results.one();
            if (row == null) {
                // agent must have been manually deleted
                return Optional.absent();
            }
            ByteBuffer bytes = checkNotNull(row.getBytes(0));
            return Optional.of(AgentConfig.parseFrom(ByteString.copyFrom(bytes)));
        }
    }

    interface AgentConfigUpdater {
        AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception;
    }
}
