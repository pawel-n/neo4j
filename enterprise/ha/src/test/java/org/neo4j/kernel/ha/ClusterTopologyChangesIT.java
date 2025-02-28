/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.ha;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.neo4j.cluster.ClusterSettings;
import org.neo4j.cluster.InstanceId;
import org.neo4j.cluster.client.ClusterClient;
import org.neo4j.cluster.client.ClusterClientModule;
import org.neo4j.cluster.member.ClusterMemberEvents;
import org.neo4j.cluster.member.ClusterMemberListener;
import org.neo4j.cluster.protocol.cluster.ClusterConfiguration;
import org.neo4j.cluster.protocol.cluster.ClusterListener;
import org.neo4j.cluster.protocol.election.NotElectableElectionCredentialsProvider;
import org.neo4j.cluster.protocol.heartbeat.HeartbeatListener;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.helpers.collection.MapUtil;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.ha.cluster.HighAvailabilityMemberState;
import org.neo4j.kernel.ha.com.master.InvalidEpochException;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacadeFactory;
import org.neo4j.kernel.impl.ha.ClusterManager;
import org.neo4j.kernel.impl.ha.ClusterManager.RepairKit;
import org.neo4j.kernel.impl.logging.SimpleLogService;
import org.neo4j.kernel.impl.util.Dependencies;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.logging.FormattedLogProvider;
import org.neo4j.test.CleanupRule;
import org.neo4j.test.RepeatRule;
import org.neo4j.test.ha.ClusterRule;
import org.neo4j.tooling.GlobalGraphOperations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import static java.util.concurrent.TimeUnit.SECONDS;

import static org.neo4j.cluster.protocol.cluster.ClusterConfiguration.COORDINATOR;
import static org.neo4j.function.Predicates.not;
import static org.neo4j.kernel.impl.ha.ClusterManager.allSeesAllAsAvailable;
import static org.neo4j.kernel.impl.ha.ClusterManager.masterAvailable;
import static org.neo4j.kernel.impl.ha.ClusterManager.masterSeesSlavesAsAvailable;

public class ClusterTopologyChangesIT
{
    @Rule
    public final ClusterRule clusterRule = new ClusterRule(getClass());

    @Rule
    public final CleanupRule cleanup = new CleanupRule();

    @Rule
    public final RepeatRule repeat = new RepeatRule();

    protected ClusterManager.ManagedCluster cluster;

    @Before
    public void setup() throws Exception
    {
        cluster = clusterRule
                .config(HaSettings.read_timeout, "1s")
                .config(HaSettings.state_switch_timeout, "2s")
                .config(HaSettings.com_chunk_size, "1024")
                .startCluster();
    }

    @After
    public void cleanup()
    {
        cluster = null;
        System.gc();
    }

    @Test
    public void masterRejoinsAfterFailureAndReelection() throws Throwable
    {
        System.out.println(repeat.getCount());

        // Given
        HighlyAvailableGraphDatabase initialMaster = cluster.getMaster();

        // When
        cluster.info( "Fail master" );
        RepairKit kit = cluster.fail( initialMaster );

        cluster.info( "Wait for 2 to become master and 3 slave" );
        cluster.await( masterAvailable( initialMaster ) );
        cluster.await( masterSeesSlavesAsAvailable( 1 ) );

        cluster.info( "Repair 1" );
        kit.repair();

        // Then
        cluster.info( "Wait for cluster recovery" );
        cluster.await( masterAvailable() );
        cluster.await( allSeesAllAsAvailable() );
        assertEquals( 3, cluster.size() );
    }

    @Test
    public void slaveShouldServeTxsAfterMasterLostQuorumWentToPendingAndThenQuorumWasRestored() throws Throwable
    {
        // GIVEN: cluster with 3 members
        HighlyAvailableGraphDatabase master = cluster.getMaster();

        final HighlyAvailableGraphDatabase slave1 = cluster.getAnySlave();
        final HighlyAvailableGraphDatabase slave2 = cluster.getAnySlave( slave1 );

        final CountDownLatch slave1Left = new CountDownLatch( 1 );
        final CountDownLatch slave2Left = new CountDownLatch( 1 );

        clusterClientOf( master ).addHeartbeatListener( new HeartbeatListener.Adapter()
        {
            @Override
            public void failed( InstanceId server )
            {
                if ( instanceIdOf( slave1 ).equals( server ) )
                {
                    slave1Left.countDown();
                }
                else if ( instanceIdOf( slave2 ).equals( server ) )
                {
                    slave2Left.countDown();
                }
            }
        } );

        // fail slave1 and await master to spot the failure
        RepairKit slave1RepairKit = cluster.fail( slave1 );
        slave1Left.await(60, SECONDS);

        // fail slave2 and await master to spot the failure
        RepairKit slave2RepairKit = cluster.fail( slave2 );
        slave2Left.await(60, SECONDS);

        // master loses quorum and goes to PENDING, cluster is unavailable
        cluster.await( not( masterAvailable() ) );
        assertEquals( HighAvailabilityMemberState.PENDING, master.getInstanceState() );

        // WHEN: both slaves are repaired, majority restored, quorum can be achieved
        slave1RepairKit.repair();
        slave2RepairKit.repair();

        // whole cluster looks fine, but slaves have stale value of the epoch if they rejoin the cluster in SLAVE state
        cluster.await( masterAvailable(  ));
        cluster.await( masterSeesSlavesAsAvailable( 2 ) );
        HighlyAvailableGraphDatabase newMaster = cluster.getMaster();

        final HighlyAvailableGraphDatabase newSlave1 = cluster.getAnySlave();
        final HighlyAvailableGraphDatabase newSlave2 = cluster.getAnySlave( newSlave1 );

        // now adding another failing listener and wait for the failure due to stale epoch
        final CountDownLatch slave1Unavailable = new CountDownLatch( 1 );
        final CountDownLatch slave2Unavailable = new CountDownLatch( 1 );
        ClusterMemberEvents clusterEvents = newMaster.getDependencyResolver().resolveDependency( ClusterMemberEvents.class );
        clusterEvents.addClusterMemberListener( new ClusterMemberListener.Adapter()
        {
            @Override
            public void memberIsUnavailable( String role, InstanceId unavailableId )
            {
                if ( instanceIdOf( newSlave1 ).equals( unavailableId ) )
                {
                    slave1Unavailable.countDown();
                }
                else if ( instanceIdOf( newSlave2 ).equals( unavailableId ) )
                {
                    slave2Unavailable.countDown();
                }
            }
        } );

        // attempt to perform transactions on both slaves throws, election is triggered
        attemptTransactions( newSlave1, newSlave2 );
        slave1Unavailable.await( 60, TimeUnit.SECONDS ); // set a timeout in case the instance does not have stale epoch
        slave2Unavailable.await( 60, TimeUnit.SECONDS );

        // THEN: done with election, cluster feels good and able to serve transactions
        cluster.info( "Waiting for cluster to stabilize" );
        cluster.await( allSeesAllAsAvailable() );

        cluster.info( "Assert ok" );
        assertNotNull( createNodeOn( newMaster ) );
        assertNotNull( createNodeOn( newSlave1 ) );
        assertNotNull( createNodeOn( newSlave2 ) );
    }

