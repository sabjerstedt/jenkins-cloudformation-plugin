/**
 * 
 */
package com.syncapse.jenkinsci.plugins.awscloudformationwrapper;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @author erickdovale
 * 
 */
public class CloudFormationUpdateBuildWrapper extends BuildWrapper {

	protected List<UpdateStackBean> stacks;

	private transient List<CloudFormation> cloudFormations = new ArrayList<CloudFormation>();

	@DataBoundConstructor
	public CloudFormationUpdateBuildWrapper(List<UpdateStackBean> stacks) {
		this.stacks = stacks;
	}

	@Override
	public void makeBuildVariables(AbstractBuild build,
			Map<String, String> variables) {

		for (CloudFormation cf : cloudFormations) {
			variables.putAll(cf.getOutputs());
		}

	}

	@Override
	public Environment setUp(AbstractBuild build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException {

        EnvVars env = build.getEnvironment(listener);
        env.overrideAll(build.getBuildVariables());
        
        boolean success = true;
        
		for (UpdateStackBean stackBean : stacks) {

			final CloudFormation cloudFormation = newCloudFormation(stackBean,
					build, env, listener.getLogger());

			try {
				if (cloudFormation.update()) {
					cloudFormations.add(cloudFormation);
					env.putAll(cloudFormation.getOutputs());
				} else {
					build.setResult(Result.FAILURE);
					break;
				}
			} catch (TimeoutException e) {
				listener.getLogger()
						.append("ERROR updating stack with name "
								+ stackBean.getStackName()
								+ ". Operation timed out. Try increasing the timeout period in your stack configuration.");
				build.setResult(Result.FAILURE);
				break;
			}

		}

		return new Environment() {
            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
                return true;
            }
        };
	}

	protected CloudFormation newCloudFormation(UpdateStackBean stackBean,
			AbstractBuild<?, ?> build, EnvVars env, PrintStream logger)
			throws IOException {

		return new CloudFormation(logger, stackBean.getStackName(), null, stackBean.getParsedParameters(env),
				stackBean.getTimeout(), stackBean.getParsedAwsAccessKey(env),
				stackBean.getParsedAwsSecretKey(env),
				stackBean.getAwsRegion(), false, env, stackBean.getTerminateEC2Resources());

	}

	@Extension
	public static class DescriptorImpl extends BuildWrapperDescriptor {

		@Override
		public String getDisplayName() {
			return "Update AWS Cloud Formation stack";
		}

		@Override
		public boolean isApplicable(AbstractProject<?, ?> item) {
			return true;
		}
		
	}

	public List<UpdateStackBean> getStacks() {
		return stacks;
	}

	/**
	 * @return
	 */
	private Object readResolve() {
		// Initialize the cloud formation collection during deserialization to avoid NPEs. 
		cloudFormations = new ArrayList<CloudFormation>();
		return this;
	}
	
}
