/**
 * 
 */
package com.syncapse.jenkinsci.plugins.awscloudformationwrapper;

import java.io.PrintStream;
import java.util.*;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.AmazonCloudFormationAsyncClient;
import com.amazonaws.services.cloudformation.model.*;
import com.amazonaws.services.cloudformation.model.Stack;
import com.google.common.collect.Lists;
import hudson.EnvVars;

/**
 * Class for interacting with CloudFormation stacks, including creating them, deleting them and getting the outputs.
 * @author erickdovale
 * 
 */
public class CloudFormation {
	
	/**
	 * Minimum time to wait before considering the creation of the stack a failure. 
	 * Default value is 5 minutes. (300 seconds)
	 */
	public static final long MIN_TIMEOUT = 300;

	private String stackName;
	private String recipe;
	private List<Parameter> parameters;
	private long timeout;
	private String awsAccessKey;
	private String awsSecretKey;
	private PrintStream logger;
	private AmazonCloudFormation amazonClient;
    private EC2 ec2;
	private Stack stack;
	private long waitBetweenAttempts;
    private boolean autoDeleteStack;
    private boolean terminateAutoScaleEC2Resources;
	private EnvVars envVars;
	private Region awsRegion;

	private Map<String, String> outputs;

	/**
	 * @param logger a logger to write progress information.
	 * @param stackName the name of the stack as defined in the AWS CloudFormation API.
	 * @param recipeBody the body of the json document describing the stack.
	 * @param parameters a Map of where the keys are the param name and the value the param value.
	 * @param timeout Time to wait for the creation of a stack to complete. This value will be the greater between {@link #MIN_TIMEOUT} and the given value.
	 * @param awsAccessKey the AWS API Access Key.
	 * @param awsSecretKey the AWS API Secret Key.
	 */
	public CloudFormation(PrintStream logger, String stackName,
			String recipeBody, Map<String, String> parameters,
			long timeout, String awsAccessKey, String awsSecretKey, Region region, 
            boolean autoDeleteStack, EnvVars envVars, boolean terminateEC2Resources) {

		this.logger = logger;
		this.stackName = stackName;
		this.recipe = recipeBody;
		this.parameters = parameters(parameters);
		this.awsAccessKey = awsAccessKey;
		this.awsSecretKey = awsSecretKey;
		this.awsRegion = region != null ? region : Region.getDefault();
		if (timeout == -12345){
			this.timeout = 0; // Faster testing.
			this.waitBetweenAttempts = 0;
		} else{
			this.timeout = timeout > MIN_TIMEOUT ? timeout : MIN_TIMEOUT;
			this.waitBetweenAttempts = 10; // query every 10s
		}
		this.amazonClient = getAWSClient();
        this.autoDeleteStack = autoDeleteStack;
		this.envVars = envVars;
        this.terminateAutoScaleEC2Resources = terminateEC2Resources;
        this.ec2 = getEC2Client();
	}

    public CloudFormation(PrintStream logger, String stackName,
			String recipeBody, Map<String, String> parameters, long timeout,
			String awsAccessKey, String awsSecretKey, boolean autoDeleteStack,
			EnvVars envVars) {
		this(logger, stackName, recipeBody, parameters, timeout, awsAccessKey,
				awsSecretKey, null, autoDeleteStack, envVars, false);
	}

    public CloudFormation(PrintStream logger, String stackName,
                          Map<String, String> parameters, long timeout,
                          String awsAccessKey, String awsSecretKey, boolean terminateEC2Resources,
                          EnvVars envVars) {
        this(logger, stackName, null, parameters, timeout, awsAccessKey,
                awsSecretKey, null, false, envVars, terminateEC2Resources);
    }

	/**
     * Return true if this stack should be automatically deleted at the end of the job, or false if it should not
     * be automatically deleted.
     * @return true if this stack should be automatically deleted at the end of the job, or false if it should not
     * be automatically deleted.
     */
    public boolean getAutoDeleteStack() {
        return autoDeleteStack;
    }

    /**
     * Return true if it is desired to terminate EC2 resources automatically after stack update
     */
    public boolean getTerminateAutoScaleEC2Resources() {
        return terminateAutoScaleEC2Resources;
    }
	
