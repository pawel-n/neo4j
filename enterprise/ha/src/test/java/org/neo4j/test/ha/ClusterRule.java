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
package org.neo4j.test.ha;

import org.junit.rules.ExternalResource;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.neo4j.function.Predicate;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.graphdb.factory.HighlyAvailableGraphDatabaseFactory;
import org.neo4j.graphdb.factory.TestHighlyAvailableGraphDatabaseFactory;
import org.neo4j.kernel.impl.ha.ClusterManager;
import org.neo4j.kernel.impl.ha.ClusterManager.Builder;
import org.neo4j.kernel.impl.ha.ClusterManager.ManagedCluster;
import org.neo4j.test.TargetDirectory;

import static java.util.Arrays.asList;

import static org.neo4j.cluster.ClusterSettings.default_timeout;
import static org.neo4j.graphdb.factory.GraphDatabaseSettings.pagecache_memory;
import static org.neo4j.helpers.collection.MapUtil.stringMap;
import static org.neo4j.kernel.ha.HaSettings.tx_push_factor;
import static org.neo4j.kernel.impl.ha.ClusterManager.allSeesAllAsAvailable;
import static org.neo4j.kernel.impl.ha.ClusterManager.clusterOfSize;

public class ClusterRule extends ExternalResource
{
    private ClusterManager clusterManager;
    private File storeDirectory;

    private ClusterManager.Provider provider = clusterOfSize( 3 );
    private final Map<String,String> config = new HashMap<>();
    private HighlyAvailableGraphDatabaseFactory factory = new TestHighlyAvailableGraphDatabaseFactory();
    private List<Predicate<ManagedCluster>> availabilityChecks = asList( allSeesAllAsAvailable() );
    private final TargetDirectory.TestDirectory testDirectory;

    public ClusterRule( Class<?> testClass )
    {
        this.testDirectory = TargetDirectory.testDirForTest( testClass );
        config.putAll( stringMap(
                default_timeout.name(), "1s",
                tx_push_factor.name(), "0",
                pagecache_memory.name(), "8m" ) );
    }

    public ClusterRule config( Setting<?> setting, String value )
    {
        config.put( setting.name(), value );
        return this;
    }

    public ClusterRule provider( ClusterManager.Provider provider )
    {
        this.provider = provider;
        return this;
    }

    public ClusterRule factory( HighlyAvailableGraphDatabaseFactory factory )
    {
        this.factory = factory;
        return this;
    }

    public ClusterRule availabilityChecks( List<Predicate<ManagedCluster>> checks )
    {
        availabilityChecks = new ArrayList<>( checks );
        return this;
    }

    public ClusterManager.ManagedCluster startCluster() throws Exception
    {
        clusterManager = new Builder( storeDirectory )
                .withCommonConfig( config ).withProvider( provider ).withDbFactory( factory ).build();
        try
        {
            clusterManager.start();
        }
        catch ( Throwable throwable )
        {
            throw new RuntimeException( throwable );
        }
        ClusterManager.ManagedCluster cluster = clusterManager.getDefaultCluster();
        for ( Predicate<ManagedCluster> availabilityCheck : availabilityChecks )
        {
            cluster.await( availabilityCheck );
        }
        return cluster;
    }

    @Override
    public Statement apply( final Statement base, final Description description )
    {
        Statement testMethod = new Statement()
        {
            @Override
            public void evaluate() throws Throwable
            {
                storeDirectory = testDirectory.directory( description.getMethodName() );
                base.evaluate();
            }
        };

        Statement testMethodWithBeforeAndAfter = super.apply( testMethod, description );

        return testDirectory.apply( testMethodWithBeforeAndAfter, description );
    }

    @Override
    protected void after()
    {
        try
        {
            if ( clusterManager != null )
            {
                clusterManager.shutdown();
            }
        }
        catch ( Throwable throwable )
        {
            throwable.printStackTrace();
        }
    }
}
