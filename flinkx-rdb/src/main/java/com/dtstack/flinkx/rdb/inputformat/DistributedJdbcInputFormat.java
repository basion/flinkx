/*
 * Licensed to the Apache Software Foundation (ASF) under one
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

package com.dtstack.flinkx.rdb.inputformat;

import com.dtstack.flinkx.enums.EDatabaseType;
import com.dtstack.flinkx.inputformat.RichInputFormat;
import com.dtstack.flinkx.rdb.DataSource;
import com.dtstack.flinkx.rdb.DatabaseInterface;
import com.dtstack.flinkx.rdb.type.TypeConverterInterface;
import com.dtstack.flinkx.rdb.util.DBUtil;
import com.dtstack.flinkx.reader.MetaColumn;
import com.dtstack.flinkx.util.ClassUtil;
import com.dtstack.flinkx.util.StringUtil;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.io.InputSplit;
import org.apache.flink.types.Row;

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * InputFormat for reading data from multiple database and generate Rows.
 *
 * @Company: www.dtstack.com
 * @author jiangbo
 */
public class DistributedJdbcInputFormat extends RichInputFormat {

    protected static final long serialVersionUID = 1L;

    protected DatabaseInterface databaseInterface;

    protected int numPartitions;

    protected String driverName;

    protected boolean hasNext;

    protected int columnCount;

    protected int resultSetType;

    protected int resultSetConcurrency;

    protected List<String> descColumnTypeList;

    protected List<DataSource> sourceList;

    private transient int sourceIndex;

    private transient Connection currentConn;

    private transient Statement currentStatement;

    private transient ResultSet currentResultSet;

    private transient Row currentRecord;

    protected String username;

    protected String password;

    protected String splitKey;

    protected String where;

    protected List<MetaColumn> metaColumns;

    protected TypeConverterInterface typeConverter;

    protected int fetchSize;

    protected int queryTimeOut;

