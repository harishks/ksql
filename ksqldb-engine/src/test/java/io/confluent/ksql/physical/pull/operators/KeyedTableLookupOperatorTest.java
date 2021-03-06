/*
 * Copyright 2018 Confluent Inc.
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

package io.confluent.ksql.physical.pull.operators;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.confluent.ksql.execution.streams.materialization.Locator.KsqlNode;
import io.confluent.ksql.execution.streams.materialization.Locator.KsqlPartitionLocation;
import io.confluent.ksql.execution.streams.materialization.Materialization;
import io.confluent.ksql.execution.streams.materialization.MaterializedTable;
import io.confluent.ksql.execution.streams.materialization.Row;
import io.confluent.ksql.execution.streams.materialization.ks.KsLocator;
import io.confluent.ksql.planner.plan.DataSourceNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.kafka.connect.data.Struct;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@SuppressWarnings({"UnstableApiUsage", "unchecked"})
@RunWith(MockitoJUnitRunner.class)
public class KeyedTableLookupOperatorTest {

  private final List<KsqlPartitionLocation> multipleKeysPartitionLocations = new ArrayList<>();

  @Mock
  private KsqlNode node1;
  @Mock
  private KsqlNode node2;
  @Mock
  private KsqlNode node3;
  @Mock
  private Materialization materialization;
  @Mock
  private MaterializedTable nonWindowedTable;
  @Mock
  private DataSourceNode logicalNode;
  @Mock
  private Struct KEY1;
  @Mock
  private Struct KEY2;
  @Mock
  private Struct KEY3;
  @Mock
  private Struct KEY4;
  @Mock
  private Row ROW1;
  @Mock
  private Row ROW3;
  @Mock
  private Row ROW4;

  @Test
  public void shouldLookupRowsForSingleKey() {
    //Given:
    final List<KsqlPartitionLocation> singleKeyPartitionLocations = new ArrayList<>();
    singleKeyPartitionLocations.add(new KsLocator.PartitionLocation(
        Optional.of(ImmutableSet.of(KEY1)), 1, ImmutableList.of(node1)));
    singleKeyPartitionLocations.add(new KsLocator.PartitionLocation(
        Optional.of(ImmutableSet.of(KEY2)), 2, ImmutableList.of(node2)));
    singleKeyPartitionLocations.add(new KsLocator.PartitionLocation(
        Optional.of(ImmutableSet.of(KEY3)), 3, ImmutableList.of(node3)));
    singleKeyPartitionLocations.add(new KsLocator.PartitionLocation(
        Optional.of(ImmutableSet.of(KEY4)), 3, ImmutableList.of(node3)));

    final KeyedTableLookupOperator lookupOperator = new KeyedTableLookupOperator(materialization, logicalNode);
    when(materialization.nonWindowed()).thenReturn(nonWindowedTable);
    when(materialization.nonWindowed().get(KEY1, 1)).thenReturn(Optional.of(ROW1));
    when(materialization.nonWindowed().get(KEY2, 2)).thenReturn(Optional.empty());
    when(materialization.nonWindowed().get(KEY3, 3)).thenReturn(Optional.of(ROW3));
    when(materialization.nonWindowed().get(KEY4, 3)).thenReturn(Optional.of(ROW4));


    lookupOperator.setPartitionLocations(singleKeyPartitionLocations);
    lookupOperator.open();

    //Then:
    assertThat(lookupOperator.next(), is(ROW1));
    assertThat(lookupOperator.next(), is(ROW3));
    assertThat(lookupOperator.next(), is(ROW4));
    assertThat(lookupOperator.next(), is(nullValue()));
  }

  @Test
  public void shouldLookupRowsForMultipleKeys() {
    //Given:
    final List<KsqlPartitionLocation> multipleKeysPartitionLocations = new ArrayList<>();
    multipleKeysPartitionLocations.add(new KsLocator.PartitionLocation(
        Optional.of(ImmutableSet.of(KEY1, KEY2)), 1, ImmutableList.of(node1)));
    multipleKeysPartitionLocations.add(new KsLocator.PartitionLocation(
        Optional.of(ImmutableSet.of(KEY3, KEY4)), 3, ImmutableList.of(node3)));

    final KeyedTableLookupOperator lookupOperator = new KeyedTableLookupOperator(materialization, logicalNode);
    when(materialization.nonWindowed()).thenReturn(nonWindowedTable);
    when(materialization.nonWindowed().get(KEY1, 1)).thenReturn(Optional.of(ROW1));
    when(materialization.nonWindowed().get(KEY3, 3)).thenReturn(Optional.of(ROW3));
    when(materialization.nonWindowed().get(KEY4, 3)).thenReturn(Optional.of(ROW4));
    lookupOperator.setPartitionLocations(multipleKeysPartitionLocations);
    lookupOperator.open();

    //Then:
    assertThat(lookupOperator.next(), is(ROW1));
    assertThat(lookupOperator.next(), is(ROW3));
    assertThat(lookupOperator.next(), is(ROW4));
    assertThat(lookupOperator.next(), is(nullValue()));
  }
}
