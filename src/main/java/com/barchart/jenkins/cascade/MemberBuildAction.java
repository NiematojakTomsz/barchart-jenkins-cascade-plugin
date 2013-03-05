/**
 * Copyright (C) 2013 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.barchart.jenkins.cascade;

import hudson.maven.MavenModuleSet;
import hudson.model.Action;
import hudson.model.TopLevelItem;
import jenkins.model.Jenkins;

import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

/**
 * Cascade build action link on member project page.
 * 
 * @author Andrei Pozolotin
 */
public class MemberBuildAction implements Action {

	final private String cascadeName;
	final private String memberName;

	public MemberBuildAction(final String cascadeName, final String memberName) {
		this.cascadeName = cascadeName;
		this.memberName = memberName;
	}

	/**
	 * Jelly form submit.
	 * <p>
	 * Start cascade build.
	 */
	public void doSubmit(final StaplerRequest request,
			final StaplerResponse response) throws Exception {

		final Jenkins jenkins = Jenkins.getInstance();

		final TopLevelItem cascadeItem = jenkins.getItem(cascadeName);

		if (!(cascadeItem instanceof CascadeProject)) {
			throw new IllegalStateException("Cascade project is invalid: "
					+ cascadeName);
		}

		final TopLevelItem memberItem = jenkins.getItem(memberName);

		if (!(memberItem instanceof MavenModuleSet)) {
			throw new IllegalStateException("Member project is invalid: "
					+ memberName);
		}

		final CascadeProject cascadeProject = (CascadeProject) cascadeItem;

		cascadeProject.scheduleBuild(0, new MemberUserCause(), this);

		response.sendRedirect(request.getContextPath() + '/'
				+ cascadeProject.getUrl());

	}

	public String getCascadeName() {
		return cascadeName;
	}

	public String getDisplayName() {
		return PluginConstants.MEMBER_ACTION_NAME;
	}

	public String getIconFileName() {
		return PluginConstants.MEMBER_ACTION_ICON;
	}

	public String getMemberName() {
		return memberName;
	}

	public String getUrlName() {
		return PluginConstants.MEMBER_ACTION_URL;
	}

}