/*
 * Copyright 2020 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.api.impl;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.RateLimiter;
import io.confluent.ksql.api.server.PushQueryHandle;
import io.confluent.ksql.api.spi.QueryPublisher;
import io.confluent.ksql.config.SessionConfig;
import io.confluent.ksql.engine.KsqlEngine;
import io.confluent.ksql.engine.PullQueryExecutionUtil;
import io.confluent.ksql.execution.streams.RoutingFilter.RoutingFilterFactory;
import io.confluent.ksql.execution.streams.RoutingOptions;
import io.confluent.ksql.internal.PullQueryExecutorMetrics;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.parser.KsqlParser.ParsedStatement;
import io.confluent.ksql.parser.KsqlParser.PreparedStatement;
import io.confluent.ksql.parser.tree.Query;
import io.confluent.ksql.parser.tree.Statement;
import io.confluent.ksql.physical.pull.PullQueryResult;
import io.confluent.ksql.query.BlockingRowQueue;
import io.confluent.ksql.rest.entity.TableRows;
import io.confluent.ksql.rest.server.resources.streaming.PullQueryConfigRoutingOptions;
import io.confluent.ksql.schema.ksql.Column;
import io.confluent.ksql.schema.utils.FormatOptions;
import io.confluent.ksql.services.ServiceContext;
import io.confluent.ksql.statement.ConfiguredStatement;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlStatementException;
import io.confluent.ksql.util.TransientQueryMetadata;
import io.confluent.ksql.util.VertxUtils;
import io.vertx.core.Context;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.kafka.common.utils.Time;

public class QueryEndpoint {

  private final KsqlEngine ksqlEngine;
  private final KsqlConfig ksqlConfig;
  private final RoutingFilterFactory routingFilterFactory;
  private final Optional<PullQueryExecutorMetrics> pullQueryMetrics;
  private final RateLimiter rateLimiter;

  public QueryEndpoint(
      final KsqlEngine ksqlEngine,
      final KsqlConfig ksqlConfig,
      final RoutingFilterFactory routingFilterFactory,
      final Optional<PullQueryExecutorMetrics> pullQueryMetrics,
      final RateLimiter rateLimiter
  ) {
    this.ksqlEngine = ksqlEngine;
    this.ksqlConfig = ksqlConfig;
    this.routingFilterFactory = routingFilterFactory;
    this.pullQueryMetrics = pullQueryMetrics;
    this.rateLimiter = rateLimiter;
  }

  public QueryPublisher createQueryPublisher(
      final String sql,
      final JsonObject properties,
      final Context context,
      final WorkerExecutor workerExecutor,
      final ServiceContext serviceContext) {
    final long startTimeNanos = Time.SYSTEM.nanoseconds();
    // Must be run on worker as all this stuff is slow
    VertxUtils.checkIsWorker();

    final ConfiguredStatement<Query> statement = createStatement(sql, properties.getMap());

    if (statement.getStatement().isPullQuery()) {
      return createPullQueryPublisher(
          context, serviceContext, routingFilterFactory, statement, pullQueryMetrics,
          startTimeNanos);
    } else {
      return createPushQueryPublisher(context, serviceContext, statement, workerExecutor);
    }
  }

  private QueryPublisher createPushQueryPublisher(
      final Context context,
      final ServiceContext serviceContext,
      final ConfiguredStatement<Query> statement,
      final WorkerExecutor workerExecutor
  ) {
    final BlockingQueryPublisher publisher = new BlockingQueryPublisher(context, workerExecutor);

    final TransientQueryMetadata queryMetadata = ksqlEngine
        .executeQuery(serviceContext, statement, true);

    publisher.setQueryHandle(new KsqlQueryHandle(queryMetadata));

    return publisher;
  }

  private QueryPublisher createPullQueryPublisher(
      final Context context,
      final ServiceContext serviceContext,
      final RoutingFilterFactory routingFilterFactory,
      final ConfiguredStatement<Query> statement,
      final Optional<PullQueryExecutorMetrics> pullQueryMetrics,
      final long startTimeNanos
  ) {

    final RoutingOptions routingOptions = new PullQueryConfigRoutingOptions(
        ksqlConfig,
        statement.getSessionConfig().getOverrides(),
        ImmutableMap.of()
    );

    PullQueryExecutionUtil.checkRateLimit(rateLimiter);

    final PullQueryResult result = ksqlEngine.executePullQuery(
        serviceContext,
        statement,
        routingFilterFactory,
        routingOptions,
        pullQueryMetrics
    );

    pullQueryMetrics.ifPresent(p -> p.recordLatency(startTimeNanos));

    final TableRows tableRows = new TableRows(
        statement.getStatementText(),
        result.getQueryId(),
        result.getSchema(),
        result.getTableRows());

    return new PullQueryPublisher(
        context,
        tableRows,
        colNamesFromSchema(tableRows.getSchema().columns()),
        colTypesFromSchema(tableRows.getSchema().columns())
    );
  }

  private ConfiguredStatement<Query> createStatement(final String queryString,
      final Map<String, Object> properties) {
    final List<ParsedStatement> statements = ksqlEngine.parse(queryString);
    if ((statements.size() != 1)) {
      throw new KsqlStatementException(
          String
              .format("Expected exactly one KSQL statement; found %d instead", statements.size()),
          queryString);
    }
    final PreparedStatement<?> ps = ksqlEngine.prepare(statements.get(0));
    final Statement statement = ps.getStatement();
    if (!(statement instanceof Query)) {
      throw new KsqlStatementException("Not a query", queryString);
    }
    @SuppressWarnings("unchecked") final PreparedStatement<Query> psq =
        (PreparedStatement<Query>) ps;
    return ConfiguredStatement.of(psq, SessionConfig.of(ksqlConfig, properties));
  }

  private static List<String> colTypesFromSchema(final List<Column> columns) {
    return columns.stream()
        .map(Column::type)
        .map(type -> type.toString(FormatOptions.none()))
        .collect(Collectors.toList());
  }

  private static List<String> colNamesFromSchema(final List<Column> columns) {
    return columns.stream()
        .map(Column::name)
        .map(ColumnName::text)
        .collect(Collectors.toList());
  }

  private static class KsqlQueryHandle implements PushQueryHandle {

    private final TransientQueryMetadata queryMetadata;

    KsqlQueryHandle(final TransientQueryMetadata queryMetadata) {
      this.queryMetadata = Objects.requireNonNull(queryMetadata, "queryMetadata");
    }

    @Override
    public List<String> getColumnNames() {
      return colNamesFromSchema(queryMetadata.getLogicalSchema().value());
    }

    @Override
    public List<String> getColumnTypes() {
      return colTypesFromSchema(queryMetadata.getLogicalSchema().value());
    }

    @Override
    public void start() {
      queryMetadata.start();
    }

    @Override
    public void stop() {
      queryMetadata.close();
    }

    @Override
    public BlockingRowQueue getQueue() {
      return queryMetadata.getRowQueue();
    }
  }
}
