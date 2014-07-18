package com.syncapse.jenkinsci.plugins.awscloudformationwrapper;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 
 * 
 * @author sabjerstedt
 *
 */
public class UpdateStackBean extends AbstractDescribableImpl<UpdateStackBean> {

	/**
	 * The name of the stack.
	 */
	private String stackName;

	/**
	 * The parameters to be passed into the cloud formation.
	 */
	private String parameters;

	/**
	 * Time to wait for a stack to be created before giving up and failing the build.
	 */
	private long timeout;

	/**
	 * The access key to call Amazon's APIs
	 */
	private String awsAccessKey;

	/**
	 * The secret key to call Amazon's APIs
	 */
	private String awsSecretKey;

    private Region awsRegion;

    private boolean terminateAutoScaleEC2Resources;

	@DataBoundConstructor
	public UpdateStackBean(String stackName,
                           String parameters, long timeout,
                           String awsAccessKey, String awsSecretKey, Region awsRegion, boolean terminateAutoScaleEC2Resources) {
		super();
		this.stackName = stackName;
		this.parameters = parameters;
		this.timeout = timeout;
		this.awsAccessKey = awsAccessKey;
		this.awsSecretKey = awsSecretKey;
        this.awsRegion = awsRegion;
        this.terminateAutoScaleEC2Resources = terminateAutoScaleEC2Resources;
	}

	public String getStackName() {
		return stackName;
	}

	public String getParameters() {
		return parameters;
	}

	public long getTimeout() {
		return timeout;
	}

	public String getAwsAccessKey() {
		return awsAccessKey;
	}

	public String getAwsSecretKey() {
		return awsSecretKey;
	}

    public Region getAwsRegion(){
    	return awsRegion;
    }

    public boolean getTerminateAutoScaleEC2Resources() {
        return terminateAutoScaleEC2Resources;
    }

    public Map<String, String> getParsedParameters(EnvVars env) {
		
		if (parameters == null || parameters.isEmpty())
			return new HashMap<String, String>();
		
		Map<String, String> result = new HashMap<String, String>();
		String token[] = null;
		
		//semicolon delimited list
		if(parameters.contains(";")) {
			for (String param : parameters.split(";")) {
				token = param.split("=");
				result.put(token[0].trim(), env.expand(token[1].trim()));
			}
		} else {
			//comma delimited parameter list
			for (String param : parameters.split(",")) {
				token = param.split("=");
				result.put(token[0].trim(), env.expand(token[1].trim()));
			}
		}
		return result;
	}
	
	public String getParsedAwsAccessKey(EnvVars env) {
		return env.expand(getAwsAccessKey());
	}

	
	public String getParsedAwsSecretKey(EnvVars env) {
		return env.expand(getAwsSecretKey());
	}

	@Extension
	public static final class DescriptorImpl extends Descriptor<UpdateStackBean>{
		
		@Override
		public String getDisplayName() {
			return "Cloud Formation";
		}
		
        public FormValidation doCheckStackName(
				@AncestorInPath AbstractProject<?, ?> project,
				@QueryParameter String value) throws IOException {
			if (0 == value.length()) {
				return FormValidation.error("Empty stack name");
			}
			return FormValidation.ok();
		}

		public FormValidation doCheckTimeout(
				@AncestorInPath AbstractProject<?, ?> project,
				@QueryParameter String value) throws IOException {
			if (value.length() > 0) {
				try {
					Long.parseLong(value);
				} catch (NumberFormatException e) {
					return FormValidation.error("Timeout value "+ value + " is not a number.");
				}
			}
			return FormValidation.ok();
		}

		public FormValidation doCheckAwsAccessKey(
				@AncestorInPath AbstractProject<?, ?> project,
				@QueryParameter String value) throws IOException {
			if (0 == value.length()) {
				return FormValidation.error("Empty aws access key");
			}
			return FormValidation.ok();
		}

		public FormValidation doCheckAwsSecretKey(
				@AncestorInPath AbstractProject<?, ?> project,
				@QueryParameter String value) throws IOException {
			if (0 == value.length()) {
				return FormValidation.error("Empty aws secret key");
			}
			return FormValidation.ok();
		}
		
		public ListBoxModel doFillAwsRegionItems() {
            ListBoxModel items = new ListBoxModel();
            for (Region region : Region.values()) {
				items.add(region.readableName, region.name());
			}
            return items;
        }

	}


}