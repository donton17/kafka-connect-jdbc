/**
 * Copyright 2015 Datamountaineer.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package com.datamountaineer.streamreactor.connect.jdbc.sink.writer;

import com.datamountaineer.streamreactor.connect.jdbc.common.DatabaseMetadata;
import com.datamountaineer.streamreactor.connect.jdbc.common.DbTable;
import com.datamountaineer.streamreactor.connect.jdbc.common.DbTableColumn;
import com.datamountaineer.streamreactor.connect.jdbc.sink.RecordDataExtractor;
import com.datamountaineer.streamreactor.connect.jdbc.sink.config.FieldAlias;
import com.datamountaineer.streamreactor.connect.jdbc.sink.config.FieldsMappings;
import com.datamountaineer.streamreactor.connect.jdbc.sink.config.InsertModeEnum;
import com.datamountaineer.streamreactor.connect.jdbc.sink.config.JdbcSinkSettings;
import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import org.apache.kafka.common.config.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class PreparedStatementBuilderHelper {

  private static final Logger logger = LoggerFactory.getLogger(PreparedStatementBuilderHelper.class);

  /**
   * Creates a new instance of PrepareStatementBuilder
   *
   * @param settings - Instance of the Jdbc sink settings
   * @return - Returns an instance of PreparedStatementBuilder depending on the settings asking for batched or
   * non-batched inserts
   */
  /*public static BatchedPreparedStatementBuilder from(final JdbcSinkSettings settings) {
    final Map<String, StructFieldsDataExtractor> map = Maps.newHashMap();
    for (final FieldsMappings tm : settings.getMappings()) {
      final StructFieldsDataExtractor fieldsValuesExtractor = new StructFieldsDataExtractor(tm);

      map.put(tm.getIncomingTopic().toLowerCase(), fieldsValuesExtractor);
    }

    final QueryBuilder queryBuilder = QueryBuilderHelper.from(settings);

    return new BatchedPreparedStatementBuilder(map, queryBuilder);
  }*/

  /**
   * This is used in the non evolving table mode. It will overlap the database columns information over the one provided
   * by the user - provides validation and also allows for columns auto mapping (Say user flags all fields to be included
   * and the payload has only 3 ouf of the 4 incoming mapping. It will therefore only consider the 3 fields discarding the
   * 4th avoiding the error)
   *
   * @param settings
   * @param databaseMetadata
   * @return
   */
  public static PreparedStatementContextIterable from(final JdbcSinkSettings settings,
                                                      final DatabaseMetadata databaseMetadata) {

    final Map<String, RecordDataExtractor> map = Maps.newHashMap();
    for (final FieldsMappings tm : settings.getMappings()) {
      FieldsMappings tableMappings = tm;
      //if the table is not set with autocreate we try to find it
      if (!tm.autoCreateTable()) {
        if (!databaseMetadata.containsTable(tm.getTableName())) {
          final String tables = Joiner.on(",").join(databaseMetadata.getTableNames());
          throw new ConfigException(String.format("%s table is not found in the database available tables:%s. Make sure you" +
                          " set the table to be autocreated or manually add it to the database.",
                  tm.getTableName(),
                  tables));
        }
        //get the columns merged
        tableMappings = validateAndMerge(tm, databaseMetadata.getTable(tm.getTableName()), settings.getInsertMode());
      }
      final RecordDataExtractor fieldsValuesExtractor = new RecordDataExtractor(tableMappings);

      map.put(tm.getIncomingTopic().toLowerCase(), fieldsValuesExtractor);
    }

    final QueryBuilder queryBuilder = QueryBuilderHelper.from(settings);

    return new PreparedStatementContextIterable(map, queryBuilder, settings.getBatchSize());
  }

  /**
   * In non table evolution we merge the field mappings with the database structure. This way we avoid the problems
   * caused when the user sets * for all fields the payload has 4 fields but only 3 found in the database.
   * Furthermore if the mapping is not found in the database an exception is thrown
   *
   * @param tm      - Field mappings
   * @param dbTable - The instance of DbTable
   * @return
   */
  public static FieldsMappings validateAndMerge(final FieldsMappings tm, final DbTable dbTable, InsertModeEnum mode) {
    final Set<String> pkColumns = new HashSet<>();
    final Map<String, DbTableColumn> dbCols = dbTable.getColumns();
    for (DbTableColumn column : dbCols.values()) {
      if (column.isPrimaryKey()) {
        pkColumns.add(column.getName());
      }
    }
    final Map<String, FieldAlias> map = new HashMap<>();
    if (tm.areAllFieldsIncluded()) {

      for (DbTableColumn column : dbTable.getColumns().values()) {
        map.put(column.getName(),
                new FieldAlias(column.getName(), column.isPrimaryKey()));
      }

      //apply the specific mappings
      for (Map.Entry<String, FieldAlias> alias : tm.getMappings().entrySet()) {
        final String colName = alias.getValue().getName();
        if (!map.containsKey(colName)) {
          final String error =
                  String.format("Invalid field mapping. For table %s the following column is not found %s in available columns:%s",
                          tm.getTableName(),
                          colName,
                          Joiner.on(",").join(map.keySet()));
          throw new ConfigException(error);
        }
        map.put(alias.getKey(), new FieldAlias(colName, pkColumns.contains(colName)));
      }
    } else {

      final Set<String> specifiedPKs = new HashSet<>();
      //in this case just validate the mappings
      for (Map.Entry<String, FieldAlias> alias : tm.getMappings().entrySet()) {
        final String colName = alias.getValue().getName();
        if (!dbCols.containsKey(colName)) {
          final String error =
                  String.format("Invalid field mapping. For table %s the following column is not found %s in available columns:%s",
                          tm.getTableName(),
                          colName,
                          Joiner.on(",").join(dbCols.keySet()));
          throw new ConfigException(error);
        }
        map.put(alias.getKey(), new FieldAlias(colName, pkColumns.contains(colName)));
        if (pkColumns.contains(colName)) {
          specifiedPKs.add(colName);
        }
      }

      if (pkColumns.size() > 0) {
        if (!specifiedPKs.containsAll(pkColumns)) {
          logger.warn(String.format("Invalid mappings. Not all PK columns have been specified. PK specified %s  out of existing %s",
                  Joiner.on(",").join(specifiedPKs),
                  Joiner.on(",").join(pkColumns)));
        }
        if (mode.equals(InsertModeEnum.UPSERT)) {
          throw new ConfigException(
                  String.format("Invalid mappings. Not all PK columns have been specified. PK specified %s  out of existing %s",
                          Joiner.on(",").join(specifiedPKs),
                          Joiner.on(",").join(pkColumns)));
        }
      }
    }
    return new FieldsMappings(tm.getTableName(), tm.getIncomingTopic(), false, map);
  }
}