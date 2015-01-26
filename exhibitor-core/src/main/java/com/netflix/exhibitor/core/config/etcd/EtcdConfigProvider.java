/*
 *    Copyright 2015 Dan Gillespie
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.exhibitor.core.config.etcd;

import com.google.common.base.Preconditions;
import com.netflix.exhibitor.core.config.*;
import mousio.etcd4j.EtcdClient;
import mousio.etcd4j.promises.EtcdResponsePromise;
import mousio.etcd4j.requests.EtcdKeyGetRequest;
import mousio.etcd4j.responses.EtcdException;
import mousio.etcd4j.responses.EtcdKeysResponse;
import org.apache.curator.utils.ZKPaths;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Properties;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class EtcdConfigProvider implements ConfigProvider {
    private final EtcdClient client;
    private final Properties defaults;
    private final String hostname;
    private final String configPath;
    private final String lockPath;
    private final AtomicReference<State> state = new AtomicReference<State>(State.LATENT);

    private enum State {
        LATENT,
        STARTED,
        CLOSED
    }

    private static final String LOCK_PATH = "locks";
    private static final String CONFIG_PATH = "configs";

    public EtcdConfigProvider(EtcdClient client, String baseKey, Properties defaults, String hostname) {
        this.client = client;
        this.defaults = defaults;
        this.hostname = hostname;
        configPath = ZKPaths.makePath(baseKey, CONFIG_PATH);
        lockPath = ZKPaths.makePath(baseKey, LOCK_PATH);
    }

    @Override
    public void start() throws Exception {
        Preconditions.checkState(state.compareAndSet(State.LATENT, State.STARTED), "Already started");
    }

    @Override
    public void close() throws IOException {
        state.set(State.CLOSED);
    }

    @Override
    public LoadedInstanceConfig loadConfig() throws Exception {
        long version = 0;
        Properties properties = new Properties();
        EtcdKeysResponse.EtcdNode configNode = getConfigNode();
        if (configNode != null) {
            version = configNode.modifiedIndex;
            properties.load(new ByteArrayInputStream(configNode.value.getBytes("UTF-8")));
        }
        PropertyBasedInstanceConfig config = new PropertyBasedInstanceConfig(properties, defaults);
        return new LoadedInstanceConfig(config, version);
    }

    @Override
    public LoadedInstanceConfig storeConfig(ConfigCollection config, long compareVersion) throws Exception {
        PropertyBasedInstanceConfig propertyConfig = new PropertyBasedInstanceConfig(config);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        propertyConfig.getProperties().store(out, "Auto-generated by Exhibitor " + hostname);

        String data = new String(out.toByteArray(), "UTF-8");
        long newVersion;
        try {
            EtcdKeysResponse response = client.put(configPath, data).prevIndex(compareVersion).send().get();
            newVersion = response.node.modifiedIndex;
        } catch (EtcdException e) {
            if (e.errorCode == 101) {
                // updated by another process first
                return null;
            }
            throw new RuntimeException("The shared configuration failed to be stored", e.getCause());
        }
        return new LoadedInstanceConfig(propertyConfig, newVersion);
    }

    @Override
    public PseudoLock newPseudoLock() throws Exception {
        return new EtcdPseudoLock(client, lockPath, hostname);
    }

    private EtcdKeysResponse.EtcdNode getConfigNode() throws EtcdException, TimeoutException, IOException {
        EtcdKeyGetRequest request = client.get(configPath);

        EtcdResponsePromise<EtcdKeysResponse> promise = request.send();
        EtcdKeysResponse response = promise.get();
        return response.node;
    }
}