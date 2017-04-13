package com.dhi.solr.dataimporthandler;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperFieldModel.DynamoDBAttributeType;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.ScanOutcome;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import com.amazonaws.services.dynamodbv2.model.AmazonDynamoDBException;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used by DynamoDataSource to present an iterator from the getData method
 * 
 * Within the constructor we do the work of setting a dynamo query or scan.
 * 
 * 
 * 
 * @author ben.demott
 */
public class DynamoResultIterator<T> implements Iterator<T> {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    
    public static final String VALIDATION_EXCEPTION = "ValidationException";
    
    DynamoDB dynamoDB;
    Table dynamoTable;
    QuerySpec dynamoQuery;
    ScanSpec dynamoScan;
    Iterator<Item> dynamoIter;
    Map<String, DynamoDBAttributeType> fieldToType;
    DynamoQueryParameters queryParameters;
    
    
    public DynamoResultIterator(AmazonDynamoDB dynamoClient, String tableName, DynamoQueryParameters queryParams, Map<String, DynamoDBAttributeType> dataTypeMap) {
        

        fieldToType = dataTypeMap;
        queryParameters = queryParams;
        
        dynamoDB = new DynamoDB(dynamoClient);
        dynamoTable = dynamoDB.getTable(tableName);
        
        // If there isn't a query condition we should do a scan
        boolean hasConditionExpression = queryParams.getKeyConditionExpression() != null;

        if(hasConditionExpression) {
            // Buildout a Query
            LOG.debug("using QuerySpec for conditional query");
            dynamoQuery = new QuerySpec()
                    .withProjectionExpression(queryParams.getProjectionExpression())
                    .withKeyConditionExpression(queryParams.getKeyConditionExpression())
                    .withFilterExpression(queryParams.getFilterExpression());
            
            if(queryParams.getNameMap() != null) {
                dynamoQuery.withNameMap(queryParams.getNameMap());
            }
            if(queryParams.getValueMap() != null) {
                dynamoQuery.withValueMap(queryParams.getValueMap());
            }

            ItemCollection<QueryOutcome> items = dynamoTable.query(dynamoQuery);
            dynamoIter = items.iterator();
            
        } else {
            // Buildout a Scan
            LOG.debug("using ScanSpec for full table scan");
            dynamoScan = new ScanSpec()
                    .withProjectionExpression(queryParams.getProjectionExpression())
                    .withFilterExpression(queryParams.getFilterExpression());
            
            if(queryParams.getNameMap() != null) {
                dynamoScan.withNameMap(queryParams.getNameMap());
            }
            if(queryParams.getValueMap() != null) {
                dynamoScan.withValueMap(queryParams.getValueMap());
            }
            
            ItemCollection<ScanOutcome> items = dynamoTable.scan(dynamoScan);
            dynamoIter = items.iterator();
            
        }
    }
    
    
    /**
     * Get debugging string for the table description, Note: only dynamo attributes (fields) that are
     * not dynamic will show up in the table description, any key indexes, or secondary indexes 
     * are also returned in the debug string.
     * 
     * @return Table Description Debug String
     */
    public String getTableDebug() {
        try {
            
            TableDescription desc = dynamoTable.describe();
            // Create a compact version of the Tables fields type map
            Map<String, String> tableFields = new TreeMap<>();
            List<AttributeDefinition> fields = desc.getAttributeDefinitions();
            for(AttributeDefinition attr: fields) {
                tableFields.put(attr.getAttributeName(), attr.getAttributeType());
            }
            
            // Create a compact version of the Tables Key Map
            Map<String, String> tableKeys = new TreeMap<>();
            List<KeySchemaElement> schema = desc.getKeySchema();
            for(KeySchemaElement ele: schema) {
                tableKeys.put(ele.getAttributeName(), ele.getKeyType());
            }
            
            return String.format("DynamoDB Table [%s] DEBUG... %nFIELDS: %s %nKEY-FIELDS:%s", desc.getTableName(), tableFields.toString(), tableKeys.toString());
            
        } catch (AmazonDynamoDBException e) {
            return String.format("TABLE DESC UNAVAILABLE: %s", e.getMessage());
        }
    }

    
    @Override
    public boolean hasNext() {
        try {
            return dynamoIter.hasNext();
        } catch (AmazonDynamoDBException e) {
            AmazonServiceException.ErrorType eType = e.getErrorType();
            if(e.getErrorCode().equals(VALIDATION_EXCEPTION)) {
                // If there is any misconfiguration with ValueMap, NameMap, or the schema itself
                // a rather cryptic error will be given, for this reason show the person the query
                // and the remote table description when a validation error occurs, this greatly
                // helps in debugging any problems.
                LOG.warn(String.format("DynamoDB Error %s %nQUERY DEBUG: %s %n%s", VALIDATION_EXCEPTION, queryParameters.toString(), getTableDebug()));
            }
            throw e;
        }
    }
    
    @Override
    public T next() {
        //get the next value
        // TODO need to make use of dataTypeMap
        Item itemData = null;
        try {
            itemData = dynamoIter.next();
        } catch (AmazonDynamoDBException e) {
            AmazonServiceException.ErrorType eType = e.getErrorType();
            if(e.getErrorCode().equals(VALIDATION_EXCEPTION)) {
                LOG.warn(String.format("DynamoDB Error %s - %s %nQUERY DEBUG: %s %n%s", VALIDATION_EXCEPTION, e.getMessage(), queryParameters.toString(), getTableDebug()));
            }
            throw e;
        }
        
        
        Map<String, Object> itemMap = new HashMap<>();
        if(fieldToType != null && !fieldToType.isEmpty()) {
            for(String fieldName: itemData.asMap().keySet()) {
                
                // Explicit field type mappings are passed in from configuration through fieldToType
                // use this to cast/map fields to specific types.
                String fieldType = fieldToType.getOrDefault(fieldName, DynamoDBAttributeType.NULL).toString().toLowerCase();
                switch (fieldType) {
                   
                    case "s":
                        // String
                        itemMap.put(fieldName, itemData.getString(fieldName));
                        break;
                    case "n":
                        // Number
                        itemMap.put(fieldName, itemData.getNumber(fieldName));
                        break;
                    case "l":
                        // Long
                        itemMap.put(fieldName, itemData.getLong(fieldName));
                        break;
                    case "bool":
                        // Boolean
                        itemMap.put(fieldName, itemData.getBOOL(fieldName));
                        break;
                    case "b":
                        // Binary
                        itemMap.put(fieldName, itemData.getBinary(fieldName));
                        break;
                    case "ss":
                        // String Set
                        String[] stringArr = (String[]) itemData.getStringSet(fieldName).toArray();
                        itemMap.put(fieldName, stringArr);
                        break;
                    case "ns":
                        // Number Set
                        Number[] numberArr = (Number[]) itemData.getNumberSet(fieldName).toArray();
                        itemMap.put(fieldName, numberArr);
                        break;
                    case "bs":
                        // binary set
                        break;
                    case "m": 
                        // MAP
                        break;
                    case "null":
                        // Null, treat null as "don't convert data type"
                        itemMap.put(fieldName, itemData.get(fieldName));
                        break;
                    default:
                        LOG.warn(String.format("DynamoDBAttributeType contains unaccounted for type: '%s'",
                                fieldType));
                }
            }
        } else {
            itemMap = itemData.asMap();
        }
        
        return (T) itemMap;
    }

    @Override
    public void remove() {
        // do nothing.
    }
}

