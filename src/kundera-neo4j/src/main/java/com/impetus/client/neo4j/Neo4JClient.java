/**
 * Copyright 2012 Impetus Infotech.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.impetus.client.neo4j;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.persistence.GenerationType;
import javax.persistence.PersistenceException;

import org.apache.commons.lang.StringUtils;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotInTransactionException;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.graphdb.index.ReadableIndex;
import org.neo4j.unsafe.batchinsert.BatchInserter;
import org.neo4j.unsafe.batchinsert.BatchInserterIndexProvider;
import org.neo4j.unsafe.batchinsert.BatchInserters;
import org.neo4j.unsafe.batchinsert.LuceneBatchInserterIndexProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.impetus.client.neo4j.config.Neo4JPropertyReader;
import com.impetus.client.neo4j.config.Neo4JPropertyReader.Neo4JSchemaMetadata;
import com.impetus.client.neo4j.index.Neo4JIndexManager;
import com.impetus.client.neo4j.query.Neo4JQuery;
import com.impetus.kundera.PersistenceProperties;
import com.impetus.kundera.client.Client;
import com.impetus.kundera.client.EnhanceEntity;
import com.impetus.kundera.configure.ClientProperties;
import com.impetus.kundera.configure.ClientProperties.DataStore;
import com.impetus.kundera.db.RelationHolder;
import com.impetus.kundera.generator.Generator;
import com.impetus.kundera.lifecycle.states.RemovedState;
import com.impetus.kundera.metadata.KunderaMetadataManager;
import com.impetus.kundera.metadata.model.EntityMetadata;
import com.impetus.kundera.metadata.model.MetamodelImpl;
import com.impetus.kundera.metadata.model.PersistenceUnitMetadata;
import com.impetus.kundera.metadata.model.Relation;
import com.impetus.kundera.metadata.model.attributes.AbstractAttribute;
import com.impetus.kundera.persistence.EntityManagerFactoryImpl.KunderaMetadata;
import com.impetus.kundera.persistence.EntityReader;
import com.impetus.kundera.persistence.KunderaTransactionException;
import com.impetus.kundera.persistence.TransactionBinder;
import com.impetus.kundera.persistence.TransactionResource;
import com.impetus.kundera.persistence.api.Batcher;
import com.impetus.kundera.persistence.context.jointable.JoinTableData;
import com.impetus.kundera.property.PropertyAccessorHelper;
import com.impetus.kundera.utils.ReflectUtils;

/**
 * Implementation of {@link Client} using Neo4J Native Java driver (see Embedded
 * Java driver at http://www.neo4j.org/develop/drivers)
 * 
 * @author amresh.singh
 */
public class Neo4JClient extends Neo4JClientBase implements Client<Neo4JQuery>, TransactionBinder, Batcher
{

    /** The log. */
    private static Logger log = LoggerFactory.getLogger(Neo4JClient.class);

    /**
     * Reference to Neo4J client factory.
     */
    private Neo4JClientFactory factory;

    /** The reader. */
    private EntityReader reader;

    /** The mapper. */
    private GraphEntityMapper mapper;

    /** The resource. */
    private TransactionResource resource;

    /** The indexer. */
    private Neo4JIndexManager indexer;