	/**
	 * @return
	 */
	public boolean delete() {
		logger.println("Deleting Cloud Formation stack: " + getExpandedStackName());
		
		DeleteStackRequest deleteStackRequest = new DeleteStackRequest();
		deleteStackRequest.withStackName(getExpandedStackName());
		
		amazonClient.deleteStack(deleteStackRequest);
		boolean result = waitForStackToBeDeleted();
		
		logger.println("Cloud Formation stack: " + getExpandedStackName()
				+ (result ? " deleted successfully" : " failed deleting.") );
		return result;
	}

	/**
	 * @return True of the stack was created successfully. False otherwise.
	 * 
	 * @throws TimeoutException if creating the stack takes longer than the timeout value passed during creation.
	 *
	 */
	public boolean create() throws TimeoutException {

		logger.println("Creating Cloud Formation stack: " + getExpandedStackName());
		
		CreateStackRequest request = createStackRequest();
		
		try {
			amazonClient.createStack(request);
			
			stack = waitForStackToBeCreated();
			
			StackStatus status = getStackStatus(stack.getStackStatus());
			
			Map<String, String> stackOutput = new HashMap<String, String>();
			if (isStackCreationSuccessful(status)){
				List<Output> outputs = stack.getOutputs();
				for (Output output : outputs){
					stackOutput.put(output.getOutputKey(), output.getOutputValue());
				}
				
				logger.println("Successfully created stack: " + getExpandedStackName());
				
				this.outputs = stackOutput;
				return true;
			} else{
				logger.println("Failed to create stack: " + getExpandedStackName() + ". Reason: " + stack.getStackStatusReason());
				return false;
			}
		} catch (AmazonServiceException e) {
			logger.println("Failed to create stack: " + getExpandedStackName() + ". Reason: " + detailedError(e));
			return false;
		} catch (AmazonClientException e) {
			logger.println("Failed to create stack: " + getExpandedStackName() + ". Error was: " + e.getCause());
			return false;
		}

	}

    /**
     * @return True of the stack was updated successfully. False otherwise.
     *
     * Currently this only supports updating existing stack parameters, not templates (will always use previous template)
     *
     * @throws TimeoutException if creating the stack takes longer than the timeout value passed during creation.
     *
     */
    public boolean update() throws TimeoutException {
        logger.println("Updating cloud formation stack: " + getExpandedStackName());

        try {
            UpdateStackRequest request = createUpdateStackRequest();

            amazonClient.updateStack(request);

            stack = waitForStackToBeUpdated();

            StackStatus status = getStackStatus(stack.getStackStatus());

            Map<String, String> stackOutput = new HashMap<String, String>();
            if (isStackUpdateSuccessful(status)) {
                List<Output> outputs = stack.getOutputs();
                for (Output output : outputs) {
                    stackOutput.put(output.getOutputKey(), output.getOutputValue());
                }

                logger.println("Successfully updated stack: " + getExpandedStackName());

                this.outputs = stackOutput;
                return true;
            } else {
                logger.println("Failed to update stack: " + getExpandedStackName() + ". Reason: " + stack.getStackStatusReason());
                return false;
            }
        } catch (AmazonServiceException e) {
            if (e.getMessage().contains("No updates are to be performed")) {
                logger.println("The stack "+getExpandedStackName()+" in AWS already matches the updated parameters, no updates are needed");
                return true;
            }

            logger.println("Failed to update stack: " + getExpandedStackName() + ". Reason: " + detailedError(e));
            return false;
        } catch (AmazonClientException e) {
            logger.println("Failed to update stack: " + getExpandedStackName() + ". Error was: " + e.getCause());
            return false;
        }
    }

    public boolean doTerminateAutoScaleEC2Resources() {
        try {
            logger.println("Attempting to terminate EC2 instances in any auto-scaling groups associated with stack " + getExpandedStackName());
            ListStackResourcesResult resources = amazonClient.listStackResources(new ListStackResourcesRequest().withStackName(getExpandedStackName()));

            for (StackResourceSummary resource : resources.getStackResourceSummaries()) {
                if (resource.getResourceType().equals("AWS::EC2::Instance")) {
                    logger.println("Skipping shut down of individual EC2 instance " + resource.toString());
                    //ec2.stopInstance(resource.getLogicalResourceId());
                } else if (resource.getResourceType().equals("AWS::AutoScaling::AutoScalingGroup")) {
                    logger.println("Shutting down EC2 instances in auto scaling group " + resource.toString());
                    ec2.stopInstancesInScalingGroup(resource.getPhysicalResourceId());
                }
            }

            return true;
        } catch (AmazonServiceException e) {
            logger.println("Amazon service exception thrown while trying to shut down EC2 instances, build will be unstable. Exception: "+e);
            return false;
        }
    }

