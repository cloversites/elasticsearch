/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.routing;

import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.gateway.local.LocalIndexShardGateway;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.InternalTestCluster;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;

/**
 */
@ElasticsearchIntegrationTest.ClusterScope(scope = ElasticsearchIntegrationTest.Scope.TEST, numDataNodes = 0)
public class DelayedAllocationTests extends ElasticsearchIntegrationTest {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return ImmutableSettings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put("gateway.type", "local")
                .put(LocalIndexShardGateway.SYNC_INTERVAL, TimeValue.timeValueSeconds(5))
                .build();
    }

    /**
     * Verifies that when there is no delay timeout, a 1/1 index shard will immediately
     * get allocated to a free node when the node hosting it leaves the cluster.
     */
    @Test
    public void testNoDelayedTimeout() throws Exception {
        internalCluster().startNodesAsync(3).get();
        prepareCreate("test").setSettings(ImmutableSettings.builder()
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 1)
                .put(LocalIndexShardGateway.SYNC_INTERVAL, TimeValue.timeValueSeconds(5))
                .put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING, 0)).get();
        ensureGreen("test");
        indexRandomData();
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(findNodeWithShard()));
        assertThat(client().admin().cluster().prepareHealth().get().getDelayedUnassignedShards(), equalTo(0));
        ensureGreen("test");
    }

    /**
     * When we do have delayed allocation set, verifies that even though there is a node
     * free to allocate the unassigned shard when the node hosting it leaves, it doesn't
     * get allocated. Once we bring the node back, it gets allocated since it existed
     * on it before.
     */
    @Test
    public void testDelayedAllocationNodeLeavesAndComesBack() throws Exception {
        internalCluster().startNodesAsync(3).get();
        prepareCreate("test").setSettings(ImmutableSettings.builder()
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 1)
                .put(LocalIndexShardGateway.SYNC_INTERVAL, TimeValue.timeValueSeconds(5))
                .put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING, TimeValue.timeValueHours(1))).get();
        ensureGreen("test");
        indexRandomData();
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(findNodeWithShard()));
        assertBusy(new Runnable() {
            @Override
            public void run() {
                assertThat(client().admin().cluster().prepareState().all().get().getState().routingNodes().unassigned().size() > 0, equalTo(true));
            }
        });
        assertThat(client().admin().cluster().prepareHealth().get().getDelayedUnassignedShards(), equalTo(1));
        internalCluster().startNode(); // this will use the same data location as the stopped node
        ensureGreen("test");
    }

    /**
     * With a very small delay timeout, verify that it expires and we get to green even
     * though the node hosting the shard is not coming back.
     */
    @Test
    public void testDelayedAllocationTimesOut() throws Exception {
        internalCluster().startNodesAsync(3).get();
        prepareCreate("test").setSettings(ImmutableSettings.builder()
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 1)
                .put(LocalIndexShardGateway.SYNC_INTERVAL, TimeValue.timeValueSeconds(5))
                .put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING, TimeValue.timeValueMillis(100))).get();
        ensureGreen("test");
        indexRandomData();
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(findNodeWithShard()));
        ensureGreen("test");
        internalCluster().startNode();
        // do a second round with longer delay to make sure it happens
        assertAcked(client().admin().indices().prepareUpdateSettings("test").setSettings(ImmutableSettings.builder().put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING, TimeValue.timeValueMillis(100))).get());
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(findNodeWithShard()));
        ensureGreen("test");
    }

    /**
     * Verify that when explicitly changing the value of the index setting for the delayed
     * allocation to a very small value, it kicks the allocation of the unassigned shard
     * even though the node it was hosted on will not come back.
     */
    @Test
    public void testDelayedAllocationChangeWithSettingTo100ms() throws Exception {
        internalCluster().startNodesAsync(3).get();
        prepareCreate("test").setSettings(ImmutableSettings.builder()
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 1)
                .put(LocalIndexShardGateway.SYNC_INTERVAL, TimeValue.timeValueSeconds(5))
                .put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING, TimeValue.timeValueHours(1))).get();
        ensureGreen("test");
        indexRandomData();
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(findNodeWithShard()));
        assertBusy(new Runnable() {
            @Override
            public void run() {
                assertThat(client().admin().cluster().prepareState().all().get().getState().routingNodes().unassigned().size() > 0, equalTo(true));
            }
        });
        assertThat(client().admin().cluster().prepareHealth().get().getDelayedUnassignedShards(), equalTo(1));
        assertAcked(client().admin().indices().prepareUpdateSettings("test").setSettings(ImmutableSettings.builder().put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING, TimeValue.timeValueMillis(100))).get());
        ensureGreen("test");
        assertThat(client().admin().cluster().prepareHealth().get().getDelayedUnassignedShards(), equalTo(0));
    }

    /**
     * Verify that when explicitly changing the value of the index setting for the delayed
     * allocation to 0, it kicks the allocation of the unassigned shard
     * even though the node it was hosted on will not come back.
     */
    @Test
    public void testDelayedAllocationChangeWithSettingTo0() throws Exception {
        internalCluster().startNodesAsync(3).get();
        prepareCreate("test").setSettings(ImmutableSettings.builder()
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 1)
                .put(LocalIndexShardGateway.SYNC_INTERVAL, TimeValue.timeValueSeconds(5))
                .put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING, TimeValue.timeValueHours(1))).get();
        ensureGreen("test");
        indexRandomData();
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(findNodeWithShard()));
        assertBusy(new Runnable() {
            @Override
            public void run() {
                assertThat(client().admin().cluster().prepareState().all().get().getState().routingNodes().unassigned().size() > 0, equalTo(true));
            }
        });
        assertThat(client().admin().cluster().prepareHealth().get().getDelayedUnassignedShards(), equalTo(1));
        assertAcked(client().admin().indices().prepareUpdateSettings("test").setSettings(ImmutableSettings.builder().put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING, TimeValue.timeValueMillis(0))).get());
        ensureGreen("test");
        assertThat(client().admin().cluster().prepareHealth().get().getDelayedUnassignedShards(), equalTo(0));
    }


    private void indexRandomData() throws Exception {
        int numDocs = scaledRandomIntBetween(100, 1000);
        IndexRequestBuilder[] builders = new IndexRequestBuilder[numDocs];
        for (int i = 0; i < builders.length; i++) {
            builders[i] = client().prepareIndex("test", "type").setSource("field", "value");
        }
        // we want to test both full divergent copies of the shard in terms of segments, and
        // a case where they are the same (using sync flush), index Random does all this goodness
        // already
        indexRandom(true, builders);
    }

    private String findNodeWithShard() {
        ClusterState state = client().admin().cluster().prepareState().get().getState();
        List<ShardRouting> startedShards = state.routingTable().shardsWithState(ShardRoutingState.STARTED);
        Collections.shuffle(startedShards, getRandom());
        return state.nodes().get(startedShards.get(0).currentNodeId()).getName();
    }
}