    /**
     * Instantiates a new neo4 j client.
     * 
     * @param factory
     *            the factory
     * @param puProperties
     *            the pu properties
     * @param persistenceUnit
     *            the persistence unit
     * @param kunderaMetadata
     *            the kundera metadata
     */
    Neo4JClient(final Neo4JClientFactory factory, Map<String, Object> puProperties, String persistenceUnit,
            final KunderaMetadata kunderaMetadata)
    {
        super(kunderaMetadata, puProperties, persistenceUnit);
        this.factory = factory;
        reader = new Neo4JEntityReader(kunderaMetadata);
        indexer = new Neo4JIndexManager();
        mapper = new GraphEntityMapper(indexer, kunderaMetadata);
        populateBatchSize(persistenceUnit, puProperties);
        this.clientMetadata = factory.getClientMetadata();

    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.kundera.client.ClientPropertiesSetter#populateClientProperties
     * (com.impetus.kundera.client.Client, java.util.Map)
     */
    @Override
    public void populateClientProperties(Client client, Map<String, Object> properties)
    {
        // All client properties currently are those that are specified in
        // neo4j.properties (or custom XML configuration file according to
        // Kundera format)
        // No custom property currently defined by Kundera, leaving empty
        if (log.isDebugEnabled())
            log.debug("No custom property to set for Neo4J");
        for (String key : properties.keySet())
        {
            Object value = properties.get(key);
            if (key.equals(PersistenceProperties.KUNDERA_BATCH_SIZE) && value instanceof Integer)
            {
                Integer batchSize = (Integer) value;
                ((Neo4JClient) client).setBatchSize(batchSize);
            }
        }
    }

    /**
     * Finds an entity from graph database.
     * 
     * @param entityClass
     *            the entity class
     * @param key
     *            the key
     * @return the object
     */
    @Override
    public Object find(Class entityClass, Object key)
    {
        GraphDatabaseService graphDb = null;
        if (resource != null)
        {
            graphDb = getConnection();
        }

        if (graphDb == null)
            graphDb = factory.getConnection();

        EntityMetadata m = KunderaMetadataManager.getEntityMetadata(kunderaMetadata, entityClass);

        Object entity = null;
        Node node = mapper.searchNode(key, m, graphDb, true);

        if (node != null && !((Neo4JTransaction) resource).containsNodeId(node.getId()))

        {
            entity = getEntityWithAssociationFromNode(m, node);
        }

        return entity;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#findAll(java.lang.Class,
     * java.lang.String[], java.lang.Object[])
     */
    @Override
    public <E> List<E> findAll(Class<E> entityClass, String[] columnsToSelect, Object... keys)
    {
        List entities = new ArrayList<E>();
        for (Object key : keys)
        {
            entities.add(find(entityClass, key));
        }
        return entities;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#find(java.lang.Class,
     * java.util.Map)
     */
    @Override
    public <E> List<E> find(Class<E> entityClass, Map<String, String> embeddedColumnMap)
    {
        throw new UnsupportedOperationException("Embedded attributes not supported in Neo4J as of now");
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#close()
     */
    @Override
    public void close()
    {
        // Closure is internally handled by Neo4J
    }

    /**
     * Deletes an entity from database.
     * 
     * @param entity
     *            the entity
     * @param key
     *            the key
     */
    @Override
    public void delete(Object entity, Object key)
    {
        // All Modifying Neo4J operations must be executed within a transaction
        checkActiveTransaction();

        GraphDatabaseService graphDb = getConnection();

        // Find Node for this particular entity
        EntityMetadata m = KunderaMetadataManager.getEntityMetadata(kunderaMetadata, entity.getClass());
        Node node = mapper.searchNode(key, m, graphDb, true);
        if (node != null)
        {
            // Remove this particular node, if not already deleted in current
            // transaction
            if (!((Neo4JTransaction) resource).containsNodeId(node.getId()))
            {
                node.delete();

                // Manually remove node index if applicable
                indexer.deleteNodeIndex(m, graphDb, node);

                // Remove all relationship edges attached to this node
                // (otherwise an
                // exception is thrown)
                for (Relationship relationship : node.getRelationships())
                {
                    relationship.delete();

                    // Manually remove relationship index if applicable
                    indexer.deleteRelationshipIndex(m, graphDb, relationship);
                }

                ((Neo4JTransaction) resource).addNodeId(node.getId());
            }
        }
        else
        {
            if (log.isDebugEnabled())
                log.debug("Entity to be deleted doesn't exist in graph. Doing nothing");
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.kundera.client.Client#persistJoinTable(com.impetus.kundera
     * .persistence.context.jointable.JoinTableData)
     */
    @Override
    public void persistJoinTable(JoinTableData joinTableData)
    {
        throw new UnsupportedOperationException("Join Table not supported for Neo4J as of now");
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#getColumnsById(java.lang.String,
     * java.lang.String, java.lang.String, java.lang.String, java.lang.Object,
     * java.lang.Class)
     */
    @Override
    public <E> List<E> getColumnsById(String schemaName, String tableName, String pKeyColumnName, String columnName,
            Object pKeyColumnValue, Class columnJavaType)
    {
        throw new UnsupportedOperationException("Operation not supported for Neo4J");
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#findIdsByColumn(java.lang.String,
     * java.lang.String, java.lang.String, java.lang.String, java.lang.Object,
     * java.lang.Class)
     */
    @Override
    public Object[] findIdsByColumn(String schemaName, String tableName, String pKeyName, String columnName,
            Object columnValue, Class entityClazz)
    {
        throw new UnsupportedOperationException("Operation not supported for Neo4J");
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#deleteByColumn(java.lang.String,
     * java.lang.String, java.lang.String, java.lang.Object)
     */
    @Override
    public void deleteByColumn(String schemaName, String tableName, String columnName, Object columnValue)
    {
        throw new UnsupportedOperationException("Operation not supported for Neo4J");
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#findByRelation(java.lang.String,
     * java.lang.Object, java.lang.Class)
     */
    @Override
    public List<Object> findByRelation(String colName, Object colValue, Class entityClazz)
    {
        throw new UnsupportedOperationException("Operation not supported for Neo4J");
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#getReader()
     */
    @Override
    public EntityReader getReader()
    {
        return reader;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#getQueryImplementor()
     */
    @Override
    public Class<Neo4JQuery> getQueryImplementor()
    {
        return Neo4JQuery.class;
    }

    /**
     * Writes an entity to database.
     * 
     * @param entityMetadata
     *            the entity metadata
     * @param entity
     *            the entity
     * @param id
     *            the id
     * @param rlHolders
     *            the rl holders
     */
    @Override
    protected void onPersist(EntityMetadata entityMetadata, Object entity, Object id, List<RelationHolder> rlHolders)
    {
        if (log.isDebugEnabled())
            log.debug("Persisting " + entity);

        // All Modifying Neo4J operations must be executed within a transaction
        checkActiveTransaction();

        GraphDatabaseService graphDb = getConnection();

        try
        {

            // Top level node
            Node node = mapper.getNodeFromEntity(entity, id, graphDb, entityMetadata, isUpdate);

            if (node != null)
            {
                MetamodelImpl metamodel = (MetamodelImpl) kunderaMetadata.getApplicationMetadata().getMetamodel(
                        getPersistenceUnit());

                ((Neo4JTransaction) resource).addProcessedNode(id, node);

                if (!rlHolders.isEmpty())
                {
                    for (RelationHolder rh : rlHolders)
                    {
                        // Search Node (to be connected to ) in Neo4J graph
                        EntityMetadata targetNodeMetadata = KunderaMetadataManager.getEntityMetadata(kunderaMetadata,
                                rh.getRelationValue().getClass());
                        Object targetNodeKey = PropertyAccessorHelper.getId(rh.getRelationValue(), targetNodeMetadata);
                        // Node targetNode = mapper.searchNode(targetNodeKey,
                        // targetNodeMetadata, graphDb);

                        Node targetNode = null; // Target node connected through
                                                // relationship

                        /**
                         * If Relationship is with an entity in Neo4J, Target
                         * node must already have been created Get a handle of
                         * it from processed nodes and add edges to it. Else, if
                         * relationship is with an entity in a database other
                         * than Neo4J, create a "Proxy Node" that points to a
                         * row in other database. This proxy node contains key
                         * equal to primary key of row in other database.
                         * */

                        if (isEntityForNeo4J(targetNodeMetadata))
                        {
                            targetNode = ((Neo4JTransaction) resource).getProcessedNode(targetNodeKey);
                        }
                        else
                        {
                            // Create Proxy nodes for insert requests
                            if (!isUpdate)
                            {
                                targetNode = mapper.createProxyNode(id, targetNodeKey, graphDb, entityMetadata,
                                        targetNodeMetadata);
                            }
                        }

                        if (targetNode != null)
                        {
                            // Join this node (source node) to target node via
                            // relationship
                            DynamicRelationshipType relType = DynamicRelationshipType.withName(rh.getRelationName());
                            Relationship relationship = node.createRelationshipTo(targetNode, relType);

                            // Populate relationship's own properties into it
                            Object relationshipObj = rh.getRelationVia();
                            if (relationshipObj != null)
                            {
                                mapper.populateRelationshipProperties(entityMetadata, targetNodeMetadata, relationship,
                                        relationshipObj);

                                // After relationship creation, manually index
                                // it if desired
                                EntityMetadata relationMetadata = KunderaMetadataManager.getEntityMetadata(
                                        kunderaMetadata, relationshipObj.getClass());
                                if (!isUpdate)
                                {
                                    indexer.indexRelationship(relationMetadata, graphDb, relationship, metamodel);
                                }
                                else
                                {
                                    indexer.updateRelationshipIndex(relationMetadata, graphDb, relationship, metamodel);
                                }

                            }
                        }

                    }
                }

                // After node creation, manually index this node, if desired
                if (!isUpdate)
                {
                    indexer.indexNode(entityMetadata, graphDb, node, metamodel);
                }
                else
                {
                    indexer.updateNodeIndex(entityMetadata, graphDb, node, metamodel);
                }
            }

        }
        catch (Exception e)
        {
            log.error("Error while persisting entity " + entity + ", Caused by: ", e);
            throw new PersistenceException(e);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.impetus.kundera.persistence.api.Batcher#addBatch(com.impetus.kundera
     * .graph.Node)
     */
    @Override
    public void addBatch(com.impetus.kundera.graph.Node node)
    {
        if (node != null)
        {
            nodes.add(node);
        }
        if (batchSize > 0 && batchSize == nodes.size())
        {
            executeBatch();
            nodes.clear();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.persistence.api.Batcher#executeBatch()
     */
    @Override
    public int executeBatch()
    {
        if (batchSize > 0)
        {
            boolean nodeAutoIndexingEnabled = indexer.isNodeAutoIndexingEnabled(factory.getConnection());
            boolean relationshipAutoIndexingEnabled = indexer
                    .isRelationshipAutoIndexingEnabled(factory.getConnection());

            BatchInserter inserter = getBatchInserter();
            BatchInserterIndexProvider indexProvider = new LuceneBatchInserterIndexProvider(inserter);

            if (inserter == null)
            {
                log.error("Unable to create instance of BatchInserter. Opertion will fail");
                throw new PersistenceException("Unable to create instance of BatchInserter. Opertion will fail");
            }

            if (resource != null && resource.isActive())
            {
                log.error("Batch Insertion MUST not be executed in a transaction");
                throw new PersistenceException("Batch Insertion MUST not be executed in a transaction");
            }

            Map<Object, Long> pkToNodeIdMap = new HashMap<Object, Long>();
            for (com.impetus.kundera.graph.Node graphNode : nodes)
            {
                if (graphNode.isDirty())
                {
                    graphNode.handlePreEvent();
                    // Delete can not be executed in batch, deleting normally
                    if (graphNode.isInState(RemovedState.class))
                    {
                        delete(graphNode.getData(), graphNode.getEntityId());
                    }
                    else if (graphNode.isUpdate())
                    {
                        // Neo4J allows only batch insertion, follow usual path
                        // for normal updates
                        persist(graphNode);
                    }
                    else
                    {
                        // Insert node
                        Object entity = graphNode.getData();
                        EntityMetadata m = KunderaMetadataManager.getEntityMetadata(kunderaMetadata, entity.getClass());
                        Object pk = PropertyAccessorHelper.getId(entity, m);
                        Map<String, Object> nodeProperties = mapper.createNodeProperties(entity, m);
                        long nodeId = inserter.createNode(nodeProperties);
                        pkToNodeIdMap.put(pk, nodeId);

                        // Index Node
                        indexer.indexNodeUsingBatchIndexer(indexProvider, m, nodeId, nodeProperties,
                                nodeAutoIndexingEnabled);

                        // Insert relationships for this particular node
                        if (!getRelationHolders(graphNode).isEmpty())
                        {
                            for (RelationHolder rh : getRelationHolders(graphNode))
                            {
                                // Search Node (to be connected to ) in Neo4J
                                // graph
                                EntityMetadata targetNodeMetadata = KunderaMetadataManager.getEntityMetadata(
                                        kunderaMetadata, rh.getRelationValue().getClass());
                                Object targetNodeKey = PropertyAccessorHelper.getId(rh.getRelationValue(),
                                        targetNodeMetadata);
                                Long targetNodeId = pkToNodeIdMap.get(targetNodeKey);

                                if (targetNodeId != null)
                                {
                                    /**
                                     * Join this node (source node) to target
                                     * node via relationship
                                     */
                                    // Relationship Type
                                    DynamicRelationshipType relType = DynamicRelationshipType.withName(rh
                                            .getRelationName());

                                    // Relationship Properties
                                    Map<String, Object> relationshipProperties = null;
                                    Object relationshipObj = rh.getRelationVia();
                                    if (relationshipObj != null)
                                    {
                                        EntityMetadata relationMetadata = KunderaMetadataManager.getEntityMetadata(
                                                kunderaMetadata, relationshipObj.getClass());

                                        relationshipProperties = mapper.createRelationshipProperties(m,
                                                targetNodeMetadata, relationshipObj);

                                        // Finally insert relationship
                                        long relationshipId = inserter.createRelationship(nodeId, targetNodeId,
                                                relType, relationshipProperties);

                                        // Index this relationship
                                        indexer.indexRelationshipUsingBatchIndexer(indexProvider, relationMetadata,
                                                relationshipId, relationshipProperties, relationshipAutoIndexingEnabled);
                                    }

                                }
                            }
                        }
                    }
                    graphNode.handlePostEvent();
                }
            }

            // Shutdown Batch inserter
            indexProvider.shutdown();
            inserter.shutdown();

            // Restore Graph Database service
            factory.setConnection((GraphDatabaseService) factory.createPoolOrConnection());

            return pkToNodeIdMap.size();
        }
        else
        {
            return 0;
        }

    }

    /**
     * Populates relationship entities into original entity.
     * 
     * @param m
     *            the m
     * @param entity
     *            the entity
     * @param relationMap
     *            the relation map
     * @param node
     *            the node
     * @param nodeIdToEntityMap
     *            the node id to entity map
     */
    private void populateRelations(EntityMetadata m, Object entity, Map<String, Object> relationMap, Node node,
            Map<Long, Object> nodeIdToEntityMap)
    {
        // Populate all relationship entities that are in Neo4J
        for (Relation relation : m.getRelations())
        {
            if (relation != null)
            {
                Class<?> targetEntityClass = relation.getTargetEntity();
                EntityMetadata targetEntityMetadata = KunderaMetadataManager.getEntityMetadata(kunderaMetadata,
                        targetEntityClass);
                Field property = relation.getProperty();
                if (relation.getPropertyType().isAssignableFrom(Map.class))
                {
                    Map<Object, Object> targetEntitiesMap = new HashMap<Object, Object>();

                    // If relationship entity is stored into Neo4J, fetch it
                    // immediately
                    if (isEntityForNeo4J(targetEntityMetadata))
                    {

                        for (Relationship relationship : node.getRelationships(Direction.OUTGOING,
                                DynamicRelationshipType.withName(relation.getJoinColumnName(kunderaMetadata))))
                        {
                            if (relationship == null)
                            {
                                continue;
                            }

                            // Target Entity
                            Node endNode = relationship.getEndNode();
                            if (endNode == null)
                            {
                                continue;
                            }
                            Object targetEntity = nodeIdToEntityMap.get(endNode.getId());
                            if (targetEntity == null)
                            {
                                targetEntity = mapper.getEntityFromNode(endNode, targetEntityMetadata);
                            }

                            // Relationship Entity
                            Object relationshipEntity = mapper.getEntityFromRelationship(relationship, m, relation);

                            // If this relationship is bidirectional, put source
                            // entity into Map field for target entity
                            Field bidirectionalField = relation.getBiDirectionalField();
                            Map<Object, Object> sourceEntitiesMap = new HashMap<Object, Object>();
                            if (bidirectionalField != null)
                            {
                                for (Relationship incomingRelationship : endNode.getRelationships(Direction.INCOMING,
                                        DynamicRelationshipType.withName(relation.getJoinColumnName(kunderaMetadata))))
                                {
                                    Node startNode = incomingRelationship.getStartNode();
                                    Object sourceEntity = nodeIdToEntityMap.get(startNode.getId());
                                    if (sourceEntity == null)
                                    {
                                        sourceEntity = mapper.getEntityFromNode(startNode, m);
                                        nodeIdToEntityMap.put(startNode.getId(), sourceEntity);
                                    }
                                    sourceEntitiesMap.put(relationshipEntity, sourceEntity);
                                }
                                PropertyAccessorHelper.set(targetEntity, bidirectionalField, sourceEntitiesMap);
                            }

                            // Set references to Target and owning entity in
                            // relationship entity
                            Class<?> relationshipClass = relation.getMapKeyJoinClass();
                            for (Field f : relationshipClass.getDeclaredFields())
                            {
                                if (!ReflectUtils.isTransientOrStatic(f))
                                {

                                    if (f.getType().equals(m.getEntityClazz()))
                                    {
                                        PropertyAccessorHelper.set(relationshipEntity, f, entity);
                                    }
                                    else if (f.getType().equals(targetEntityClass))
                                    {
                                        PropertyAccessorHelper.set(relationshipEntity, f, targetEntity);
                                    }
                                }
                            }
                            targetEntitiesMap.put(relationshipEntity, targetEntity);
                        }

                        PropertyAccessorHelper.set(entity, property, targetEntitiesMap);
                    }

                    /**
                     * If relationship entity is stored in a database other than
                     * Neo4J foreign keys are stored in "Proxy Nodes", retrieve
                     * these foreign keys and set into EnhanceEntity
                     */
                    else
                    {

                        for (Relationship relationship : node.getRelationships(Direction.OUTGOING,
                                DynamicRelationshipType.withName(relation.getJoinColumnName(kunderaMetadata))))
                        {
                            Node proxyNode = relationship.getEndNode();

                            String targetEntityIdColumnName = ((AbstractAttribute) targetEntityMetadata
                                    .getIdAttribute()).getJPAColumnName();
                            Object targetObjectId = proxyNode.getProperty(targetEntityIdColumnName);
                            Object relationshipEntity = mapper.getEntityFromRelationship(relationship, m, relation);

                            targetEntitiesMap.put(targetObjectId, relationshipEntity);
                        }

                        relationMap.put(relation.getJoinColumnName(kunderaMetadata), targetEntitiesMap);
                    }
                }
            }
        }
    }

    /**
     * Returns instance of {@link BatchInserter}.
     * 
     * @return the batch inserter
     */
    protected BatchInserter getBatchInserter()
    {
        PersistenceUnitMetadata puMetadata = kunderaMetadata.getApplicationMetadata().getPersistenceUnitMetadata(
                getPersistenceUnit());
        Properties props = puMetadata.getProperties();

        // Datastore file path
        String datastoreFilePath = (String) props.get(PersistenceProperties.KUNDERA_DATASTORE_FILE_PATH);
        if (StringUtils.isEmpty(datastoreFilePath))
        {
            throw new PersistenceException(
                    "For Neo4J, it's mandatory to specify kundera.datastore.file.path property in persistence.xml");
        }

        // Shut down Graph DB, at a time only one service may have lock on DB
        // file
        if (factory.getConnection() != null)
        {
            factory.getConnection().shutdown();
        }

        BatchInserter inserter = null;

        // Create Batch inserter with configuration if specified
        Neo4JSchemaMetadata nsmd = Neo4JPropertyReader.nsmd;
        ClientProperties cp = nsmd != null ? nsmd.getClientProperties() : null;
        if (cp != null)
        {
            DataStore dataStore = nsmd != null ? nsmd.getDataStore() : null;
            Properties properties = dataStore != null && dataStore.getConnection() != null ? dataStore.getConnection()
                    .getProperties() : null;

            if (properties != null)
            {
                Map<String, String> config = new HashMap<String, String>((Map) properties);
                inserter = BatchInserters.inserter(datastoreFilePath, config);
            }
        }

        // Create Batch inserter without configuration if not provided
        if (inserter == null)
        {
            inserter = BatchInserters.inserter(datastoreFilePath);
        }
        return inserter;
    }

    /**
     * Binds Transaction resource to this client.
     * 
     * @param resource
     *            the resource
     */
    @Override
    public void bind(TransactionResource resource)
    {
        if (resource != null && resource instanceof Neo4JTransaction)
        {
            ((Neo4JTransaction) resource).setGraphDb(factory.getConnection());
            this.resource = resource;
        }
        else
        {
            throw new KunderaTransactionException("Invalid transaction resource provided:" + resource
                    + " Should have been an instance of :" + Neo4JTransaction.class);
        }
    }

    /**
     * Execute lucene query.
     * 
     * @param m
     *            the m
     * @param luceneQuery
     *            the lucene query
     * @return the list
     */
    public List<Object> executeLuceneQuery(EntityMetadata m, String luceneQuery)
    {
        log.info("Executing Lucene Query on Neo4J:" + luceneQuery);

        GraphDatabaseService graphDb = getConnection();
        List<Object> entities = new ArrayList<Object>();

        if (!indexer.isNodeAutoIndexingEnabled(graphDb) && m.isIndexable())
        {
            Index<Node> nodeIndex = graphDb.index().forNodes(m.getIndexName());

            IndexHits<Node> hits = nodeIndex.query(luceneQuery);
            addEntityFromIndexHits(m, entities, hits);
        }
        else
        {

            ReadableIndex<Node> autoNodeIndex = graphDb.index().getNodeAutoIndexer().getAutoIndex();
            IndexHits<Node> hits = autoNodeIndex.query(luceneQuery);
            addEntityFromIndexHits(m, entities, hits);

        }
        return entities;
    }

    /**
     * Adds the entity from index hits.
     * 
     * @param m
     *            the m
     * @param entities
     *            the entities
     * @param hits
     *            the hits
     */
    protected void addEntityFromIndexHits(EntityMetadata m, List<Object> entities, IndexHits<Node> hits)
    {
        for (Node node : hits)
        {
            if (node != null)
            {
                Object entity = getEntityWithAssociationFromNode(m, node);
                if (entity != null)
                {

                    entities.add(entity);

                }
            }
        }
    }

    /**
     * Gets the entity with association from node.
     * 
     * @param m
     *            the m
     * @param node
     *            the node
     * @return the entity with association from node
     */
    private Object getEntityWithAssociationFromNode(EntityMetadata m, Node node)
    {
        Map<String, Object> relationMap = new HashMap<String, Object>();

        /**
         * Map containing Node ID as key and Entity object as value. Helps cache
         * entity objects found earlier for faster lookup and prevents repeated
         * processing
         */
        Map<Long, Object> nodeIdToEntityMap = new HashMap<Long, Object>();

        Object entity = mapper.getEntityFromNode(node, m);

        nodeIdToEntityMap.put(node.getId(), entity);
        populateRelations(m, entity, relationMap, node, nodeIdToEntityMap);

        nodeIdToEntityMap.clear();

        if (!relationMap.isEmpty() && entity != null)
        {
            return new EnhanceEntity(entity, PropertyAccessorHelper.getId(entity, m), relationMap);
        }
        else
        {
            return entity;
        }

    }

    /**
     * Checks whether there is an active transaction within this client Batch
     * operations are run without any transaction boundary hence this check is
     * not applicable for them All Modifying Neo4J operations must be executed
     * within a transaction.
     */
    private void checkActiveTransaction()
    {
        if (batchSize == 0 && (resource == null || !resource.isActive()))
        {
            throw new NotInTransactionException("All Modifying Neo4J operations must be executed within a transaction");
        }
    }

    /**
     * Gets the connection.
     * 
     * @return the connection
     */
    public GraphDatabaseService getConnection()
    {
        if (resource != null)
        {
            return ((Neo4JTransaction) resource).getGraphDb();
        }
        else
        {
            return factory.getConnection();
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.client.Client#getIdGenerator()
     */
    @Override
    public Generator getIdGenerator()
    {
        throw new UnsupportedOperationException(GenerationType.class.getSimpleName()
                + " Strategies not supported by this client : Neo4JClient");
    }

}
