/*
 * Copyright (c) 2015 The Jupiter Project
 *
 * Licensed under the Apache License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jupiter.rpc;

import org.jupiter.common.util.JServiceLoader;
import org.jupiter.common.util.Maps;
import org.jupiter.common.util.Reflects;
import org.jupiter.common.util.internal.logging.InternalLogger;
import org.jupiter.common.util.internal.logging.InternalLoggerFactory;
import org.jupiter.registry.*;
import org.jupiter.rpc.channel.CopyOnWriteGroupList;
import org.jupiter.rpc.channel.DirectoryJChannelGroup;
import org.jupiter.rpc.channel.JChannelGroup;
import org.jupiter.rpc.load.balance.LoadBalancer;

import java.util.Collection;
import java.util.concurrent.ConcurrentMap;

import static org.jupiter.common.util.JConstants.UNKNOWN_APP_NAME;
import static org.jupiter.common.util.Preconditions.checkNotNull;
import static org.jupiter.registry.RegisterMeta.Address;
import static org.jupiter.registry.RegisterMeta.ServiceMeta;

/**
 * jupiter
 * org.jupiter.rpc
 *
 * @author jiachun.fjc
 */
public abstract class AbstractJClient implements JClient {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractJClient.class);

    // 注册服务(SPI)
    private final RegistryService registryService = JServiceLoader.loadFirst(RegistryService.class);
    private final ConcurrentMap<UnresolvedAddress, JChannelGroup> addressGroups = Maps.newConcurrentHashMap();
    private final String appName;

    protected final DirectoryJChannelGroup directoryGroup = new DirectoryJChannelGroup();

    private volatile Class<LoadBalancer<JChannelGroup>> defaultLoadBalancerClass;

    public AbstractJClient() {
        this(UNKNOWN_APP_NAME);
    }

    public AbstractJClient(String appName) {
        this.appName = appName;
    }

    @Override
    public void connectToRegistryServer(String connectString) {
        registryService.connectToRegistryServer(connectString);
    }

    @Override
    public String appName() {
        return appName;
    }

    @Override
    public JChannelGroup group(UnresolvedAddress address) {
        checkNotNull(address, "address");

        JChannelGroup group = addressGroups.get(address);
        if (group == null) {
            JChannelGroup newGroup = newChannelGroup(address);
            group = addressGroups.putIfAbsent(address, newGroup);
            if (group == null) {
                group = newGroup;
            }
        }
        return group;
    }

    @SuppressWarnings("unchecked")
    @Override
    public LoadBalancer<JChannelGroup> newDefaultLoadBalancer() {
        if (defaultLoadBalancerClass == null) {
            LoadBalancer<JChannelGroup> firstInstance = JServiceLoader.loadFirst(LoadBalancer.class);
            defaultLoadBalancerClass = (Class<LoadBalancer<JChannelGroup>>) firstInstance.getClass();
            return firstInstance;
        }
        return Reflects.newInstance(defaultLoadBalancerClass);
    }

    @Override
    public Collection<JChannelGroup> groups() {
        return addressGroups.values();
    }

    @Override
    public boolean addChannelGroup(Directory directory, JChannelGroup group) {
        boolean added = directory(directory).addIfAbsent(group);
        if (added) {
            logger.info("Added channel group: {} to {}.", group, directory.directory());
        }
        return added;
    }

    @Override
    public boolean removeChannelGroup(Directory directory, JChannelGroup group) {
        CopyOnWriteGroupList groups = directory(directory);
        boolean removed = groups.remove(group);
        if (removed) {
            logger.warn("Removed channel group: {} in directory: {}.", group, directory.directory());
        }
        return removed;
    }

    @Override
    public CopyOnWriteGroupList directory(Directory directory) {
        return directoryGroup.find(directory);
    }

    @Override
    public boolean isDirectoryAvailable(Directory directory) {
        CopyOnWriteGroupList groups = directory(directory);
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).isAvailable()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Collection<RegisterMeta> lookup(Directory directory) {
        ServiceMeta serviceMeta = transformToServiceMeta(directory);

        return registryService.lookup(serviceMeta);
    }

    @Override
    public void subscribe(Directory directory, NotifyListener listener) {
        registryService.subscribe(transformToServiceMeta(directory), listener);
    }

    @Override
    public void offlineListening(UnresolvedAddress address, OfflineListener listener) {
        if (registryService instanceof AbstractRegistryService) {
            ((AbstractRegistryService) registryService).offlineListening(transformToAddress(address), listener);
        } else {
            throw new UnsupportedOperationException();
        }
    }

    protected abstract JChannelGroup newChannelGroup(UnresolvedAddress address);

    private static ServiceMeta transformToServiceMeta(Directory directory) {
        ServiceMeta serviceMeta = new ServiceMeta();
        serviceMeta.setGroup(checkNotNull(directory.getGroup(), "group"));
        serviceMeta.setVersion(checkNotNull(directory.getVersion(), "version"));
        serviceMeta.setServiceProviderName(checkNotNull(directory.getServiceProviderName(), "serviceProviderName"));

        return serviceMeta;
    }

    private static Address transformToAddress(UnresolvedAddress address) {
        return new Address(address.getHost(), address.getPort());
    }

    static {
        try {
            // touch off TracingUtil.<clinit>
            // because getLocalAddress() and getPid() sometimes too slow
            Class.forName("org.jupiter.rpc.tracing.TracingUtil");
        } catch (ClassNotFoundException ignored) {}
    }
}
