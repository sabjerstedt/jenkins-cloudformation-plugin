package com.syncapse.jenkinsci.plugins.awscloudformationwrapper;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.cloudformation.model.*;
import hudson.EnvVars;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.amazonaws.services.cloudformation.AmazonCloudFormation;

@RunWith(MockitoJUnitRunner.class)
public class CloudFormationTest {

	private static final String TEST_STACK = "testStack";

    private Stack stack = mock(Stack.class);

	private CloudFormation cf; // SUT

	private String recipeBody = "recipe body";
	private Map<String, String> parameters = new HashMap<String, String>() {{
        put("param1",    "value1");
        put("param2", "value2");
        put("param3",   "value3");
    }};

	private String awsAccessKey = "accessKey";
	private String awsSecretKey = "secretKey";

	@Mock
	private AmazonCloudFormation awsClient;

    @Mock
    private EC2 ec2Client;

	@Before
	public void setup() throws Exception {

		cf = new CloudFormation(System.out, TEST_STACK, recipeBody, parameters,
				-12345, awsAccessKey, awsSecretKey, true, new EnvVars()) {
			@Override
			protected AmazonCloudFormation getAWSClient() {
				return awsClient;
			}

            @Override
            protected EC2 getEC2Client() {
                return ec2Client;
            }
		};

		when(awsClient.createStack(any(CreateStackRequest.class))).thenReturn(
				createResultWithId(TEST_STACK));
		when(
				awsClient
						.describeStackEvents(any(DescribeStackEventsRequest.class)))
				.thenReturn(new DescribeStackEventsResult());


        when(stack.getStackName()).thenReturn(TEST_STACK);
        when(stack.getParameters()).thenReturn(Arrays.asList(new Parameter().withParameterKey("param2").withParameterValue("value2"), new Parameter().withParameterKey("param1").withParameterValue("valueChanges")));
        when(awsClient.describeStacks(any(DescribeStacksRequest.class))).thenReturn(new DescribeStacksResult().withStacks(stack));
	}

	@Test
	public void cloudFormationCreate_Wait_for_Stack_To_Be_Created()
			throws Exception {

		when(awsClient.describeStacks(any(DescribeStacksRequest.class)))
				.thenReturn(stackPendingResult(), stackPendingResult(),
						stackCompletedResult());
		assertTrue(cf.create());
		verify(awsClient, times(3)).describeStacks(
				any(DescribeStacksRequest.class));

	}

	@Test
	public void create_returns_false_when_stack_creation_fails()
			throws Exception {
		when(awsClient.describeStacks(any(DescribeStacksRequest.class)))
				.thenReturn(stackFailedResult());
		assertFalse(cf.create());
	}

	@Test
	public void delete_waits_for_stack_to_be_deleted() throws Exception {
		when(awsClient.describeStacks()).thenReturn(stackDeletingResult(),
				stackDeletingResult(), stackDeleteSuccessfulResult());
		cf.delete();
		verify(awsClient, times(3)).describeStacks();
	}

	@Test
	public void delete_returns_false_when_stack_fails_to_delete()
			throws Exception {
		when(awsClient.describeStacks()).thenReturn(stackDeleteFailedResult());
		assertFalse(cf.delete());
	}

    @Test
    public void update_stack_waits_for_update() {
        when(stack.getStackStatus()).thenReturn(StackStatus.UPDATE_COMPLETE.toString());
        boolean result = cf.update();
        assertTrue(result);
        verify(stack, times(2)).getStackStatus();
    }

    @Test
    public void update_stack_sets_params_correctly() {
        when(stack.getStackStatus()).thenReturn(StackStatus.UPDATE_COMPLETE.toString());
        boolean result = cf.update();
        assertTrue(result);
        ArgumentCaptor<UpdateStackRequest> updateStackRequest = ArgumentCaptor.forClass(UpdateStackRequest.class);
        verify(awsClient).updateStack(updateStackRequest.capture());
        assertEquals(3, updateStackRequest.getValue().getParameters().size());
        for (Parameter param : updateStackRequest.getValue().getParameters()) {
            if (param.getParameterKey().equals("param1"))
                assertEquals("value1", param.getParameterValue());

            if (param.getParameterKey().equals("param2")) {
                assertEquals(null, param.getParameterValue());
                assertEquals(true, param.getUsePreviousValue());
            }

            if (param.getParameterKey().equals("param3"))
                assertEquals("value3", param.getParameterValue());

            if (!param.getParameterKey().equals("param1") && !param.getParameterKey().equals("param2") && !param.getParameterKey().equals("param3"))
                fail("Extra parameters found" + param);
        }
    }

