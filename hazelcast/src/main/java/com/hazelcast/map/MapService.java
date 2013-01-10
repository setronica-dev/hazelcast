/*
 * Copyright (c) 2008-2012, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.map;

import com.hazelcast.client.ClientCommandHandler;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MapServiceConfig;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryListener;
import com.hazelcast.core.Member;
import com.hazelcast.logging.ILogger;
import com.hazelcast.map.client.MapGetHandler;
import com.hazelcast.map.proxy.DataMapProxy;
import com.hazelcast.map.proxy.MapProxy;
import com.hazelcast.map.proxy.ObjectMapProxy;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.partition.MigrationEndpoint;
import com.hazelcast.partition.MigrationType;
import com.hazelcast.partition.PartitionInfo;
import com.hazelcast.spi.*;
import com.hazelcast.spi.exception.TransactionException;
import com.hazelcast.spi.impl.NodeEngineImpl;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public class MapService implements ManagedService, MigrationAwareService, MembershipAwareService,
        TransactionalService, RemoteService, EventPublishingService<EventData, EntryListener>, ClientProtocolService {

    public final static String MAP_SERVICE_NAME = MapServiceConfig.SERVICE_NAME;

    private final ILogger logger;
    private final AtomicLong counter = new AtomicLong(new Random().nextLong());
    private final PartitionContainer[] partitionContainers;
    private final NodeEngineImpl nodeEngine;
    private final ConcurrentMap<String, MapProxy> proxies = new ConcurrentHashMap<String, MapProxy>();
    private final ConcurrentMap<String, MapInfo> mapInfos = new ConcurrentHashMap<String, MapInfo>();
    private final Map<String, ClientCommandHandler> commandHandlers = new HashMap<String, ClientCommandHandler>();
    private final ConcurrentMap<ListenerKey, String> eventRegistrations;
    private final ScheduledThreadPoolExecutor recordTaskExecutor;


    public MapService(final NodeEngine nodeEngine) {
        this.nodeEngine = (NodeEngineImpl) nodeEngine;
        this.logger = nodeEngine.getLogger(MapService.class.getName());
        partitionContainers = new PartitionContainer[nodeEngine.getPartitionCount()];
        eventRegistrations = new ConcurrentHashMap<ListenerKey, String>();
        recordTaskExecutor = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(10);
        recordTaskExecutor.setMaximumPoolSize(50);
    }

    public void init(NodeEngine nodeEngine, Properties properties) {
        int partitionCount = nodeEngine.getPartitionCount();
        for (int i = 0; i < partitionCount; i++) {
            PartitionInfo partition = nodeEngine.getPartitionInfo(i);
            partitionContainers[i] = new PartitionContainer(this, partition);
        }
        registerClientOperationHandlers();
    }

    public MapInfo getMapInfo(String mapName){
        MapInfo mapInfo = mapInfos.get(mapName);
        if(mapInfo == null) {
            mapInfo = new MapInfo(mapName, nodeEngine.getConfig().getMapConfig(mapName));
            mapInfos.put(mapName, mapInfo);
        }
        return mapInfo;
    }

    private void registerClientOperationHandlers() {
        registerHandler("MGET", new MapGetHandler(this));
    }

    void registerHandler(String command, ClientCommandHandler handler) {
        commandHandlers.put(command, handler);
    }

    public PartitionContainer getPartitionContainer(int partitionId) {
        return partitionContainers[partitionId];
    }

    public RecordStore getRecordStore(int partitionId, String mapName) {
        return getPartitionContainer(partitionId).getRecordStore(mapName);
    }

    public long nextId() {
        return counter.incrementAndGet();
    }

    public void beforeMigration(MigrationServiceEvent event) {
        // TODO: what if partition has transactions?
    }

    public Operation prepareMigrationOperation(MigrationServiceEvent event) {
        if (event.getPartitionId() < 0 || event.getPartitionId() >= nodeEngine.getPartitionCount()) {
            return null;
        }
        final PartitionContainer container = partitionContainers[event.getPartitionId()];
        return new MapMigrationOperation(container, event.getPartitionId(), event.getReplicaIndex(), false);
    }

    public void commitMigration(MigrationServiceEvent event) {
        logger.log(Level.FINEST, "Committing " + event);
        if (event.getMigrationEndpoint() == MigrationEndpoint.SOURCE) {
            if (event.getMigrationType() == MigrationType.MOVE) {
                clearPartitionData(event.getPartitionId());
            } else if (event.getMigrationType() == MigrationType.MOVE_COPY_BACK) {
                final PartitionContainer container = partitionContainers[event.getPartitionId()];
                for (DefaultRecordStore mapPartition : container.maps.values()) {
                    final MapConfig mapConfig = getMapInfo(mapPartition.name).getMapConfig();
                    if (mapConfig.getTotalBackupCount() < event.getCopyBackReplicaIndex()) {
                        mapPartition.clear();
                    }
                }
            }
        }
    }

    public void rollbackMigration(MigrationServiceEvent event) {
        logger.log(Level.FINEST, "Rolling back " + event);
        if (event.getMigrationEndpoint() == MigrationEndpoint.DESTINATION) {
            clearPartitionData(event.getPartitionId());
        }
    }

    public int getMaxBackupCount() {
        int max = 1;
        for (PartitionContainer container : partitionContainers) {
            max = Math.max(max, container.getMaxBackupCount());
        }
        return max;
    }

    private void clearPartitionData(final int partitionId) {
        logger.log(Level.FINEST, "Clearing partition data -> " + partitionId);
        final PartitionContainer container = partitionContainers[partitionId];
        for (DefaultRecordStore mapPartition : container.maps.values()) {
            mapPartition.clear();
        }
        container.maps.clear();
        container.transactions.clear(); // TODO: not sure?
    }

    public Record createRecord(String name, Data dataKey, Data valueData, long ttl) {
        // todo based on map config choose the record impl
        DefaultRecord record = new DefaultRecord(nextId(), dataKey, valueData);
        MapInfo mapInfo = getMapInfo(name);
        if (ttl <= 0 && mapInfo.getMapConfig().getTimeToLiveSeconds() > 0) {
            record.getState().updateTtlExpireTime(mapInfo.getMapConfig().getTimeToLiveSeconds());
            scheduleOperation(name, dataKey, mapInfo.getMapConfig().getTimeToLiveSeconds());
        }
        if (mapInfo.getMapConfig().getMaxIdleSeconds() > 0) {
            record.getState().updateIdleExpireTime(mapInfo.getMapConfig().getMaxIdleSeconds());
            scheduleOperation(name, dataKey, mapInfo.getMapConfig().getMaxIdleSeconds());
        }
        return record;
    }

    public void prepare(String txnId, int partitionId) throws TransactionException {
        System.out.println(nodeEngine.getThisAddress() + " MapService prepare " + txnId);
        PartitionContainer pc = partitionContainers[partitionId];
        TransactionLog txnLog = pc.getTransactionLog(txnId);
        int maxBackupCount = 1; //txnLog.getMaxBackupCount();
        try {
            nodeEngine.getOperationService().takeBackups(MAP_SERVICE_NAME, new MapTxnBackupPrepareOperation(txnLog), 0, partitionId,
                    maxBackupCount, 60);
        } catch (Exception e) {
            throw new TransactionException(e);
        }
    }

    public void commit(String txnId, int partitionId) throws TransactionException {
        System.out.println(nodeEngine.getThisAddress() + " MapService commit " + txnId);
        getPartitionContainer(partitionId).commit(txnId);
        int maxBackupCount = 1; //txnLog.getMaxBackupCount();
        try {
            nodeEngine.getOperationService().takeBackups(MAP_SERVICE_NAME, new MapTxnBackupCommitOperation(txnId), 0, partitionId,
                    maxBackupCount, 60);
        } catch (Exception ignored) {
            //commit can never fail
        }
    }

    public void rollback(String txnId, int partitionId) throws TransactionException {
        System.out.println(nodeEngine.getThisAddress() + " MapService commit " + txnId);
        getPartitionContainer(partitionId).rollback(txnId);
        int maxBackupCount = 1; //txnLog.getMaxBackupCount();
        try {
            nodeEngine.getOperationService().takeBackups(MAP_SERVICE_NAME, new MapTxnBackupRollbackOperation(txnId), 0, partitionId,
                    maxBackupCount, 60);
        } catch (Exception e) {
            throw new TransactionException(e);
        }
    }

    public NodeEngine getNodeEngine() {
        return nodeEngine;
    }

    public String getName() {
        return MAP_SERVICE_NAME;
    }

    public MapProxy getProxy(Object... params) {
        final String name = String.valueOf(params[0]);
        if (params.length > 1 && Boolean.TRUE.equals(params[1])) {
            return new DataMapProxy(name, this, nodeEngine);
        }
        MapProxy proxy = proxies.get(name);
        if (proxy == null) {
            proxy = new ObjectMapProxy(name, this, nodeEngine);
            final MapProxy currentProxy = proxies.putIfAbsent(name, proxy);
            proxy = currentProxy != null ? currentProxy : proxy;
        }
        return proxy;
    }

    public Collection<ServiceProxy> getProxies() {
        return new HashSet<ServiceProxy>(proxies.values());
    }

    public void memberAdded(final MembershipServiceEvent membershipEvent) {
    }

    public void memberRemoved(final MembershipServiceEvent membershipEvent) {
        // submit operations to partition threads to;
        // * release locks
        // * rollback transaction
        // * do not know ?
    }

    public void destroy() {
    }

    public Map<String, ClientCommandHandler> getCommandMap() {
        return commandHandlers;
    }

    public void publishEvent(Address caller, String mapName, int eventType, Data dataKey, Data dataOldValue, Data dataValue) {
        Collection<EventRegistration> candidates = nodeEngine.getEventService().getRegistrations(MAP_SERVICE_NAME, mapName);
        Set<EventRegistration> registrationsWithValue = new HashSet<EventRegistration>();
        Set<EventRegistration> registrationsWithoutValue = new HashSet<EventRegistration>();
        for (EventRegistration candidate : candidates) {
            EntryEventFilter filter = (EntryEventFilter) candidate.getFilter();
            if (filter.eval(dataKey)) {
                if (filter.isIncludeValue()) {
                    registrationsWithValue.add(candidate);
                } else {
                    registrationsWithoutValue.add(candidate);
                }
            }
        }
        if (registrationsWithValue.isEmpty() && registrationsWithoutValue.isEmpty())
            return;
        String source = nodeEngine.getNode().address.toString();
        EventData event = new EventData(source, caller,  dataKey, dataValue,
                dataOldValue, eventType);

        nodeEngine.getEventService().publishEvent(MAP_SERVICE_NAME, registrationsWithValue, event);
        nodeEngine.getEventService().publishEvent(MAP_SERVICE_NAME, registrationsWithoutValue, event.cloneWithoutValues());
    }

    public void addEventListener(EntryListener entryListener, EventFilter eventFilter, String mapName) {
        EventRegistration registration = nodeEngine.getEventService().registerListener(MAP_SERVICE_NAME, mapName, eventFilter, entryListener);
        eventRegistrations.put(new ListenerKey(entryListener, ((EntryEventFilter) eventFilter).getKey()), registration.getId());
    }

    public void removeEventListener(EntryListener entryListener, String mapName, Object key) {
        String registrationId = eventRegistrations.get(new ListenerKey(entryListener, key));
        nodeEngine.getEventService().deregisterListener(MAP_SERVICE_NAME, mapName, registrationId);
    }

    private Data toData(Object obj) {
        return nodeEngine.getSerializationService().toData(obj);
    }

    private Object toObject(Data data) {
        return nodeEngine.getSerializationService().toObject(data);
    }

    public void dispatchEvent(EventData eventData, EntryListener listener) {
        Member member = nodeEngine.getClusterService().getMember(eventData.getCaller());
        EntryEvent event = null;

        if(eventData.getDataOldValue() == null) {
            event = new EntryEvent(eventData.getSource(), member, eventData.getEventType(), toObject(eventData.getDataKey()), toObject(eventData.getDataNewValue()) );
        }
        else {
            event = new EntryEvent(eventData.getSource(), member, eventData.getEventType(), toObject(eventData.getDataKey()), toObject(eventData.getDataOldValue()), toObject(eventData.getDataNewValue()) );
        }

        switch (event.getEventType()) {
            case ADDED:
                listener.entryAdded(event);
                break;
            case EVICTED:
                listener.entryEvicted(event);
                break;
            case UPDATED:
                listener.entryUpdated(event);
                break;
            case REMOVED:
                listener.entryRemoved(event);
                break;
        }
    }


    public void scheduleOperation(String mapName, Data key, long executeTime) {
        MapRecordStateOperation stateOperation = new MapRecordStateOperation(mapName, key);
        MapRecordTask recordTask = new MapRecordTask(nodeEngine, stateOperation, nodeEngine.getPartitionId(key));
        recordTaskExecutor.schedule(recordTask, executeTime, TimeUnit.MILLISECONDS);
    }

    private class CleanupTask implements Runnable {
        public void run() {
            cleanExpiredRecords();
            executeDelayedMapStoreOperations();
        }

        private void executeDelayedMapStoreOperations() {
//            List<DelayedOperation> removeList = new ArrayList<DelayedOperation>();
//            for (DelayedOperation operation : delayedMapStoreOperations) {
//                if(operation.shouldExecute()) {
//                    operation.execute();
//                    removeList.add(operation);
//                }
//            }
//            for (DelayedOperation operation : removeList) {
//                delayedMapStoreOperations.remove(operation);
//            }
        }

        private void cleanExpiredRecords() {

//            Node node = nodeEngine.getNode();
//            for (int i = 0; i < nodeEngine.getPartitionCount(); i++) {
//                Address owner = node.partitionService.getPartitionOwner(i);
//                if (node.address.equals(owner)) {
//                    for (String mapName : mapInfos.keySet()) {
//                        if(!mapInfos.get(mapName).shouldCheckExpiredRecords())
//                            continue;
//                        PartitionContainer pc = partitionContainers[i];
//                        RecordStore recordStore = pc.getRecordStore(mapName);
//                        Set<Map.Entry<Data, Record>> recordEntries = recordStore.getRecords().entrySet();
//                        List<Data> keysToRemoved = new ArrayList<Data>();
//                        for (Map.Entry<Data, Record> entry : recordEntries) {
//                            Record record = entry.getValue();
//                            if (!record.isActive() && !record.isDirty())
//                                keysToRemoved.add(entry.getKey());
//                        }
//                        recordStore.evictAll(keysToRemoved);
//                    }
//                }
//            }
        }
    }
}