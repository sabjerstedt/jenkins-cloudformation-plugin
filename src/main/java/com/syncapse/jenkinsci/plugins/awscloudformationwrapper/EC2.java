package com.syncapse.jenkinsci.plugins.awscloudformationwrapper;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.autoscaling.AmazonAutoScaling;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient;
import com.amazonaws.services.autoscaling.model.*;
import com.amazonaws.services.autoscaling.model.Instance;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Class for interacting with EC2 API.
 * @author sabjerstedt
 */

public class EC2 {
    private AmazonEC2 ec2Client;
    private AmazonAutoScaling autoScalingClient;
    private PrintStream logger;
    private long timeout;

    public EC2(String awsAccessKey, String awsSecretKey, Region awsRegion, PrintStream logger, long timeout) {
        AWSCredentials credentials = new BasicAWSCredentials(awsAccessKey,
                awsSecretKey);
        ec2Client = new AmazonEC2Client(credentials);

        ec2Client.setEndpoint(awsRegion.endPoint.replace("cloudformation", "ec2"));

        autoScalingClient = new AmazonAutoScalingClient(credentials);
        autoScalingClient.setEndpoint(awsRegion.endPoint.replace("cloudformation", "autoscaling"));

        this.logger = logger;
        this.timeout = timeout;
    }

    public void terminateInstances(List<String> instanceIds, boolean waitForTermination) {
        logger.println("Terminating instances " + instanceIds);
        ec2Client.terminateInstances(new TerminateInstancesRequest().withInstanceIds(instanceIds));

        if (waitForTermination) {
            logger.println("Waiting for EC2 instances to fully terminate");
            boolean terminated = false;
            long startTime = System.currentTimeMillis();
            int count = 0;

            while (!terminated) {
                count++;

                if (count % 10 == 0) {
                    logger.println("Still waiting for instances to terminate (instances: "+instanceIds+")");
                }

                if (isTimeout(startTime)) {
                    logger.println("Timed out waiting for EC2 instances to terminate");
                    throw new TimeoutException("Timed out waiting for EC2 instances to terminate");
                }

                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ex) {
                    logger.println("Received interrupted exception while waiting for EC2 instances to terminate, will no longer wait..");
                    break;
                }

                terminated = true;
                DescribeInstancesResult instanceInfo = ec2Client.describeInstances(new DescribeInstancesRequest().withInstanceIds(instanceIds));

                for (Reservation reservation : instanceInfo.getReservations()) {
                    for (com.amazonaws.services.ec2.model.Instance instance : reservation.getInstances()) {
                        if (!instance.getState().getName().equals(InstanceStateName.Terminated.toString()))
                            terminated = false;
                    }
                }
            }
        }
    }

    public void stopInstancesInScalingGroup(String autoScalingGroupName, boolean waitForInstancesToRestart) throws TimeoutException {
        DescribeAutoScalingGroupsResult groupInfo = autoScalingClient.describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(autoScalingGroupName));
        boolean waitForInstancesToTerminate = waitForInstancesToRestart;

        if (groupInfo.getAutoScalingGroups().get(0).getMinSize() == 0) {
            logger.println("Will not stop any EC2 instances in group "+autoScalingGroupName+", the minSize is zero");
            return;
        }

        List<String> instancesToTerminate = new ArrayList<String>();
        for (Instance instance : groupInfo.getAutoScalingGroups().get(0).getInstances()) {
            instancesToTerminate.add(instance.getInstanceId());
        }

        //Terminate instances
        terminateInstances(instancesToTerminate, waitForInstancesToTerminate);

        if (waitForInstancesToRestart) {
            //Wait for instances in auto-scaling group to restart, TODO make this configurable
            logger.println("Waiting for EC2 instances in auto-scaling group "+autoScalingGroupName+" to restart");
            boolean instancesRestarted = false;
            long startTime = System.currentTimeMillis();
            int waitCount = 0;

            while (!instancesRestarted) {
                waitCount++;

                if (waitCount % 10 == 0) {
                    logger.println("Still waiting for auto-scaling group "+autoScalingGroupName+" to become healthy..");
                }

                if (isTimeout(startTime)) {
                    logger.println("Timed out waiting for EC2 instances to restart");
                    throw new TimeoutException("Timed out waiting for EC2 instances to restart");
                }

                groupInfo = autoScalingClient.describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(autoScalingGroupName));
                int count = 0;

                for (Instance instance : groupInfo.getAutoScalingGroups().get(0).getInstances()) {
                    if (instance.getHealthStatus().equals("Healthy") && !instancesToTerminate.contains(instance.getInstanceId()))
                        count++;
                }

                if (groupInfo.getAutoScalingGroups().get(0).getMinSize() <= count)
                    instancesRestarted = true;

                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ex) {
                    logger.println("Received interrupted exception while waiting for EC2 instances to restart, will no longer wait..");
                    break;
                }
            }
        }
    }

    private boolean isTimeout(long startTime) {
        return timeout == 0 ? false : (System.currentTimeMillis() - startTime) > (timeout * 1000);
    }
}