    private String detailedError(AmazonServiceException e){
		StringBuffer message = new StringBuffer();
		message.append("Detailed Message: ").append(e.getMessage()).append('\n');
		message.append("Status Code: ").append(e.getStatusCode()).append('\n');
		message.append("Error Code: ").append(e.getErrorCode()).append('\n');
		return message.toString();
	}

	protected AmazonCloudFormation getAWSClient() {
		AWSCredentials credentials = new BasicAWSCredentials(this.awsAccessKey,
				this.awsSecretKey);
		AmazonCloudFormation amazonClient = new AmazonCloudFormationAsyncClient(
				credentials);
		amazonClient.setEndpoint(awsRegion.endPoint);
		return amazonClient;
	}

    protected EC2 getEC2Client() {
        return new EC2(awsAccessKey, awsSecretKey, awsRegion, logger);
    }
	
	private boolean waitForStackToBeDeleted() {
		
		while (true){
			
			stack = getStack(amazonClient.describeStacks());
			
			if (stack == null) return true;
			
			StackStatus stackStatus = getStackStatus(stack.getStackStatus());
			
			if (StackStatus.DELETE_COMPLETE == stackStatus) return true;
				
			if (StackStatus.DELETE_FAILED == stackStatus) return false;
			
			sleep();
			
		}
		
	}

	private List<Parameter> parameters(Map<String, String> parameters) {
	
		if (parameters == null || parameters.values().size() == 0) {
			return null;
		}
	
		List<Parameter> result = Lists.newArrayList();
		Parameter parameter = null;
		for (String name : parameters.keySet()) {
			parameter = new Parameter();
			parameter.setParameterKey(name);
			parameter.setParameterValue(parameters.get(name));
			result.add(parameter);
		}
	
		return result;
	}

	private Stack waitForStackToBeCreated() throws TimeoutException{
		
		DescribeStacksRequest describeStacksRequest = new DescribeStacksRequest().withStackName(getExpandedStackName());
		StackStatus status = StackStatus.CREATE_IN_PROGRESS;
		Stack stack = null;
		long startTime = System.currentTimeMillis();
		while ( isStackCreationInProgress(status) ){
			if (isTimeout(startTime)){
				throw new TimeoutException("Timed out waiting for stack to be created. (timeout=" + timeout + ")");
			}
			stack = getStack(amazonClient.describeStacks(describeStacksRequest));
			status = getStackStatus(stack.getStackStatus());
			if (isStackCreationInProgress(status)) sleep();
		}
		
		printStackEvents();
		
		return stack;
	}

    private Stack waitForStackToBeUpdated() {
        DescribeStacksRequest describeStacksRequest = new DescribeStacksRequest().withStackName(getExpandedStackName());
        StackStatus status = StackStatus.UPDATE_IN_PROGRESS;
        Stack stack = null;
        long startTime = System.currentTimeMillis();
        while ( isStackUpdateInProgress(status) ){
            if (isTimeout(startTime)){
                throw new TimeoutException("Timed out waiting for stack to be updated. (timeout=" + timeout + ")");
            }
            stack = getStack(amazonClient.describeStacks(describeStacksRequest));
            status = getStackStatus(stack.getStackStatus());
            if (isStackUpdateInProgress(status)) sleep();
        }

        printStackEvents();

        return stack;
    }

	private void printStackEvents() {
		DescribeStackEventsRequest r = new DescribeStackEventsRequest();
		r.withStackName(getExpandedStackName());
		DescribeStackEventsResult describeStackEvents = amazonClient.describeStackEvents(r);
		
		List<StackEvent> stackEvents = describeStackEvents.getStackEvents();
		Collections.reverse(stackEvents);
		
		for (StackEvent event : stackEvents) {
			logger.println(event.getEventId() + " - " + event.getResourceType() + " - " + event.getResourceStatus() + " - " + event.getResourceStatusReason());
		}
		
	}

