/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.stratos.integration.tests.application;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.common.beans.application.ApplicationBean;
import org.apache.stratos.common.beans.policy.deployment.ApplicationPolicyBean;
import org.apache.stratos.integration.common.RestConstants;
import org.apache.stratos.integration.common.TopologyHandler;
import org.apache.stratos.integration.tests.StratosIntegrationTest;
import org.apache.stratos.messaging.domain.application.Application;
import org.apache.stratos.messaging.domain.application.ApplicationStatus;
import org.apache.stratos.messaging.domain.application.ClusterDataHolder;
import org.apache.stratos.messaging.domain.instance.ClusterInstance;
import org.apache.stratos.messaging.domain.topology.Cluster;
import org.apache.stratos.messaging.domain.topology.Member;
import org.apache.stratos.messaging.domain.topology.Service;
import org.apache.stratos.messaging.message.receiver.application.ApplicationManager;
import org.apache.stratos.messaging.message.receiver.topology.TopologyManager;
import org.testng.annotations.Test;

import java.util.*;

import static org.testng.Assert.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertTrue;

/**
 * This will handle the scale-up and scale-down of a particular cluster bursting test cases
 */
public class PartitionOneAfterAnotherClusterTest extends StratosIntegrationTest {
    private static final Log log = LogFactory.getLog(SampleApplicationsTest.class);
    private static final String RESOURCES_PATH = "/partition-round-robin-cluster-test";


