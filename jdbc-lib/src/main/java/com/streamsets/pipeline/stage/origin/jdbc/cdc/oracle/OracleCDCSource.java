/**
 * Copyright 2015 StreamSets Inc.
 * <p>
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.origin.jdbc.cdc.oracle;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.streamsets.pipeline.api.BatchMaker;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.BaseSource;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.lib.jdbc.HikariPoolConfigBean;
import com.streamsets.pipeline.lib.jdbc.JdbcErrors;
import com.streamsets.pipeline.lib.jdbc.JdbcUtil;
import com.streamsets.pipeline.stage.common.DefaultErrorRecordHandler;
import com.streamsets.pipeline.stage.common.ErrorRecordHandler;
import com.streamsets.pipeline.stage.origin.jdbc.cdc.ChangeTypeValues;
import com.zaxxer.hikari.HikariDataSource;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import plsql.plsqlLexer;
import plsql.plsqlParser;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.streamsets.pipeline.lib.jdbc.JdbcErrors.JDBC_00;
import static com.streamsets.pipeline.lib.jdbc.JdbcErrors.JDBC_16;
import static com.streamsets.pipeline.lib.jdbc.JdbcErrors.JDBC_40;
import static com.streamsets.pipeline.lib.jdbc.JdbcErrors.JDBC_41;
import static com.streamsets.pipeline.lib.jdbc.JdbcErrors.JDBC_42;
import static com.streamsets.pipeline.lib.jdbc.JdbcErrors.JDBC_43;
import static com.streamsets.pipeline.lib.jdbc.JdbcErrors.JDBC_44;
import static com.streamsets.pipeline.lib.jdbc.JdbcErrors.JDBC_47;
import static com.streamsets.pipeline.lib.jdbc.JdbcErrors.JDBC_48;
import static com.streamsets.pipeline.lib.jdbc.JdbcErrors.JDBC_49;
import static com.streamsets.pipeline.lib.jdbc.JdbcErrors.JDBC_50;
import static com.streamsets.pipeline.lib.jdbc.JdbcErrors.JDBC_52;
import static com.streamsets.pipeline.stage.origin.jdbc.cdc.oracle.Groups.CDC;
import static com.streamsets.pipeline.stage.origin.jdbc.cdc.oracle.Groups.CREDENTIALS;

public class OracleCDCSource extends BaseSource {

  private static final Logger LOG = LoggerFactory.getLogger(OracleCDCSource.class);
  private static final String CDB_ROOT = "CDB$ROOT";
  private static final String HIKARI_CONFIG_PREFIX = "hikariConf.";
  private static final String DRIVER_CLASSNAME = HIKARI_CONFIG_PREFIX + "driverClassName";
  private static final String USERNAME = HIKARI_CONFIG_PREFIX + "username";
  private static final String CONNECTION_STR = HIKARI_CONFIG_PREFIX + "connectionString";
  private static final String CURRENT_SCN = "SELECT CURRENT_SCN FROM V$DATABASE";
  private static final String GET_OLDEST_SCN =
      "SELECT FIRST_CHANGE# from V$ARCHIVED_LOG ORDER BY FIRST_CHANGE# FETCH NEXT 10 ROWS ONLY";
  private static final int MISSING_LOG_CODE = 1291;
  private static final String SWITCH_TO_CDB_ROOT = "ALTER SESSION SET CONTAINER = CDB$ROOT";
  private static final String PREFIX = "oracle.cdc.";
  private static final String SCN = PREFIX + "scn";
  private static final String USER = PREFIX + "user";
  private static final String OPERATION = PREFIX + "operation";
  private static final String DATE = "DATE";
  private static final String TIME = "TIME";
  private static final String TIMESTAMP = "TIMESTAMP";
  private static final String TIMESTAMP_HEADER = PREFIX + TIMESTAMP.toLowerCase();
  private static final String TABLE = PREFIX + "table";
  private static final String INSERT = "INSERT";
  private static final String DELETE = "DELETE";
  private static final String UPDATE = "UPDATE";
  private static final String SELECT_FOR_UPDATE = "SELECT_FOR_UPDATE";
  private static final int INSERT_CODE = 1;
  private static final int DELETE_CODE = 2;
  private static final int UPDATE_CODE = 3;
  private static final int COMMIT_CODE = 7;
  private static final int SELECT_FOR_UPDATE_CODE = 25;
  private static final String NULL = "NULL";
  private static final String VERSION_STR = "v2";

  private static final String NLS_DATE_FORMAT = "ALTER SESSION SET NLS_DATE_FORMAT = 'DD-MM-YYYY HH24:MI:SS'";
  private static final String NLS_NUMERIC_FORMAT = "ALTER SESSION SET NLS_NUMERIC_CHARACTERS = \'.,\'";
  private static final String NLS_TIMESTAMP_FORMAT =
      "ALTER SESSION SET NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD HH24:MI:SS.FF'";
  private static final SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
  public static final String OFFSET_DELIM = "::";

  private final OracleCDCConfigBean configBean;
  private final HikariPoolConfigBean hikariConfigBean;
  private final Properties driverProperties = new Properties();
  private final Map<String, Map<String, Integer>> tableSchemas = new HashMap<>();
  private final Map<String, Map<String, String>> dateTimeColumns = new HashMap<>();
  private final Map<String, Map<String, PrecisionAndScale>> decimalColumns = new HashMap<>();

  private String logMinerProcedure;
  private String baseLogEntriesSql = null;
  private String redoLogEntriesSql = null;
  private ErrorRecordHandler errorRecordHandler;
  private boolean containerized = false;

  private HikariDataSource dataSource = null;
  private Connection connection = null;
  private PreparedStatement produceSelectChanges;
  private PreparedStatement getOldestSCN;
  private PreparedStatement getLatestSCN;
  private CallableStatement startLogMnr;
  private CallableStatement endLogMnr;
  private PreparedStatement dateStatement;
  private PreparedStatement tsStatement;
  private PreparedStatement numericFormat;
  private PreparedStatement switchContainer;

  private final ParseTreeWalker parseTreeWalker = new ParseTreeWalker();
  private final SQLListener sqlListener = new SQLListener();
  private boolean startedLogMiner = false;

  public OracleCDCSource(HikariPoolConfigBean hikariConf, OracleCDCConfigBean oracleCDCConfigBean) {
    this.configBean = oracleCDCConfigBean;
    this.hikariConfigBean = hikariConf;
  }

  @Override
  public String produce(String lastSourceOffset, int maxBatchSize, final BatchMaker batchMaker) throws StageException {
    final int batchSize = Math.min(configBean.baseConfigBean.maxBatchSize, maxBatchSize);
    // Sometimes even though the SCN number has been updated, the select won't return the latest changes for a bit,
    // because the view gets materialized only on calling the SELECT - so the executeQuery may not return anything.
    // To avoid missing data in such cases, we return the new SCN only when we actually read data.
    PreparedStatement selectChanges = null;
    PreparedStatement dateChanges = null;
    String nextOffset = "";
    try {
      if (dataSource == null || dataSource.isClosed()) {
        connection = null; // Force re-init without checking validity which takes time.
        dataSource = JdbcUtil.createDataSourceForRead(hikariConfigBean, driverProperties);
      }
      if (connection == null || !connection.isValid(30)) {
        if (connection != null) {
          dataSource.evictConnection(connection);
        }
        connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        initializeStatements();
        initializeLogMnrStatements();
        alterSession();
      }

      if (produceSelectChanges == null || produceSelectChanges.isClosed()) {
        produceSelectChanges = getSelectChangesStatement();
      }
      selectChanges = this.produceSelectChanges;
      String lastOffset;
      boolean unversioned = true;
      int base = 0;
      long rowsRead;
      if (!StringUtils.isEmpty(lastSourceOffset)) {
        // versioned offsets are of the form : v2::commit_scn:numrowsread.
        // unversioned offsets: scn::nurowsread
        // so if the offset is unversioned, just pick up the next commit_scn and don't skip any rows so we get all
        // actions from the following commit onwards.
        // This may cause duplicates when upgrading from unversioned to v2 offsets,
        // but this allows us to handle out of order commits.
        if (lastSourceOffset.startsWith("v")) {
          unversioned = false;
          base++;
        } // currently we don't care what version it is, but later we might
        String[] splits = lastSourceOffset.split(OFFSET_DELIM);
        lastOffset = splits[base++];
        rowsRead = Long.valueOf(splits[base]);
        BigDecimal startCommitSCN = new BigDecimal(lastOffset);
        selectChanges.setBigDecimal(1, startCommitSCN);
        long rowsToSkip = unversioned ? 0 : rowsRead;
        selectChanges.setLong(2, rowsToSkip);
        selectChanges.setBigDecimal(3, startCommitSCN);
        if (LOG.isDebugEnabled()) {
          LOG.debug("Starting Commit SCN = " + startCommitSCN + ", Rows skipped = " + rowsToSkip);
        }
      } else {
        if (configBean.startValue == StartValues.DATE) {
          dateChanges = connection.prepareStatement( // NOSONAR -- sonar thinks this is not closed, while it is.
              Utils.format(baseLogEntriesSql,
                  "COMMIT_TIMESTAMP >= TO_DATE('" + configBean.startDate + "', 'DD-MM-YYYY HH24:MI:SS')"));
          LOG.debug(Utils.format(baseLogEntriesSql, "COMMIT_TIMESTAMP >= " +
              "TO_DATE('" + configBean.startDate + "', 'DD-MM-YYYY HH24:MI:SS')"));
          selectChanges = dateChanges;
        } else {
          BigDecimal startCommitSCN = new BigDecimal(configBean.startSCN);
          produceSelectChanges.setBigDecimal(1, startCommitSCN);
          produceSelectChanges.setLong(2, 0);
          produceSelectChanges.setBigDecimal(3, startCommitSCN);
        }
      }

      try {
        startLogMiner();
      } catch (SQLException ex) {
        LOG.error("Error while starting LogMiner", ex);
        throw new StageException(JDBC_52);
      }
      nextOffset = generateRecords(batchSize, selectChanges, batchMaker);
    } catch (Exception ex) {
      // In preview, destroy gets called after timeout which can cause a SQLException
      if (getContext().isPreview() && ex instanceof SQLException) {
        LOG.warn("Exception while previewing", ex);
        return NULL;
      }
      LOG.error("Error while attempting to produce records", ex);
      errorRecordHandler.onError(JDBC_44, Throwables.getStackTraceAsString(ex));
    } finally {
      try {
        if (startedLogMiner) {
          endLogMnr.execute();
          startedLogMiner = false;
        }
      } catch (SQLException ex) {
        LOG.warn("Error while stopping LogMiner", ex);
      }
      if (dateChanges != null) {
        try {
          dateChanges.close();
        } catch (SQLException e) {
          LOG.warn("Error while closing statement", e);
        }
      }
    }
    if (!StringUtils.isEmpty(nextOffset)) {
      return VERSION_STR + OFFSET_DELIM + nextOffset;
    } else {
      return lastSourceOffset == null ? "" : lastSourceOffset;
    }
  }

  private String generateRecords(
      int batchSize,
      PreparedStatement selectChanges,
      BatchMaker batchMaker
  ) throws SQLException, StageException, ParseException {
    String operation;
    String nextOffset = null;
    selectChanges.setMaxRows(batchSize);
    try (ResultSet resultSet = selectChanges.executeQuery()) {
      while (resultSet.next()) {
        String scn = resultSet.getBigDecimal(1).toPlainString();
        String username = resultSet.getString(2);
        short op = resultSet.getShort(3);
        String timestamp = resultSet.getString(4);
        String redoSQL = resultSet.getString(5);
        String table = resultSet.getString(6);
        BigDecimal commitSCN = resultSet.getBigDecimal(7);
        long seq = resultSet.getLong(8);
        String scnSeq = commitSCN + OFFSET_DELIM + seq;
        if (LOG.isDebugEnabled()) {
          LOG.debug("Commit SCN = " + commitSCN + ", SCN = " + scn + ", Redo SQL = " + redoSQL);
        }
        sqlListener.reset();
        plsqlLexer lexer = new plsqlLexer(new ANTLRInputStream(redoSQL));
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        plsqlParser parser = new plsqlParser(tokenStream);
        ParserRuleContext ruleContext;
        switch (op) {
          case UPDATE_CODE:
            ruleContext = parser.update_statement();
            operation = UPDATE;
            break;
          case INSERT_CODE:
            ruleContext = parser.insert_statement();
            operation = INSERT;
            break;
          case DELETE_CODE:
            ruleContext = parser.delete_statement();
            operation = DELETE;
            break;
          case SELECT_FOR_UPDATE_CODE:
            ruleContext = parser.update_statement();
            operation = SELECT_FOR_UPDATE;
            break;
          default:
            errorRecordHandler.onError(JDBC_43, redoSQL);
            continue;
        }
        // Walk it and attach our sqlListener
        parseTreeWalker.walk(sqlListener, ruleContext);
        Map<String, String> columns = sqlListener.getColumns();
        Map<String, Field> fields = new HashMap<>();

        Record record = getContext().createRecord(scnSeq);
        Record.Header recordHeader = record.getHeader();

        for (Map.Entry<String, String> column : columns.entrySet()) {
          String columnName = column.getKey();
          fields.put(columnName, objectToField(table, columnName, column.getValue()));
          if (decimalColumns.containsKey(table) && decimalColumns.get(table).containsKey(columnName)) {
            int precision = decimalColumns.get(table).get(columnName).precision;
            int scale = decimalColumns.get(table).get(columnName).scale;
            recordHeader.setAttribute("jdbc." + columnName + ".precision", String.valueOf(precision));
            recordHeader.setAttribute("jdbc." + columnName + ".scale", String.valueOf(scale));
          }
        }
        recordHeader.setAttribute(SCN, scn);
        recordHeader.setAttribute(USER, username);
        recordHeader.setAttribute(OPERATION, operation);
        recordHeader.setAttribute(TIMESTAMP_HEADER, timestamp);
        recordHeader.setAttribute(TABLE, table);
        record.set(Field.create(fields));
        batchMaker.addRecord(record);
        nextOffset = scnSeq;
      }
    }
    return nextOffset;
  }

  private void startLogMiner() throws SQLException, StageException {
    try (ResultSet rs = getOldestSCN.executeQuery()) {
      while (rs.next()) {
        BigDecimal oldestSCN = rs.getBigDecimal(1);
        startLogMnr.setBigDecimal(1, oldestSCN);
        startLogMnr.setBigDecimal(2, getEndingSCN());
        try {
          startLogMnr.execute();
          startedLogMiner = true;
          break;
        } catch (SQLException ex) {
          if (ex.getErrorCode() == MISSING_LOG_CODE) {
            LOG.debug("Caught SQLException due to missing log file, trying next oldest SCN", ex);
          } else {
            throw new StageException(JDBC_52);
          }
        }
      }
    }
    connection.commit();
    if (!startedLogMiner) {
      throw new StageException(JDBC_52);
    }
  }

  @Override
  public List<ConfigIssue> init() {
    List<ConfigIssue> issues = super.init();
    errorRecordHandler = new DefaultErrorRecordHandler(getContext());

    if (!hikariConfigBean.driverClassName.isEmpty()) {
      try {
        Class.forName(hikariConfigBean.driverClassName);
      } catch (ClassNotFoundException e) {
        LOG.error("Hikari Driver class not found.", e);
        issues.add(getContext().createConfigIssue(
            Groups.LEGACY.name(), DRIVER_CLASSNAME, JdbcErrors.JDBC_28, e.toString()));
      }
    }
    issues = hikariConfigBean.validateConfigs(getContext(), issues);
    driverProperties.putAll(hikariConfigBean.driverProperties);
    if (connection == null) { // For tests, we set a mock connection
      try {
        dataSource = JdbcUtil.createDataSourceForRead(hikariConfigBean, driverProperties);
        connection = dataSource.getConnection();
        connection.setAutoCommit(false);
      } catch (StageException | SQLException e) {
        LOG.error("Error while connecting to DB", e);
        issues.add(getContext().createConfigIssue(
            Groups.CREDENTIALS.name(), USERNAME, JDBC_00, configBean.baseConfigBean.database));
        return issues;
      }
    }

    String container = configBean.pdb;
    configBean.baseConfigBean.database =
        configBean.baseConfigBean.caseSensitive ?
            configBean.baseConfigBean.database.trim() :
            configBean.baseConfigBean.database.trim().toUpperCase();
    List<String> tables;

    try {
      initializeStatements();
      alterSession();
    } catch (SQLException ex) {
      LOG.error("Error while creating statement", ex);
      issues.add(getContext().createConfigIssue(
          Groups.CDC.name(), "oracleCDCConfigBean.baseConfigBean.database", JDBC_00, configBean.baseConfigBean.database));
    }

    BigDecimal scn = null;
    try {
      scn = getEndingSCN();
      switch (configBean.startValue) {
        case SCN:
          if (new BigDecimal(configBean.startSCN).compareTo(scn) > 0) {
            issues.add(
                getContext().createConfigIssue(CDC.name(), "oracleCDCConfigBean.startSCN", JDBC_47, scn.toPlainString()));
          }
          break;
        case DATE:
          try {
            Date startDate = getDate(configBean.startDate);
            if (startDate.after(new Date(System.currentTimeMillis()))) {
              issues.add(getContext().createConfigIssue(CDC.name(), "oracleCDCConfigBean.startDate", JDBC_48));
            }
          } catch (ParseException ex) {
            LOG.error("Invalid date", ex);
            issues.add(getContext().createConfigIssue(CDC.name(), "oracleCDCConfigBean.startDate", JDBC_49));
          }
          break;
        case LATEST:
          configBean.startSCN = scn.toPlainString();
          break;
        default:
          throw new IllegalStateException("Unknown start value!");
      }
    } catch (SQLException ex) {
      LOG.error("Error while getting SCN", ex);
      issues.add(getContext().createConfigIssue(CREDENTIALS.name(), USERNAME, JDBC_42));
    }

    try (Statement reusedStatement = connection.createStatement()) {
      int majorVersion = getDBVersion(issues);
      // If version is 12+, then the check for table presence must be done in an alternate container!
      if (majorVersion == -1) {
        return issues;
      }
      if (majorVersion >= 12) {
        if (!StringUtils.isEmpty(container)) {
          String switchToPdb = "ALTER SESSION SET CONTAINER = " + configBean.pdb;
          try {
            reusedStatement.execute(switchToPdb);
          } catch (SQLException ex) {
            LOG.error("Error while switching to container: " + container, ex);
            issues.add(getContext().createConfigIssue(Groups.CREDENTIALS.name(), USERNAME, JDBC_40, container));
            return issues;
          }
          containerized = true;
        }
      }
      tables = new ArrayList<>(configBean.baseConfigBean.tables.size());
      for (String table : configBean.baseConfigBean.tables) {
        table = table.trim();
        if (!configBean.baseConfigBean.caseSensitive) {
          tables.add(table.toUpperCase());
        } else {
          tables.add(table);
        }
      }
      validateTablePresence(reusedStatement, tables, issues);
      if (!issues.isEmpty()) {
        return issues;
      }
      for (String table : tables) {
        table = table.trim();
        try {
          tableSchemas.put(table, getTableSchema(table));
        } catch (SQLException ex) {
          LOG.error("Error while switching to container: " + container, ex);
          issues.add(getContext().createConfigIssue(Groups.CREDENTIALS.name(), USERNAME, JDBC_50));
        }
      }
      container = CDB_ROOT;
      if (majorVersion >= 12) {
        try {
          switchContainer.execute();
          LOG.info("Switched to CDB$ROOT to start LogMiner.");
        } catch (SQLException ex) {
          // Fatal only if we switched to a PDB earlier
          if (containerized) {
            LOG.error("Error while switching to container: " + container, ex);
            issues.add(getContext().createConfigIssue(Groups.CREDENTIALS.name(), USERNAME, JDBC_40, container));
            return issues;
          }
          // Log it anyway
          LOG.info("Switching containers failed, ignoring since there was no PDB switch", ex);
        }
      }
    } catch (SQLException ex) {
      LOG.error("Error while creating statement", ex);
      issues.add(getContext().createConfigIssue(
          Groups.CDC.name(), "oracleCDCConfigBean.baseConfigBean.database",
          JDBC_00, configBean.baseConfigBean.database));
      return issues;
    }

    final String ddlTracking =
        configBean.dictionary.equals(DictionaryValues.DICT_FROM_REDO_LOGS) ? " + DBMS_LOGMNR.DDL_DICT_TRACKING" : "";

    this.logMinerProcedure =  "BEGIN"
        + " DBMS_LOGMNR.START_LOGMNR("
        + " STARTSCN => ?,"
        + " ENDSCN => ?,"
        + " OPTIONS => DBMS_LOGMNR." + configBean.dictionary.name()
        + "          + DBMS_LOGMNR.CONTINUOUS_MINE"
        + "          + DBMS_LOGMNR.COMMITTED_DATA_ONLY"
        + "          + DBMS_LOGMNR.PRINT_PRETTY_SQL"
        + "          + DBMS_LOGMNR.NO_ROWID_IN_STMT"
        + "          + DBMS_LOGMNR.NO_SQL_DELIMITER"
        + ddlTracking
        + ");"
        + " END;";

    // ORDER BY is not required, since log miner returns all records from a transaction once it is committed, in the
    // order of statement execution. Not having ORDER BY also increases performance a whole lot.
    baseLogEntriesSql = Utils.format(
        "SELECT SCN, USERNAME, OPERATION_CODE, TIMESTAMP, SQL_REDO, TABLE_NAME, COMMIT_SCN, SEQUENCE#" +
            " FROM V$LOGMNR_CONTENTS" +
            " WHERE" +
            " OPERATION_CODE IN ({})" +
            " AND SEG_OWNER='{}' AND TABLE_NAME IN ({})" +
            " AND {}" ,
        getSupportedOperations(), configBean.baseConfigBean.database, formatTableList(tables) // the final one is filled in differently by each one
    );

    redoLogEntriesSql = Utils.format(baseLogEntriesSql, "((COMMIT_SCN = ? AND SEQUENCE# > ?) OR COMMIT_SCN > ?)");

    try {
      initializeLogMnrStatements();
    } catch (SQLException ex) {
      LOG.error("Error while creating statement", ex);
      issues.add(getContext().createConfigIssue(
          Groups.CDC.name(), "oracleCDCConfigBean.baseConfigBean.database", JDBC_00, configBean.baseConfigBean.database));
    }

    if (configBean.baseConfigBean.caseSensitive) {
      sqlListener.setCaseSensitive();
    }
    return issues;
  }

  private void initializeStatements() throws SQLException {
    getOldestSCN = connection.prepareStatement(GET_OLDEST_SCN);
    getLatestSCN = connection.prepareStatement(CURRENT_SCN);
    dateStatement = connection.prepareStatement(NLS_DATE_FORMAT);
    tsStatement = connection.prepareStatement(NLS_TIMESTAMP_FORMAT);
    numericFormat = connection.prepareStatement(NLS_NUMERIC_FORMAT);
    switchContainer = connection.prepareStatement(SWITCH_TO_CDB_ROOT);
  }

  private void initializeLogMnrStatements() throws SQLException {
    startedLogMiner = false;
    produceSelectChanges = getSelectChangesStatement();
    startLogMnr = connection.prepareCall(logMinerProcedure);
    LOG.debug("Redo select query = " + produceSelectChanges.toString());
    endLogMnr = connection.prepareCall("BEGIN DBMS_LOGMNR.END_LOGMNR; END;");
  }

  private PreparedStatement getSelectChangesStatement() throws SQLException {
    return connection.prepareStatement(redoLogEntriesSql);
  }

  private String formatTableList(List<String> tables) {
    List<String> quoted = new ArrayList<>(tables.size());
    for (String table : tables) {
      quoted.add("'" + table + "'");
    }
    Joiner joiner = Joiner.on(',');
    return joiner.join(quoted);
  }

  private String getSupportedOperations() {
    List<Integer> supportedOps = new ArrayList<>();

    for (ChangeTypeValues change : configBean.baseConfigBean.changeTypes) {
      switch (change) {
        case INSERT:
          supportedOps.add(INSERT_CODE);
          break;
        case UPDATE:
          supportedOps.add(UPDATE_CODE);
          break;
        case DELETE:
          supportedOps.add(DELETE_CODE);
          break;
        case SELECT_FOR_UPDATE:
          supportedOps.add(SELECT_FOR_UPDATE_CODE);
          break;
        default:
      }
    }
    Joiner joiner = Joiner.on(',');
    return joiner.join(supportedOps);
  }

  private BigDecimal getEndingSCN() throws SQLException {
    try (ResultSet rs = getLatestSCN.executeQuery()) {
      if (!rs.next()) {
        throw new SQLException("Missing SCN");
      }
      BigDecimal scn = rs.getBigDecimal(1);
      LOG.debug("Current latest SCN is: " + scn.toPlainString());
      return scn;
    }
  }

  private void validateTablePresence(Statement statement, List<String> tables, List<ConfigIssue> issues) {
    for (String table : tables) {
      try {
        statement.execute("SELECT * FROM \"" + configBean.baseConfigBean.database + "\".\"" + table + "\" WHERE 1 = 0");
      } catch (SQLException ex) {
        StringBuilder sb = new StringBuilder("Table: ").append(table).append(" does not exist.");
        if (StringUtils.isEmpty(configBean.pdb)) {
          sb.append(" PDB was not specified. If the database was created inside a PDB, please specify PDB");
        }
        LOG.error(sb.toString(), ex);
        issues.add(getContext().createConfigIssue(Groups.CDC.name(), "oracleCDCConfigBean.baseConfigBean.tables", JDBC_16, table));
      }
    }
  }

  private Map<String, Integer> getTableSchema(String tableName) throws SQLException {
    Map<String, Integer> columns = new HashMap<>();
    String query = "SELECT * FROM \"" + configBean.baseConfigBean.database + "\".\"" + tableName + "\" WHERE 1 = 0";
    try(Statement schemaStatement = connection.createStatement();
        ResultSet rs = schemaStatement.executeQuery(query)) {
      ResultSetMetaData md = rs.getMetaData();
      int colCount = md.getColumnCount();
      for (int i = 1; i <= colCount; i++) {
        int colType = md.getColumnType(i);
        String colName = md.getColumnName(i);
        if (!configBean.baseConfigBean.caseSensitive) {
          colName = colName.toUpperCase();
        }
        if (colType == Types.DATE || colType == Types.TIME || colType == Types.TIMESTAMP) {
          if (dateTimeColumns.get(tableName) == null) {
            dateTimeColumns.put(tableName, new HashMap<String, String>());
          }
          dateTimeColumns.get(tableName).put(colName, md.getColumnTypeName(i));
        }

        if (colType == Types.DECIMAL || colType == Types.NUMERIC) {
          if (decimalColumns.get(tableName) == null) {
            decimalColumns.put(tableName, new HashMap<String, PrecisionAndScale>());
          }
          decimalColumns.get(tableName).put(colName, new PrecisionAndScale(md.getPrecision(i), md.getScale(i)));
        }
        columns.put(md.getColumnName(i), md.getColumnType(i));
      }
    }
    return columns;
  }

  private int getDBVersion(List<ConfigIssue> issues) {
    // Getting metadata version using connection.getMetaData().getDatabaseProductVersion() returns 12c which makes
    // comparisons brittle, so use the actual numerical versions.
    try(Statement statement = connection.createStatement();
        ResultSet versionSet = statement.executeQuery("SELECT version FROM product_component_version")) {
      if (versionSet.next()) {
        String versionStr = versionSet.getString("version");
        if (versionStr != null) {
          int majorVersion = Integer.parseInt(versionStr.substring(0, versionStr.indexOf('.')));
          LOG.info("Oracle Version is " + majorVersion);
          return majorVersion;
        }
      }
    } catch (SQLException ex) {
      LOG.error("Error while getting db version info", ex);
      issues.add(getContext().createConfigIssue(Groups.JDBC.name(), CONNECTION_STR, JDBC_41));
    }
    return -1;
  }

  @Override
  public void destroy() {
    // Close all statements
    closeStatements(dateStatement, endLogMnr, startLogMnr, produceSelectChanges, getLatestSCN, getOldestSCN);

    // Connection if it exists
    try {
      if (connection != null) {
        connection.close();
      }
    } catch (SQLException ex) {
      LOG.warn("Error while closing connection to database", ex);
    }

    // And finally the hiraki data source
    if(dataSource != null) {
      dataSource.close();
    }
  }

  private void closeStatements(Statement ...statements) {
    if(statements == null) {
      return;
    }

    for(Statement stmt : statements) {
      try {
        if(stmt != null) {
          stmt.close();
        }
      } catch (SQLException e) {
        LOG.warn("Error while closing connection to database", e);
      }
    }
  }

  private Field objectToField(String table, String column, String columnValue) throws ParseException {
    int columnType = tableSchemas.get(table).get(column);
    Field field;
    Field.Type type;
    // All types as of JDBC 2.0 are here:
    // https://docs.oracle.com/javase/8/docs/api/constant-values.html#java.sql.Types.ARRAY
    // Good source of recommended mappings is here:
    // http://www.cs.mun.ca/java-api-1.5/guide/jdbc/getstart/mapping.html
    columnValue = NULL.equalsIgnoreCase(columnValue) ? null : columnValue;
    switch (columnType) {
      case Types.BIGINT:
        field = Field.create(Field.Type.LONG, columnValue);
        break;
      case Types.BINARY:
      case Types.LONGVARBINARY:
      case Types.VARBINARY:
        field = Field.create(Field.Type.BYTE_ARRAY, columnValue);
        break;
      case Types.BIT:
      case Types.BOOLEAN:
        field = Field.create(Field.Type.BOOLEAN, columnValue);
        break;
      case Types.CHAR:
      case Types.LONGNVARCHAR:
      case Types.LONGVARCHAR:
      case Types.NCHAR:
      case Types.NVARCHAR:
      case Types.VARCHAR:
        field = Field.create(Field.Type.STRING, columnValue);
        break;
      case Types.DECIMAL:
      case Types.NUMERIC:
        field = Field.create(Field.Type.DECIMAL, columnValue);
        break;
      case Types.DOUBLE:
        field = Field.create(Field.Type.DOUBLE, columnValue);
        break;
      case Types.FLOAT:
      case Types.REAL:
        field = Field.create(Field.Type.FLOAT, columnValue);
        break;
      case Types.INTEGER:
        field = Field.create(Field.Type.INTEGER, columnValue);
        break;
      case Types.SMALLINT:
      case Types.TINYINT:
        field = Field.create(Field.Type.SHORT, columnValue);
        break;
      /*
       * Weird behavior seen:
       * If the field is date/time/timestamp the fieldtype returns 93 (timestamp), so we need to check the string
       * representation also.
       * Date/Time/Timestamp.valueOf methods don't always work either - so need to use the SQL TO_DATE method. It is
       * expensive, but no other option :(
       */
      case Types.DATE:
      case Types.TIME:
      case Types.TIMESTAMP:
        String colType = dateTimeColumns.get(table).get(column);
        if (DATE.equalsIgnoreCase(colType)) {
          type = Field.Type.DATE;
        } else if (TIME.equalsIgnoreCase(colType)) {
          type = Field.Type.TIME;
        } else if (TIMESTAMP.equalsIgnoreCase(colType)) {
          type = Field.Type.DATETIME;
        } else {
          return null;
        }
        if (columnValue == null) {
          field = Field.create(type, null);
        } else {
          field = getDateTimeField(type, columnValue);
        }
        break;
      case Types.ROWID:
      case Types.CLOB:
      case Types.NCLOB:
      case Types.BLOB:
      case Types.ARRAY:
      case Types.DATALINK:
      case Types.DISTINCT:
      case Types.JAVA_OBJECT:
      case Types.NULL:
      case Types.OTHER:
      case Types.REF:
        //case Types.REF_CURSOR: // JDK8 only
      case Types.SQLXML:
      case Types.STRUCT:
        //case Types.TIME_WITH_TIMEZONE: // JDK8 only
        //case Types.TIMESTAMP_WITH_TIMEZONE: // JDK8 only
      default:
        return null;
    }

    return field;
  }

  private Field getDateTimeField(Field.Type type, Object value) throws ParseException {
    if (type == Field.Type.DATE) {
      return Field.create(type, getDate((String) value));
    } else {
      return Field.create(type, Timestamp.valueOf((String) value));
    }
  }

  private Date getDate(String s) throws ParseException {
    return dateFormat.parse(s);
  }

  private void alterSession() throws SQLException {
    if (containerized) {
      switchContainer.execute();
    }
    dateStatement.execute();
    tsStatement.execute();
    numericFormat.execute();
  }

  @VisibleForTesting
  void setConnection(Connection conn) {
    this.connection = conn;
  }

  @VisibleForTesting
  void setDataSource(HikariDataSource dataSource) {
    this.dataSource = dataSource;
  }

  private class PrecisionAndScale {
    int precision;
    int scale;
    PrecisionAndScale(int precision, int scale) {
      this.precision = precision;
      this.scale = scale;
    }
  }
}
