package com.syncapse.jenkinsci.plugins.awscloudformationwrapper;

import hudson.EnvVars;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildWrapper.Environment;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class CloudFormationUpdateBuildWrapperTest {

	private CloudFormationUpdateBuildWrapper wrapper;

	@Mock private CloudFormation mockCF1;
	@Mock private CloudFormation mockCF2;
	@Mock private AbstractBuild build;
	@Mock private Launcher launcher;
	@Mock private BuildListener listener;

	private EnvVars envVars;

	@Before
	public void setUp() throws Exception {
		envVars = new EnvVars();
		when(build.getEnvironment(listener)).thenReturn(envVars);
		when(listener.getLogger()).thenReturn(System.out);
	}

	@Test
	public void when_1_stack_is_entered_one_stack_updated()
			throws Exception {
		when_1_stack_is_entered();
        then_1_stack_is_updated();
	}

    @Test
    public void when_1_stack_is_entered_and_update_fails()
            throws Exception {
        when_1_stack_is_entered_failure();
        then_1_stack_is_updated();
        then_failure();
    }

    private void then_failure() {
        verify(build, times(1)).setResult(Result.FAILURE);
    }

    @Test
    public void when_2_stacks_are_entered_two_stacks_updated()
            throws Exception {
        when_2_stack_are_entered();
        then_2_stacks_are_updated();
    }

    @Test
    public void when_1_stack_entered_with_terminate_it_is_updated_and_terminated() throws Exception {
        when_1_stack_is_entered_with_terminate_ec2();
        then_1_stack_is_updated();
        and_ec2_termination_attempted();
    }

    @Test
    public void when_1_stack_entered_with_terminate_it_is_updated_and_terminate_fails() throws Exception {
        when_1_stack_is_entered_with_terminate_ec2_failure();
        then_1_stack_is_updated();
        and_ec2_termination_failed();
    }

    private void when_2_stack_are_entered() throws Exception {
		List<UpdateStackBean> stackBeans = new ArrayList<UpdateStackBean>();
		stackBeans.add(new UpdateStackBean("stack1", "{param1: 1}", 0, "accessKey", "secretKey", null, false, false));
		stackBeans.add(new UpdateStackBean("stack2", "{param2: 2}", 0, "accessKey", "secretKey", null, false, false));

		wrapper = spy(new CloudFormationUpdateBuildWrapper(stackBeans));
		
		doReturn(mockCF1).when(wrapper).newCloudFormation(
				((UpdateStackBean)argThat(hasProperty("stackName", equalTo("stack1")))),
				any(AbstractBuild.class), any(EnvVars.class),
				any(PrintStream.class));

		doReturn(mockCF2).when(wrapper).newCloudFormation(
				((UpdateStackBean)argThat(hasProperty("stackName", equalTo("stack2")))),
				any(AbstractBuild.class), any(EnvVars.class),
				any(PrintStream.class));
        when(mockCF1.update()).thenReturn(true);
        when(mockCF2.update()).thenReturn(true);
	}

    private void then_2_stacks_are_updated() throws Exception {
        Environment env = wrapper.setUp(build, launcher, listener);
        verify(mockCF1, times(1)).update();
        verify(mockCF2, times(1)).update();
    }

	private void then_1_stack_is_updated() throws Exception {
		Environment env = wrapper.setUp(build, launcher, listener);
		verify(mockCF1, times(1)).update();
	}

    private void and_ec2_termination_attempted() throws Exception{
        verify(mockCF1, times(1)).doTerminateAutoScaleEC2Resources();
    }

    private void and_ec2_termination_failed() throws Exception{
        verify(mockCF1, times(1)).doTerminateAutoScaleEC2Resources();
        verify(build, times(1)).setResult(Result.UNSTABLE);
    }

	private void when_1_stack_is_entered() throws Exception {
		List<UpdateStackBean> stackBeans = new ArrayList<UpdateStackBean>();
        stackBeans.add(new UpdateStackBean("stack1", "{param1: 1}", 0, "accessKey", "secretKey", null, false, false));

        wrapper = spy(new CloudFormationUpdateBuildWrapper(stackBeans));

        doReturn(mockCF1).when(wrapper).newCloudFormation(
                ((UpdateStackBean)argThat(hasProperty("stackName", equalTo("stack1")))),
                any(AbstractBuild.class), any(EnvVars.class),
                any(PrintStream.class));

		when(mockCF1.update()).thenReturn(true);
	}

    private void when_1_stack_is_entered_failure() throws Exception {
        List<UpdateStackBean> stackBeans = new ArrayList<UpdateStackBean>();
        stackBeans.add(new UpdateStackBean("stack1", "{param1: 1}", 0, "accessKey", "secretKey", null, false, false));

        wrapper = spy(new CloudFormationUpdateBuildWrapper(stackBeans));

        doReturn(mockCF1).when(wrapper).newCloudFormation(
                ((UpdateStackBean)argThat(hasProperty("stackName", equalTo("stack1")))),
                any(AbstractBuild.class), any(EnvVars.class),
                any(PrintStream.class));

        when(mockCF1.update()).thenReturn(false);
    }

    private void when_1_stack_is_entered_with_terminate_ec2() throws Exception {
        List<UpdateStackBean> stackBeans = new ArrayList<UpdateStackBean>();
        stackBeans.add(new UpdateStackBean("stack1", "{param1: 1}", 0, "accessKey", "secretKey", null, true, true));

        wrapper = spy(new CloudFormationUpdateBuildWrapper(stackBeans));

        when(mockCF1.getTerminateAutoScaleEC2Resources()).thenReturn(true);
        doReturn(mockCF1).when(wrapper).newCloudFormation(
                ((UpdateStackBean) argThat(hasProperty("stackName", equalTo("stack1")))),
                any(AbstractBuild.class), any(EnvVars.class),
                any(PrintStream.class));

        when(mockCF1.update()).thenReturn(true);
        when(mockCF1.doTerminateAutoScaleEC2Resources()).thenReturn(true);
    }

    private void when_1_stack_is_entered_with_terminate_ec2_failure() throws Exception {
        List<UpdateStackBean> stackBeans = new ArrayList<UpdateStackBean>();
        stackBeans.add(new UpdateStackBean("stack1", "{param1: 1}", 0, "accessKey", "secretKey", null, true, true));

        wrapper = spy(new CloudFormationUpdateBuildWrapper(stackBeans));

        when(mockCF1.getTerminateAutoScaleEC2Resources()).thenReturn(true);
        doReturn(mockCF1).when(wrapper).newCloudFormation(
                ((UpdateStackBean)argThat(hasProperty("stackName", equalTo("stack1")))),
                any(AbstractBuild.class), any(EnvVars.class),
                any(PrintStream.class));

        when(mockCF1.update()).thenReturn(true);
        when(mockCF1.doTerminateAutoScaleEC2Resources()).thenReturn(false);
    }

}