    @Test(timeOut = APPLICATION_TEST_TIMEOUT)
    public void testDeployApplication() {
        try {
            log.info("-------------------Started Partition One after another test case----------------------");

            String autoscalingPolicyId = "autoscaling-policy-3";

            boolean addedScalingPolicy =
                    restClientTenant1.addEntity(RESOURCES_PATH + RestConstants.AUTOSCALING_POLICIES_PATH
                                    + "/" + autoscalingPolicyId + ".json",
                            RestConstants.AUTOSCALING_POLICIES, RestConstants.AUTOSCALING_POLICIES_NAME);
            assertEquals(addedScalingPolicy, true);

            boolean addedC1 =
                    restClientTenant1.addEntity(RESOURCES_PATH + RestConstants.CARTRIDGES_PATH + "/" + "c7.json",
                            RestConstants.CARTRIDGES, RestConstants.CARTRIDGES_NAME);
            assertEquals(addedC1, true);

            boolean addedN1 = restClientTenant1.addEntity(RESOURCES_PATH + RestConstants.NETWORK_PARTITIONS_PATH + "/" +
                            "network-partition-11.json",
                    RestConstants.NETWORK_PARTITIONS, RestConstants.NETWORK_PARTITIONS_NAME);
            assertEquals(addedN1, true);

            boolean addedDep =
                    restClientTenant1.addEntity(RESOURCES_PATH + RestConstants.DEPLOYMENT_POLICIES_PATH + "/" +
                                    "deployment-policy-5.json",
                            RestConstants.DEPLOYMENT_POLICIES, RestConstants.DEPLOYMENT_POLICIES_NAME);
            assertEquals(addedDep, true);

            boolean added = restClientTenant1.addEntity(RESOURCES_PATH + RestConstants.APPLICATIONS_PATH + "/" +
                            "single-cluster-scaling-test.json", RestConstants.APPLICATIONS,
                    RestConstants.APPLICATIONS_NAME);
            assertEquals(added, true);

            ApplicationBean bean = (ApplicationBean) restClientTenant1.getEntity(RestConstants.APPLICATIONS,
                    "single-cluster-scaling-test", ApplicationBean.class, RestConstants.APPLICATIONS_NAME);
            assertEquals(bean.getApplicationId(), "single-cluster-scaling-test");

            boolean addAppPolicy =
                    restClientTenant1.addEntity(RESOURCES_PATH + RestConstants.APPLICATION_POLICIES_PATH + "/" +
                                    "application-policy-4.json", RestConstants.APPLICATION_POLICIES,
                            RestConstants.APPLICATION_POLICIES_NAME);
            assertEquals(addAppPolicy, true);

            ApplicationPolicyBean policyBean = (ApplicationPolicyBean) restClientTenant1.getEntity(
                    RestConstants.APPLICATION_POLICIES,
                    "application-policy-4", ApplicationPolicyBean.class,
                    RestConstants.APPLICATION_POLICIES_NAME);

            //deploy the application
            String resourcePath = RestConstants.APPLICATIONS + "/" + "single-cluster-scaling-test" +
                    RestConstants.APPLICATIONS_DEPLOY + "/" + "application-policy-4";
            boolean deployed = restClientTenant1.deployEntity(resourcePath,
                    RestConstants.APPLICATIONS_NAME);
            assertEquals(deployed, true);

            //Application active handling
            TopologyHandler.getInstance().assertApplicationStatus(bean.getApplicationId(),
                    ApplicationStatus.Active, tenant1Id);

            //Cluster active handling
            TopologyHandler.getInstance().assertClusterActivation(bean.getApplicationId(), tenant1Id);

            //Verifying whether members got created using round robin algorithm
            assertClusterWithRoundRobinAlgorithm(bean.getApplicationId());

            //Removing one member from cluster and check for auto healing


            boolean removedAuto = restClientTenant1.removeEntity(RestConstants.AUTOSCALING_POLICIES,
                    autoscalingPolicyId, RestConstants.AUTOSCALING_POLICIES_NAME);
            assertEquals(removedAuto, false);

            boolean removedNet = restClientTenant1.removeEntity(RestConstants.NETWORK_PARTITIONS,
                    "network-partition-11",
                    RestConstants.NETWORK_PARTITIONS_NAME);
            //Trying to remove the used network partition
            assertEquals(removedNet, false);

            boolean removedDep = restClientTenant1.removeEntity(RestConstants.DEPLOYMENT_POLICIES,
                    "deployment-policy-5", RestConstants.DEPLOYMENT_POLICIES_NAME);
            assertEquals(removedDep, false);

            //Un-deploying the application
            String resourcePathUndeploy = RestConstants.APPLICATIONS + "/" + "single-cluster-scaling-test" +
                    RestConstants.APPLICATIONS_UNDEPLOY;

            boolean unDeployed = restClientTenant1.undeployEntity(resourcePathUndeploy,
                    RestConstants.APPLICATIONS_NAME);
            assertEquals(unDeployed, true);

            boolean undeploy =
                    TopologyHandler.getInstance().assertApplicationUndeploy("single-cluster-scaling-test", tenant1Id);
            if (!undeploy) {
                //Need to forcefully undeploy the application
                log.info("Force undeployment is going to start for the [application] " + "single-cluster-scaling-test");

                restClientTenant1.undeployEntity(RestConstants.APPLICATIONS + "/" + "single-cluster-scaling-test" +
                        RestConstants.APPLICATIONS_UNDEPLOY + "?force=true", RestConstants.APPLICATIONS);

                boolean forceUndeployed = TopologyHandler.getInstance()
                        .assertApplicationUndeploy("single-cluster-scaling-test", tenant1Id);
                assertEquals(forceUndeployed, true, String.format("Forceful undeployment failed for the application %s",
                        "single-cluster-scaling-test"));

            }

            boolean removed = restClientTenant1.removeEntity(RestConstants.APPLICATIONS, "single-cluster-scaling-test",
                    RestConstants.APPLICATIONS_NAME);
            assertEquals(removed, true);

            ApplicationBean beanRemoved = (ApplicationBean) restClientTenant1.getEntity(RestConstants.APPLICATIONS,
                    "single-cluster-scaling-test", ApplicationBean.class, RestConstants.APPLICATIONS_NAME);
            assertEquals(beanRemoved, null);

            boolean removedC1 = restClientTenant1.removeEntity(RestConstants.CARTRIDGES, "c7",
                    RestConstants.CARTRIDGES_NAME);
            assertEquals(removedC1, true);


            removedAuto = restClientTenant1.removeEntity(RestConstants.AUTOSCALING_POLICIES,
                    autoscalingPolicyId, RestConstants.AUTOSCALING_POLICIES_NAME);
            assertEquals(removedAuto, true);

            removedDep = restClientTenant1.removeEntity(RestConstants.DEPLOYMENT_POLICIES,
                    "deployment-policy-5", RestConstants.DEPLOYMENT_POLICIES_NAME);
            assertEquals(removedDep, true);

            removedNet = restClientTenant1.removeEntity(RestConstants.NETWORK_PARTITIONS,
                    "network-partition-11", RestConstants.NETWORK_PARTITIONS_NAME);
            assertEquals(removedNet, false);


            boolean removeAppPolicy = restClientTenant1.removeEntity(RestConstants.APPLICATION_POLICIES,
                    "application-policy-4", RestConstants.APPLICATION_POLICIES_NAME);
            assertEquals(removeAppPolicy, true);

            removedNet = restClientTenant1.removeEntity(RestConstants.NETWORK_PARTITIONS,
                    "network-partition-11", RestConstants.NETWORK_PARTITIONS_NAME);
            assertEquals(removedNet, true);

            log.info(
                    "-------------------------------Ended Partition one after another test case-------------------------------");

        }
        catch (Exception e) {
            log.error("An error occurred while handling  application bursting", e);
            assertTrue("An error occurred while handling  application bursting", false);
        }
    }

