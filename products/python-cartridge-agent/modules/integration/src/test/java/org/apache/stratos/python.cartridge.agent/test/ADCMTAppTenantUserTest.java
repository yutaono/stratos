package org.apache.stratos.python.cartridge.agent.test;/*
 * Licensed to the Apache Software Foundation (ASF) under one 
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY 
 * KIND, either express or implied.  See the License for the 
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.common.domain.LoadBalancingIPType;
import org.apache.stratos.messaging.domain.topology.*;
import org.apache.stratos.messaging.event.instance.notifier.ArtifactUpdatedEvent;
import org.apache.stratos.messaging.event.topology.CompleteTopologyEvent;
import org.apache.stratos.messaging.event.topology.MemberInitializedEvent;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static junit.framework.Assert.assertTrue;

public class ADCMTAppTenantUserTest extends PythonAgentTestManager {
    private static final Log log = LogFactory.getLog(ADCMTAppTenantUserTest.class);
    private static final int ADC_TIMEOUT = 180000;
    private static final String SUITE_NAME = "suite-4";
    private static final String APPLICATION_PATH = "/tmp/pca-test-suite-4";
    private static final String CLUSTER_ID = "tomcat.domain";
    private static final String DEPLOYMENT_POLICY_NAME = "deployment-policy-4";
    private static final String AUTOSCALING_POLICY_NAME = "autoscaling-policy-4";
    private static final String APP_ID = "application-4";
    private static final String MEMBER_ID = "tomcat.member-1";
    private static final String CLUSTER_INSTANCE_ID = "cluster-1-instance-1";
    private static final String NETWORK_PARTITION_ID = "network-partition-1";
    private static final String PARTITION_ID = "partition-1";
    private static final String TENANT_ID = "4";
    private static final String SERVICE_NAME = "tomcat-mt";

    private boolean hasADCTestCompleted = false;

    @BeforeSuite
    public void setupADCMTAppTest() {
        // Set jndi.properties.dir system property for initializing event publishers and receivers
        System.setProperty("jndi.properties.dir", getResourcesPath(SUITE_NAME));

        // start Python agent with configurations provided in resource path
        setup(SUITE_NAME);

        // Simulate server socket
        startServerSocket(8080);
    }

    /**
     * TearDown method for test method testPythonCartridgeAgent
     */
    @AfterSuite
    public void tearDownADCMTAppTest() {
        // TODO: app path is duplicated in Java test and payload
        tearDown(APPLICATION_PATH);
    }

    @Test(timeOut = ADC_TIMEOUT)
    public void testADCForMTApps() {
        startCommunicatorThread();
        assertAgentActivation();
        Thread adcTestThread = new Thread(new Runnable() {
            @Override
            public void run() {
                log.info("Running ADC MT Test thread...");
                // Send artifact updated event
                publishEvent(getArtifactUpdatedEventForPublicRepo());
                log.info("Publishing artifact updated event for repo: " +
                        getArtifactUpdatedEventForPublicRepo().getRepoURL());

                List<String> outputLines = new ArrayList<String>();
                while (!outputStream.isClosed() && !hasADCTestCompleted) {
                    List<String> newLines = getNewLines(outputLines, outputStream.toString());
                    if (newLines.size() > 0) {
                        for (String line : newLines) {
                            if (line.contains("Git clone executed")) {
                                log.info("Agent has completed git clone. Asserting the operation...");
                                assertRepoClone(getArtifactUpdatedEventForPublicRepo());
                                hasADCTestCompleted = true;
                            }
                        }
                    }
                    sleep(1000);
                }
            }
        });
        adcTestThread.start();

        while (!hasADCTestCompleted) {
            // wait until the instance activated event is received.
            sleep(1000);
        }
    }

    private void assertAgentActivation() {
        Thread startupTestThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!eventReceiverInitiated) {
                    sleep(1000);
                }
                List<String> outputLines = new ArrayList<String>();
                while (!outputStream.isClosed()) {
                    List<String> newLines = getNewLines(outputLines, outputStream.toString());
                    if (newLines.size() > 0) {
                        for (String line : newLines) {
                            if (line.contains("Subscribed to 'topology/#'")) {
                                sleep(2000);
                                // Send complete topology event
                                log.info("Publishing complete topology event...");
                                Topology topology = createTestTopology();
                                CompleteTopologyEvent completeTopologyEvent = new CompleteTopologyEvent(topology);
                                publishEvent(completeTopologyEvent);
                                log.info("Complete topology event published");

                                // Publish member initialized event
                                log.info("Publishing member initialized event...");
                                MemberInitializedEvent memberInitializedEvent = new MemberInitializedEvent(
                                        SERVICE_NAME, CLUSTER_ID, CLUSTER_INSTANCE_ID, MEMBER_ID, NETWORK_PARTITION_ID,
                                        PARTITION_ID
                                );
                                publishEvent(memberInitializedEvent);
                                log.info("Member initialized event published");
                            }

                            // Send artifact updated event to activate the instance first
                            if (line.contains("Artifact repository found")) {
                                publishEvent(getArtifactUpdatedEventForPublicRepo());
                                log.info("Artifact updated event published");
                            }
                            log.info(line);
                        }
                    }
                    sleep(1000);
                }
            }
        });
        startupTestThread.start();

        while (!instanceStarted || !instanceActivated) {
            // wait until the instance activated event is received.
            // this will assert whether instance got activated within timeout period; no need for explicit assertions
            sleep(2000);
        }
    }

    private ArtifactUpdatedEvent getArtifactUpdatedEventForPublicRepo() {
        ArtifactUpdatedEvent publicRepoEvent = createTestArtifactUpdatedEvent();
        publicRepoEvent.setRepoURL("https://bitbucket.org/testapache2211/opentestrepo1.git");
        return publicRepoEvent;
    }

    private void assertRepoClone(ArtifactUpdatedEvent artifactUpdatedEvent) {
        File file = new File(APPLICATION_PATH + "/repository/tenants/4/test1.txt");
        assertTrue("Git clone failed for repo [url] " + artifactUpdatedEvent.getRepoURL(),
                file.exists());
    }

    private static ArtifactUpdatedEvent createTestArtifactUpdatedEvent() {
        ArtifactUpdatedEvent artifactUpdatedEvent = new ArtifactUpdatedEvent();
        artifactUpdatedEvent.setClusterId(CLUSTER_ID);
        artifactUpdatedEvent.setTenantId(TENANT_ID);
        return artifactUpdatedEvent;
    }

    /**
     * Create test topology
     *
     * @return
     */
    private Topology createTestTopology() {
        Topology topology = new Topology();
        Service service = new Service(SERVICE_NAME, ServiceType.SingleTenant, UUID.randomUUID().toString());
        topology.addService(service);

        Cluster cluster = new Cluster(service.getServiceName(), CLUSTER_ID, DEPLOYMENT_POLICY_NAME,
                AUTOSCALING_POLICY_NAME, APP_ID);
        service.addCluster(cluster);

        Member member = new Member(service.getServiceName(), cluster.getClusterId(), MEMBER_ID,
                CLUSTER_INSTANCE_ID, NETWORK_PARTITION_ID, PARTITION_ID, LoadBalancingIPType.Private,
                System.currentTimeMillis());

        member.setDefaultPrivateIP("10.0.0.1");
        member.setDefaultPublicIP("20.0.0.1");
        Properties properties = new Properties();
        properties.setProperty("prop1", "value1");
        member.setProperties(properties);
        member.setStatus(MemberStatus.Created);
        cluster.addMember(member);

        return topology;
    }
}