	private boolean isTimeout(long startTime) {
		return timeout == 0 ? false : (System.currentTimeMillis() - startTime) > (timeout * 1000);
	}

	private Stack getStack(DescribeStacksResult result) {
		for (Stack aStack : result.getStacks())
			if (getExpandedStackName().equals(aStack.getStackName())){
				return aStack;
			}
		
		return null;
		
	}

	private boolean isStackCreationSuccessful(StackStatus status) {
		return status == StackStatus.CREATE_COMPLETE;
	}

    private boolean isStackUpdateSuccessful(StackStatus status) {
        return status == StackStatus.UPDATE_COMPLETE;
    }

	private void sleep() {
		try {
			Thread.sleep(waitBetweenAttempts * 1000);
		} catch (InterruptedException e) {
			if (stack != null){
				logger.println("Received an interruption signal. There is a stack created or in the proces of creation. Check in your amazon account to ensure you are not charged for this.");
				logger.println("Stack details: " + stack);
			}
		}
	}

	private boolean isStackCreationInProgress(StackStatus status) {
		return status == StackStatus.CREATE_IN_PROGRESS;
	}

    private boolean isStackUpdateInProgress(StackStatus status) {
        return status == StackStatus.UPDATE_IN_PROGRESS;
    }

	private StackStatus getStackStatus(String status) {
		StackStatus result = StackStatus.fromValue(status);
		return result;
	}

	private CreateStackRequest createStackRequest() {

		CreateStackRequest r = new CreateStackRequest();
		r.withStackName(getExpandedStackName());
		r.withParameters(parameters);
		r.withTemplateBody(recipe);
		r.withCapabilities("CAPABILITY_IAM");
		
		return r;
	}

    private UpdateStackRequest createUpdateStackRequest() {
        UpdateStackRequest r = new UpdateStackRequest();
        r.withStackName(getExpandedStackName());
        r.withParameters(getUpdateParameters());
        r.withCapabilities("CAPABILITY_IAM");
        r.withUsePreviousTemplate(true);

        return r;
    }

    private List<Parameter> getUpdateParameters() {
        List<Parameter> updateRequestParams = new ArrayList<Parameter>();

        DescribeStacksRequest describeStacksRequest = new DescribeStacksRequest().withStackName(getExpandedStackName());
        Stack existingStack = getStack(amazonClient.describeStacks(describeStacksRequest));
        List<Parameter> existingParams = existingStack.getParameters();

        //Scroll through existing parameters, update if needed, otherwise set UsePreviousValue flag
        for (Parameter existingParam : existingParams) {
            boolean updated = false;

            for (Parameter updatedParam : parameters) {
                if (updatedParam.getParameterKey().equals(existingParam.getParameterKey()) && !updatedParam.getParameterValue().equals(existingParam.getParameterValue()))  {
                    updatedParam.setUsePreviousValue(false);
                    updateRequestParams.add(updatedParam);
                    updated = true;
                    logger.println("Updating template parameter '"+updatedParam.getParameterKey()+"' from '"+existingParam.getParameterValue()+"' to '"+updatedParam.getParameterValue()+"'");
                    break;
                }
            }

            if (!updated) {
                updateRequestParams.add(new Parameter().withParameterKey(existingParam.getParameterKey()).withUsePreviousValue(true));
            }
        }

        //Go through updated parameters, check for any new parameters and add them
        for (Parameter updateParam : parameters) {
            boolean newParam = true;

            for (Parameter paramToUpdate : updateRequestParams) {
                if (paramToUpdate.getParameterKey().equals(updateParam.getParameterKey()))
                    newParam = false;
            }

            if (newParam) {
                logger.println("Adding new template parameter "+updateParam.getParameterKey()+"='"+updateParam.getParameterValue()+"'");
                updateRequestParams.add(updateParam);
            }
        }

        return updateRequestParams;
    }

    public Map<String, String> getOutputs() {
		// Prefix outputs with stack name to prevent collisions with other stacks created in the same build.
		HashMap<String, String> map = new HashMap<String, String>();

        if (outputs == null)
            return map;

		for (String key : outputs.keySet()) {
			map.put(getExpandedStackName() + "_" + key, outputs.get(key));
		}
		return map;
	}

	private String getExpandedStackName() {
		return envVars.expand(stackName);
	}

    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }
}
