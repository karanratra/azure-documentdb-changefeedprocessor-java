/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.microsoft.azure.documentdb.changefeedprocessor;


import com.microsoft.azure.documentdb.ChangeFeedOptions;
import com.microsoft.azure.documentdb.changefeedprocessor.internal.ChangeFeedObserverFactory;
import com.microsoft.azure.documentdb.changefeedprocessor.internal.IPartitionObserver;
import com.microsoft.azure.documentdb.changefeedprocessor.internal.PartitionManager;
import com.microsoft.azure.documentdb.changefeedprocessor.internal.WorkerData;
import com.microsoft.azure.documentdb.changefeedprocessor.internal.documentleasestore.DocumentServiceLease;
import com.microsoft.azure.documentdb.changefeedprocessor.services.DocumentServices;
import com.microsoft.azure.documentdb.changefeedprocessor.services.DocumentServicesClient;
import com.microsoft.azure.documentdb.changefeedprocessor.services.CheckpointServices;
import com.microsoft.azure.documentdb.changefeedprocessor.services.ResourcePartition;
import com.microsoft.azure.documentdb.changefeedprocessor.services.ResourcePartitionServices;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ChangeFeedEventHost implements IPartitionObserver<DocumentServiceLease> {

    final String DefaultUserAgentSuffix = "changefeed-0.2";
    final String LeaseContainerName = "docdb-changefeed";
    final String LSNPropertyName = "_lsn";

    private DocumentCollectionInfo _collectionLocation;
    private ChangeFeedOptions _changeFeedOptions;
    private ChangeFeedHostOptions _options;
    private String _hostName;
    DocumentCollectionInfo _auxCollectionLocation;
    ConcurrentMap<String, WorkerData> _partitionKeyRangeIdToWorkerMap;
    PartitionManager<DocumentServiceLease> _partitionManager;

    DocumentServicesClient _documentServicesClient;
    ResourcePartitionServices _resourcePartitionSvcs;
    CheckpointServices _checkpointSvcs;

    private IChangeFeedObserverFactory _observerFactory;

    public ChangeFeedEventHost( String hostName, DocumentCollectionInfo documentCollectionLocation, DocumentCollectionInfo auxCollectionLocation){
        this(hostName, documentCollectionLocation, auxCollectionLocation, new ChangeFeedOptions(), new ChangeFeedHostOptions());
    }

    public ChangeFeedEventHost(
            String hostName,
            DocumentCollectionInfo documentCollectionLocation,
            DocumentCollectionInfo auxCollectionLocation,
            ChangeFeedOptions changeFeedOptions,
            ChangeFeedHostOptions hostOptions) {

        if (documentCollectionLocation == null) throw new IllegalArgumentException("documentCollectionLocation");
        if (documentCollectionLocation.getUri() == null) throw new IllegalArgumentException("documentCollectionLocation.getUri()");
        if (documentCollectionLocation.getDatabaseName() == null || documentCollectionLocation.getDatabaseName().isEmpty()) throw new IllegalArgumentException("documentCollectionLocation.getDatabaseName() is null or empty");
        if (documentCollectionLocation.getCollectionName() == null || documentCollectionLocation.getCollectionName().isEmpty()) throw new IllegalArgumentException("documentCollectionLocation.getCollectionName() is null or empty");
        if (hostOptions.getMinPartitionCount() > hostOptions.getMaxPartitionCount()) throw new IllegalArgumentException("hostOptions.MinPartitionCount cannot be greater than hostOptions.MaxPartitionCount");

        this._collectionLocation = CanoninicalizeCollectionInfo(documentCollectionLocation);
        this._changeFeedOptions = changeFeedOptions;
        this._options = hostOptions;
        this._hostName = hostName;
        this._auxCollectionLocation = CanoninicalizeCollectionInfo(auxCollectionLocation);
        this._partitionKeyRangeIdToWorkerMap = new ConcurrentHashMap<String, WorkerData>();

        this._documentServicesClient = new DocumentServicesClient(documentCollectionLocation.getUri().toString(),
                documentCollectionLocation.getDatabaseName(),
                documentCollectionLocation.getCollectionName(),
                documentCollectionLocation.getMasterKey());

        this._resourcePartitionSvcs = new ResourcePartitionServices(_documentServicesClient);
        this._checkpointSvcs = new CheckpointServices();
    }

    private DocumentCollectionInfo CanoninicalizeCollectionInfo(DocumentCollectionInfo collectionInfo)
    {
        DocumentCollectionInfo result = collectionInfo;
        if (result.getConnectionPolicy().getUserAgentSuffix() == null ||
                result.getConnectionPolicy().getUserAgentSuffix().isEmpty())
        {
            result = new DocumentCollectionInfo(collectionInfo);
            result.getConnectionPolicy().setUserAgentSuffix(DefaultUserAgentSuffix);
        }

        return result;
    }

    /**
     * This code used to be async
     */
    public void registerObserver(Class type)
    {
        ChangeFeedObserverFactory factory = new ChangeFeedObserverFactory(type);

        registerObserverFactory(factory);
        start();
    }
    void registerObserverFactory(ChangeFeedObserverFactory factory) {
        this._observerFactory = factory;
    }

    void start(){
        initializePartitions();
        initializeLeaseManager();
    }

    void initializePartitions(){
        // list partitions
        // create resourcePartition
        List<String> partitionIds = null;

        // TEST: single partition
        if( partitionIds == null ) {
            _resourcePartitionSvcs.create("singleInstanceTest");
            return;
        }

        for(String id : partitionIds) {
            _resourcePartitionSvcs.create(id);
        }
    }

    void initializeLeaseManager() {
        // simulate a callback from partitionManager
        hackStartSinglePartition();
    }

    void hackStartSinglePartition() {
        // onPartitionAcquired(null);
        ResourcePartition resourcePartition = _resourcePartitionSvcs.get("singleInstanceTest");

        Object initialData = _checkpointSvcs.getCheckpointData("singleInstanceTest");
        resourcePartition.start(initialData);
    }

    public List listPartition(){
        DocumentServices service = this._documentServicesClient;

        DocumentServicesClient client =  service.createClient();

        List list = (List)client.listPartitionRange();

        return list;
    }

    @Override
    public void onPartitionAcquired(DocumentServiceLease documentServiceLease) {
        String partitionId = documentServiceLease.id;

        ResourcePartition resourcePartition = _resourcePartitionSvcs.get(partitionId);
        Object initialData = _checkpointSvcs.getCheckpointData(partitionId);

        resourcePartition.start(initialData);
    }

    @Override
    public void onPartitionReleasedAsync(DocumentServiceLease documentServiceLease, ChangeFeedObserverCloseReason reason) {
        String partitionId = documentServiceLease.id;

        System.out.println("Partition finished");

        ResourcePartition resourcePartition = _resourcePartitionSvcs.get(partitionId);
        resourcePartition.stop();
    }
}