    @Test
    public void create_stack_timeout() {
        cf.setTimeout(1);
        when(stack.getStackStatus()).thenReturn(StackStatus.CREATE_IN_PROGRESS.toString());
        try {
            boolean result = cf.create();
        } catch (TimeoutException e) {
            return;
        }

        fail("No timeout exception thrown");
    }

    @Test
    public void update_stack_timeout() {
        cf.setTimeout(1);
        when(stack.getStackStatus()).thenReturn(StackStatus.UPDATE_IN_PROGRESS.toString());
        try {
            boolean result = cf.update();
        } catch (TimeoutException e) {
            return;
        }

        fail("No timeout exception thrown");
    }

    @Test
    public void update_stack_fails() {
        when(stack.getStackStatus()).thenReturn(StackStatus.UPDATE_ROLLBACK_COMPLETE.toString());
        boolean result = cf.update();
        assertFalse(result);
    }

    @Test
    public void update_stack_no_updates_needed() {
        when(awsClient.updateStack(any(UpdateStackRequest.class))).thenThrow(new AmazonServiceException("No updates are to be performed"));
        boolean result = cf.update();
        assertTrue(result);
    }

    @Test
    public void update_stack_aws_service_exception() {
        when(awsClient.updateStack(any(UpdateStackRequest.class))).thenThrow(new AmazonServiceException("Some service exception"));
        boolean result = cf.update();
        assertFalse(result);
    }

    @Test
    public void terminate_autoscale_ec2_resources_none_found() {
        when(awsClient.listStackResources(any(ListStackResourcesRequest.class))).thenReturn(new ListStackResourcesResult().withStackResourceSummaries(Arrays.asList(new StackResourceSummary().withResourceType("Blah").withPhysicalResourceId("blah"))));
        boolean result = cf.doTerminateAutoScaleEC2Resources();
        verify(ec2Client, times(0)).stopInstancesInScalingGroup(anyString());
    }

    @Test
    public void terminate_autoscale_ec2_resources() {
        when(awsClient.listStackResources(any(ListStackResourcesRequest.class))).thenReturn(new ListStackResourcesResult().withStackResourceSummaries(Arrays.asList(new StackResourceSummary().withResourceType("AWS::AutoScaling::AutoScalingGroup").withPhysicalResourceId("someid"))));
        boolean result = cf.doTerminateAutoScaleEC2Resources();
        verify(ec2Client, times(1)).stopInstancesInScalingGroup("someid");
    }

	private DescribeStacksResult stackDeleteFailedResult() {
		return describeStacksResultWithStatus(StackStatus.DELETE_FAILED);
	}

	private DescribeStacksResult stackDeleteSuccessfulResult() {
		return new DescribeStacksResult(); // A result with no stacks in it.
	}

	private DescribeStacksResult stackDeletingResult() {
		return describeStacksResultWithStatus(StackStatus.DELETE_IN_PROGRESS);
	}

	private DescribeStacksResult stackFailedResult() {
		return describeStacksResultWithStatus(StackStatus.CREATE_FAILED);
	}

	private CreateStackResult createResultWithId(String stackId) {
		return new CreateStackResult().withStackId(stackId);
	}

	private DescribeStacksResult stackCompletedResult() {
		return describeStacksResultWithStatus(StackStatus.CREATE_COMPLETE);
	}

	private DescribeStacksResult stackPendingResult() {
		return describeStacksResultWithStatus(StackStatus.CREATE_IN_PROGRESS);
	}

	private DescribeStacksResult describeStacksResultWithStatus(
			StackStatus status) {
		return new DescribeStacksResult().withStacks(new Stack()
				.withStackStatus(status.name()).withStackName(TEST_STACK));
	}

}