    @Test
    public void failedInstanceShouldReceiveCorrectCoordinatorIdUponRejoiningCluster() throws Throwable
    {
        // Given
        HighlyAvailableGraphDatabase initialMaster = cluster.getMaster();

        // When
        cluster.shutdown( initialMaster );
        cluster.await( masterAvailable( initialMaster ) );
        cluster.await( masterSeesSlavesAsAvailable( 1 ) );

        // create node on new master to ensure that it has the greatest tx id
        createNodeOn( cluster.getMaster() );
        cluster.sync();

        ClusterClientModule clusterClient = newClusterClient( new InstanceId( 1 ) );
        cleanup.add(clusterClient.life);

        final AtomicReference<InstanceId> coordinatorIdWhenReJoined = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch( 1 );
        clusterClient.clusterClient.addClusterListener( new ClusterListener.Adapter()
        {
            @Override
            public void enteredCluster( ClusterConfiguration clusterConfiguration )
            {
                coordinatorIdWhenReJoined.set( clusterConfiguration.getElected( COORDINATOR ) );
                latch.countDown();
            }
        } );

        clusterClient.life.start();

        // Then
        latch.await( 20, SECONDS );
        assertEquals( new InstanceId( 2 ), coordinatorIdWhenReJoined.get() );
    }

    private static long nodeCountOn( HighlyAvailableGraphDatabase db )
    {
        try ( Transaction ignored = db.beginTx() )
        {
            return Iterables.count( GlobalGraphOperations.at( db ).getAllNodes() );
        }
    }

    private static ClusterClient clusterClientOf( HighlyAvailableGraphDatabase db )
    {
        return db.getDependencyResolver().resolveDependency( ClusterClient.class );
    }

    private static InstanceId instanceIdOf( HighlyAvailableGraphDatabase db )
    {
        return clusterClientOf( db ).getServerId();
    }

    private static Node createNodeOn( HighlyAvailableGraphDatabase db )
    {
        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.createNode();
            node.setProperty( "key", String.valueOf( System.currentTimeMillis() ) );
            tx.success();
            return node;
        }
    }

    private ClusterClientModule newClusterClient( InstanceId id )
    {
        Map<String,String> configMap = MapUtil.stringMap(
                ClusterSettings.initial_hosts.name(), cluster.getInitialHostsConfigString(),
                ClusterSettings.server_id.name(), String.valueOf( id.toIntegerIndex() ),
                ClusterSettings.cluster_server.name(), "0.0.0.0:8888" );

        Config config = new Config( configMap, GraphDatabaseFacadeFactory.Configuration.class,
                GraphDatabaseSettings.class );

        LifeSupport life = new LifeSupport();
        SimpleLogService logService = new SimpleLogService( FormattedLogProvider.toOutputStream( System.out ), FormattedLogProvider.toOutputStream( System.out ) );

        ClusterClientModule clusterClientModule = new ClusterClientModule(life, new Dependencies(  ), new Monitors(), config, logService, new NotElectableElectionCredentialsProvider());

        return clusterClientModule;
    }

    private static void attemptTransactions( HighlyAvailableGraphDatabase... dbs )
    {
        for ( HighlyAvailableGraphDatabase db : dbs )
        {
            try
            {
                createNodeOn( db );
            }
            catch ( Exception ignored )
            {
            }
        }
    }

    private static void assertHasInvalidEpoch( HighlyAvailableGraphDatabase db )
    {
        InvalidEpochException invalidEpochException = null;
        try
        {
            createNodeOn( db );
        }
        catch ( InvalidEpochException e )
        {
            invalidEpochException = e;
        }
        assertNotNull( "Expected InvalidEpochException was not thrown", invalidEpochException );
    }
}
