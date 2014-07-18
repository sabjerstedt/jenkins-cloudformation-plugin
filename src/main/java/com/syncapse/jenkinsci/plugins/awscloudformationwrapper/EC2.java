package com.syncapse.jenkinsci.plugins.awscloudformationwrapper;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.autoscaling.AmazonAutoScaling;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient;
import com.amazonaws.services.autoscaling.model.*;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;

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

    public EC2(String awsAccessKey, String awsSecretKey, Region awsRegion, PrintStream logger) {
        AWSCredentials credentials = new BasicAWSCredentials(awsAccessKey,
                awsSecretKey);
        ec2Client = new AmazonEC2Client(credentials);

        ec2Client.setEndpoint(awsRegion.endPoint.replace("cloudformation", "ec2"));

        autoScalingClient = new AmazonAutoScalingClient(credentials);
        autoScalingClient.setEndpoint(awsRegion.endPoint.replace("cloudformation", "autoscaling"));

        this.logger = logger;
    }

    public void stopInstance(String instanceId) {
        logger.println("Terminating single instance "+instanceId);
        ec2Client.terminateInstances(new TerminateInstancesRequest().withInstanceIds(instanceId));
    }

    public void stopInstances(List<String> instanceIds) {
        logger.println("Terminating instances "+instanceIds);
        ec2Client.terminateInstances(new TerminateInstancesRequest().withInstanceIds(instanceIds));
    }

    public void stopInstancesInScalingGroup(String autoScalingGroupName) {
        DescribeAutoScalingGroupsResult groupInfo = autoScalingClient.describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(autoScalingGroupName));
        List<String> instancesToShutdown = new ArrayList<String>();
        for (AutoScalingGroup group : groupInfo.getAutoScalingGroups()) {
            for (Instance instance : group.getInstances()) {
                instancesToShutdown.add(instance.getInstanceId());
            }
        }
        stopInstances(instancesToShutdown);
    }
}