    /**
     * Assert application activation
     *
     * @param applicationName
     */
    private void assertClusterWithRoundRobinAlgorithm(String applicationName) {
        Application application = ApplicationManager.getApplications().getApplication(applicationName);
        assertNotNull(String.format("Application is not found: [application-id] %s",
                applicationName), application);

        Set<ClusterDataHolder> clusterDataHolderSet = application.getClusterDataRecursively();
        for (ClusterDataHolder clusterDataHolder : clusterDataHolderSet) {
            String serviceName = clusterDataHolder.getServiceType();
            String clusterId = clusterDataHolder.getClusterId();
            Service service = TopologyManager.getTopology().getService(serviceName);
            assertNotNull(String.format("Service is not found: [application-id] %s [service] %s",
                    applicationName, serviceName), service);

            Cluster cluster = service.getCluster(clusterId);
            assertNotNull(String.format("Cluster is not found: [application-id] %s [service] %s [cluster-id] %s",
                    applicationName, serviceName, clusterId), cluster);

            for (ClusterInstance instance : cluster.getInstanceIdToInstanceContextMap().values()) {
                List<String> partitionsUsedInMembers = new ArrayList<String>();
                Map<String, List<Long>> partitionIdToMembersMap = new HashMap<String, List<Long>>();
                for (Member member : cluster.getMembers()) {
                    String partitionId = member.getPartitionId();
                    if (!partitionIdToMembersMap.containsKey(partitionId)) {
                        List<Long> members = new ArrayList<Long>();
                        members.add(member.getInitTime());
                        partitionIdToMembersMap.put(partitionId, members);
                    } else {
                        partitionIdToMembersMap.get(partitionId).add(member.getInitTime());
                    }
                    if (!partitionsUsedInMembers.contains(partitionId)) {
                        partitionsUsedInMembers.add(partitionId);
                    }
                }
                String p1 = "network-partition-11-partition-1";
                String p2 = "network-partition-11-partition-2";
                List<Long> p1InitTime = partitionIdToMembersMap.get(p1);
                Collections.sort(p1InitTime);

                List<Long> p2InitTime = partitionIdToMembersMap.get(p2);
                Collections.sort(p2InitTime);

                List<Long> allInitTime = new ArrayList<Long>();
                allInitTime.addAll(p1InitTime);
                allInitTime.addAll(p2InitTime);
                Collections.sort(allInitTime);

                int p1Index = -1;
                int p2Index = -1;
                String previousPartition = null;
                for (int i = 0; i < allInitTime.size(); i++) {
                    if (previousPartition == null) {
                        if (p1InitTime.get(0) == allInitTime.get(i)) {
                            previousPartition = p1;
                            p1Index++;
                        } else if (p2InitTime.get(0) == allInitTime.get(i)) {
                            previousPartition = p2;
                            p2Index++;

                        }
                    } else if (previousPartition.equals(p1)) {
                        p2Index++;
                        previousPartition = p2;
                        assertEquals(allInitTime.get(i), p2InitTime.get(p2Index),
                                "Partition-2 doesn't not contain correct values in current iteration");
                        if (p1Index >= 0) {
                            assertEquals(allInitTime.get(i - 1), p1InitTime.get(p1Index),
                                    "Partition-1 doesn't not contain correct values in the previous iteration");
                            if (p1Index + 1 <= (p1InitTime.size() - 1) && i + 1 <= (allInitTime.size() - 1)) {
                                assertEquals(allInitTime.get(i + 1), p1InitTime.get(p1Index + 1),
                                        "Partition-1 doesn't not contain correct values in the next iteration");

                            }
                        }
                    } else {
                        p1Index++;
                        previousPartition = p1;
                        assertEquals(allInitTime.get(i), p1InitTime.get(p1Index),
                                "Partition-1 doesn't not contain correct values in current iteration");
                        if (p2Index >= 0) {
                            assertEquals(allInitTime.get(i - 1), p2InitTime.get(p2Index),
                                    "Partition-2 doesn't not contain correct values  in the previous iteration");
                            if ((p2Index + 1) <= (p2InitTime.size() - 1) && (i + 1) <= (allInitTime.size() - 1)) {
                                assertEquals(allInitTime.get(i + 1), p2InitTime.get(p2Index + 1),
                                        "Partition-2 doesn't not contain correct values in the next iteration");
                            }
                        }
                    }
                }
            }
        }
    }
}