    public DistributedJdbcInputFormat() {
        resultSetType = ResultSet.TYPE_FORWARD_ONLY;
        resultSetConcurrency = ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public void configure(Configuration configuration) {
        // null
    }

    @Override
    protected void openInternal(InputSplit inputSplit) throws IOException {
        try{
            ClassUtil.forName(driverName, getClass().getClassLoader());
            sourceList = ((DistributedJdbcInputSplit) inputSplit).getSourceList();
        }catch (Exception e){
            throw new IllegalArgumentException("open() failed." + e.getMessage(), e);
        }

        LOG.info("JdbcInputFormat[" + jobName + "]open: end");
    }

    private void openNextSource() throws SQLException{
        DataSource currentSource = sourceList.get(sourceIndex);
        currentConn = DBUtil.getConnection(currentSource.getJdbcUrl(), currentSource.getUserName(), currentSource.getPassword());
        currentConn.setAutoCommit(false);
        String queryTemplate = DBUtil.getQuerySql(databaseInterface, currentSource.getTable(),metaColumns,splitKey,where, currentSource.isSplitByKey());
        currentStatement = currentConn.createStatement(resultSetType, resultSetConcurrency);

        if (currentSource.isSplitByKey()){
            String n = currentSource.getParameterValues()[0].toString();
            String m = currentSource.getParameterValues()[1].toString();
            queryTemplate = queryTemplate.replace("${N}",n).replace("${M}",m);

            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Executing '%s' with parameters %s", queryTemplate,
                        Arrays.deepToString(currentSource.getParameterValues())));
            }
        }

        if(databaseInterface.getDatabaseType() == EDatabaseType.MySQL){
            currentStatement.setFetchSize(Integer.MIN_VALUE);
        } else {
            currentStatement.setFetchSize(fetchSize);
        }

        currentStatement.setQueryTimeout(queryTimeOut);
        currentResultSet = currentStatement.executeQuery(queryTemplate);
        columnCount = currentResultSet.getMetaData().getColumnCount();

        if(descColumnTypeList == null) {
            descColumnTypeList = DBUtil.analyzeTable(currentSource.getJdbcUrl(), currentSource.getUserName(),
                    currentSource.getPassword(),databaseInterface, currentSource.getTable(),metaColumns);
        }

        LOG.info("open source:" + currentSource.getJdbcUrl() + ",table:" + currentSource.getTable());
    }

    private boolean readNextRecord() throws IOException{
        try{
            if(currentConn == null){
                openNextSource();
            }

            hasNext = currentResultSet.next();
            if (hasNext){
                currentRecord = new Row(columnCount);
                DBUtil.getRow(databaseInterface.getDatabaseType(),currentRecord,descColumnTypeList,currentResultSet,typeConverter);
                if(!"*".equals(metaColumns.get(0).getName())){
                    for (int i = 0; i < columnCount; i++) {
                        Object val = currentRecord.getField(i);
                        if (val != null && val instanceof String){
                            val = StringUtil.string2col(String.valueOf(val),metaColumns.get(i).getType(),metaColumns.get(i).getTimeFormat());
                            currentRecord.setField(i,val);
                        }
                    }
                }
            } else {
                if(sourceIndex + 1 < sourceList.size()){
                    closeCurrentSource();
                    sourceIndex++;
                    return readNextRecord();
                }
            }

            return !hasNext;
        }catch (SQLException se) {
            throw new IOException("Couldn't read data - " + se.getMessage(), se);
        } catch (Exception npe) {
            throw new IOException("Couldn't access resultSet", npe);
        }
    }

    @Override
    protected Row nextRecordInternal(Row row) throws IOException {
        return currentRecord;
    }

    private void closeCurrentSource(){
        try {
            if(currentConn != null && !currentConn.isClosed()){
                currentConn.commit();
            }

            DBUtil.closeDBResources(currentResultSet,currentStatement,currentConn);
            currentConn = null;
            currentStatement = null;
            currentResultSet = null;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void closeInternal() throws IOException {

    }

    @Override
    public InputSplit[] createInputSplits(int minPart) throws IOException {
        DistributedJdbcInputSplit[] inputSplits = new DistributedJdbcInputSplit[numPartitions];

        if(splitKey != null && splitKey.length()> 0){
            Object[][] parmeter = DBUtil.getParameterValues(numPartitions);
            for (int j = 0; j < numPartitions; j++) {
                DistributedJdbcInputSplit split = new DistributedJdbcInputSplit(j,numPartitions);
                List<DataSource> sourceCopy = deepCopyList(sourceList);
                for (int i = 0; i < sourceCopy.size(); i++) {
                    sourceCopy.get(i).setSplitByKey(true);
                    sourceCopy.get(i).setParameterValues(parmeter[j]);
                }
                split.setSourceList(sourceCopy);
                inputSplits[j] = split;
            }
        } else {
            int partNum = sourceList.size() / numPartitions;
            if (partNum == 0){
                for (int i = 0; i < sourceList.size(); i++) {
                    DistributedJdbcInputSplit split = new DistributedJdbcInputSplit(i,numPartitions);
                    split.setSourceList(Arrays.asList(sourceList.get(i)));
                    inputSplits[i] = split;
                }
            } else {
                for (int j = 0; j < numPartitions; j++) {
                    DistributedJdbcInputSplit split = new DistributedJdbcInputSplit(j,numPartitions);
                    split.setSourceList(new ArrayList<>(sourceList.subList(j * partNum,(j + 1) * partNum)));
                    inputSplits[j] = split;
                }

                if (partNum * numPartitions < sourceList.size()){
                    sourceList = sourceList.subList(partNum * numPartitions,sourceList.size());
                    for (int i = 0; i < sourceList.size(); i++) {
                        DistributedJdbcInputSplit split = inputSplits[i];
                        split.getSourceList().add(sourceList.get(i));
                    }
                }
            }
        }

        return inputSplits;
    }

    @Override
    public boolean reachedEnd() throws IOException {
        return readNextRecord();
    }

    public <T> List<T> deepCopyList(List<T> src) throws IOException{
        try {
            ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(byteOut);
            out.writeObject(src);

            ByteArrayInputStream byteIn = new ByteArrayInputStream(byteOut.toByteArray());
            ObjectInputStream in = new ObjectInputStream(byteIn);
            List<T> dest = (List<T>) in.readObject();

            return dest;
        } catch (Exception e){
            throw new IOException(e);
        }
    }
}